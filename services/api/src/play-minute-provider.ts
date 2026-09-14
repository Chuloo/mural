import { createHmac, timingSafeEqual } from 'node:crypto';
import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import type { PurchaseEnvironment, VerifiedMinutePurchase } from './minute-purchases.js';
import { loadProviderOrder, MinuteReceiptVault, orderIDPattern, providerHash, type MinuteDeliveryAdapter } from './minute-provider-delivery.js';
import type { PlayTransport } from './google-play-transport.js';

export interface PlayMinuteConfig {
  packageName: string;
  environment?: PurchaseEnvironment;
  allowLive?: boolean;
  bindingKey: Buffer;
  /** Explicit ISO currency minor-unit exponents, e.g. { usd: 2 }. Catalog prices stay server-owned. */
  currencyExponents: Readonly<Record<string, number>>;
  purchasesEnabled?: boolean;
}
const sameHash = (left: unknown, right: string) => typeof left === 'string' && /^[a-f0-9]{64}$/.test(left) &&
  timingSafeEqual(Buffer.from(left, 'hex'), Buffer.from(right, 'hex'));
const validToken = (token: unknown): token is string => typeof token === 'string' && /^[\x21-\x7e]{1,4096}$/.test(token);
function moneyMinor(value: any, expectedCurrency: string, exponent: number): number {
  if (!value || value.currencyCode !== expectedCurrency.toUpperCase() || typeof value.units !== 'string' || !/^\d{1,9}$/.test(value.units) ||
    !Number.isSafeInteger(value.nanos ?? 0) || (value.nanos ?? 0) < 0 || (value.nanos ?? 0) > 999_999_999)
    throw new ServiceError('play_price_not_reconciled', 409);
  const nanos = BigInt(value.units) * 1_000_000_000n + BigInt(value.nanos ?? 0), divisor = 10n ** BigInt(9 - exponent);
  if (nanos % divisor !== 0n || nanos / divisor > 100_000_000n) throw new ServiceError('play_price_not_reconciled', 409);
  return Number(nanos / divisor);
}

