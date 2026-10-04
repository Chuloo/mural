import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import type { PoolClient } from 'pg';
import { normalizeAccessEmail, trustedClientNetwork } from './access-requests.js';
import type { AuthAdmissionConfig } from './auth-admission.js';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import type { PurchaseEmail, PurchaseEmailSender } from './web-purchase-email.js';
import type { WebPurchaseConfig } from './web-purchase-config.js';

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const digest = (text: string) => createHash('sha256').update(text).digest('hex');
const limits = { challenge: { network: 10, global: 5000 }, verify: { network: 30, global: 10_000 } };
export const WEB_PURCHASE_PATH = '/v1/web-purchases';
export interface WebPurchaseIdentity { accountID: string; email: string }

/** This token authorizes live Stripe checkout only; auth_sessions never contains it. */
export class WebPurchases {
  readonly #key: Buffer;
  readonly #encryptionKey: Buffer;
  #deliveryFlight: Promise<void> | undefined;
  constructor(readonly db: Database, readonly config: WebPurchaseConfig, readonly admission: AuthAdmissionConfig,
    readonly sender: PurchaseEmailSender, readonly onDeliveryFailure?: (retryable: boolean) => void) {
    if (config.hmacKey === admission.hmacKey || config.hmacKey === admission.proxyToken)
      throw new Error('Website purchase secrets must be independent.');
    this.#key = Buffer.from(config.hmacKey, 'hex');
    this.#encryptionKey = createHmac('sha256', this.#key).update('mural-web-purchase-delivery-v1').digest();
  }
  #hash(domain: string, text: string): string { return createHmac('sha256', this.#key).update(`${domain}\n${text}`).digest('hex'); }
  allowedOrigin(value: unknown): string {
    if (value !== 'https://mural.chat') throw new ServiceError('purchase_origin_not_allowed', 403);
    return value;
  }
  clientAddress(headers: IncomingHttpHeaders, remote: string): string {
    try { return trustedClientNetwork(headers, remote, this.admission.proxyToken, this.admission.allowLocalLoopback); }
    catch { throw new ServiceError('accounts_proxy_not_ready', 503); }
  }
  async enter(operation: 'challenge' | 'verify', address: string): Promise<void> {
    const accepted = await transaction(this.db, async sql => {
      const now = (await sql.query<{ now: Date }>('SELECT now()')).rows[0]!.now;
      const hour = new Date(now); hour.setUTCMinutes(0, 0, 0);
      const identifier = this.#hash('network', `${now.toISOString().slice(0,10)}\n${address}`);
      // Reject one exhausted network before consuming the shared allowance.
      for (const scope of ['network','global'] as const) {
        const max = limits[operation][scope];
        const row = (await sql.query(`INSERT INTO web_purchase_limits(operation,scope,identifier,window_start,expires_at,hits)
          VALUES($1,$2,$3,$4,$5,1) ON CONFLICT(operation,scope,identifier,window_start)
          DO UPDATE SET hits=LEAST(web_purchase_limits.hits+1,$6) RETURNING hits`,
        [operation,scope,scope==='global'?'all':identifier,hour,new Date(hour.getTime()+2*3_600_000),max+1])).rows[0];
        if (row.hits > max) return false;
      }
      return true;
    });
    if (!accepted) throw new ServiceError('rate_limit', 429);
  }
  async #match(sql: Pick<PoolClient,'query'>, email: string): Promise<{ id: string; email: string } | undefined> {
    const rows = (await sql.query(`SELECT a.id,a.email FROM accounts a WHERE lower(btrim(a.email))=$1
      AND a.deleted_at IS NULL AND NOT a.is_guest AND EXISTS(SELECT 1 FROM identities i WHERE i.account_id=a.id)
      ORDER BY a.id LIMIT 2`, [email])).rows;
    return rows.length === 1 ? rows[0] : undefined;
  }
  #encrypt(message: PurchaseEmail): Buffer {
    const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', this.#encryptionKey, nonce);
    cipher.setAAD(Buffer.from(message.challengeID));
    const data = Buffer.concat([cipher.update(JSON.stringify(message), 'utf8'),cipher.final()]);
    return Buffer.concat([nonce,cipher.getAuthTag(),data]);
  }
  #decrypt(challengeID: string, encrypted: Buffer): PurchaseEmail {
    const cipher = createDecipheriv('aes-256-gcm', this.#encryptionKey, encrypted.subarray(0,12));
    cipher.setAAD(Buffer.from(challengeID)); cipher.setAuthTag(encrypted.subarray(12,28));
    const message = JSON.parse(Buffer.concat([cipher.update(encrypted.subarray(28)),cipher.final()]).toString()) as PurchaseEmail;
    if (message.challengeID !== challengeID || !/^[0-9]{6}$/.test(message.code) || normalizeAccessEmail(message.email) !== message.email)
      throw new Error('Invalid encrypted delivery.');
    return message;
  }
  async challenge(input: unknown): Promise<{ challengeID: string; expiresInSeconds: number; resendAfterSeconds: number }> {
    if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => key !== 'email'))
      throw new ServiceError('invalid_request');
    const email = normalizeAccessEmail((input as Record<string,unknown>).email), emailHash = this.#hash('email', email);
    const id = randomUUID(), code = String(randomInt(1_000_000)).padStart(6,'0');
    const accepted = await transaction(this.db, async sql => {
      await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`web-purchase-email:${emailHash}`]);
      const recent = (await sql.query(`SELECT count(*)::integer AS count,
        bool_or(created_at>now()-interval '60 seconds') AS cooldown FROM web_purchase_challenges
        WHERE email_hash=$1 AND created_at>now()-interval '1 hour'`,[emailHash])).rows[0];
      if (recent.count >= 3 || recent.cooldown) return false;
      const account = await this.#match(sql,email);
      await sql.query(`INSERT INTO web_purchase_challenges(id,account_id,email_hash,code_hash,expires_at,encrypted_delivery,delivery_state)
        VALUES($1,$2,$3,$4,now()+interval '10 minutes',$5,$6)`,
      [id,account?.id ?? null,emailHash,this.#hash('code',`${id}\n${account?code:randomBytes(32).toString('hex')}`),
        account?this.#encrypt({challengeID:id,email,code}):null,account?'pending':'discarded']);
      return true;
    });
    if (!accepted) throw new ServiceError('purchase_email_rate_limit',429);
    // Delivery is handled by the durable queue, never in the account-existence response.
    return { challengeID:id,expiresInSeconds:600,resendAfterSeconds:60 };
  }
  async verify(input: unknown): Promise<{ token: string; email: string; expiresInSeconds: number }> {
    if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => !['challengeID','code'].includes(key)))
      throw new ServiceError('invalid_request');
    const body = input as Record<string,unknown>;
    if (typeof body.challengeID !== 'string' || !uuid.test(body.challengeID) || typeof body.code !== 'string' || !/^[0-9]{6}$/.test(body.code))
      throw new ServiceError('invalid_purchase_code');
    const id = body.challengeID.toLowerCase(), code = body.code;
    const result = await transaction(this.db, async sql => {
      const row = (await sql.query(`SELECT *,expires_at>now() AS current FROM web_purchase_challenges WHERE id=$1 FOR UPDATE`,[id])).rows[0];
      if (!row || row.used_at || !row.current || row.attempts >= 5) return undefined;
      await sql.query('UPDATE web_purchase_challenges SET attempts=attempts+1 WHERE id=$1',[id]);
      const valid = timingSafeEqual(Buffer.from(row.code_hash,'hex'),Buffer.from(this.#hash('code',`${id}\n${code}`),'hex'));
      if (!valid || !row.account_id) return undefined;
      const account = (await sql.query('SELECT email FROM accounts WHERE id=$1 AND deleted_at IS NULL AND NOT is_guest',[row.account_id])).rows[0];
      if (!account || typeof account.email !== 'string') return undefined;
      let email: string;
      try { email = normalizeAccessEmail(account.email); } catch { return undefined; }
      if (this.#hash('email',email) !== row.email_hash || (await this.#match(sql,email))?.id !== row.account_id) return undefined;
      const token = randomBytes(32).toString('base64url');
      await sql.query(`UPDATE web_purchase_challenges SET used_at=now(),encrypted_delivery=NULL,
        delivery_state=CASE WHEN delivery_state IN ('pending','sending') THEN 'discarded' ELSE delivery_state END WHERE id=$1`,[id]);
      await sql.query(`INSERT INTO web_purchase_sessions(id,account_id,email_hash,token_hash,expires_at)
        VALUES($1,$2,$3,$4,now()+interval '30 minutes')`,[randomUUID(),row.account_id,row.email_hash,digest(token)]);
      return { token,email,expiresInSeconds:1800 };
    });
    if (!result) throw new ServiceError('invalid_purchase_code',401);
    return result;
  }
  async authenticate(authorization: unknown): Promise<WebPurchaseIdentity> {
    if (typeof authorization !== 'string' || !/^Bearer [A-Za-z0-9_-]{43}$/.test(authorization)) throw new ServiceError('purchase_verification_required',401);
    const row = (await this.db.query(`SELECT s.account_id,s.email_hash,a.email FROM web_purchase_sessions s
      JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL
      AND a.deleted_at IS NULL AND NOT a.is_guest`,[digest(authorization.slice(7))])).rows[0];
    if (!row || typeof row.email !== 'string') throw new ServiceError('purchase_verification_required',401);
    let email: string;
    try { email = normalizeAccessEmail(row.email); } catch { throw new ServiceError('purchase_verification_required',401); }
    if (this.#hash('email',email) !== row.email_hash || (await this.#match(this.db,email))?.id !== row.account_id)
      throw new ServiceError('purchase_verification_required',401);
    return { accountID:row.account_id,email };
  }
  async revoke(authorization: unknown): Promise<void> {
    await this.authenticate(authorization);
    await this.db.query('UPDATE web_purchase_sessions SET revoked_at=now() WHERE token_hash=$1',[digest((authorization as string).slice(7))]);
  }
  async drainDeliveries(): Promise<void> {
    if (this.#deliveryFlight) return this.#deliveryFlight;
    this.#deliveryFlight = this.#deliverBatch().finally(() => { this.#deliveryFlight = undefined; });
    return this.#deliveryFlight;
  }
  async #deliverBatch(): Promise<void> {
    // A durable lease and the same provider idempotency key make restart retries safe.
    const batch = await transaction(this.db, async sql => {
      await sql.query(`UPDATE web_purchase_challenges SET delivery_state='failed',encrypted_delivery=NULL,delivery_lease_until=NULL
        WHERE delivery_state IN ('pending','sending') AND (expires_at<=now() OR used_at IS NOT NULL OR
          (delivery_attempts>=3 AND (delivery_lease_until IS NULL OR delivery_lease_until<=now())))`);
      const rows = (await sql.query(`SELECT id,account_id,encrypted_delivery,delivery_attempts FROM web_purchase_challenges
        WHERE delivery_state IN ('pending','sending') AND encrypted_delivery IS NOT NULL AND next_delivery_at<=now()
        AND (delivery_lease_until IS NULL OR delivery_lease_until<=now()) AND expires_at>now() AND used_at IS NULL
        ORDER BY created_at LIMIT 4 FOR UPDATE SKIP LOCKED`)).rows;
      for (const row of rows) await sql.query(`UPDATE web_purchase_challenges SET delivery_state='sending',
        delivery_attempts=delivery_attempts+1,delivery_lease_until=now()+interval '30 seconds' WHERE id=$1`,[row.id]);
      return rows;
    });
    await Promise.all(batch.map(async row => {
      let providerID: string | undefined, retryable = false;
      try {
        const message = this.#decrypt(row.id,row.encrypted_delivery);
        if ((await this.#match(this.db,message.email))?.id !== row.account_id) throw new ServiceError('purchase_email_rejected');
        providerID = await this.sender.send(message);
      } catch (error) {
        retryable = error instanceof ServiceError && error.code === 'purchase_email_retry';
        try { this.onDeliveryFailure?.(retryable); } catch { /* Only safe diagnostics. */ }
      }
      await this.db.query(`UPDATE web_purchase_challenges SET delivery_state=$3,provider_message_id=$4,
        encrypted_delivery=CASE WHEN $3='pending' THEN encrypted_delivery ELSE NULL END,
        next_delivery_at=now()+interval '5 seconds',delivery_lease_until=NULL
        WHERE id=$1 AND delivery_state='sending' AND delivery_attempts=$2`,
      [row.id,row.delivery_attempts+1,providerID?'sent':retryable && row.delivery_attempts<2?'pending':'failed',providerID??null]);
    }));
  }
  async prune(): Promise<void> {
    await this.db.query(`DELETE FROM web_purchase_challenges WHERE expires_at<now()-interval '24 hours'`);
    await this.db.query('DELETE FROM web_purchase_sessions WHERE expires_at<=now() OR revoked_at IS NOT NULL');
    await this.db.query('DELETE FROM web_purchase_limits WHERE expires_at<=now()');
  }
}
