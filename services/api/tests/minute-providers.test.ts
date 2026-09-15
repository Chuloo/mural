import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import Stripe from 'stripe';
import { jwtVerify, importSPKI } from 'jose';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { minuteBalance } from '../src/minutes.js';
import { AIValuePurchases, makeAIValueProduct } from '../src/ai-value-purchases.js';
import { MinutePurchases, type MinuteProduct } from '../src/minute-purchases.js';
import { MinuteReceiptVault, MinuteDeliveryWorker, providerHash } from '../src/minute-provider-delivery.js';
import { StripeMinuteProvider, configuredStripeMinuteProvider, normalizeStripeRefund, type StripeMinuteConfig, type StripeMinuteTransport } from '../src/stripe-minute-provider.js';
import { PlayMinuteProvider, configuredPlayMinuteProvider, type PlayMinuteConfig } from '../src/play-minute-provider.js';
import { GooglePlayHTTPTransport, GoogleServiceAccountTokens, type PlayTransport } from '../src/google-play-transport.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
const stripeConfig = (): StripeMinuteConfig => ({ secretKey: 'sk_test_' + 'fixture'.repeat(4), webhookSecret: 'whsec_' + 'fixture'.repeat(4),
  accountID: 'acct_synthetic', webOrigin: 'https://mural.example.test', checkoutEnabled: true });
