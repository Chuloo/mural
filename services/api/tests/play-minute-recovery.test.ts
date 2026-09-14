import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { minuteBalance } from '../src/minutes.js';
import { MinutePurchases } from '../src/minute-purchases.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';
import { PlayMinuteProvider } from '../src/play-minute-provider.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
const clone = <T>(value: T): T => structuredClone(value);
const total = { currencyCode: 'USD', units: '9', nanos: 970_000_000 };
async function fixture() {
  const schema = `play_recovery_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const vault = new MinuteReceiptVault(db, 'recovery-key', new Map([['recovery-key', randomBytes(32)]]));
  let purchase: any, order: any, calls = 0, consumes = 0;
  const token = `synthetic-recovery-token.${randomUUID()}`;
  const play = new PlayMinuteProvider(db, vault, { packageName: 'chat.mural.android', bindingKey: Buffer.alloc(32, 7),
    currencyExponents: { usd: 2 }, purchasesEnabled: true }, {
    purchase: async (merchant, actualToken) => { calls++; assert.equal(merchant, 'chat.mural.android'); assert.equal(actualToken, token); return clone(purchase); },
    order: async (merchant, id) => { calls++; assert.equal(merchant, 'chat.mural.android'); assert.equal(id, order.orderId); return clone(order); },
    consume: async () => { consumes++; purchase.productLineItem[0].productOfferDetails.consumptionState = 'CONSUMPTION_STATE_CONSUMED'; },
    voided: async () => ({}),
  });
  const purchases = new MinutePurchases(db, { verifiers: [play], salesEnabled: true,
    catalog: [{ provider: 'play', environment: 'test', merchant: play.merchant, sku: 'synthetic-thirty',
      providerProduct: 'synthetic_thirty', minutes: 30, currency: 'usd', totalMinor: 997 }] });
  return { db, play, vault, purchases, token, worker: new MinuteDeliveryWorker(db, purchases, [play]),
    get calls() { return calls; }, get consumes() { return consumes; }, get purchase() { return purchase; }, get order() { return order; },
    async account(guest = false) { const id = randomUUID(); await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)', [id,guest]); return id; },
    async bind(accountID: string) {
      const created = await purchases.createOrder(accountID, 'play', 'synthetic-thirty', randomUUID());
      const binding = await play.prepare(accountID, created.orderID);
      purchase = { productLineItem: [{ productId: 'synthetic_thirty', productOfferDetails: { quantity: 1, refundableQuantity: 1,
        consumptionState: 'CONSUMPTION_STATE_YET_TO_BE_CONSUMED' } }], purchaseStateContext: { purchaseState: 'PURCHASED' },
        testPurchaseContext: { fopType: 'TEST' }, orderId: 'GPA.1234-5678-9012-34567',
        obfuscatedExternalAccountId: binding.obfuscatedAccountID, obfuscatedExternalProfileId: binding.obfuscatedProfileID };
      order = { orderId: purchase.orderId, purchaseToken: token, state: 'PROCESSED', total: clone(total),
        lineItems: [{ productId: 'synthetic_thirty', total: clone(total), oneTimePurchaseDetails: { quantity: 1 } }], orderHistory: {} };
      return created;
    },
    recover(accountID: string) { return purchases.reconcile('play', { kind: 'recovery', accountID, purchaseToken: token }); },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); },
  };
}

integration('reinstall recovery resolves the provider binding without a local order ID and grants exactly once', async () => {
  const f = await fixture();
  try {
    const member = await f.account(), created = await f.bind(member);
    assert.equal((await f.db.query('SELECT 1 FROM minute_provider_receipts')).rowCount, 0);
    const [first, second] = await Promise.all([f.recover(member), f.recover(member)]);
    assert.deepEqual(first, second); assert.equal(first.orderID, created.orderID); assert.equal(first.grantedMilliseconds, 1_800_000);
    assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 1_800_000);
    assert.equal((await f.db.query("SELECT 1 FROM minute_entries WHERE kind='purchase'")).rowCount, 1);
    assert.equal(await f.vault.read(created.orderID, f.play), f.token);
    assert.equal(f.consumes, 0); await f.worker.runBatch(); assert.equal(f.consumes, 1);
    assert.deepEqual(await f.recover(member), first); await f.worker.runBatch(); assert.equal(f.consumes, 1);
    assert.equal(JSON.stringify(first).includes(f.token), false);
  } finally { await f.cleanup(); }
});

integration('recovery requires a real active member and bounded tokens before provider work', async () => {
  const f = await fixture();
  try {
    const member = await f.account(), guest = await f.account(true), deleted = await f.account(); await f.bind(member);
    await f.db.query('UPDATE accounts SET deleted_at=now() WHERE id=$1', [deleted]);
    for (const account of [guest,deleted,randomUUID()]) await assert.rejects(f.play.recover(account, f.token), { code: 'sign_in_required' });
    for (const token of ['', 'x'.repeat(4097), 'private\nsecret']) await assert.rejects(f.play.recover(member, token), { code: 'invalid_play_verification' });
    await assert.rejects(f.play.recover('malformed', f.token), { code: 'invalid_play_verification' });
    assert.equal(f.calls, 0); assert.equal((await f.db.query('SELECT 1 FROM minute_provider_receipts')).rowCount, 0);
  } finally { await f.cleanup(); }
});

integration('a valid token cannot be recovered into a different account or redirected using client fields', async () => {
  const f = await fixture();
  try {
    const owner = await f.account(), other = await f.account(); await f.bind(owner);
    await assert.rejects(f.recover(other), { code: 'purchase_verification_failed' });
    for (const extra of [{ orderID: randomUUID() }, { minutes: 900 }, { state: 'purchased' }, { totalMinor: 1 }, { merchant: 'other' }])
      await assert.rejects(f.purchases.reconcile('play', { kind: 'recovery', accountID: owner, purchaseToken: f.token, ...extra }), { code: 'purchase_verification_failed' });
    assert.equal((await minuteBalance(f.db, owner)).balanceMilliseconds, 0); assert.equal((await minuteBalance(f.db, other)).balanceMilliseconds, 0);
    assert.equal((await f.db.query('SELECT 1 FROM minute_provider_receipts')).rowCount, 0); assert.equal(f.consumes, 0);
  } finally { await f.cleanup(); }
});

integration('recovery rechecks both provider bindings and original product, amount, currency and environment', async () => {
  const f = await fixture();
  try {
    const member = await f.account(); await f.bind(member);
    const purchase = clone(f.purchase), order = clone(f.order);
    const mutations = [
      () => { f.purchase.obfuscatedExternalProfileId = 'f'.repeat(64); },
      () => { f.purchase.obfuscatedExternalAccountId = 'e'.repeat(64); },
      () => { delete f.purchase.testPurchaseContext; },
      () => { f.purchase.productLineItem[0].productId = 'other_product'; },
      () => { f.order.total.units = '1'; },
      () => { f.order.total.currencyCode = 'EUR'; },
      () => { f.order.purchaseToken = 'another-token'; },
    ];
    for (const change of mutations) {
      Object.keys(f.purchase).forEach(key => delete f.purchase[key]); Object.assign(f.purchase, clone(purchase));
      Object.keys(f.order).forEach(key => delete f.order[key]); Object.assign(f.order, clone(order));
      change(); await assert.rejects(f.recover(member), { code: 'purchase_verification_failed' });
    }
    assert.equal((await f.db.query('SELECT 1 FROM minute_provider_receipts')).rowCount, 0); assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 0);
  } finally { await f.cleanup(); }
});

integration('recovered pending purchases wait and survive a restart before verified completion and consumption', async () => {
  const f = await fixture();
  try {
    const member = await f.account(); await f.bind(member); f.purchase.purchaseStateContext.purchaseState = 'PENDING';
    const pending = await f.recover(member); assert.equal(pending.state, 'pending'); assert.equal(pending.grantedMilliseconds, 0);
    assert.equal((await f.worker.runBatch()).retried, 1); assert.equal(f.consumes, 0);
    f.purchase.purchaseStateContext.purchaseState = 'PURCHASED';
    await f.db.query('UPDATE minute_provider_jobs SET available_at=now()');
    const restarted = new MinuteDeliveryWorker(f.db, f.purchases, [f.play]);
    assert.equal((await restarted.runBatch()).completed, 1);
    assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 1_800_000); assert.equal(f.consumes, 1);
    assert.equal((await f.recover(member)).state, 'purchased'); assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 1_800_000);
  } finally { await f.cleanup(); }
});

integration('recovery after refund cannot restore voided time and remains available while new sales are paused', async () => {
  const f = await fixture();
  try {
    const member = await f.account(); await f.bind(member); await f.recover(member);
    const paused = new MinutePurchases(f.db, { verifiers: [f.play] });
    f.order.state = 'REFUNDED'; f.order.orderHistory = { refundEvent: { refundDetails: { total: clone(total) } } };
    const recovered = await paused.reconcile('play', { kind: 'recovery', accountID: member, purchaseToken: f.token });
    assert.equal(recovered.state, 'voided'); assert.equal(recovered.reversedMilliseconds, 1_800_000);
    assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 0); await f.worker.runBatch(); assert.equal(f.consumes, 0);
    f.order.state = 'PROCESSED'; f.order.orderHistory = {};
    assert.equal((await f.recover(member)).state, 'voided'); assert.equal((await minuteBalance(f.db, member)).balanceMilliseconds, 0);
  } finally { await f.cleanup(); }
});
