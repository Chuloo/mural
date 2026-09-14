import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import type { PoolClient } from 'pg';
import { ServiceError } from './errors.js';
import type { MinutePurchases, MinutePurchaseVerifier, PurchaseScope } from './minute-purchases.js';

export const providerHash = (value: string) => createHash('sha256').update(value).digest('hex');
export const orderIDPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const referencePattern = /^[\x21-\x7e]{1,4096}$/;
function aad(orderID: string, scope: PurchaseScope, referenceHash: string): Buffer {
  return Buffer.from(JSON.stringify(['mural-minute-receipt-v1', orderID.toLowerCase(), scope.provider, scope.environment, scope.merchant, referenceHash]));
}
function scopeMatches(row: any, scope: PurchaseScope) {
  return row.provider === scope.provider && row.environment === scope.environment && row.merchant === scope.merchant;
}
export async function loadProviderOrder(db: Database, orderID: string, scope: PurchaseScope, accountID?: string): Promise<any> {
  if (!orderIDPattern.test(orderID)) throw new ServiceError('purchase_not_found', 404);
  const row = (await db.query(`SELECT o.*,a.deleted_at,a.is_guest,q.quote AS ai_value_quote FROM minute_purchase_orders o
    JOIN accounts a ON a.id=o.account_id LEFT JOIN ai_value_purchase_quotes q ON q.order_id=o.id WHERE o.id=$1`, [orderID])).rows[0];
  if (!row || !scopeMatches(row, scope) || (accountID !== undefined && (row.account_id !== accountID || row.deleted_at || row.is_guest)))
    throw new ServiceError('purchase_not_found', 404);
  return row;
}
async function schedule(sql: Database | PoolClient, orderID: string) {
  await sql.query(`INSERT INTO minute_provider_jobs(order_id) VALUES($1) ON CONFLICT(order_id) DO UPDATE SET
    generation=minute_provider_jobs.generation+1,available_at=now(),completed_at=NULL,
    state=CASE WHEN minute_provider_jobs.state='leased' THEN 'leased' ELSE 'pending' END`, [orderID]);
}

/** Encryption keys come from protected server configuration, never from a request or a database row. */
export class MinuteReceiptVault {
  readonly #keys = new Map<string, Buffer>();
  constructor(readonly db: Database, readonly activeKeyID: string, keys: ReadonlyMap<string, Buffer>) {
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(activeKeyID) || keys.size < 1 || keys.size > 10) throw new ServiceError('invalid_receipt_encryption');
    for (const [id, key] of keys) {
      if (!/^[a-zA-Z0-9_-]{1,64}$/.test(id) || !Buffer.isBuffer(key) || key.length !== 32) throw new ServiceError('invalid_receipt_encryption');
      this.#keys.set(id, Buffer.from(key));
    }
    if (!this.#keys.has(activeKeyID)) throw new ServiceError('invalid_receipt_encryption');
  }
  async save(orderID: string, scope: PurchaseScope, reference: string, enqueue = true): Promise<void> {
    if (typeof reference !== 'string' || !referencePattern.test(reference)) throw new ServiceError('invalid_provider_reference');
    const order = await loadProviderOrder(this.db, orderID, scope), referenceHash = providerHash(reference), iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.#keys.get(this.activeKeyID)!, iv);
    cipher.setAAD(aad(order.id, scope, referenceHash));
    const encrypted = Buffer.concat([iv, cipher.update(reference, 'utf8'), cipher.final(), cipher.getAuthTag()]);
    await transaction(this.db, async sql => {
      await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`minute-receipt:${scope.provider}:${scope.environment}:${scope.merchant}:${referenceHash}`]);
      const prior = (await sql.query(`SELECT order_id,reference_hash,provider,environment,merchant FROM minute_provider_receipts
        WHERE order_id=$1 OR (provider=$2 AND environment=$3 AND merchant=$4 AND reference_hash=$5)`,
      [order.id, scope.provider, scope.environment, scope.merchant, referenceHash])).rows;
      if (prior.some(row => row.order_id !== order.id || row.reference_hash !== referenceHash || !scopeMatches(row, scope)))
        throw new ServiceError('provider_reference_conflict', 409);
      if (!prior.length) await sql.query(`INSERT INTO minute_provider_receipts(order_id,provider,environment,merchant,reference_hash,encryption_key_id,encrypted_reference)
        VALUES($1,$2,$3,$4,$5,$6,$7)`, [order.id, scope.provider, scope.environment, scope.merchant, referenceHash, this.activeKeyID, encrypted]);
      if (enqueue) await schedule(sql, order.id);
    });
  }
  async read(orderID: string, scope: PurchaseScope): Promise<string> {
    if (!orderIDPattern.test(orderID)) throw new ServiceError('provider_reference_unavailable', 503);
    const row = (await this.db.query('SELECT * FROM minute_provider_receipts WHERE order_id=$1', [orderID])).rows[0];
    if (!row || !scopeMatches(row, scope)) throw new ServiceError('provider_reference_unavailable', 503);
    try {
      const key = this.#keys.get(row.encryption_key_id), encrypted: Buffer = row.encrypted_reference;
      if (!key || !Buffer.isBuffer(encrypted) || encrypted.length < 29 || encrypted.length > 8192) throw new Error();
      const decipher = createDecipheriv('aes-256-gcm', key, encrypted.subarray(0, 12));
      decipher.setAAD(aad(orderID, scope, row.reference_hash)); decipher.setAuthTag(encrypted.subarray(-16));
      const reference = Buffer.concat([decipher.update(encrypted.subarray(12, -16)), decipher.final()]).toString('utf8');
      if (!referencePattern.test(reference) || providerHash(reference) !== row.reference_hash) throw new Error();
      return reference;
    } catch { throw new ServiceError('provider_reference_unavailable', 503); }
  }
  async find(scope: PurchaseScope, reference: string): Promise<string | undefined> {
    if (!referencePattern.test(reference)) throw new ServiceError('invalid_provider_reference');
    return (await this.db.query(`SELECT order_id FROM minute_provider_receipts WHERE provider=$1 AND environment=$2 AND merchant=$3 AND reference_hash=$4`,
      [scope.provider, scope.environment, scope.merchant, providerHash(reference)])).rows[0]?.order_id;
  }
  async schedule(orderID: string): Promise<void> { await schedule(this.db, orderID); }
  async scheduleReconciliation(limit = 100): Promise<number> {
    if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new ServiceError('invalid_delivery_batch');
    const result = await this.db.query(`UPDATE minute_provider_jobs SET state='pending',generation=generation+1,available_at=now(),completed_at=NULL
      WHERE order_id IN (SELECT order_id FROM minute_provider_jobs WHERE state='done' AND completed_at<now()-interval '6 hours'
        ORDER BY completed_at LIMIT $1) AND state='done' RETURNING order_id`, [limit]);
    return result.rowCount ?? 0;
  }
  async rememberVoid(orderID: string): Promise<void> {
    await transaction(this.db, async sql => {
      await sql.query('INSERT INTO minute_provider_voids(order_id) VALUES($1) ON CONFLICT DO NOTHING', [orderID]);
      await schedule(sql, orderID);
    });
  }
}

