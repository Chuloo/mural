import { createHmac, timingSafeEqual } from 'node:crypto';
import { isIP } from 'node:net';
import type { IncomingHttpHeaders } from 'node:http';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export const ACCESS_REQUEST_PATH = '/v1/access-requests';
export const ACCESS_CONSENT_VERSION = 'waitlist-v1';
// Signup capacity must accommodate a public launch without one visitor consuming shared capacity.
export const ACCESS_REQUEST_LIMITS = Object.freeze({
  networkHourly: 5, requestsHourly: 100_000, requestsDaily: 200_000,
  newAddressesDaily: 100_000, retainedAddresses: 100_000
});
export interface AccessRequestConfig { hmacKey: string; proxyToken: string; localOrigin?: string }
export function accessRequestConfig(env: NodeJS.ProcessEnv): AccessRequestConfig | undefined {
  if (env.ACCESS_REQUESTS_ENABLED !== 'true') return undefined;
  const hmacKey = env.ACCESS_REQUEST_HMAC_KEY ?? '', proxyToken = env.ACCESS_REQUEST_PROXY_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(hmacKey) || !/^[a-f0-9]{64}$/.test(proxyToken) || hmacKey === proxyToken)
    throw new Error('Access request secrets must be separate random 32-byte hex values.');
  const localOrigin = env.ACCESS_REQUEST_LOCAL_ORIGIN;
  if (localOrigin) {
    const url = new URL(localOrigin);
    if (url.origin !== localOrigin || url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))
      throw new Error('Only an exact HTTP loopback origin can be added.');
  }
  return { hmacKey, proxyToken, ...(localOrigin ? { localOrigin } : {}) };
}

export function normalizeAccessEmail(value: unknown): string {
  if (typeof value !== 'string') throw new ServiceError('invalid_access_request');
  const email = value.trim().toLowerCase();
  if (email.length > 254 || !/^[\x21-\x7e]+$/.test(email)) throw new ServiceError('invalid_access_request');
  const parts = email.split('@'), local = parts[0] ?? '', domain = parts[1] ?? '';
  if (parts.length !== 2 || !local || local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..') ||
      !/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)) throw new ServiceError('invalid_access_request');
  const labels = domain.split('.');
  if (labels.length < 2 || labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) ||
      !/^(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})$/.test(labels.at(-1) ?? '')) throw new ServiceError('invalid_access_request');
  return email;
}

export function parseAccessRequest(body: unknown): { email: string; honeypot: boolean } {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Buffer.isBuffer(body)) throw new ServiceError('invalid_access_request');
  const input = body as Record<string, unknown>;
  if (Object.keys(input).some(key => !['email', 'consentVersion', 'source', 'website'].includes(key)) ||
      input.consentVersion !== ACCESS_CONSENT_VERSION || input.source !== 'website' ||
      (input.website !== undefined && (typeof input.website !== 'string' || input.website.length > 200))) throw new ServiceError('invalid_access_request');
  return { email: normalizeAccessEmail(input.email), honeypot: typeof input.website === 'string' && input.website.trim().length > 0 };
}

function canonicalIP(value: string): string {
  if (isIP(value) === 4) return value;
  if (isIP(value) !== 6 || value.includes('%')) throw new ServiceError('access_request_proxy_not_ready', 503);
  const canonical = new URL(`http://[${value}]/`).hostname.slice(1, -1);
  // Normalize mapped IPv4 and group IPv6 privacy addresses by /64 for abuse control.
  if (canonical.startsWith('::ffff:')) {
    const parts = canonical.slice(7).split(':').map(part => parseInt(part, 16));
    if (parts.length === 2) return `${parts[0]! >> 8}.${parts[0]! & 255}.${parts[1]! >> 8}.${parts[1]! & 255}`;
  }
  const halves = canonical.split('::'), left = halves[0] ? halves[0].split(':') : [], right = halves[1] ? halves[1].split(':') : [];
  const full = halves.length === 2 ? [...left, ...Array<string>(8 - left.length - right.length).fill('0'), ...right] : left;
  return `${full.slice(0, 4).map(part => part.padStart(4, '0')).join(':')}::/64`;
}

export function trustedClientNetwork(headers: IncomingHttpHeaders, remoteAddress: string, proxyToken: string, allowLoopback = false): string {
  const supplied = headers['x-mural-proxy-token'], address = headers['x-mural-client-ip'];
  if (typeof supplied === 'string' && typeof address === 'string') {
    const actual = Buffer.from(supplied), expected = Buffer.from(proxyToken);
    if (actual.length === expected.length && timingSafeEqual(actual, expected)) return canonicalIP(address);
  }
  if (allowLoopback && ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remoteAddress)) return canonicalIP(remoteAddress);
  throw new ServiceError('access_request_proxy_not_ready', 503);
}

