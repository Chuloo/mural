import { createHash, randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendMinuteEntry, lockMinuteWallet, millisecondsForMinutes } from './minutes.js';

/** Recover a durable client attempt without contacting Stripe or creating a new purchase.
 * Both minute and AI-value orders share this immutable, account-scoped key. */
export async function stripeOrderByKey(db: Database, accountID: string, key: string): Promise<{ orderID: string }> {
  if (!uuid.test(accountID) || typeof key !== 'string' || !/^[A-Za-z0-9._:-]{8,128}$/.test(key))
    throw new ServiceError('invalid_minute_order');
  const row = (await db.query(`SELECT o.id FROM minute_purchase_orders o JOIN accounts a ON a.id=o.account_id
    WHERE o.account_id=$1 AND o.idempotency_key=$2 AND o.provider='stripe'
      AND a.deleted_at IS NULL AND NOT a.is_guest`, [accountID, key])).rows[0];
  if (!row) throw new ServiceError('purchase_not_found', 404);
  return { orderID: row.id };
}

export type PurchaseProvider = 'stripe' | 'play';
export type PurchaseEnvironment = 'test' | 'live';
export interface PurchaseScope {
  readonly provider: PurchaseProvider;
  readonly environment: PurchaseEnvironment;
  /** Stripe account ID or the Play package name, pinned by server configuration. */
  readonly merchant: string;
}
export interface MinuteProduct extends PurchaseScope {
  readonly sku: string;
  readonly providerProduct: string;
  readonly minutes: number;
  readonly currency: string;
  /** Final amount from the server catalog, in currency minor units. No default launch prices. */
  readonly totalMinor: number;
}
export interface VerifiedMinutePurchase extends PurchaseScope {
  /** Must come from verified provider metadata that was bound to the server order before purchase. */
  readonly orderID: string;
  /** Stable Checkout session ID for Stripe; purchaseToken for Play, never the optional Play orderId. */
  readonly transactionID: string;
  /** Verified event/snapshot identity. Retries with the same identity must contain the same facts. */
  readonly eventID: string;
  readonly providerProduct: string;
  readonly quantity: number;
  readonly currency: string;
  readonly totalMinor: number;
  readonly state: 'pending' | 'purchased' | 'voided';
  /** Cumulative successful refunds only, obtained from the provider. Pending/failed refunds are excluded. */
  readonly refundedMinor: number;
}
export interface MinutePurchaseVerifier extends PurchaseScope {
  /**
   * A trusted server adapter verifies the signature/credentials, then retrieves authoritative state.
   * A browser redirect, unsigned notification or client purchase state is never sufficient evidence.
   * The adapter must persist encrypted provider references for consumption and reconciliation retries.
   */
  verify(input: unknown): Promise<VerifiedMinutePurchase>;
}
export interface MinuteOrder extends MinuteProduct { readonly orderID: string }
export interface MinutePurchaseStatus {
  readonly orderID: string;
  readonly state: 'created' | 'pending' | 'purchased' | 'voided';
  readonly grantedMilliseconds: number;
  readonly reversedMilliseconds: number;
  readonly reversalOutstandingMilliseconds: number;
  readonly fulfillmentRecorded: boolean;
}

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const scopeKey = (scope: PurchaseScope) => JSON.stringify([scope.provider, scope.environment, scope.merchant]);
const productKey = (scope: PurchaseScope, sku: string) => JSON.stringify([scopeKey(scope), sku]);
const money = (value: number, positive = false) => Number.isSafeInteger(value) && value >= (positive ? 1 : 0) && value <= 100_000_000;
function validScope(scope: PurchaseScope) {
  return scope && ['stripe', 'play'].includes(scope.provider) && ['test', 'live'].includes(scope.environment) &&
    typeof scope.merchant === 'string' && identifier.test(scope.merchant);
}
function validateProduct(product: MinuteProduct) {
  if (!validScope(product) || typeof product.sku !== 'string' || product.sku.length > 128 || !identifier.test(product.sku) ||
    typeof product.providerProduct !== 'string' || !identifier.test(product.providerProduct) ||
    typeof product.currency !== 'string' || !/^[a-z]{3}$/.test(product.currency) || !money(product.totalMinor, true) ||
    !Number.isSafeInteger(product.minutes) || product.minutes < 1 || product.minutes > 1440) throw new ServiceError('invalid_minute_product');
}
function validateEvidence(evidence: VerifiedMinutePurchase, verifier: PurchaseScope) {
  // Receipt tokens are only used transiently, hashed before persistence, and never interpolated into errors.
  if (!validScope(evidence) || scopeKey(evidence) !== scopeKey(verifier) || !uuid.test(evidence.orderID) ||
    typeof evidence.transactionID !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(evidence.transactionID) ||
    typeof evidence.eventID !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(evidence.eventID) ||
    typeof evidence.providerProduct !== 'string' || !identifier.test(evidence.providerProduct) || evidence.quantity !== 1 ||
    typeof evidence.currency !== 'string' || !/^[a-z]{3}$/.test(evidence.currency) || !money(evidence.totalMinor, true) ||
    !['pending', 'purchased', 'voided'].includes(evidence.state) || !money(evidence.refundedMinor) ||
    evidence.refundedMinor > evidence.totalMinor || (evidence.state === 'pending' && evidence.refundedMinor !== 0))
    throw new ServiceError('invalid_purchase_evidence', 502);
}
function order(row: any): MinuteOrder {
  return { orderID: row.id, provider: row.provider, environment: row.environment, merchant: row.merchant,
    sku: row.sku, providerProduct: row.provider_product, minutes: Number(row.allowance_ms) / 60_000,
    currency: row.currency, totalMinor: Number(row.total_minor) };
}
function status(orderID: string, row?: any): MinutePurchaseStatus {
  return { orderID, state: row?.state ?? 'created', grantedMilliseconds: Number(row?.granted_ms ?? 0),
    reversedMilliseconds: Number(row?.recovered_ms ?? 0),
    reversalOutstandingMilliseconds: Math.max(0, Math.min(Number(row?.granted_ms ?? 0), Number(row?.reversal_target_ms ?? 0)) - Number(row?.recovered_ms ?? 0)),
    fulfillmentRecorded: Number(row?.granted_ms ?? 0) > 0 };
}