const playConfig = (): PlayMinuteConfig => ({ packageName: 'chat.mural.synthetic', bindingKey: Buffer.alloc(32, 7), currencyExponents: { usd: 2 }, purchasesEnabled: true });
const total = { currencyCode: 'USD', units: '9', nanos: 970_000_000 };
const clone = <T>(value: T): T => structuredClone(value);
class FakeStripe implements StripeMinuteTransport {
  current: any; priceValue: any; intentValue: any; chargeValue: any; lineValue: any; refundsValue: any[] = [];
  merchant = 'acct_synthetic'; createCalls: any[] = []; refundPages?: any[];
  readonly stripe = new Stripe(stripeConfig().secretKey);
  constructor() { this.priceValue = { id: 'price_synthetic', active: true, livemode: false, currency: 'usd', unit_amount: 997, type: 'one_time' }; }
  bind(orderID: string) {
    this.current = { id: 'cs_test_synthetic', livemode: false, mode: 'payment', client_reference_id: orderID,
      metadata: { mural_minute_order: orderID }, currency: 'usd', amount_total: 997,
      status: 'open', payment_status: 'unpaid', url: 'https://checkout.stripe.com/c/pay/cs_test_synthetic', payment_intent: 'pi_synthetic' };
    this.lineValue = { quantity: 1, price: { id: 'price_synthetic' }, currency: 'usd', amount_total: 997 };
    this.intentValue = { id: 'pi_synthetic', livemode: false, status: 'succeeded', currency: 'usd', amount_received: 997,
      metadata: { mural_minute_order: orderID }, latest_charge: 'ch_synthetic' };
    this.chargeValue = { id: 'ch_synthetic', status: 'succeeded', payment_intent: 'pi_synthetic', livemode: false, paid: true, captured: true,
      currency: 'usd', amount: 997, disputed: false };
  }
  managed(tax = 200, base = 997) {
    this.priceValue.tax_behavior = 'exclusive'; this.priceValue.unit_amount = base;
    this.current.managed_payments = { enabled: true };
    for (const value of [this.current,this.intentValue,this.chargeValue]) value.presentment_details = null;
    this.current.amount_subtotal = base; this.current.amount_total = base + tax;
    this.current.total_details = { amount_discount: 0, amount_shipping: 0, amount_tax: tax };
    this.lineValue.price.tax_behavior = 'exclusive'; this.lineValue.amount_subtotal = base;
    this.lineValue.amount_tax = tax; this.lineValue.amount_discount = 0; this.lineValue.amount_total = base + tax;
    this.intentValue.managed_payments = { enabled: true }; this.intentValue.amount = base + tax; this.intentValue.amount_received = base + tax;
    this.chargeValue.amount = base + tax; this.chargeValue.amount_captured = base + tax;
  }
  paid() { this.current.status = 'complete'; this.current.payment_status = 'paid'; }
  async account() { return { id: this.merchant }; }
  async price() { return clone(this.priceValue); }
  async create(params: any, key: string) { this.createCalls.push({ params, key }); return clone(this.current); }
  async session() { return clone(this.current); }
  async lines() { return { data: [clone(this.lineValue)], has_more: false } as any; }
  async sessionsForIntent() { return { data: [clone(this.current)], has_more: false } as any; }
  async intent() { return clone(this.intentValue); }
  async charge() { return clone(this.chargeValue); }
  async refunds(_intent: string, after?: string) {
    if (this.refundPages) return clone(this.refundPages[after ? 1 : 0]);
    return { data: clone(this.refundsValue), has_more: false } as any;
  }
  verifyEvent(raw: Buffer, signature: string) { return this.stripe.webhooks.constructEvent(raw, signature, stripeConfig().webhookSecret); }
  event(type = 'checkout.session.completed', overrides: any = {}) {
    const data = type.startsWith('checkout') ? { id: this.current.id } : { id: 'ch_synthetic', payment_intent: 'pi_synthetic' };
    const payload = JSON.stringify({ id: `evt_${randomUUID().replaceAll('-', '')}`, object: 'event', type, livemode: false,
      account: this.merchant, data: { object: data }, ...overrides });
    return { kind: 'webhook', raw: Buffer.from(payload), signature: this.stripe.webhooks.generateTestHeaderString({ payload, secret: stripeConfig().webhookSecret }) };
  }
}
class FakePlay implements PlayTransport {
  purchaseValue: any; orderValue: any; voidsValue: any = {}; consumeCount = 0; failConsume = false;
  token = `synthetic-purchase-token.${randomUUID()}`;
  bind(binding: { obfuscatedAccountID: string; obfuscatedProfileID: string }) {
    this.purchaseValue = { productLineItem: [{ productId: 'synthetic_thirty', productOfferDetails: { quantity: 1, refundableQuantity: 1,
      consumptionState: 'CONSUMPTION_STATE_YET_TO_BE_CONSUMED' } }], purchaseStateContext: { purchaseState: 'PURCHASED' },
      testPurchaseContext: { fopType: 'TEST' }, orderId: 'GPA.1234-5678-9012-34567',
      obfuscatedExternalAccountId: binding.obfuscatedAccountID, obfuscatedExternalProfileId: binding.obfuscatedProfileID };
    this.orderValue = { orderId: this.purchaseValue.orderId, purchaseToken: this.token, state: 'PROCESSED', total: clone(total),
      lineItems: [{ productId: 'synthetic_thirty', total: clone(total), oneTimePurchaseDetails: { quantity: 1 } }], orderHistory: {} };
  }
  async purchase() { return clone(this.purchaseValue); }
  async order() { return clone(this.orderValue); }
  async consume() {
    this.consumeCount++;
    if (this.failConsume) throw new Error('Do not reflect provider credentials or bodies');
    this.purchaseValue.productLineItem[0].productOfferDetails.consumptionState = 'CONSUMPTION_STATE_CONSUMED';
  }
  async voided() { return clone(this.voidsValue); }
}
async function fixture(managedPayments = false) {
  const schema = `providers_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const encryptionKey = randomBytes(32), vault = new MinuteReceiptVault(db, 'test-key', new Map([['test-key', encryptionKey]]));
  const stripeTransport = new FakeStripe(), playTransport = new FakePlay();
  const stripe = new StripeMinuteProvider(db, vault, { ...stripeConfig(), managedPayments }, stripeTransport), play = new PlayMinuteProvider(db, vault, playConfig(), playTransport);
  const catalog: MinuteProduct[] = [{ provider: 'stripe', environment: 'test', merchant: stripe.merchant, sku: 'synthetic-thirty', providerProduct: 'price_synthetic', minutes: 30, currency: 'usd', totalMinor: 997 },
    { provider: 'play', environment: 'test', merchant: play.merchant, sku: 'synthetic-thirty', providerProduct: 'synthetic_thirty', minutes: 30, currency: 'usd', totalMinor: 997 }];
  const purchases = new MinutePurchases(db, { catalog, verifiers: [stripe,play], salesEnabled: true });
  const worker = new MinuteDeliveryWorker(db, purchases, [stripe,play]);
  return { db, schema, encryptionKey, vault, stripeTransport, playTransport, stripe, play, catalog, purchases, worker,
    async account() { const id = randomUUID(); await db.query('INSERT INTO accounts(id) VALUES($1)', [id]); return id; },
    async stripeOrder(account: string, bind = true) {
      const order = await purchases.createOrder(account, 'stripe', 'synthetic-thirty', randomUUID()); stripeTransport.bind(order.orderID);
      if (managedPayments) stripeTransport.managed();
      if (bind) await db.query('INSERT INTO minute_stripe_checkout_attempts(order_id,managed_payments) VALUES($1,$2)', [order.orderID,managedPayments]);
      return order;
    },
    async playOrder(account: string) { const order = await purchases.createOrder(account, 'play', 'synthetic-thirty', randomUUID()); playTransport.bind(await play.prepare(account, order.orderID)); return order; },
    async ready() { await db.query("UPDATE minute_provider_jobs SET available_at=now() WHERE state='pending'"); },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
  };
}

test('provider configuration stays absent when missing and requires explicit live activation', () => {
  const db = {} as any, vault = {} as any;
  assert.equal(configuredStripeMinuteProvider(db, vault), undefined);
  assert.equal(configuredPlayMinuteProvider(db, vault, undefined, undefined), undefined);
  assert.throws(() => new StripeMinuteProvider(db, vault, { ...stripeConfig(), environment: 'live', secretKey: 'sk_live_' + 'fixture'.repeat(4) }, new FakeStripe()), /configuration_invalid/);
  assert.throws(() => new StripeMinuteProvider(db, vault, { ...stripeConfig(), webOrigin: 'https://evil.test/path' }, new FakeStripe()), /configuration_invalid/);
  assert.throws(() => new PlayMinuteProvider(db, vault, { ...playConfig(), environment: 'live' }, new FakePlay()), /configuration_invalid/);
  assert.throws(() => new PlayMinuteProvider(db, vault, { ...playConfig(), bindingKey: Buffer.alloc(12) }, new FakePlay()), /configuration_invalid/);
  assert.throws(() => new MinuteReceiptVault(db, 'key', new Map([['key', Buffer.alloc(31)]])), /invalid_receipt_encryption/);
});

integration('Stripe checkout snapshots the server quote and persists its reference before exposing the URL', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account);
    const result = await f.stripe.checkout(account, order.orderID);
    assert.equal(result.orderID, order.orderID); assert.equal(new URL(result.checkoutURL).origin, 'https://checkout.stripe.com');
    assert.equal(await f.vault.read(order.orderID, f.stripe), f.stripeTransport.current.id);
    assert.equal((await f.db.query('SELECT state FROM minute_provider_jobs')).rows[0].state, 'pending');
    const call = f.stripeTransport.createCalls[0];
    assert.deepEqual(call.params.line_items, [{ price: 'price_synthetic', quantity: 1 }]);
    assert.equal(call.params.client_reference_id, order.orderID); assert.equal(call.params.allow_promotion_codes, false);
    assert.equal(call.params.payment_intent_data.metadata.mural_minute_order, order.orderID);
    await f.stripe.checkout(account, order.orderID); assert.equal(f.stripeTransport.createCalls.length, 1);
    await assert.rejects(f.stripe.checkout(await f.account(), order.orderID), /purchase_not_found/);
  } finally { await f.cleanup(); }
});

integration('Stripe wrong merchant, changed price and old uncertain checkout attempts cannot open a new payment', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account, false);
    f.stripeTransport.merchant = 'acct_other'; await assert.rejects(f.stripe.checkout(account, order.orderID), /stripe_merchant_mismatch/);
    f.stripeTransport.merchant = f.stripe.merchant; f.stripeTransport.priceValue.unit_amount++;
    await assert.rejects(f.stripe.checkout(account, order.orderID), /stripe_minute_price_mismatch/);
    f.stripeTransport.priceValue.unit_amount--;
    const older = await f.stripeOrder(account, false);
    await f.db.query("INSERT INTO minute_stripe_checkout_attempts(order_id,started_at) VALUES($1,now()-interval '25 hours')", [older.orderID]);
    await assert.rejects(f.stripe.checkout(account, older.orderID), /checkout_reconciliation_required/);
    assert.equal(f.stripeTransport.createCalls.length, 0);
  } finally { await f.cleanup(); }
});

integration('signed Stripe completion is re-fetched and duplicate webhooks cannot grant twice', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account); f.stripeTransport.paid();
    const event = f.stripeTransport.event();
    await assert.rejects(f.purchases.reconcile('stripe', { ...event, signature: 'invalid' }), /purchase_verification_failed/);
    await f.purchases.reconcile('stripe', event); await f.purchases.reconcile('stripe', event);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
    assert.deepEqual(await f.worker.runBatch(), { processed: 1, completed: 1, retried: 0 });
    assert.equal((await f.db.query('SELECT state FROM minute_provider_jobs')).rows[0].state, 'done');
    f.stripeTransport.lineValue.quantity = 2;
    await assert.rejects(f.purchases.reconcile('stripe', event), /purchase_verification_failed/);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_events')).rows[0].count, '1');
  } finally { await f.cleanup(); }
});

integration('Stripe accepts successful partial refunds only and handles full refunds and disputes', async () => {
  const f = await fixture();
  try {
    const account = await f.account(); await f.stripeOrder(account); f.stripeTransport.paid();
    const event = f.stripeTransport.event(); await f.purchases.reconcile('stripe', event);
    f.stripeTransport.refundsValue = [{ id: 're_first', payment_intent: 'pi_synthetic', currency: 'usd', amount: 200, status: 'succeeded' },
      { id: 're_pending', payment_intent: 'pi_synthetic', currency: 'usd', amount: 300, status: 'pending' }];
    await f.purchases.reconcile('stripe', f.stripeTransport.event('refund.updated'));
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000 - Math.ceil(1_800_000 * 200 / 997));
    // The same notification can lead to a newer authoritative snapshot without an idempotency conflict.
    f.stripeTransport.refundsValue[1].status = 'succeeded';
    await f.purchases.reconcile('stripe', event);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000 - Math.ceil(1_800_000 * 500 / 997));
    f.stripeTransport.chargeValue.disputed = true;
    await f.purchases.reconcile('stripe', f.stripeTransport.event('charge.dispute.created'));
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
  } finally { await f.cleanup(); }
});

integration('receipt encryption binds ciphertext to order and scope, retains old decryption keys and never stores plaintext', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account), token = 'private-synthetic-reference';
    await f.vault.save(order.orderID, f.stripe, token);
    const row = (await f.db.query('SELECT * FROM minute_provider_receipts')).rows[0];
    assert.equal(row.encrypted_reference.includes(Buffer.from(token)), false); assert.equal(row.reference_hash, providerHash(token));
    const wrong = new MinuteReceiptVault(f.db, 'test-key', new Map([['test-key', randomBytes(32)]]));
    await assert.rejects(wrong.read(order.orderID, f.stripe), /provider_reference_unavailable/);
    await assert.rejects(f.vault.read(order.orderID, f.play), /provider_reference_unavailable/);
    const rotated = new MinuteReceiptVault(f.db, 'new-key', new Map([['new-key', randomBytes(32)],['test-key', f.encryptionKey]]));
    assert.equal(await rotated.read(order.orderID, f.stripe), token);
    await assert.rejects(f.vault.save(order.orderID, f.stripe, 'different-reference'), /provider_reference_conflict/);
    await assert.rejects(f.db.query("UPDATE minute_provider_receipts SET encryption_key_id='wrong'"), /immutable/);
    const other = await f.purchases.createOrder(account, 'stripe', 'synthetic-thirty', randomUUID());
    await f.db.query(`INSERT INTO minute_provider_receipts(order_id,provider,environment,merchant,reference_hash,encryption_key_id,encrypted_reference)
      VALUES($1,'stripe','test',$2,$3,'test-key',$4)`, [other.orderID, f.stripe.merchant, '0'.repeat(64), row.encrypted_reference]);
    await assert.rejects(f.vault.read(other.orderID, f.stripe), /provider_reference_unavailable/);
  } finally { await f.cleanup(); }
});

integration('a crash after receipt storage is recovered by the delivery worker', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account); f.stripeTransport.paid();
    await f.stripe.verify(f.stripeTransport.event()); // Simulate process death before ledger reconciliation.
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    assert.deepEqual(await f.worker.runBatch(), { processed: 1, completed: 1, retried: 0 });
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
    await f.vault.schedule(order.orderID);
    await Promise.all([f.worker.runBatch(), f.worker.runBatch()]);
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='purchase'")).rows[0].count, '1');
  } finally { await f.cleanup(); }
});

integration('Play verifies stable account and order binding, paid amount and test environment before granting', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.playOrder(account);
    const input = { kind: 'client', accountID: account, orderID: order.orderID, purchaseToken: f.playTransport.token };
    const original = clone(f.playTransport.purchaseValue), originalOrder = clone(f.playTransport.orderValue);
    for (const mutation of [
      () => { f.playTransport.purchaseValue.obfuscatedExternalAccountId = '0'.repeat(64); },
      () => { f.playTransport.purchaseValue.obfuscatedExternalProfileId = '0'.repeat(64); },
      () => { delete f.playTransport.purchaseValue.testPurchaseContext; },
      () => { f.playTransport.purchaseValue.productLineItem[0].productOfferDetails.quantity = 2; },
      () => { f.playTransport.orderValue.total.nanos = 960_000_000; },
      () => { f.playTransport.orderValue.purchaseToken = 'other-token'; },
      () => { delete f.playTransport.purchaseValue.orderId; },
    ]) {
      mutation(); await assert.rejects(f.purchases.reconcile('play', input), /purchase_verification_failed/);
      f.playTransport.purchaseValue = clone(original); f.playTransport.orderValue = clone(originalOrder);
    }
    await assert.rejects(f.purchases.reconcile('play', { ...input, accountID: await f.account() }), /purchase_verification_failed/);
    await f.purchases.reconcile('play', input);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
    assert.equal(f.playTransport.consumeCount, 0);
    await f.worker.runBatch(); assert.equal(f.playTransport.consumeCount, 1);
  } finally { await f.cleanup(); }
});

integration('Play pending payments never grant or consume; consumption retries do not grant twice', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.playOrder(account);
    const input = { kind: 'client', accountID: account, orderID: order.orderID, purchaseToken: f.playTransport.token };
    f.playTransport.purchaseValue.purchaseStateContext.purchaseState = 'PENDING';
    await f.purchases.reconcile('play', input);
    assert.deepEqual(await f.worker.runBatch(), { processed: 1, completed: 0, retried: 1 });
    assert.equal(f.playTransport.consumeCount, 0); assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    f.playTransport.purchaseValue.purchaseStateContext.purchaseState = 'PURCHASED'; f.playTransport.failConsume = true; await f.ready();
    assert.deepEqual(await f.worker.runBatch(), { processed: 1, completed: 0, retried: 1 });
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
    assert.equal((await f.db.query('SELECT last_error_code FROM minute_provider_jobs')).rows[0].last_error_code, 'provider_delivery_failed');
    f.playTransport.failConsume = false; await f.ready();
    assert.deepEqual(await f.worker.runBatch(), { processed: 1, completed: 1, retried: 0 });
    assert.equal(f.playTransport.consumeCount, 2);
    await f.vault.schedule(order.orderID); await f.worker.runBatch(); assert.equal(f.playTransport.consumeCount, 2);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
  } finally { await f.cleanup(); }
});

integration('Play partial refunds use successful order history; full void polling survives stale purchase responses', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.playOrder(account);
    const input = { kind: 'client', accountID: account, orderID: order.orderID, purchaseToken: f.playTransport.token };
    await f.purchases.reconcile('play', input); await f.worker.runBatch();
    f.playTransport.orderValue.state = 'PARTIALLY_REFUNDED';
    f.playTransport.orderValue.orderHistory.partialRefundEvents = [{ state: 'PROCESSED_SUCCESSFULLY', refundDetails: { total: { currencyCode: 'USD', units: '1', nanos: 0 } } },
      { state: 'PENDING', refundDetails: { total: { currencyCode: 'USD', units: '2', nanos: 0 } } }];
    await f.purchases.reconcile('play', input);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000 - Math.ceil(1_800_000 * 100 / 997));
    f.playTransport.voidsValue = { voidedPurchases: [{ purchaseToken: f.playTransport.token, voidedQuantity: 1 }], tokenPagination: { nextPageToken: 'next-page' } };
    const result = await f.play.pollVoids(Date.now() - 60_000, Date.now() - 1000);
    assert.deepEqual(result, { scheduled: 1, nextPageToken: 'next-page' });
    await f.worker.runBatch();
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    assert.equal((await f.purchases.status(account, order.orderID)).state, 'voided');
  } finally { await f.cleanup(); }
});

integration('Play full refund prevents consumption even if the purchase-state endpoint has not caught up', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.playOrder(account);
    await f.purchases.reconcile('play', { kind: 'client', accountID: account, orderID: order.orderID, purchaseToken: f.playTransport.token });
    f.playTransport.orderValue.state = 'REFUNDED';
    f.playTransport.orderValue.orderHistory.refundEvent = { refundDetails: { total: clone(total) }, refundReason: 'OTHER' };
    assert.equal((await f.worker.runBatch()).completed, 1);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    assert.equal(f.playTransport.consumeCount, 0);
    assert.equal((await f.purchases.status(account, order.orderID)).state, 'voided');
  } finally { await f.cleanup(); }
});

integration('an uncertain Play consume response retries from authoritative consumed state without another consume', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.playOrder(account);
    await f.purchases.reconcile('play', { kind: 'client', accountID: account, orderID: order.orderID, purchaseToken: f.playTransport.token });
    const consume = f.playTransport.consume.bind(f.playTransport);
    f.playTransport.consume = async () => { await consume(); throw new Error('Simulated response lost after provider success'); };
    assert.equal((await f.worker.runBatch()).retried, 1);
    assert.equal(f.playTransport.consumeCount, 1);
    await f.ready(); assert.equal((await f.worker.runBatch()).completed, 1);
    assert.equal(f.playTransport.consumeCount, 1);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
  } finally { await f.cleanup(); }
});

integration('expired delivery leases resume and notifications received during a lease remain scheduled', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.stripeOrder(account); f.stripeTransport.paid();
    await f.stripe.verify(f.stripeTransport.event());
    await f.db.query("UPDATE minute_provider_jobs SET state='leased',lease_id=$1,lease_until=now()-interval '1 minute'", [randomUUID()]);
    assert.equal((await f.worker.runBatch(1)).completed, 1);
    await f.vault.schedule(order.orderID);
    const originalComplete = f.stripe.complete.bind(f.stripe);
    f.stripe.complete = async id => { await f.vault.schedule(id); await originalComplete(id); };
    await f.worker.runBatch(1);
    assert.equal((await f.db.query('SELECT state FROM minute_provider_jobs')).rows[0].state, 'pending');
    f.stripe.complete = originalComplete; await f.worker.runBatch(1);
    await f.db.query("UPDATE minute_provider_jobs SET completed_at=now()-interval '7 hours' WHERE state='done'");
    assert.equal(await f.vault.scheduleReconciliation(), 1);
    assert.equal((await f.worker.runBatch(1)).completed, 1);
  } finally { await f.cleanup(); }
});

test('Google transport uses fixed encoded endpoints, authenticated empty consumption and bounded responses', async () => {
  const calls: any[] = [];
  const transport = new GooglePlayHTTPTransport({ accessToken: async () => 'synthetic-access-token-for-test' }, (async (url: any, init: any) => {
    calls.push({ url, init }); return new Response('{}', { status: 200 });
  }) as any);
  await transport.purchase('chat.mural.test', 'token?/secret'); await transport.consume('chat.mural.test', 'thirty', 'token?/secret');
  await transport.order('chat.mural.test', 'GPA.1234-5678'); await transport.voided('chat.mural.test', 1000, 2000, 'page token');
  assert.equal(calls[0].url, 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/chat.mural.test/purchases/productsv2/tokens/token%3F%2Fsecret');
  assert.equal(calls[1].init.method, 'POST'); assert.equal(calls[1].init.body, undefined); assert.equal(calls[1].init.redirect, 'error');
  assert.equal(calls[0].init.headers.authorization, 'Bearer synthetic-access-token-for-test');
  assert.match(calls[3].url, /includeQuantityBasedPartialRefund=true/); assert.match(calls[3].url, /token=page\+token/);
  const tooLarge = new GooglePlayHTTPTransport({ accessToken: async () => 'synthetic-access-token-for-test' }, (async () => new Response('x'.repeat(270_000))) as any);
  await assert.rejects(tooLarge.purchase('chat.mural.test', 'token'), /^Error: google_provider_unavailable$/);
  const failed = new GooglePlayHTTPTransport({ accessToken: async () => 'synthetic-access-token-for-test' }, (async () => { throw new Error('secret body'); }) as any);
  await assert.rejects(failed.purchase('chat.mural.test', 'token'), /^Error: google_provider_unavailable$/);
});

test('service-account OAuth signs the exact audience/scope and coalesces token refresh without exposing provider errors', async () => {
  // Ephemeral test signing material only; no service-account credential is generated or persisted.
  const pair = generateKeyPairSync('rsa', { modulusLength: 2048, publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } });
  let calls = 0;
  const source = new GoogleServiceAccountTokens('test@synthetic.iam.gserviceaccount.com', pair.privateKey, (async (url: any, init: any) => {
    calls++; assert.equal(url, 'https://oauth2.googleapis.com/token'); assert.equal(init.redirect, 'error');
    const assertion = new URLSearchParams(init.body).get('assertion')!;
    const verified = await jwtVerify(assertion, await importSPKI(pair.publicKey, 'RS256'), { audience: url, issuer: 'test@synthetic.iam.gserviceaccount.com' });
    assert.equal(verified.payload.scope, 'https://www.googleapis.com/auth/androidpublisher');
    return new Response(JSON.stringify({ access_token: 'synthetic-access-token-for-test', token_type: 'Bearer', expires_in: 3600 }));
  }) as any);
  assert.deepEqual(await Promise.all([source.accessToken(), source.accessToken(), source.accessToken()]), Array(3).fill('synthetic-access-token-for-test'));
  assert.equal(calls, 1); await source.accessToken(); assert.equal(calls, 1);
  const failed = new GoogleServiceAccountTokens('test@synthetic.iam.gserviceaccount.com', pair.privateKey, (async () => new Response('private provider body', { status: 403 })) as any);
  await assert.rejects(failed.accessToken(), /^Error: google_service_authorization_unavailable$/);
});

integration('restricted runtime can store encrypted receipts and process jobs but cannot rewrite bindings', async () => {
  const f = await fixture(true), role = `delivery_role_${randomUUID().replaceAll('-', '')}`;
  let runtime: ReturnType<typeof connectDatabase> | undefined;
  try {
    const account = await f.account(), order = await f.stripeOrder(account); f.stripeTransport.paid();
    await f.db.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${f.schema} TO ${role}; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ${f.schema} TO ${role}`);
    for (const file of ['minute-runtime-grants.sql','minute-purchase-runtime-grants.sql','minute-provider-runtime-grants.sql']) {
      await f.db.query((await readFile(new URL(`../operations/${file}`, import.meta.url), 'utf8')).replaceAll('mural_runtime', role));
    }
    const url = new URL(databaseURL!); url.searchParams.set('options', `-c search_path=${f.schema} -c role=${role}`); runtime = connectDatabase(url.toString());
    const vault = new MinuteReceiptVault(runtime, 'test-key', new Map([['test-key', f.encryptionKey]]));
    const stripe = new StripeMinuteProvider(runtime, vault, stripeConfig(), f.stripeTransport);
    const purchases = new MinutePurchases(runtime, { verifiers: [stripe] }), worker = new MinuteDeliveryWorker(runtime, purchases, [stripe]);
    await stripe.verify(f.stripeTransport.event()); await worker.runBatch();
    assert.equal((await minuteBalance(runtime, account)).balanceMilliseconds, 1_800_000);
    await assert.rejects(runtime.query('UPDATE minute_provider_receipts SET encrypted_reference=$1', [Buffer.alloc(40)]), /permission denied/);
    await assert.rejects(runtime.query('DELETE FROM minute_play_order_bindings'), /permission denied/);
    assert.equal((await runtime.query('SELECT gross_minor FROM minute_stripe_paid_totals WHERE order_id=$1',[order.orderID])).rows[0].gross_minor,'1197');
    await assert.rejects(runtime.query('UPDATE minute_stripe_paid_totals SET gross_minor=1500'), /permission denied/);
    await assert.rejects(runtime.query('DELETE FROM minute_stripe_paid_totals'), /permission denied/);
    assert.equal(await vault.read(order.orderID, stripe), 'cs_test_synthetic');
  } finally {
    await runtime?.end(); await f.cleanup();
    const db = connectDatabase(databaseURL!); try { await db.query(`DROP ROLE IF EXISTS ${role}`); } finally { await db.end(); }
  }
});