export interface MinuteDeliveryAdapter extends MinutePurchaseVerifier {
  /** Called only after the wallet grant commits. Play consumes; Stripe needs no acknowledgment. */
  complete(orderID: string): Promise<void>;
}
export class MinuteDeliveryWorker {
  readonly #adapters = new Map<string, MinuteDeliveryAdapter>();
  constructor(readonly db: Database, readonly purchases: {
    reconcile(provider: PurchaseScope['provider'], input: unknown): Promise<{ state: string }>
  }, adapters: readonly MinuteDeliveryAdapter[]) {
    for (const adapter of adapters) this.#adapters.set(`${adapter.provider}:${adapter.environment}:${adapter.merchant}`, adapter);
  }
  /** Durable leases survive process failure; two workers cannot complete each other's work. */
  async runBatch(limit = 10): Promise<{ processed: number; completed: number; retried: number }> {
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new ServiceError('invalid_delivery_batch');
    let processed = 0, completed = 0, retried = 0;
    for (let index = 0; index < limit; index++) {
      // Lease only the item about to run so a slow provider cannot expire later items in a batch.
      const job = await transaction(this.db, async sql => {
        const found = (await sql.query(`SELECT j.order_id FROM minute_provider_jobs j
          WHERE (j.state='pending' AND j.available_at<=now()) OR (j.state='leased' AND j.lease_until<now())
          ORDER BY j.available_at,j.order_id FOR UPDATE SKIP LOCKED LIMIT 1`)).rows[0];
        if (!found) return undefined;
        return (await sql.query(`UPDATE minute_provider_jobs SET state='leased',lease_id=$2,
          lease_until=now()+interval '5 minutes',attempts=LEAST(attempts+1,1000000) WHERE order_id=$1 RETURNING *`,
        [found.order_id, randomUUID()])).rows[0];
      });
      if (!job) break;
      processed++;
      let failure: 'provider_delivery_failed' | 'provider_still_pending' | undefined;
      try {
        const receipt = (await this.db.query('SELECT provider,environment,merchant FROM minute_provider_receipts WHERE order_id=$1', [job.order_id])).rows[0];
        const adapter = this.#adapters.get(`${receipt.provider}:${receipt.environment}:${receipt.merchant}`);
        if (!adapter) throw new Error();
        const status = await this.purchases.reconcile(adapter.provider, { kind: 'stored', orderID: job.order_id });
        if (status.state === 'pending') failure = 'provider_still_pending';
        else if (status.state === 'purchased') await adapter.complete(job.order_id);
        else if (status.state !== 'voided') throw new Error();
      } catch { failure = 'provider_delivery_failed'; }
      if (failure) {
        const seconds = failure === 'provider_still_pending' ? 60 : Math.min(21600, 15 * 2 ** Math.min(job.attempts - 1, 11));
        await this.db.query(`UPDATE minute_provider_jobs SET state='pending',lease_id=NULL,lease_until=NULL,
          available_at=now()+($3::integer*interval '1 second'),last_error_code=$4 WHERE order_id=$1 AND lease_id=$2`,
        [job.order_id, job.lease_id, seconds, failure]);
        retried++;
      } else {
        await this.db.query(`UPDATE minute_provider_jobs SET state=CASE WHEN generation=$3 THEN 'done' ELSE 'pending' END,
          completed_at=CASE WHEN generation=$3 THEN now() ELSE NULL END,lease_id=NULL,lease_until=NULL,last_error_code=NULL,
          available_at=now() WHERE order_id=$1 AND lease_id=$2`, [job.order_id, job.lease_id, job.generation]);
        completed++;
      }
    }
    return { processed, completed, retried };
  }
}