/** Whole milliseconds, rounded up once against the original immutable purchase amount. */
export function refundedMilliseconds(allowanceMilliseconds: number, refundedMinor: number, totalMinor: number): number {
  if (!Number.isSafeInteger(allowanceMilliseconds) || allowanceMilliseconds < 60_000 || allowanceMilliseconds > 86_400_000 ||
    allowanceMilliseconds % 60_000 !== 0 || !money(totalMinor, true) || !money(refundedMinor) || refundedMinor > totalMinor)
    throw new ServiceError('invalid_minute_refund');
  return Number((BigInt(allowanceMilliseconds) * BigInt(refundedMinor) + BigInt(totalMinor) - 1n) / BigInt(totalMinor));
}

/** Caller already owns the account/wallet lock. Never take funds away from an in-flight reservation. */
export async function recoverMinutePurchaseShortfalls(sql: PoolClient, accountID: string): Promise<void> {
  const purchases = (await sql.query(`SELECT order_id,granted_ms,reversal_target_ms,recovered_ms,environment
    FROM minute_purchase_transactions WHERE account_id=$1 AND recovered_ms<LEAST(reversal_target_ms,granted_ms)
    ORDER BY created_at,order_id FOR UPDATE`, [accountID])).rows;
  let wallet = await lockMinuteWallet(sql, accountID, false);
  for (const purchase of purchases) {
    const recovered = Number(purchase.recovered_ms);
    // A test refund may recover test minutes only; real refunds never draw on sandbox receipts.
    const available = purchase.environment==='test' ? Math.min(wallet.sandbox,wallet.balance-wallet.reserved) :
      Math.max(0,wallet.balance-wallet.reserved-wallet.sandbox);
    const amount = Math.min(Math.min(Number(purchase.granted_ms), Number(purchase.reversal_target_ms)) - recovered, available);
    if (!amount) continue;
    await appendMinuteEntry(sql, accountID, `minute-purchase-refund:${purchase.order_id}:${recovered + amount}`, 'forfeit', -amount, 0,
      purchase.environment==='test' ? 'sandbox' : 'funded');
    await sql.query('UPDATE minute_purchase_transactions SET recovered_ms=$2,updated_at=now() WHERE order_id=$1',
      [purchase.order_id, recovered + amount]);
    wallet = await lockMinuteWallet(sql, accountID, false);
  }
}