test('gross tax-inclusive refunds normalize conservatively into the original price basis', () => {
  assert.equal(normalizeStripeRefund(500, 0, 600), 0);
  assert.equal(normalizeStripeRefund(500, 300, 600), 250);
  assert.equal(normalizeStripeRefund(500, 1, 600), 1);
  assert.equal(normalizeStripeRefund(500, 600, 600), 500);
  assert.equal(normalizeStripeRefund(500, 123, 500), 123);
  for (const amounts of [[500,601,600],[500,-1,600],[500,1,499],[0,0,0],[500,1.2,600],[500,1,NaN]])
    assert.throws(() => normalizeStripeRefund(...amounts as [number,number,number]), /refund_not_reconciled/);
});

test('provider gross bounds stay separate from catalog bases and retain exact large refund arithmetic', () => {
  const base = 100_000_000, gross = Number.MAX_SAFE_INTEGER;
  assert.equal(normalizeStripeRefund(base, gross, gross), base);
  assert.equal(normalizeStripeRefund(base, 1, gross), 1);
  assert.equal(normalizeStripeRefund(base, 4_503_599_627_370_495, gross), 50_000_000);
  assert.equal(normalizeStripeRefund(base, 100_000_001, 100_000_001), base);
  for (const amounts of [[base+1,1,gross],[base,1,gross+1],[base,gross+1,gross],
    [base,1,Infinity],[base,NaN,gross],[base,1,100_000_000.5]])
    assert.throws(() => normalizeStripeRefund(...amounts as [number,number,number]), /refund_not_reconciled/);
});