export class PlayMinuteProvider implements MinuteDeliveryAdapter {
  readonly provider = 'play' as const;
  readonly environment: PurchaseEnvironment;
  readonly merchant: string;
  readonly #bindingKey: Buffer;
  readonly #exponents: Readonly<Record<string, number>>;
  readonly #purchasesEnabled: boolean;
  constructor(readonly db: Database, readonly vault: MinuteReceiptVault, config: PlayMinuteConfig, readonly transport: PlayTransport) {
    this.environment = config.environment ?? 'test';
    if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*){1,9}$/.test(config.packageName) || config.packageName.length > 200 ||
      !['test','live'].includes(this.environment) || (this.environment === 'live' && config.allowLive !== true) ||
      !Buffer.isBuffer(config.bindingKey) || config.bindingKey.length !== 32 || !Object.keys(config.currencyExponents).length ||
      Object.entries(config.currencyExponents).some(([currency, exponent]) => !/^[a-z]{3}$/.test(currency) || !Number.isInteger(exponent) || exponent < 0 || exponent > 3))
      throw new ServiceError('play_minute_configuration_invalid', 503);
    this.merchant = config.packageName; this.#bindingKey = Buffer.from(config.bindingKey);
    this.#exponents = Object.freeze({ ...config.currencyExponents }); this.#purchasesEnabled = config.purchasesEnabled === true;
  }
  #binding(purpose: string, value: string): string {
    return createHmac('sha256', this.#bindingKey).update(JSON.stringify(['mural-play-v1', this.environment, this.merchant, purpose, value])).digest('hex');
  }
  /** Set both returned values on BillingFlowParams; accountID must be the authenticated principal. */
  async prepare(accountID: string, orderID: string): Promise<{ orderID: string; obfuscatedAccountID: string; obfuscatedProfileID: string }> {
    if (!this.#purchasesEnabled) throw new ServiceError('minute_purchases_unavailable', 503);
    const order = await loadProviderOrder(this.db, orderID, this, accountID);
    if (this.#exponents[order.currency] === undefined || (order.entitlement_kind==='ai_value' && order.ai_value_quote?.currencyExponent!==this.#exponents[order.currency]))
      throw new ServiceError('play_currency_not_configured', 503);
    const accountHash = this.#binding('account', accountID), orderHash = this.#binding('order', order.id);
    await this.db.query(`INSERT INTO minute_play_order_bindings(order_id,account_hash,order_hash) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`, [order.id, accountHash, orderHash]);
    const saved = (await this.db.query('SELECT account_hash,order_hash FROM minute_play_order_bindings WHERE order_id=$1', [order.id])).rows[0];
    if (!sameHash(saved.account_hash, accountHash) || !sameHash(saved.order_hash, orderHash)) throw new ServiceError('play_binding_configuration_changed', 409);
    return { orderID: order.id, obfuscatedAccountID: accountHash, obfuscatedProfileID: orderHash };
  }
  async #facts(token: string, expectedOrderID?: string, accountID?: string): Promise<{ evidence: VerifiedMinutePurchase; consumptionState: string }> {
    const purchase = await this.transport.purchase(this.merchant, token);
    if (!purchase || typeof purchase.obfuscatedExternalProfileId !== 'string' || !/^[a-f0-9]{64}$/.test(purchase.obfuscatedExternalProfileId)) throw new ServiceError('play_purchase_binding_invalid', 409);
    const binding = (await this.db.query('SELECT * FROM minute_play_order_bindings WHERE order_hash=$1', [purchase.obfuscatedExternalProfileId])).rows[0];
    if (!binding || (expectedOrderID !== undefined && binding.order_id !== expectedOrderID) || !sameHash(purchase.obfuscatedExternalAccountId, binding.account_hash))
      throw new ServiceError('play_purchase_binding_invalid', 409);
    const order = await loadProviderOrder(this.db, binding.order_id, this, accountID);
    const test = purchase.testPurchaseContext;
    if ((this.environment === 'test' && test?.fopType !== 'TEST') || (this.environment === 'live' && test !== undefined))
      throw new ServiceError('play_purchase_environment_mismatch', 409);
    const lines = purchase.productLineItem, item = lines?.[0], details = item?.productOfferDetails;
    if (!Array.isArray(lines) || lines.length !== 1 || item.productId !== order.provider_product || !details || details.quantity !== 1 ||
      (details.refundableQuantity !== undefined && ![0,1].includes(details.refundableQuantity)) || details.rentOfferDetails || details.preorderOfferDetails ||
      !['CONSUMPTION_STATE_YET_TO_BE_CONSUMED','CONSUMPTION_STATE_CONSUMED'].includes(details.consumptionState))
      throw new ServiceError('play_purchase_product_mismatch', 409);
    const purchaseState = purchase.purchaseStateContext?.purchaseState;
    if (!['PURCHASED','PENDING','CANCELLED'].includes(purchaseState)) throw new ServiceError('play_purchase_state_invalid', 409);
    let state: VerifiedMinutePurchase['state'] = purchaseState === 'PENDING' ? 'pending' : purchaseState === 'CANCELLED' ? 'voided' : 'purchased';
    let refundedMinor = 0;
    if (purchaseState !== 'PENDING' && purchase.orderId) {
      if (typeof purchase.orderId !== 'string' || !/^GPA\.[0-9.-]{8,100}$/.test(purchase.orderId)) throw new ServiceError('play_order_not_reconciled', 409);
      const paidOrder = await this.transport.order(this.merchant, purchase.orderId), paidItem = paidOrder?.lineItems?.[0];
      const exponent = order.entitlement_kind==='ai_value' ? order.ai_value_quote?.currencyExponent : this.#exponents[order.currency];
      if (!Number.isInteger(exponent) || exponent<0 || exponent>3 || paidOrder?.orderId !== purchase.orderId || paidOrder.purchaseToken !== token ||
        !Array.isArray(paidOrder.lineItems) || paidOrder.lineItems.length !== 1 || paidItem.productId !== order.provider_product ||
        paidItem.oneTimePurchaseDetails?.quantity !== 1 || paidItem.subscriptionDetails || paidItem.paidAppDetails ||
        paidItem.oneTimePurchaseDetails?.rentalDetails || paidItem.oneTimePurchaseDetails?.preorderDetails ||
        !['PROCESSED','PENDING_REFUND','PARTIALLY_REFUNDED','REFUNDED','CANCELED'].includes(paidOrder.state) ||
        moneyMinor(paidOrder.total, order.currency, exponent) !== Number(order.total_minor) ||
        moneyMinor(paidItem.total, order.currency, exponent) !== Number(order.total_minor)) throw new ServiceError('play_order_not_reconciled', 409);
      const refunds = paidOrder.orderHistory?.partialRefundEvents ?? [];
      if (!Array.isArray(refunds) || refunds.length > 1000) throw new ServiceError('play_refund_not_reconciled', 409);
      for (const refund of refunds) {
        if (!['PENDING','PROCESSED_SUCCESSFULLY'].includes(refund?.state)) throw new ServiceError('play_refund_not_reconciled', 409);
        if (refund.state === 'PROCESSED_SUCCESSFULLY') refundedMinor += moneyMinor(refund.refundDetails?.total, order.currency, exponent);
      }
      if (paidOrder.state === 'REFUNDED') {
        const full = moneyMinor(paidOrder.orderHistory?.refundEvent?.refundDetails?.total, order.currency, exponent);
        // A full-refund event may represent the remaining amount after earlier partial refunds.
        if (full !== Number(order.total_minor) && full + refundedMinor !== Number(order.total_minor)) throw new ServiceError('play_refund_not_reconciled', 409);
        refundedMinor = Number(order.total_minor);
        state = 'voided';
      }
      if (!Number.isSafeInteger(refundedMinor) || refundedMinor > Number(order.total_minor) ||
        (paidOrder.state === 'PARTIALLY_REFUNDED' && refundedMinor === 0)) throw new ServiceError('play_refund_not_reconciled', 409);
      if (paidOrder.state === 'CANCELED' || paidOrder.orderHistory?.refundEvent?.refundReason === 'CHARGEBACK') state = 'voided';
    } else if (purchaseState === 'PURCHASED') {
      // Promo purchases can omit orderId. Paid minute packs require a verifiable monetary order.
      throw new ServiceError('play_order_not_reconciled', 409);
    }
    if (details.refundableQuantity === 0 || (await this.db.query('SELECT 1 FROM minute_provider_voids WHERE order_id=$1', [order.id])).rowCount) state = 'voided';
    const snapshot = { orderID: order.id, tokenHash: providerHash(token), state, refundedMinor, total: Number(order.total_minor) };
    return { evidence: { provider: this.provider, environment: this.environment, merchant: this.merchant, orderID: order.id,
      transactionID: token, eventID: `play-snapshot:${providerHash(JSON.stringify(snapshot))}`, providerProduct: order.provider_product,
      quantity: 1, currency: order.currency, totalMinor: Number(order.total_minor), state, refundedMinor }, consumptionState: details.consumptionState };
  }
  async verify(input: unknown): Promise<VerifiedMinutePurchase> {
    if (!input || typeof input !== 'object') throw new ServiceError('invalid_play_verification');
    const request = input as any;
    if (request.kind === 'recovery') {
      if (Object.keys(request).some(key => !['kind','accountID','purchaseToken'].includes(key)))
        throw new ServiceError('invalid_play_verification');
      return this.recover(request.accountID, request.purchaseToken);
    }
    let token: string, orderID: string | undefined, accountID: string | undefined;
    if (request.kind === 'stored') { orderID = request.orderID; token = await this.vault.read(request.orderID, this); }
    else if (request.kind === 'client') {
      if (!orderIDPattern.test(request.orderID) || !orderIDPattern.test(request.accountID) || !validToken(request.purchaseToken))
        throw new ServiceError('invalid_play_verification');
      orderID = request.orderID; accountID = request.accountID; token = request.purchaseToken;
      await loadProviderOrder(this.db, orderID!, this, accountID);
    } else throw new ServiceError('invalid_play_verification');
    const { evidence } = await this.#facts(token, orderID, accountID);
    await this.vault.save(evidence.orderID, this, token, request.kind !== 'stored');
    return evidence;
  }
  /** Recovers an unconsumed purchase after reinstall without trusting a client-side order identifier. */
  async recover(accountID: string, purchaseToken: string): Promise<VerifiedMinutePurchase> {
    if (!orderIDPattern.test(accountID) || !validToken(purchaseToken)) throw new ServiceError('invalid_play_verification');
    if (!(await this.db.query('SELECT 1 FROM accounts WHERE id=$1 AND deleted_at IS NULL AND NOT is_guest', [accountID])).rowCount)
      throw new ServiceError('sign_in_required', 401);
    const { evidence } = await this.#facts(purchaseToken, undefined, accountID);
    await this.vault.save(evidence.orderID, this, purchaseToken);
    return evidence;
  }
  async complete(orderID: string): Promise<void> {
    const token = await this.vault.read(orderID, this), facts = await this.#facts(token, orderID);
    if (facts.evidence.state !== 'purchased') throw new ServiceError('play_delivery_state_changed', 409);
    if (facts.consumptionState === 'CONSUMPTION_STATE_CONSUMED') return;
    await this.transport.consume(this.merchant, facts.evidence.providerProduct, token);
  }
  /** Poll authenticated Google voids; callers persist the cursor only after this batch succeeds. */
  async pollVoids(startMilliseconds: number, endMilliseconds: number, pageToken?: string): Promise<{ scheduled: number; nextPageToken?: string }> {
    if (!Number.isSafeInteger(startMilliseconds) || !Number.isSafeInteger(endMilliseconds) || startMilliseconds < Date.now() - 30 * 86_400_000 ||
      startMilliseconds >= endMilliseconds || endMilliseconds > Date.now() || (pageToken !== undefined && !/^[\x21-\x7e]{1,4096}$/.test(pageToken)))
      throw new ServiceError('invalid_void_window');
    const response = await this.transport.voided(this.merchant, startMilliseconds, endMilliseconds, pageToken);
    const voids = response?.voidedPurchases ?? [];
    if (!Array.isArray(voids) || voids.length > 1000) throw new ServiceError('play_void_response_invalid', 502);
    let scheduled = 0;
    for (const voided of voids) {
      if (!validToken(voided?.purchaseToken) || (voided.voidedQuantity !== undefined && voided.voidedQuantity !== 1))
        throw new ServiceError('play_void_response_invalid', 502);
      const orderID = await this.vault.find(this, voided.purchaseToken);
      if (orderID) { await this.vault.rememberVoid(orderID); scheduled++; }
    }
    const next = response?.tokenPagination?.nextPageToken;
    if (next !== undefined && !/^[\x21-\x7e]{1,4096}$/.test(next)) throw new ServiceError('play_void_response_invalid', 502);
    return { scheduled, ...(next ? { nextPageToken: next } : {}) };
  }
}
export function configuredPlayMinuteProvider(db: Database, vault: MinuteReceiptVault, config: PlayMinuteConfig | undefined,
  transport: PlayTransport | undefined): PlayMinuteProvider | undefined {
  if (!config && !transport) return undefined;
  if (!config || !transport) throw new ServiceError('play_minute_configuration_invalid', 503);
  return new PlayMinuteProvider(db, vault, config, transport);
}
