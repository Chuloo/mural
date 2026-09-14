import Stripe from 'stripe';
import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import type { PurchaseEnvironment, VerifiedMinutePurchase } from './minute-purchases.js';
import { loadProviderOrder, MinuteReceiptVault, providerHash, type MinuteDeliveryAdapter } from './minute-provider-delivery.js';

/** Narrow transport boundary keeps contract tests offline while production uses the official SDK. */
export interface StripeMinuteTransport {
  account(): Promise<{ id: string }>;
  price(id: string): Promise<Stripe.Price>;
  create(params: Stripe.Checkout.SessionCreateParams, key: string): Promise<Stripe.Checkout.Session>;
  session(id: string): Promise<Stripe.Checkout.Session>;
  lines(id: string): Promise<Stripe.ApiList<Stripe.LineItem>>;
  sessionsForIntent(id: string): Promise<Stripe.ApiList<Stripe.Checkout.Session>>;
  intent(id: string): Promise<Stripe.PaymentIntent>;
  charge(id: string): Promise<Stripe.Charge>;
  refunds(intent: string, after?: string): Promise<Stripe.ApiList<Stripe.Refund>>;
  verifyEvent(raw: Buffer, signature: string): Stripe.Event;
}
export interface StripeMinuteConfig {
  secretKey: string;
  webhookSecret: string;
  accountID: string;
  environment?: PurchaseEnvironment;
  allowLive?: boolean;
  checkoutEnabled?: boolean;
  webOrigin: string;
}
function validateConfig(config: StripeMinuteConfig) {
  const environment = config.environment ?? 'test';
  if (!['test','live'].includes(environment) || (environment === 'live' && config.allowLive !== true) ||
    !new RegExp(`^(?:sk|rk)_${environment}_[A-Za-z0-9]{8,}$`).test(config.secretKey) ||
    !/^whsec_[A-Za-z0-9]{8,}$/.test(config.webhookSecret) || !/^acct_[A-Za-z0-9]+$/.test(config.accountID))
    throw new ServiceError('stripe_minute_configuration_invalid', 503);
  let origin: URL;
  try { origin = new URL(config.webOrigin); } catch { throw new ServiceError('stripe_minute_configuration_invalid', 503); }
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.port || origin.pathname !== '/' || origin.search || origin.hash)
    throw new ServiceError('stripe_minute_configuration_invalid', 503);
  return { environment, origin: origin.origin };
}
export class StripeSDKMinuteTransport implements StripeMinuteTransport {
  readonly #stripe: Stripe;
  readonly #webhookSecret: string;
  constructor(config: StripeMinuteConfig) {
    validateConfig(config);
    this.#stripe = new Stripe(config.secretKey, { maxNetworkRetries: 1, timeout: 15_000 }); this.#webhookSecret = config.webhookSecret;
  }
  account() { return this.#stripe.accounts.retrieve(null); }
  price(id: string) { return this.#stripe.prices.retrieve(id); }
  create(params: Stripe.Checkout.SessionCreateParams, key: string) { return this.#stripe.checkout.sessions.create(params, { idempotencyKey: key }); }
  session(id: string) { return this.#stripe.checkout.sessions.retrieve(id); }
  lines(id: string) { return this.#stripe.checkout.sessions.listLineItems(id, { limit: 2 }); }
  sessionsForIntent(id: string) { return this.#stripe.checkout.sessions.list({ payment_intent: id, limit: 2 }); }
  intent(id: string) { return this.#stripe.paymentIntents.retrieve(id); }
  charge(id: string) { return this.#stripe.charges.retrieve(id); }
  refunds(intent: string, after?: string) { return this.#stripe.refunds.list({ payment_intent: intent, limit: 100, ...(after ? { starting_after: after } : {}) }); }
  verifyEvent(raw: Buffer, signature: string) { return this.#stripe.webhooks.constructEvent(raw, signature, this.#webhookSecret, 300); }
}
const objectID = (value: any): string | undefined => typeof value === 'string' ? value : typeof value?.id === 'string' ? value.id : undefined;
const stripeID = (value: unknown, prefix: string) => typeof value === 'string' && new RegExp(`^${prefix}_[A-Za-z0-9_]{1,250}$`).test(value);
const integerMoney = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 100_000_000;
const incomingEvents = new Set(['checkout.session.completed','checkout.session.async_payment_succeeded','checkout.session.async_payment_failed',
  'checkout.session.expired','charge.refunded','charge.dispute.created','charge.dispute.updated','charge.dispute.closed',
  'refund.created','refund.updated','refund.failed']);

export class StripeMinuteProvider implements MinuteDeliveryAdapter {
  readonly provider = 'stripe' as const;
  readonly environment: PurchaseEnvironment;
  readonly merchant: string;
  readonly #origin: string;
  readonly #checkoutEnabled: boolean;
  constructor(readonly db: Database, readonly vault: MinuteReceiptVault, config: StripeMinuteConfig,
    readonly transport: StripeMinuteTransport = new StripeSDKMinuteTransport(config)) {
    const validated = validateConfig(config); this.environment = validated.environment; this.merchant = config.accountID;
    this.#origin = validated.origin; this.#checkoutEnabled = config.checkoutEnabled === true;
  }
  async #account() {
    if ((await this.transport.account()).id !== this.merchant) throw new ServiceError('stripe_merchant_mismatch', 503);
  }
  #sessionMatches(session: Stripe.Checkout.Session, order: any) {
    if (!stripeID(session.id, 'cs') || session.livemode !== (this.environment === 'live') || session.mode !== 'payment' ||
      session.client_reference_id !== order.id || session.metadata?.mural_minute_order !== order.id ||
      session.currency !== order.currency || session.amount_total !== Number(order.total_minor)) throw new ServiceError('stripe_minute_payment_mismatch', 409);
  }
  async checkout(accountID: string, orderID: string): Promise<{ orderID: string; checkoutURL: string }> {
    try { return await this.#checkout(accountID, orderID); }
    catch (error) {
      if (error instanceof ServiceError) throw error;
      throw new ServiceError('stripe_provider_unavailable', 502);
    }
  }
  async #checkout(accountID: string, orderID: string): Promise<{ orderID: string; checkoutURL: string }> {
    if (!this.#checkoutEnabled) throw new ServiceError('minute_purchases_unavailable', 503);
    const order = await loadProviderOrder(this.db, orderID, this, accountID); await this.#account();
    let session: Stripe.Checkout.Session;
    const mapped = (await this.db.query('SELECT 1 FROM minute_provider_receipts WHERE order_id=$1', [order.id])).rowCount;
    if (mapped) session = await this.transport.session(await this.vault.read(order.id, this));
    else {
      const price = await this.transport.price(order.provider_product);
      if (price.id !== order.provider_product || !price.active || price.livemode !== (this.environment === 'live') ||
        price.currency !== order.currency || price.unit_amount !== Number(order.total_minor) || price.type !== 'one_time')
        throw new ServiceError('stripe_minute_price_mismatch', 503);
      await this.db.query('INSERT INTO minute_stripe_checkout_attempts(order_id) VALUES($1) ON CONFLICT DO NOTHING', [order.id]);
      const safe = (await this.db.query("SELECT 1 FROM minute_stripe_checkout_attempts WHERE order_id=$1 AND started_at>now()-interval '23 hours'", [order.id])).rowCount;
      if (!safe) throw new ServiceError('checkout_reconciliation_required', 409);
      session = await this.transport.create({ mode: 'payment', client_reference_id: order.id,
        metadata: { mural_minute_order: order.id }, payment_intent_data: { metadata: { mural_minute_order: order.id } },
        line_items: [{ price: order.provider_product, quantity: 1 }], adaptive_pricing: { enabled: false },
        allow_promotion_codes: false, automatic_tax: { enabled: false },
        success_url: `${this.#origin}/payment-return?status=success`, cancel_url: `${this.#origin}/payment-return?status=cancelled` },
      `mural-minute-checkout-${order.id}`);
    }
    this.#sessionMatches(session, order);
    // Never return a payable URL until its receipt and retry job are durable.
    await this.vault.save(order.id, this, session.id);
    let url: URL;
    try { url = new URL(session.url ?? ''); } catch { throw new ServiceError('checkout_no_longer_open', 409); }
    if (session.status !== 'open' || url.protocol !== 'https:' || url.hostname !== 'checkout.stripe.com' || url.port || url.username || url.password)
      throw new ServiceError('checkout_no_longer_open', 409);
    return { orderID: order.id, checkoutURL: url.href };
  }
  async #webhookSession(input: any): Promise<string> {
    if (!Buffer.isBuffer(input.raw) || input.raw.length > 524_288 || typeof input.signature !== 'string' || input.signature.length > 1024)
      throw new ServiceError('invalid_webhook_signature');
    let event: Stripe.Event;
    try { event = this.transport.verifyEvent(input.raw, input.signature); } catch { throw new ServiceError('invalid_webhook_signature'); }
    if (event.livemode !== (this.environment === 'live') || (event.account && event.account !== this.merchant))
      throw new ServiceError('stripe_event_scope_mismatch');
    if (!incomingEvents.has(event.type)) throw new ServiceError('stripe_event_not_supported');
    const object = event.data.object as any;
    if (event.type.startsWith('checkout.session.')) {
      if (!stripeID(object.id, 'cs')) throw new ServiceError('invalid_stripe_reference');
      return object.id;
    }
    let intent = objectID(object.payment_intent);
    if (!intent && stripeID(objectID(object.charge), 'ch')) intent = objectID((await this.transport.charge(objectID(object.charge)!)).payment_intent);
    if (!stripeID(intent, 'pi')) throw new ServiceError('invalid_stripe_reference');
    const sessions = await this.transport.sessionsForIntent(intent!);
    if (sessions.has_more || sessions.data.length !== 1 || !stripeID(sessions.data[0]!.id, 'cs')) throw new ServiceError('unmapped_minute_purchase', 409);
    return sessions.data[0]!.id;
  }
  async verify(input: unknown): Promise<VerifiedMinutePurchase> {
    if (!input || typeof input !== 'object') throw new ServiceError('invalid_stripe_verification');
    const request = input as any;
    let sessionID: string;
    if (request.kind === 'stored') sessionID = await this.vault.read(request.orderID, this);
    else if (request.kind === 'webhook') sessionID = await this.#webhookSession(request);
    else throw new ServiceError('invalid_stripe_verification');
    await this.#account();
    const session = await this.transport.session(sessionID);
    if (session.id !== sessionID) throw new ServiceError('stripe_minute_payment_mismatch', 409);
    const order = await loadProviderOrder(this.db, session.client_reference_id ?? '', this);
    if (request.kind === 'stored' && order.id !== request.orderID) throw new ServiceError('stripe_minute_payment_mismatch', 409);
    this.#sessionMatches(session, order);
    const lines = await this.transport.lines(sessionID), line = lines.data[0];
    if (lines.has_more || lines.data.length !== 1 || !line || line.quantity !== 1 || line.price?.id !== order.provider_product ||
      line.currency !== order.currency || line.amount_total !== Number(order.total_minor)) throw new ServiceError('stripe_minute_product_mismatch', 409);
    let state: VerifiedMinutePurchase['state'], refundedMinor = 0;
    if (session.payment_status === 'paid') {
      if (session.status !== 'complete') throw new ServiceError('stripe_payment_not_reconciled', 409);
      const intentID = objectID(session.payment_intent);
      if (!stripeID(intentID, 'pi')) throw new ServiceError('stripe_payment_not_reconciled', 409);
      const intent = await this.transport.intent(intentID!);
      if (intent.id !== intentID || intent.livemode !== (this.environment === 'live') || intent.status !== 'succeeded' ||
        intent.currency !== order.currency || intent.amount_received !== Number(order.total_minor) ||
        intent.metadata.mural_minute_order !== order.id || !stripeID(objectID(intent.latest_charge), 'ch'))
        throw new ServiceError('stripe_payment_not_reconciled', 409);
      const charge = await this.transport.charge(objectID(intent.latest_charge)!);
      if (charge.id !== objectID(intent.latest_charge) || charge.status !== 'succeeded' || objectID(charge.payment_intent) !== intentID || charge.livemode !== (this.environment === 'live') || !charge.paid || !charge.captured ||
        charge.currency !== order.currency || charge.amount !== Number(order.total_minor)) throw new ServiceError('stripe_payment_not_reconciled', 409);
      let after: string | undefined, complete = false;
      const refundIDs = new Set<string>();
      for (let page = 0; page < 10; page++) {
        const refunds = await this.transport.refunds(intentID!, after);
        if (!Array.isArray(refunds.data) || refunds.data.length > 100) throw new ServiceError('stripe_refund_not_reconciled', 409);
        for (const refund of refunds.data) {
          if (refundIDs.has(refund.id) || !stripeID(refund.id, 're') || objectID(refund.payment_intent) !== intentID || refund.currency !== order.currency ||
            !integerMoney(refund.amount) || !['succeeded','pending','failed','canceled','requires_action'].includes(refund.status ?? '')) throw new ServiceError('stripe_refund_not_reconciled', 409);
          refundIDs.add(refund.id);
          if (refund.status === 'succeeded') refundedMinor += refund.amount;
        }
        if (!refunds.has_more) { complete = true; break; }
        const lastID = refunds.data.at(-1)?.id;
        if (!lastID || lastID === after) throw new ServiceError('stripe_refund_not_reconciled', 409);
        after = lastID;
      }
      if (!complete || !Number.isSafeInteger(refundedMinor) || refundedMinor > Number(order.total_minor)) throw new ServiceError('stripe_refund_not_reconciled', 409);
      state = charge.disputed ? 'voided' : 'purchased';
    } else if (session.payment_status === 'unpaid') state = session.status === 'expired' ? 'voided' : 'pending';
    else throw new ServiceError('stripe_payment_not_reconciled', 409);
    const snapshot = { orderID: order.id, sessionID, state, refundedMinor, total: Number(order.total_minor) };
    await this.vault.save(order.id, this, sessionID, request.kind !== 'stored');
    return { provider: this.provider, environment: this.environment, merchant: this.merchant,
      orderID: order.id, transactionID: sessionID, eventID: `stripe-snapshot:${providerHash(JSON.stringify(snapshot))}`,
      providerProduct: order.provider_product, quantity: 1, currency: order.currency, totalMinor: Number(order.total_minor), state, refundedMinor };
  }
  async complete(_orderID: string): Promise<void> { /* Stripe Checkout has no fulfillment acknowledgment API. */ }
}

/** Missing optional configuration leaves the provider absent; partial configuration is an error. */
export function configuredStripeMinuteProvider(db: Database, vault: MinuteReceiptVault, config?: StripeMinuteConfig): StripeMinuteProvider | undefined {
  return config ? new StripeMinuteProvider(db, vault, config) : undefined;
}
