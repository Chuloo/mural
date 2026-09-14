import { createHmac } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import { transaction, type Database } from './db.js';
import { trustedClientNetwork } from './access-requests.js';
import { ServiceError } from './errors.js';

export type AuthOperation = 'challenge' | 'exchange' | 'account' | 'guest';
export interface AuthAdmissionConfig { hmacKey: string; proxyToken: string; allowLocalLoopback: boolean }
export function accountAdmissionConfig(env: NodeJS.ProcessEnv): AuthAdmissionConfig | undefined {
  if (env.ACCOUNTS_ENABLED !== 'true') return undefined;
  const hmacKey = env.ACCOUNTS_HMAC_KEY ?? '', proxyToken = env.ACCOUNTS_PROXY_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(hmacKey) || !/^[a-f0-9]{64}$/.test(proxyToken) || hmacKey === proxyToken)
    throw new Error('Accounts require separate random 32-byte hex secrets.');
  return { hmacKey, proxyToken, allowLocalLoopback: env.ACCOUNTS_ALLOW_LOCAL_LOOPBACK === 'true' };
}
const limits: Record<AuthOperation, { network: number; global: number }> = {
  challenge: { network: 60, global: 2000 }, exchange: { network: 120, global: 4000 }, account: { network: 600, global: 20_000 },
  guest: { network: 30, global: 2000 }
};
export class AuthAdmission {
  constructor(readonly db: Database, readonly config: AuthAdmissionConfig) {}
  async enter(operation: AuthOperation, headers: IncomingHttpHeaders, remoteAddress: string): Promise<void> {
    let address: string;
    try { address = trustedClientNetwork(headers, remoteAddress, this.config.proxyToken, this.config.allowLocalLoopback); }
    catch { throw new ServiceError('accounts_proxy_not_ready', 503); }
    const accepted = await transaction(this.db, async sql => {
      const now = (await sql.query<{ now: Date }>('SELECT now()')).rows[0]!.now;
      const hour = new Date(now); hour.setUTCMinutes(0, 0, 0);
      const identifier = createHmac('sha256', Buffer.from(this.config.hmacKey, 'hex')).update(`${now.toISOString().slice(0, 10)}\n${address}`).digest('hex');
      for (const scope of ['global', 'network'] as const) {
        const maximum = limits[operation][scope];
        const row = (await sql.query(`INSERT INTO auth_rate_limits(operation,scope,identifier,window_start,expires_at,hits)
          VALUES($1,$2,$3,$4,$5,1) ON CONFLICT(operation,scope,identifier,window_start)
          DO UPDATE SET hits=LEAST(auth_rate_limits.hits+1,$6) RETURNING hits`,
        [operation, scope, scope === 'global' ? 'all' : identifier, hour, new Date(hour.getTime() + 2 * 3_600_000), maximum + 1])).rows[0];
        if (row.hits > maximum) {
          // Rejected requests from one network must not exhaust everyone else's allowance.
          if (scope === 'network') await sql.query(`UPDATE auth_rate_limits SET hits=hits-1
            WHERE operation=$1 AND scope='global' AND identifier='all' AND window_start=$2`, [operation, hour]);
          return false;
        }
      }
      return true;
    });
    if (!accepted) throw new ServiceError('rate_limit', 429);
  }
}
