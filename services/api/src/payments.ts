import { randomUUID } from 'node:crypto';
import Stripe from 'stripe';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';
import { NANO_USD } from './pricing.js';

export interface TopUp { id: string; priceID: string; aiMinor: number; serviceFeeMinor: number; paymentFeeMinor: number }
export type Catalog = ReadonlyMap<string, TopUp>;
export function catalogFromEnvironment(env: NodeJS.ProcessEnv): Catalog {
  const products: TopUp[] = [];
  for (const dollars of [10, 25]) {
    const priceID = env[`STRIPE_TEST_PRICE_${dollars}_USD`];
    const fee = env[`STRIPE_TEST_PAYMENT_FEE_${dollars}_MINOR`];
    if (!priceID || !/^price_[A-Za-z0-9]+$/.test(priceID) || !fee || !/^\d+$/.test(fee)) continue;
    const paymentFeeMinor = Number(fee);
    if (!Number.isSafeInteger(paymentFeeMinor) || paymentFeeMinor > 10_000) continue;
    products.push({ id: `ai-${dollars}-usd`, priceID, aiMinor: dollars * 100, serviceFeeMinor: dollars * 15, paymentFeeMinor });
  }
  return new Map(products.map(product => [product.id, product]));
}
export class SandboxPayments {
  readonly stripe: Stripe;
  constructor(key: string, readonly webhookSecret: string, readonly catalog: Catalog, readonly origin: string) {
    if (!key.startsWith('sk_test_') || !webhookSecret.startsWith('whsec_')) throw new ServiceError('sandbox_credentials_required', 503);
    this.stripe = new Stripe(key, { maxNetworkRetries: 1, timeout: 10_000 });
  }
  verify(raw: Buffer, signature: string): Stripe.Event {
    try {
      const event = this.stripe.webhooks.constructEvent(raw, signature, this.webhookSecret);
      if (event.livemode) throw new Error();
      return event;
    } catch { throw new ServiceError('invalid_webhook_signature', 400); }
  }
  async checkout(db: Database, account: string, productID: string, key: string) {
    const product = this.catalog.get(productID);
    if (!product || !key || key.length > 128) throw new ServiceError('invalid_checkout');
    const total = product.aiMinor + product.serviceFeeMinor + product.paymentFeeMinor;
    const order = await transaction(db, async sql => {
      await lockWallet(sql, account, true);
      const previous = (await sql.query('SELECT * FROM checkout_orders WHERE account_id=$1 AND idempotency_key=$2', [account, key])).rows[0];
      if (previous) {
        if (previous.product !== productID) throw new ServiceError('idempotency_conflict', 409);
        return previous;
      }
      const id = randomUUID();
      return (await sql.query(`INSERT INTO checkout_orders(id,account_id,idempotency_key,product,currency,total_minor,credit_nano,stripe_price_id)
        VALUES($1,$2,$3,$4,'usd',$5,$6,$7) RETURNING *`, [id, account, key, productID, total,
        (BigInt(product.aiMinor) * NANO_USD / 100n).toString(), product.priceID])).rows[0];
    });
    if (order.state !== 'created') throw new ServiceError('checkout_already_paid', 409);
    const aiMinor = Number(BigInt(order.credit_nano) * 100n / NANO_USD);
    const serviceFeeMinor = Math.ceil(aiMinor * 15 / 100);
    const paymentFeeMinor = Number(order.total_minor) - aiMinor - serviceFeeMinor;
    const result = (session: Stripe.Checkout.Session) => {
      if (session.livemode || session.client_reference_id !== order.id || session.mode !== 'payment' ||
          session.currency !== order.currency || session.amount_total !== Number(order.total_minor)) throw new ServiceError('invalid_checkout_response', 502);
      if (session.status !== 'open' || !session.url) throw new ServiceError('checkout_no_longer_open', 409);
      return { checkoutURL: session.url, orderID: order.id, sandbox: true,
        quote: { currency: 'USD', aiMinor, serviceFeeMinor, paymentFeeMinor } };
    };
    // Stripe can prune idempotency keys after 24 hours. A mapped order always retrieves its original session.
    if (order.stripe_session_id) return result(await this.stripe.checkout.sessions.retrieve(order.stripe_session_id));
    const price = await this.stripe.prices.retrieve(order.stripe_price_id);
    if (!price.active || price.livemode || price.currency !== 'usd' || price.unit_amount !== Number(order.total_minor) || price.type !== 'one_time')
      throw new ServiceError('stripe_price_mismatch', 503);
    const attempted = await db.query(`UPDATE checkout_orders SET create_attempted_at=COALESCE(create_attempted_at,now())
      WHERE id=$1 AND (create_attempted_at IS NULL OR create_attempted_at>now()-interval '23 hours') RETURNING id`, [order.id]);
    if (!attempted.rowCount) throw new ServiceError('checkout_reconciliation_required', 409);
    const session = await this.stripe.checkout.sessions.create({
      mode: 'payment', client_reference_id: order.id, line_items: [{ price: order.stripe_price_id, quantity: 1 }],
      adaptive_pricing: { enabled: false },
      success_url: `${this.origin}/payment-return?status=success`, cancel_url: `${this.origin}/payment-return?status=cancelled`,
      metadata: { mural_order_id: order.id }, allow_promotion_codes: false,
      custom_text: { submit: { message: `AI usage: $${(aiMinor / 100).toFixed(2)}. Mural fee (15%): $${(serviceFeeMinor / 100).toFixed(2)}. Payment fee: $${(paymentFeeMinor / 100).toFixed(2)}. USD. Sandbox only.` } }
    }, { idempotencyKey: `mural-checkout-${order.id}` });
    try {
      const response = result(session);
      await transaction(db, async sql => {
        await lockWallet(sql, account, true);
        const mapped = await sql.query(`UPDATE checkout_orders SET stripe_session_id=$2
          WHERE id=$1 AND state='created' AND (stripe_session_id IS NULL OR stripe_session_id=$2) RETURNING id`, [order.id, session.id]);
        if (!mapped.rowCount) throw new ServiceError('checkout_mapping_conflict', 409);
      });
      return response;
    } catch (error) {
      // Never return an untracked payable URL. Failed cleanup leaves the order pending for reconciliation.
      if (!session.livemode && session.status === 'open') await this.stripe.checkout.sessions.expire(session.id).catch(() => {});
      throw error;
    }
  }
}

