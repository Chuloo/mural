import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from 'jose';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { lockWallet } from './ledger.js';
import type { PoolClient } from 'pg';
import { appendMinuteEntry, captureWelcomeOffer } from './minutes.js';

export type Provider = 'google' | 'apple';
export type Identity = { provider: Provider; subject: string; email: string | null };
export type AuthConfig = { googleClientID?: string; appleClientID?: string;
  googleAndroidServerClientID?: string; googleAndroidClientIDs?: string[] };
export const hasGoogleSignIn = (config: AuthConfig) => Boolean(config.googleClientID ||
  (config.googleAndroidServerClientID && config.googleAndroidClientIDs?.length));
const googleKeys = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));
const appleKeys = createRemoteJWKSet(new URL('https://appleid.apple.com/auth/keys'));
export const digest = (text: string) => createHash('sha256').update(text).digest('hex');

export async function verifyIdentity(provider: Provider, token: string, nonceHash: string,
  config: AuthConfig, getKey?: JWTVerifyGetKey): Promise<Identity> {
  const audiences = provider === 'google'
    ? [config.googleClientID, ...(config.googleAndroidClientIDs?.length ? [config.googleAndroidServerClientID] : [])].filter((id): id is string => Boolean(id))
    : [config.appleClientID].filter((id): id is string => Boolean(id));
  if (!audiences.length) throw new ServiceError('identity_provider_not_configured', 503);
  try {
    if (!token || token.length > 16_384) throw new Error();
    const { payload } = await jwtVerify(token, getKey ?? (provider === 'google' ? googleKeys : appleKeys), {
      algorithms: ['RS256'], audience: audiences, issuer: provider === 'google' ? ['https://accounts.google.com', 'accounts.google.com'] : 'https://appleid.apple.com',
      maxTokenAge: '10 minutes', clockTolerance: 5, requiredClaims: ['exp', 'iat', 'sub', 'nonce']
    });
    if (typeof payload.nonce !== 'string' || !/^[a-f0-9]{64}$/.test(nonceHash)) throw new Error();
    if (!timingSafeEqual(Buffer.from(digest(payload.nonce)), Buffer.from(nonceHash))) throw new Error();
    if (typeof payload.sub !== 'string' || !payload.sub || payload.sub.length > 255) throw new Error();
    if (provider === 'google') {
      const tokenAudiences = typeof payload.aud === 'string' ? [payload.aud] : payload.aud ?? [];
      const android = config.googleAndroidServerClientID !== undefined && tokenAudiences.includes(config.googleAndroidServerClientID);
      const parties = android ? config.googleAndroidClientIDs ?? [] : [config.googleClientID];
      // Native Android tokens must identify an explicitly registered Android client.
      // The web client ID is an audience, not permission for arbitrary Android apps.
      if ((android && (typeof payload.azp !== 'string' || !parties.includes(payload.azp))) ||
          (!android && payload.azp !== undefined && payload.azp !== config.googleClientID) ||
          (tokenAudiences.length > 1 && typeof payload.azp !== 'string')) throw new Error();
    }
    const verified = payload.email_verified === true || payload.email_verified === 'true';
    const email = typeof payload.email === 'string' && verified && Buffer.byteLength(payload.email) <= 254 &&
      /^[^\s@\x00-\x1f\x7f]+@[^\s@\x00-\x1f\x7f]+$/.test(payload.email) ? payload.email : null;
    return { provider, subject: payload.sub, email };
  } catch { throw new ServiceError('invalid_identity_token', 401); }
}