export class AccessRequests {
  constructor(readonly db: Database, readonly config: AccessRequestConfig) {}
  allowedOrigin(origin: unknown): string {
    if (typeof origin !== 'string' || (origin !== 'https://mural.chat' && origin !== this.config.localOrigin)) throw new ServiceError('origin_not_allowed', 403);
    return origin;
  }
  clientAddress(headers: IncomingHttpHeaders, remoteAddress: string, origin: string): string {
    return trustedClientNetwork(headers, remoteAddress, this.config.proxyToken, origin === this.config.localOrigin);
  }
  async submit(body: unknown, address: string): Promise<void> {
    const parsed = parseAccessRequest(body);
    const outcome = await transaction(this.db, async sql => {
      const now = (await sql.query<{ now: Date }>('SELECT now()')).rows[0]!.now;
      const hour = new Date(now); hour.setUTCMinutes(0, 0, 0);
      const day = new Date(now); day.setUTCHours(0, 0, 0, 0);
      const identifier = createHmac('sha256', Buffer.from(this.config.hmacKey, 'hex'))
        .update(`${day.toISOString()}\n${address}`).digest('hex');
      // Every request locks global buckets in the same order, so concurrent admissions cannot exceed caps.
      if (await increment(sql, 'global_day', 'all', day, ACCESS_REQUEST_LIMITS.requestsDaily, 48) > ACCESS_REQUEST_LIMITS.requestsDaily) return 'rate';
      if (await increment(sql, 'global_hour', 'all', hour, ACCESS_REQUEST_LIMITS.requestsHourly, 2) > ACCESS_REQUEST_LIMITS.requestsHourly) {
        await sql.query("UPDATE access_request_limits SET hits=hits-1 WHERE scope='global_day' AND identifier='all' AND window_start=$1", [day]);
        return 'rate';
      }
      if (await increment(sql, 'ip_hour', identifier, hour, ACCESS_REQUEST_LIMITS.networkHourly, 2) > ACCESS_REQUEST_LIMITS.networkHourly) {
        await sql.query(`UPDATE access_request_limits SET hits=hits-1 WHERE identifier='all' AND
          ((scope='global_day' AND window_start=$1) OR (scope='global_hour' AND window_start=$2))`, [day, hour]);
        return 'rate';
      }
      if (parsed.honeypot) return 'accepted';
      await sql.query("DELETE FROM access_requests WHERE requested_at < now() - interval '12 months'");
      const newCount = (await sql.query("SELECT hits FROM access_request_limits WHERE scope='new_day' AND identifier='all' AND window_start=$1", [day])).rows[0]?.hits ?? 0;
      const count = Number((await sql.query('SELECT count(*) AS count FROM access_requests')).rows[0].count);
      // At capacity, return the same failure for new and existing emails to avoid disclosing membership.
      if (newCount >= ACCESS_REQUEST_LIMITS.newAddressesDaily || count >= ACCESS_REQUEST_LIMITS.retainedAddresses) return 'capacity';
      const previous = (await sql.query('SELECT 1 FROM access_requests WHERE email=$1', [parsed.email])).rowCount;
      if (!previous) await increment(sql, 'new_day', 'all', day, ACCESS_REQUEST_LIMITS.newAddressesDaily, 48);
      await sql.query(`INSERT INTO access_requests(email,requested_at,consent_version,source) VALUES($1,now(),$2,'website')
        ON CONFLICT(email) DO UPDATE SET requested_at=now()`, [parsed.email, ACCESS_CONSENT_VERSION]);
      return 'accepted';
    });
    // Throw after commit: rejected attempts still consume their rate budget.
    if (outcome === 'rate') throw new ServiceError('access_request_rate_limit', 429);
    if (outcome === 'capacity') throw new ServiceError('access_requests_full', 503);
  }
}

async function increment(sql: PoolClient, scope: string, identifier: string, start: Date, limit: number, expiryHours: number): Promise<number> {
  const expiry = new Date(start.getTime() + expiryHours * 3_600_000);
  return (await sql.query(`INSERT INTO access_request_limits(scope,identifier,window_start,expires_at,hits) VALUES($1,$2,$3,$4,1)
    ON CONFLICT(scope,identifier,window_start) DO UPDATE SET hits=LEAST(access_request_limits.hits+1,$5) RETURNING hits`,
  [scope, identifier, start, expiry, limit + 1])).rows[0].hits;
}

export async function pruneAccessRequests(db: Database): Promise<{ requests: number; limits: number }> {
  return transaction(db, async sql => {
    const limits = (await sql.query('DELETE FROM access_request_limits WHERE expires_at < now()')).rowCount ?? 0;
    const requests = (await sql.query("DELETE FROM access_requests WHERE requested_at < now()-interval '12 months'")).rowCount ?? 0;
    return { requests, limits };
  });
}
export async function deleteAccessRequest(db: Database, email: unknown): Promise<void> {
  await db.query('DELETE FROM access_requests WHERE email=$1', [normalizeAccessEmail(email)]);
}