const objectID = (value: unknown): string | null => typeof value === 'string' ? value : value && typeof value === 'object' && 'id' in value && typeof value.id === 'string' ? value.id : null;

// Only call with a cryptographically verified Stripe event. Entire accounting update + receipt are atomic.
export async function applyStripeEvent(db: Database, event: Stripe.Event): Promise<void> {
  if (event.livemode) throw new ServiceError('live_payments_not_enabled', 503);
  await transaction(db, async sql => {
    await sql.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`stripe:${event.id}`]);
    if ((await sql.query("SELECT event_id FROM webhook_receipts WHERE provider='stripe' AND event_id=$1", [event.id])).rowCount) return;
    const object = event.data.object as unknown as Record<string, unknown>;
    const id = objectID(object);
    if (!id) throw new ServiceError('invalid_webhook');
    if (event.type === 'checkout.session.completed' || event.type === 'checkout.session.async_payment_succeeded') {
      const reference = object.client_reference_id;
      if (typeof reference !== 'string' || !/^[a-f0-9-]{36}$/.test(reference)) throw new ServiceError('unmapped_checkout', 409);
      const owner = (await sql.query('SELECT account_id FROM checkout_orders WHERE id=$1', [reference])).rows[0];
      if (!owner) throw new ServiceError('unmapped_checkout', 409);
      await lockWallet(sql, owner.account_id);
      const order = (await sql.query('SELECT * FROM checkout_orders WHERE id=$1 FOR UPDATE', [reference])).rows[0];
      if ((order.stripe_session_id && order.stripe_session_id !== id) || object.mode !== 'payment' ||
          object.currency !== order.currency || object.amount_total !== Number(order.total_minor)) throw new ServiceError('payment_mismatch', 409);
      const paymentIntent = objectID(object.payment_intent);
      if (object.payment_status === 'paid') {
        if (!paymentIntent) throw new ServiceError('payment_intent_missing', 409);
        if (order.payment_intent_id && order.payment_intent_id !== paymentIntent) throw new ServiceError('payment_mismatch', 409);
        const priorGrant=(await sql.query('SELECT sandbox_delta_nano FROM ledger WHERE reference=$1',[`purchase:${order.id}`])).rows[0];
        // Pre-migration rows retain their original facts and an unverified wallet provenance flag.
        // New legacy checkouts remain sandbox-only, even if public cash conversations are enabled.
        await appendEntry(sql, order.account_id, `purchase:${order.id}`, 'purchase', BigInt(order.credit_nano), 0n, null,
          priorGrant ? BigInt(priorGrant.sandbox_delta_nano) : BigInt(order.credit_nano));
        await sql.query(`UPDATE checkout_orders SET stripe_session_id=$2,payment_intent_id=$3,
          state=CASE WHEN state='created' THEN 'paid' ELSE state END WHERE id=$1`, [order.id, id, paymentIntent]);
      }
    } else if (event.type === 'charge.refunded' || event.type === 'charge.dispute.created') {
      const paymentIntent = objectID(object.payment_intent);
      if (!paymentIntent) throw new ServiceError('payment_intent_missing', 409);
      const owner = (await sql.query('SELECT account_id FROM checkout_orders WHERE payment_intent_id=$1', [paymentIntent])).rows[0];
      // A refund arriving before payment confirmation is retried, never discarded.
      if (!owner) throw new ServiceError('payment_not_reconciled', 409);
      await lockWallet(sql, owner.account_id);
      const order = (await sql.query('SELECT * FROM checkout_orders WHERE payment_intent_id=$1 FOR UPDATE', [paymentIntent])).rows[0];
      const disputed = event.type === 'charge.dispute.created';
      const refunded = disputed ? BigInt(order.refunded_minor) : typeof object.amount_refunded === 'number' && Number.isSafeInteger(object.amount_refunded) ? BigInt(object.amount_refunded) : -1n;
      const total = BigInt(order.total_minor), credit = BigInt(order.credit_nano);
      if (refunded < 0n || refunded > total) throw new ServiceError('invalid_refund', 409);
      const target = disputed || order.state === 'disputed' ? credit : (credit * refunded + total - 1n) / total;
      const reversed = BigInt(order.reversed_nano);
      if (target > reversed) {
        await appendEntry(sql, order.account_id, `reversal:${order.id}:${target}`, 'reversal', -(target - reversed), 0n, null, -(target - reversed));
        await sql.query(`UPDATE checkout_orders SET reversed_nano=$2,refunded_minor=GREATEST(refunded_minor,$3),state=$4 WHERE id=$1`,
          [order.id, target.toString(), refunded.toString(), disputed || order.state === 'disputed' ? 'disputed' : 'refunded']);
      }
    }
    await sql.query("INSERT INTO webhook_receipts(provider,event_id,object_id,event_type) VALUES('stripe',$1,$2,$3)", [event.id, id, event.type]);
  });
}