export async function createChallenge(db: Database) {
  const id = randomUUID(), nonce = randomBytes(32).toString('hex');
  await db.query("INSERT INTO auth_challenges(id,nonce_hash,expires_at) VALUES($1,$2,now()+interval '5 minutes')", [id, digest(nonce)]);
  return { challengeID: id, nonce, expiresInSeconds: 300 };
}
export async function exchangeIdentity(db: Database, provider: Provider, token: string, challengeID: string, config: AuthConfig,
  verify: typeof verifyIdentity = verifyIdentity, expectedAccountID?: string) {
  const challenge = (await db.query('SELECT nonce_hash FROM auth_challenges WHERE id=$1 AND expires_at>now() AND used_at IS NULL', [challengeID])).rows[0];
  if (!challenge) throw new ServiceError('invalid_challenge', 401);
  const identity = await verify(provider, token, challenge.nonce_hash, config);
  if (identity.provider !== provider) throw new ServiceError('invalid_identity_token', 401);
  const bearer = randomBytes(32).toString('base64url');
  return transaction(db, async sql => {
    const claimed = await sql.query('UPDATE auth_challenges SET used_at=now() WHERE id=$1 AND used_at IS NULL AND expires_at>now() RETURNING id', [challengeID]);
    if (!claimed.rowCount) throw new ServiceError('invalid_challenge', 401);
    // Serialize signup for one provider subject; never merge accounts by email.
    await sql.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`${identity.provider}:${identity.subject}`]);
    let account = (await sql.query('SELECT account_id FROM identities WHERE provider=$1 AND subject=$2', [identity.provider, identity.subject])).rows[0]?.account_id;
    // Recovery may renew only its original owner, before any new signup, grant or token is issued.
    if (expectedAccountID !== undefined && account !== expectedAccountID) throw new ServiceError('same_account_required', 409);
    if (!account) {
      account = randomUUID();
      await sql.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [account, identity.email]);
      await sql.query('INSERT INTO identities(provider,subject,account_id) VALUES($1,$2,$3)', [identity.provider, identity.subject, account]);
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
      await captureWelcomeOffer(sql, account);
    }
    await lockWallet(sql, account, true);
    if (identity.email !== null) await sql.query('UPDATE accounts SET email=$2 WHERE id=$1', [account, identity.email]);
    await sql.query(`DELETE FROM auth_sessions WHERE account_id=$1 AND (expires_at<=now() OR revoked_at IS NOT NULL OR id IN
      (SELECT id FROM auth_sessions WHERE account_id=$1 AND expires_at>now() AND revoked_at IS NULL ORDER BY created_at DESC,id DESC OFFSET 9))`, [account]);
    await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '24 hours')", [randomUUID(), account, digest(bearer)]);
    return { accountID: account as string, accessToken: bearer, expiresInSeconds: 86_400 };
  });
}
export async function authenticate(db: Database, authorization?: string, allowGuest = false): Promise<string> {
  const result = await db.query(`SELECT s.account_id FROM auth_sessions s JOIN accounts a ON a.id=s.account_id
    WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.deleted_at IS NULL AND ($2 OR NOT a.is_guest)`, [bearerHash(authorization), allowGuest]);
  const id = result.rows[0]?.account_id;
  if (!id) throw new ServiceError('sign_in_required', 401);
  return id;
}

export function bearerHash(authorization?: string): string {
  if (!authorization || !/^Bearer [A-Za-z0-9_-]{43}$/.test(authorization)) throw new ServiceError('sign_in_required', 401);
  return digest(authorization.slice(7));
}
async function assertSession(sql: PoolClient, account: string, authorization: string): Promise<void> {
  if (!(await sql.query(`SELECT 1 FROM auth_sessions WHERE account_id=$1 AND token_hash=$2
    AND expires_at>now() AND revoked_at IS NULL`, [account, bearerHash(authorization)])).rowCount) throw new ServiceError('sign_in_required', 401);
}
export async function accountProfile(db: Database, authorization?: string) {
  const result = await db.query(`SELECT a.id,a.email,a.created_at,ARRAY(SELECT DISTINCT provider FROM identities WHERE account_id=a.id ORDER BY provider) AS providers
    FROM auth_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=$1 AND s.expires_at>now()
    AND s.revoked_at IS NULL AND a.deleted_at IS NULL AND NOT a.is_guest`, [bearerHash(authorization)]);
  const row = result.rows[0];
  if (!row) throw new ServiceError('sign_in_required', 401);
  return { accountID: row.id as string, email: row.email as string | null, providers: row.providers as Provider[], createdAt: (row.created_at as Date).toISOString() };
}
export async function signOut(db: Database, authorization?: string): Promise<void> {
  const account = await authenticate(db, authorization);
  await transaction(db, async sql => {
    await lockWallet(sql, account, true); await assertSession(sql, account, authorization!);
    // Server-side closure survives a lost sign-out response or a client that has already discarded its bearer.
    await sql.query(`UPDATE hosted_sessions SET close_requested_at=COALESCE(close_requested_at,now()),
      close_reason=COALESCE(close_reason,'sign_out') WHERE account_id=$1 AND state<>'closed'`, [account]);
    await sql.query('UPDATE auth_sessions SET revoked_at=now() WHERE account_id=$1', [account]);
  });
}

export async function pruneAuthenticationRecords(db: Database): Promise<void> {
  await db.query('DELETE FROM auth_challenges WHERE expires_at<now()');
  await db.query('DELETE FROM auth_sessions WHERE expires_at<now() OR revoked_at IS NOT NULL');
  await db.query('DELETE FROM auth_rate_limits WHERE expires_at<now()');
}