integration('taxed provider totals above the catalog ceiling persist and refund at the exact original base', async () => {
  const f = await fixture(true);
  try {
    const account = await f.account(), base = 100_000_000;
    const product = { ...f.catalog[0]!, totalMinor: base };
    const purchases = new MinutePurchases(f.db, { catalog: [product], verifiers: [f.stripe], salesEnabled: true });
    for (const gross of [100_000_001, Number.MAX_SAFE_INTEGER]) {
      const order = await purchases.createOrder(account, 'stripe', product.sku, randomUUID());
      await f.db.query('INSERT INTO minute_stripe_checkout_attempts(order_id,managed_payments) VALUES($1,true)', [order.orderID]);
      f.stripeTransport.bind(order.orderID); f.stripeTransport.managed(gross-base,base); f.stripeTransport.paid();
      f.stripeTransport.current.id = `cs_test_${order.orderID.replaceAll('-','')}`;
      f.stripeTransport.refundsValue=[];
      const paid = await f.stripe.verify(f.stripeTransport.event());
      assert.equal(paid.totalMinor,base); assert.equal(paid.refundedMinor,0);
      assert.deepEqual((await f.db.query('SELECT gross_minor,tax_minor FROM minute_stripe_paid_totals WHERE order_id=$1', [order.orderID])).rows[0],
        { gross_minor:String(gross),tax_minor:String(gross-base) });
      f.stripeTransport.refundsValue=[{id:'re_large',charge:'ch_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:gross,status:'succeeded'}];
      assert.equal((await f.stripe.verify(f.stripeTransport.event('refund.updated'))).refundedMinor,base);
      // Each individual amount is safe; their cumulative sum must never overflow or exceed gross.
      f.stripeTransport.refundsValue.push({id:'re_excess',charge:'ch_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:1,status:'succeeded'});
      await assert.rejects(f.stripe.verify(f.stripeTransport.event('refund.updated')), /refund_not_reconciled/);
    }
    const order = await purchases.createOrder(account, 'stripe', product.sku, randomUUID());
    await f.db.query('INSERT INTO minute_stripe_checkout_attempts(order_id,managed_payments) VALUES($1,true)', [order.orderID]);
    for (const gross of ['0','-1','9007199254740992'])
      await assert.rejects(f.db.query('INSERT INTO minute_stripe_paid_totals(order_id,gross_minor,tax_minor) VALUES($1,$2,0)', [order.orderID,gross]), {code:'23514'});
    await assert.rejects(f.db.query('INSERT INTO minute_stripe_paid_totals(order_id,gross_minor,tax_minor) VALUES($1,10,11)', [order.orderID]), {code:'23514'});
    await assert.rejects(f.db.query('UPDATE minute_stripe_paid_totals SET gross_minor=1'), /immutable/);
  } finally { await f.cleanup(); }
});

integration('Managed Payments pins mode before creation and excludes unsupported Stripe parameters', async () => {
  const f = await fixture(true);
  try {
    const account = await f.account(), order = await f.stripeOrder(account, false);
    const original = f.stripeTransport.create.bind(f.stripeTransport);
    f.stripeTransport.create = async (params, key) => {
      assert.equal((await f.db.query('SELECT managed_payments FROM minute_stripe_checkout_attempts WHERE order_id=$1', [order.orderID])).rows[0].managed_payments, true);
      return original(params,key);
    };
    await f.stripe.checkout(account,order.orderID);
    const params = f.stripeTransport.createCalls[0].params;
    assert.deepEqual(params.managed_payments, { enabled: true });
    assert.equal('automatic_tax' in params, false); assert.equal('adaptive_pricing' in params, false);
    assert.equal('payment_method_types' in params, false); assert.equal('invoice_creation' in params, false);
    await assert.rejects(f.db.query('UPDATE minute_stripe_checkout_attempts SET managed_payments=false WHERE order_id=$1',[order.orderID]), /immutable/);
    await assert.rejects(f.db.query('DELETE FROM minute_stripe_checkout_attempts WHERE order_id=$1',[order.orderID]), /immutable/);
    const nowStandard = new StripeMinuteProvider(f.db,f.vault,stripeConfig(),f.stripeTransport);
    await nowStandard.checkout(account,order.orderID);
    assert.equal(f.stripeTransport.createCalls.length,1);
    f.stripeTransport.paid(); await nowStandard.verify(f.stripeTransport.event());
  } finally { await f.cleanup(); }
});

integration('Managed taxed paid totals, local presentment, partial and full Link refunds preserve one entitlement', async () => {
  const f = await fixture(true);
  try {
    const account = await f.account(), order = await f.stripeOrder(account); f.stripeTransport.paid();
    for (const value of [f.stripeTransport.current,f.stripeTransport.intentValue,f.stripeTransport.chargeValue])
      value.presentment_details = { presentment_currency:'eur',presentment_amount:1300 };
    const verified = await f.stripe.verify(f.stripeTransport.event());
    assert.equal(verified.currency,'usd'); assert.equal(verified.totalMinor,997);
    await f.purchases.reconcile('stripe',f.stripeTransport.event());
    await f.purchases.reconcile('stripe',f.stripeTransport.event());
    assert.equal((await minuteBalance(f.db,account)).balanceMilliseconds,1800000);
    f.stripeTransport.refundsValue = [
      {id:'re_partial',charge:'ch_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:400,status:'succeeded',
        presentment_details:{presentment_currency:'eur',presentment_amount:434}},
      {id:'re_pending',charge:'ch_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:797,status:'pending'}];
    const partial = await f.stripe.verify(f.stripeTransport.event('refund.updated'));
    assert.equal(partial.refundedMinor,Math.ceil(997*400/1197));
    await f.purchases.reconcile('stripe',f.stripeTransport.event('refund.updated'));
    assert.equal((await minuteBalance(f.db,account)).balanceMilliseconds,1800000-Math.ceil(1800000*partial.refundedMinor/997));
    f.stripeTransport.refundsValue[1].status='succeeded';
    await f.purchases.reconcile('stripe',f.stripeTransport.event('refund.updated'));
    assert.equal((await minuteBalance(f.db,account)).balanceMilliseconds,0);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_transactions')).rows[0].count,'1');
    const paid=(await f.db.query('SELECT gross_minor,tax_minor FROM minute_stripe_paid_totals WHERE order_id=$1',[order.orderID])).rows[0];
    assert.deepEqual(paid,{gross_minor:'1197',tax_minor:'200'});
    await assert.rejects(f.db.query('UPDATE minute_stripe_paid_totals SET gross_minor=1500'),/immutable/);
    f.stripeTransport.managed(300);
    await assert.rejects(f.stripe.verify(f.stripeTransport.event()),/managed_totals_mismatch/);
  } finally { await f.cleanup(); }
});

integration('Managed verification rejects downgrade, missing binding, malformed tax, wrong currency and inconsistent presentment', async () => {
  const f=await fixture(true);
  try {
    const account=await f.account(); await f.stripeOrder(account); f.stripeTransport.paid();
    const original=clone({session:f.stripeTransport.current,line:f.stripeTransport.lineValue,intent:f.stripeTransport.intentValue,charge:f.stripeTransport.chargeValue});
    for (const mutate of [
      () => {f.stripeTransport.current.managed_payments={enabled:false};},
      () => {f.stripeTransport.intentValue.managed_payments=null;},
      () => {f.stripeTransport.current.total_details.amount_tax=-1;},
      () => {f.stripeTransport.current.total_details.amount_tax=200.5;},
      () => {f.stripeTransport.current.total_details.amount_tax=Number.MAX_SAFE_INTEGER+1;},
      () => {f.stripeTransport.current.amount_total=Number.MAX_SAFE_INTEGER+1;},
      () => {f.stripeTransport.current.total_details.amount_shipping=1;},
      () => {f.stripeTransport.current.total_details.amount_discount=1;},
      () => {f.stripeTransport.current.amount_subtotal=998;},
      () => {f.stripeTransport.lineValue.price.tax_behavior='inclusive';},
      () => {f.stripeTransport.lineValue.amount_tax=199;},
      () => {f.stripeTransport.intentValue.amount_received=997;},
      () => {f.stripeTransport.intentValue.currency='eur';},
      () => {f.stripeTransport.chargeValue.amount_captured=997;},
      () => {f.stripeTransport.current.presentment_details={presentment_amount:1.5,presentment_currency:'eur'};},
      () => {f.stripeTransport.current.presentment_details={presentment_amount:1300,presentment_currency:'eur'};
        f.stripeTransport.intentValue.presentment_details={presentment_amount:1301,presentment_currency:'eur'};},
    ]) {
      f.stripeTransport.current=clone(original.session);f.stripeTransport.lineValue=clone(original.line);
      f.stripeTransport.intentValue=clone(original.intent);f.stripeTransport.chargeValue=clone(original.charge);mutate();
      await assert.rejects(f.stripe.verify(f.stripeTransport.event()));
    }
    await f.stripeOrder(account,false);f.stripeTransport.paid();
    await assert.rejects(f.stripe.verify(f.stripeTransport.event()),/checkout_binding_missing/);
  } finally {await f.cleanup();}
});

integration('standard historical orders remain standard after Managed Payments is enabled',async()=>{
  const f=await fixture();
  try {
    const account=await f.account(),order=await f.stripeOrder(account);f.stripeTransport.paid();
    const managed=new StripeMinuteProvider(f.db,f.vault,{...stripeConfig(),managedPayments:true},f.stripeTransport);
    await managed.verify(f.stripeTransport.event());
    f.stripeTransport.managed();
    await assert.rejects(managed.verify(f.stripeTransport.event()),/payment_mismatch/);
  } finally {await f.cleanup();}
});


integration('Managed tax and payment fees never become spendable AI value and full gross refund reverses exactly once',async()=>{
  const f=await fixture(true);
  try {
    const account=await f.account();
    await f.db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
    const product=makeAIValueProduct({provider:'stripe',environment:'test',merchant:f.stripe.merchant,sku:'synthetic-ai',providerProduct:'price_synthetic',
      currency:'usd',currencyExponent:2,aiValueMinor:369,policyVersion:1,serviceFeeBasisPoints:1500,
      processing:{rateBasisPoints:790,fixedMinor:30,bufferBasisPoints:100},
      exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'}});
    assert.equal(product.totalMinor,500);
    const purchases=new AIValuePurchases(f.db,{catalog:[product],verifiers:[f.stripe],salesEnabled:true});
    const order=await purchases.createOrder(account,'stripe',product.sku,randomUUID());
    f.stripeTransport.bind(order.orderID); f.stripeTransport.managed(100,500);
    await f.stripe.checkout(account,order.orderID);f.stripeTransport.paid();
    const purchased=await purchases.reconcile('stripe',f.stripeTransport.event());
    assert.equal(purchased.grantedNanoUSD,'3690000000');
    f.stripeTransport.refundsValue=[{id:'re_half',payment_intent:'pi_synthetic',charge:'ch_synthetic',currency:'usd',amount:300,status:'succeeded'}];
    const partial=await purchases.reconcile('stripe',f.stripeTransport.event('refund.updated'));
    assert.equal(partial.reversedNanoUSD,'1845000000');
    f.stripeTransport.refundsValue.push({id:'re_remaining',payment_intent:'pi_synthetic',charge:'ch_synthetic',currency:'usd',amount:300,status:'succeeded'});
    const refunded=await purchases.reconcile('stripe',f.stripeTransport.event('refund.updated'));
    await purchases.reconcile('stripe',f.stripeTransport.event('refund.updated'));
    assert.equal(refunded.reversedNanoUSD,'3690000000');
    assert.equal((await f.db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[account])).rows[0].balance_nano,'0');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
  } finally {await f.cleanup();}
});

integration('Managed refunds reject foreign charges, over-refunds and untrusted currencies',async()=>{
  const f=await fixture(true);
  try {
    const account=await f.account();await f.stripeOrder(account);f.stripeTransport.paid();
    const valid={id:'re_fixture',charge:'ch_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:100,status:'succeeded'};
    for (const patch of [{charge:'ch_other'},{payment_intent:'pi_other'},{currency:'eur'},{amount:1198},{amount:-1},{amount:0.5},
      {presentment_details:{presentment_amount:50,presentment_currency:'eur'}}]) {
      f.stripeTransport.refundsValue=[{...valid,...patch}];
      await assert.rejects(f.stripe.verify(f.stripeTransport.event('refund.updated')));
    }
  } finally {await f.cleanup();}
});


integration('Managed local-method py charges and pyr refunds retain all scoped amount checks',async()=>{
  const f=await fixture(true);
  try {
    const account=await f.account();await f.stripeOrder(account);f.stripeTransport.paid();
    f.stripeTransport.intentValue.latest_charge='py_synthetic';f.stripeTransport.chargeValue.id='py_synthetic';
    await f.purchases.reconcile('stripe',f.stripeTransport.event());
    assert.equal((await minuteBalance(f.db,account)).balanceMilliseconds,1800000);
    f.stripeTransport.refundsValue=[{id:'pyr_synthetic',charge:'py_synthetic',payment_intent:'pi_synthetic',currency:'usd',amount:1197,status:'succeeded',presentment_details:null}];
    const event=f.stripeTransport.event('refund.updated',{data:{object:{id:'pyr_synthetic',charge:'py_synthetic'}}});
    await f.purchases.reconcile('stripe',event);
    assert.equal((await minuteBalance(f.db,account)).balanceMilliseconds,0);
    for (const bad of ['tr_synthetic','pm_synthetic','py_../other','py_', 'whatever_synthetic']) {
      f.stripeTransport.intentValue.latest_charge=bad;
      await assert.rejects(f.stripe.verify(f.stripeTransport.event()));
    }
    f.stripeTransport.intentValue.latest_charge='py_synthetic';
    for (const bad of ['py_synthetic','tr_synthetic','pyr_../other','pyr_','whatever_synthetic']) {
      f.stripeTransport.refundsValue[0].id=bad;
      await assert.rejects(f.stripe.verify(event));
    }
  }finally{await f.cleanup();}
});