/** No routes install this service automatically, and no catalog or sales activation is implicit. */
export class MinutePurchases {
  readonly #catalog = new Map<string, Readonly<MinuteProduct>>();
  readonly #verifiers = new Map<PurchaseProvider, MinutePurchaseVerifier>();
  readonly #salesEnabled: boolean;
  constructor(readonly db: Database, options: {
    catalog?: readonly MinuteProduct[]; verifiers?: readonly MinutePurchaseVerifier[]; salesEnabled?: boolean;
  } = {}) {
    this.#salesEnabled = options.salesEnabled === true;
    for (const verifier of options.verifiers ?? []) {
      if (!validScope(verifier) || typeof verifier.verify !== 'function' || this.#verifiers.has(verifier.provider))
        throw new ServiceError('invalid_purchase_verifier');
      // Copy scope so later mutation of a caller-owned object cannot switch environment or merchant.
      this.#verifiers.set(verifier.provider, Object.freeze({ provider: verifier.provider, environment: verifier.environment,
        merchant: verifier.merchant, verify: verifier.verify.bind(verifier) }));
    }
    const bindings = new Set<string>();
    for (const product of options.catalog ?? []) {
      validateProduct(product);
      const key = productKey(product, product.sku), binding = JSON.stringify([scopeKey(product), product.providerProduct, product.currency]);
      const verifier = this.#verifiers.get(product.provider);
      if (this.#catalog.has(key) || bindings.has(binding) || !verifier || scopeKey(product) !== scopeKey(verifier))
        throw new ServiceError('invalid_minute_catalog');
      this.#catalog.set(key, Object.freeze({ ...product })); bindings.add(binding);
    }
  }
  products(provider: PurchaseProvider): readonly Readonly<MinuteProduct>[] {
    return this.#salesEnabled ? [...this.#catalog.values()].filter(product => product.provider === provider) : [];
  }
  async createOrder(accountID: string, provider: PurchaseProvider, sku: string, idempotencyKey: string): Promise<MinuteOrder> {
    if (!this.#salesEnabled) throw new ServiceError('minute_purchases_unavailable', 503);
    if (!uuid.test(accountID) || typeof sku !== 'string' || typeof idempotencyKey !== 'string' ||
      !/^[A-Za-z0-9._:-]{8,128}$/.test(idempotencyKey)) throw new ServiceError('invalid_minute_order');
    const verifier = this.#verifiers.get(provider);
    if (!verifier) throw new ServiceError('minute_purchases_unavailable', 503);
    return transaction(this.db, async sql => {
      await lockMinuteWallet(sql, accountID);
      if ((await sql.query('SELECT is_guest FROM accounts WHERE id=$1', [accountID])).rows[0].is_guest)
        throw new ServiceError('purchase_requires_account', 403);
      const prior = (await sql.query('SELECT * FROM minute_purchase_orders WHERE account_id=$1 AND idempotency_key=$2',
        [accountID, idempotencyKey])).rows[0];
      if (prior) {
        if (prior.entitlement_kind !== 'minutes' || prior.provider !== provider || prior.sku !== sku || prior.environment !== verifier.environment || prior.merchant !== verifier.merchant)
          throw new ServiceError('idempotency_conflict', 409);
        return order(prior);
      }
      const product = this.#catalog.get(productKey(verifier, sku));
      if (!product) throw new ServiceError('minute_product_unavailable', 503);
      const row = (await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,
        merchant,sku,provider_product,currency,total_minor,allowance_ms) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *`,
      [randomUUID(), accountID, idempotencyKey, provider, product.environment, product.merchant, sku,
        product.providerProduct, product.currency, product.totalMinor, millisecondsForMinutes(product.minutes)])).rows[0];
      return order(row);
    });
  }
  async status(accountID: string, orderID: string): Promise<MinutePurchaseStatus> {
    if (!uuid.test(accountID) || !uuid.test(orderID)) throw new ServiceError('purchase_not_found', 404);
    const found = (await this.db.query(`SELECT p.* FROM minute_purchase_orders o LEFT JOIN minute_purchase_transactions p ON p.order_id=o.id
      JOIN accounts a ON a.id=o.account_id WHERE o.id=$1 AND o.account_id=$2 AND o.entitlement_kind='minutes' AND a.deleted_at IS NULL`, [orderID, accountID])).rows[0];
    if (!found) throw new ServiceError('purchase_not_found', 404);
    return status(orderID, found.order_id ? found : undefined);
  }
  async reconcile(provider: PurchaseProvider, input: unknown): Promise<MinutePurchaseStatus> {
    const verifier = this.#verifiers.get(provider);
    if (!verifier) throw new ServiceError('purchase_verification_unavailable', 503);
    let verified: VerifiedMinutePurchase;
    try { verified = await verifier.verify(input); }
    catch { throw new ServiceError('purchase_verification_failed', 502); }
    return this.applyVerifiedEvidence(provider, verified);
  }
  /** Server-internal: only the shared provider verifier/router may call this with verified facts. */
  async applyVerifiedEvidence(provider: PurchaseProvider, verified: VerifiedMinutePurchase): Promise<MinutePurchaseStatus> {
    const verifier = this.#verifiers.get(provider);
    if (!verifier) throw new ServiceError('purchase_verification_unavailable', 503);
    validateEvidence(verified, verifier);
    // Canonical primitives only: no provider body or unrelated fields enter storage or digests.
    const evidence = { provider: verified.provider, environment: verified.environment, merchant: verified.merchant,
      orderID: verified.orderID.toLowerCase(), transactionHash: digest(verified.transactionID), eventHash: digest(verified.eventID),
      providerProduct: verified.providerProduct, currency: verified.currency, totalMinor: verified.totalMinor,
      state: verified.state, refundedMinor: verified.refundedMinor };
    const evidenceHash = digest(JSON.stringify(evidence));
    return transaction(this.db, async sql => {
      // Serialize each provider transaction and event before locking the owner's wallet. Replay against
      // another account cannot win a race to grant the same purchase a second time.
      const locks = [`minute-purchase-transaction:${scopeKey(evidence)}:${evidence.transactionHash}`,
        `minute-purchase-event:${scopeKey(evidence)}:${evidence.eventHash}`].sort();
      for (const key of locks) await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [key]);
      const purchasedOrder = (await sql.query('SELECT * FROM minute_purchase_orders WHERE id=$1', [evidence.orderID])).rows[0];
      if (!purchasedOrder) throw new ServiceError('unmapped_minute_purchase', 409);
      if (purchasedOrder.entitlement_kind !== 'minutes') throw new ServiceError('purchase_entitlement_mismatch', 409);
      if (purchasedOrder.provider !== provider || purchasedOrder.environment !== evidence.environment || purchasedOrder.merchant !== evidence.merchant ||
        purchasedOrder.provider_product !== evidence.providerProduct || purchasedOrder.currency !== evidence.currency ||
        Number(purchasedOrder.total_minor) !== evidence.totalMinor) throw new ServiceError('minute_purchase_mismatch', 409);
      if ((await sql.query(`SELECT 1 FROM ai_value_purchase_transactions WHERE provider=$1 AND environment=$2
        AND merchant=$3 AND transaction_hash=$4`, [provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rowCount)
        throw new ServiceError('purchase_transaction_conflict',409);
      await lockMinuteWallet(sql, purchasedOrder.account_id, false);
      const duplicate = (await sql.query(`SELECT evidence_hash FROM minute_purchase_events
        WHERE provider=$1 AND environment=$2 AND merchant=$3 AND event_hash=$4`,
      [provider, evidence.environment, evidence.merchant, evidence.eventHash])).rows[0];
      if (duplicate && duplicate.evidence_hash !== evidenceHash) throw new ServiceError('purchase_event_conflict', 409);
      const existing = (await sql.query(`SELECT * FROM minute_purchase_transactions WHERE
        order_id=$1 OR (provider=$2 AND environment=$3 AND merchant=$4 AND transaction_hash=$5) FOR UPDATE`,
      [evidence.orderID, provider, evidence.environment, evidence.merchant, evidence.transactionHash])).rows;
      if (existing.some(row => row.order_id !== evidence.orderID || row.transaction_hash !== evidence.transactionHash))
        throw new ServiceError('purchase_transaction_conflict', 409);
      let purchase = existing[0];
      if (!purchase) purchase = (await sql.query(`INSERT INTO minute_purchase_transactions
        (order_id,account_id,provider,environment,merchant,transaction_hash,state,allowance_ms)
        VALUES($1,$2,$3,$4,$5,$6,'pending',$7) RETURNING *`,
      [evidence.orderID, purchasedOrder.account_id, provider, evidence.environment, evidence.merchant, evidence.transactionHash,
        purchasedOrder.allowance_ms])).rows[0];
      if (!duplicate) {
        const state = evidence.state === 'voided' || purchase.state === 'voided' ? 'voided' :
          evidence.state === 'purchased' || purchase.state === 'purchased' ? 'purchased' : 'pending';
        const granted = Number(purchase.granted_ms), allowance = Number(purchasedOrder.allowance_ms);
        if (state === 'purchased' && !granted) {
          await appendMinuteEntry(sql, purchasedOrder.account_id, `minute-purchase:${evidence.orderID}`, 'purchase', allowance, 0,
            purchasedOrder.environment==='test' ? 'sandbox' : 'funded');
        }
        const refund = Math.max(Number(purchase.refunded_minor), evidence.refundedMinor);
        const target = state === 'voided' ? allowance : refundedMilliseconds(allowance, refund, Number(purchasedOrder.total_minor));
        await sql.query(`UPDATE minute_purchase_transactions SET state=$2,granted_ms=$3,refunded_minor=$4,
          reversal_target_ms=GREATEST(reversal_target_ms,$5),updated_at=now() WHERE order_id=$1`,
        [evidence.orderID, state, state === 'purchased' ? allowance : granted, refund, target]);
        await sql.query(`INSERT INTO minute_purchase_events(id,order_id,provider,environment,merchant,event_hash,evidence_hash,state,refunded_minor)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
        [randomUUID(), evidence.orderID, provider, evidence.environment, evidence.merchant, evidence.eventHash, evidenceHash, evidence.state, evidence.refundedMinor]);
      }
      // New purchases and repeated refund events also recover any older shortfall for this account.
      await recoverMinutePurchaseShortfalls(sql, purchasedOrder.account_id);
      return status(evidence.orderID, (await sql.query('SELECT * FROM minute_purchase_transactions WHERE order_id=$1', [evidence.orderID])).rows[0]);
    });
  }
  /** Call after a reservation settles/releases, and from the reconciliation worker. Safe to retry. */
  async reconcileAccount(accountID: string): Promise<void> {
    if (!uuid.test(accountID)) throw new ServiceError('account_not_found', 404);
    await transaction(this.db, async sql => { await lockMinuteWallet(sql, accountID, false); await recoverMinutePurchaseShortfalls(sql, accountID); });
  }
}
