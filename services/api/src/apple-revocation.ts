import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT, type JWTVerifyGetKey } from 'jose';
import type { Database } from './db.js';
import type { AppleRevoker } from './auth.js';
import { ServiceError } from './errors.js';
import { boundedJSON } from './live-provider.js';

export interface AppleRevocationConfig { clientID: string; teamID: string; keyID: string; privateKeyPEM: string }
const appleKeys = createRemoteJWKSet(new URL('https://appleid.apple.com/auth/keys'));
/** Tokens stay in request-local memory. No identity-provider refresh token is persisted. */
export class AppleTokenRevoker implements AppleRevoker {
  constructor(private readonly db: Database, private readonly config: AppleRevocationConfig,
    private readonly dependencies: { fetch?: typeof fetch; getKey?: JWTVerifyGetKey } = {}) {
    if (!config.clientID || !/^[A-Z0-9]{10}$/.test(config.teamID) || !/^[A-Z0-9]{10}$/.test(config.keyID) || !config.privateKeyPEM.includes('BEGIN PRIVATE KEY'))
      throw new ServiceError('apple_revocation_not_configured', 503);
  }
  async validateConfiguration(): Promise<void> {
    try { await importPKCS8(this.config.privateKeyPEM, 'ES256'); }
    catch { throw new ServiceError('apple_revocation_not_configured', 503); }
  }
  async revoke(accountID: string, freshAuthorizationCode: string, lockedAppleSubject?: string): Promise<void> {
    if (!freshAuthorizationCode || freshAuthorizationCode.length > 4096) throw new ServiceError('invalid_apple_authorization_code');
    // Deletion passes the subject read while holding its account lock, avoiding a second pool checkout.
    const subject = lockedAppleSubject ?? (await this.db.query("SELECT subject FROM identities WHERE account_id=$1 AND provider='apple'", [accountID])).rows[0]?.subject;
    if (!subject) throw new ServiceError('apple_identity_missing', 409);
    try {
      const key = await importPKCS8(this.config.privateKeyPEM, 'ES256');
      const secret = await new SignJWT({}).setProtectedHeader({ alg: 'ES256', kid: this.config.keyID })
        .setIssuer(this.config.teamID).setSubject(this.config.clientID).setAudience('https://appleid.apple.com')
        .setIssuedAt().setExpirationTime('5 minutes').sign(key);
      const send = this.dependencies.fetch ?? fetch;
      const request = (path: string, values: Record<string, string>) => send(`https://appleid.apple.com/auth/${path}`, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5_000),
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: this.config.clientID, client_secret: secret, ...values })
      });
      // Native authorization has no redirect URI. Do not retry this single-use code automatically.
      const exchanged = await request('token', { code: freshAuthorizationCode, grant_type: 'authorization_code' });
      if (!exchanged.ok) { await exchanged.body?.cancel(); throw new Error(); }
      const tokens = await boundedJSON(exchanged, 32_768);
      if (typeof tokens.id_token !== 'string') throw new Error();
      const { payload } = await jwtVerify(tokens.id_token, this.dependencies.getKey ?? appleKeys, {
        algorithms: ['RS256'], issuer: 'https://appleid.apple.com', audience: this.config.clientID,
        maxTokenAge: '10 minutes', clockTolerance: 5, requiredClaims: ['sub', 'iat', 'exp']
      });
      if (payload.sub !== subject) throw new Error();
      const refresh = typeof tokens.refresh_token === 'string' && tokens.refresh_token.length > 0;
      const token = refresh ? tokens.refresh_token : tokens.access_token;
      if (typeof token !== 'string' || !token || token.length > 8192) throw new Error();
      const revoked = await request('revoke', { token, token_type_hint: refresh ? 'refresh_token' : 'access_token' });
      await revoked.body?.cancel();
      if (revoked.status !== 200) throw new Error();
    } catch { throw new ServiceError('apple_revocation_failed', 502); }
  }
}