export interface AppleRevoker { revoke(accountID: string, freshAuthorizationCode: string, lockedAppleSubject?: string): Promise<void> }
export async function deleteAccount(db: Database, account: string, appleRevoker?: AppleRevoker, authorizationCode?: string, authorization?: string) {
  return transaction(db, async sql => {
    const wallet = await lockWallet(sql, account, true);
    if (authorization) await assertSession(sql, account, authorization);
    const pending = (await sql.query("SELECT id FROM checkout_orders WHERE account_id=$1 AND state='created' LIMIT 1", [account])).rowCount;
    // The foundation has no refund/checkout-expiry workflow yet. Do not orphan paid value.
    if (pending || wallet.balance !== 0n || wallet.reserved !== 0n) throw new ServiceError('unresolved_billing', 409);
    const minutes = (await sql.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1', [account])).rows[0];
    const minutePurchase = (await sql.query("SELECT 1 FROM minute_entries WHERE account_id=$1 AND kind='purchase' LIMIT 1", [account])).rowCount;
    // The account lock serializes deletion with order creation, fulfillment and refund recovery.
    // A missing transaction is an unpaid/uncertain order, not evidence that no charge can arrive.
    const unresolvedMinuteOrder = (await sql.query(`SELECT 1 FROM minute_purchase_orders o
      LEFT JOIN minute_purchase_transactions p ON p.order_id=o.id WHERE o.account_id=$1 AND o.entitlement_kind='minutes'
      AND (p.order_id IS NULL OR p.state='pending' OR p.recovered_ms<LEAST(p.reversal_target_ms,p.granted_ms)) LIMIT 1`, [account])).rowCount;
    const unresolvedValueOrder = (await sql.query(`SELECT 1 FROM minute_purchase_orders o
      LEFT JOIN ai_value_purchase_transactions p ON p.order_id=o.id WHERE o.account_id=$1 AND o.entitlement_kind='ai_value'
      AND (p.order_id IS NULL OR p.state='pending') LIMIT 1`, [account])).rowCount;
    if (unresolvedMinuteOrder || unresolvedValueOrder || Number(minutes?.reserved_ms ?? 0) > 0 || (minutePurchase && Number(minutes?.balance_ms ?? 0) > 0))
      throw new ServiceError('unresolved_billing', 409);
    const apple = (await sql.query("SELECT subject FROM identities WHERE account_id=$1 AND provider='apple'", [account])).rows[0];
    if (apple) {
      if (!appleRevoker || !authorizationCode) throw new ServiceError('apple_revocation_not_configured', 503);
      await appleRevoker.revoke(account, authorizationCode, apple.subject);
    }
    await sql.query('DELETE FROM identities WHERE account_id=$1', [account]);
    await sql.query('DELETE FROM auth_sessions WHERE account_id=$1', [account]);
    // Unused promotional time is forfeited on deletion; it must not trap a free account.
    if (Number(minutes?.balance_ms ?? 0) > 0)
      await appendMinuteEntry(sql, account, `minute-deletion:${account}`, 'forfeit', -Number(minutes.balance_ms), 0);
    const records = await sql.query(`SELECT 1 FROM ledger WHERE account_id=$1 UNION ALL SELECT 1 FROM reservations WHERE account_id=$1
      UNION ALL SELECT 1 FROM checkout_orders WHERE account_id=$1 UNION ALL SELECT 1 FROM usage_records WHERE account_id=$1
      UNION ALL SELECT 1 FROM hosted_sessions WHERE account_id=$1 UNION ALL SELECT 1 FROM minute_entries WHERE account_id=$1
      UNION ALL SELECT 1 FROM minute_purchase_orders WHERE account_id=$1
      UNION ALL SELECT 1 FROM minute_campaign_recipients WHERE account_id=$1
      UNION ALL SELECT 1 FROM minute_guest_links WHERE member_account_id=$1 OR guest_account_id=$1
      UNION ALL SELECT 1 FROM minute_guest_link_intents WHERE member_account_id=$1 OR guest_account_id=$1 LIMIT 1`, [account]);
    if (records.rowCount) {
      await sql.query('UPDATE accounts SET email=NULL,deleted_at=now() WHERE id=$1', [account]);
      return { retainedFinancialRecords: true };
    }
    // Signup-only accounts have no financial retention reason to keep their ID or empty wallet.
    await sql.query('DELETE FROM wallets WHERE account_id=$1', [account]);
    await sql.query('DELETE FROM minute_wallets WHERE account_id=$1', [account]);
    await sql.query('DELETE FROM accounts WHERE id=$1', [account]);
    return { retainedFinancialRecords: false };
  });
}
