import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendMinuteEntry, finishMinuteReservation, minuteBalance, reserveMinutes } from '../src/minutes.js';
import { MinutePurchases, refundedMilliseconds, type MinuteProduct, type MinutePurchaseVerifier,
  type VerifiedMinutePurchase, type PurchaseProvider } from '../src/minute-purchases.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
// Deliberately synthetic amounts. These fixtures are not launch prices or merchant configuration.
function product(provider: PurchaseProvider = 'stripe', overrides: Partial<MinuteProduct> = {}): MinuteProduct {
  return { provider, environment: 'test', merchant: provider === 'stripe' ? 'acct_synthetic' : 'chat.mural.synthetic',
    sku: 'synthetic-thirty', providerProduct: provider === 'stripe' ? 'price_synthetic' : 'synthetic_thirty',
    minutes: 30, currency: 'usd', totalMinor: 997, ...overrides };
}
function verifier(p: MinuteProduct, verify: MinutePurchaseVerifier['verify'] = async () => { throw new Error('No evidence'); }): MinutePurchaseVerifier {
  return { provider: p.provider, environment: p.environment, merchant: p.merchant, verify };
}
async function fixture() {
  const schema = `purchases_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const evidence = new Map<string, VerifiedMinutePurchase>();
  const products = [product(), product('play')];
  const verifiers = products.map(p => verifier(p, async input => {
    if (typeof input !== 'string' || !evidence.has(input)) throw new Error('Provider verification denied');
    return evidence.get(input)!;
  }));
  const service = new MinutePurchases(db, { catalog: products, verifiers, salesEnabled: true });
  return { db, schema, service, evidence, products, verifiers,
    async account(guest = false) {
      const id = randomUUID();
      await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)', [id, guest]);
      return id;
    },
    async order(account: string, provider: PurchaseProvider = 'stripe', key = randomUUID()) {
      return service.createOrder(account, provider, 'synthetic-thirty', key);
    },
    proof(order: Awaited<ReturnType<MinutePurchases['createOrder']>>, overrides: Partial<VerifiedMinutePurchase> = {}) {
      const proof: VerifiedMinutePurchase = { provider: order.provider, environment: order.environment, merchant: order.merchant,
        orderID: order.orderID, transactionID: `synthetic-transaction:${randomUUID()}`, eventID: `synthetic-event:${randomUUID()}`,
        providerProduct: order.providerProduct, quantity: 1, currency: order.currency, totalMinor: order.totalMinor,
        state: 'purchased', refundedMinor: 0, ...overrides };
      const key = randomUUID(); evidence.set(key, proof); return { key, proof };
    },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
  };
}

test('minute catalog is explicit, bound to a provider environment and validates exact quantities and prices', () => {
  const db = {} as any, p = product();
  assert.doesNotThrow(() => new MinutePurchases(db));
  for (const invalid of [{ minutes: 0 }, { minutes: 0.5 }, { minutes: 1441 }, { totalMinor: 0 }, { totalMinor: 3.5 },
    { totalMinor: Number.MAX_SAFE_INTEGER }, { currency: 'USD' }, { sku: '../price' }, { providerProduct: '' }, { merchant: 'https://evil.test/' }]) {
    assert.throws(() => new MinutePurchases(db, { catalog: [product('stripe', invalid)], verifiers: [verifier(p)] }), /invalid_minute_product/);
  }
  assert.throws(() => new MinutePurchases(db, { catalog: [p] }), /invalid_minute_catalog/);
  assert.throws(() => new MinutePurchases(db, { catalog: [p, p], verifiers: [verifier(p)] }), /invalid_minute_catalog/);
  assert.throws(() => new MinutePurchases(db, { catalog: [p, { ...p, sku: 'different' }], verifiers: [verifier(p)] }), /invalid_minute_catalog/);
  assert.throws(() => new MinutePurchases(db, { catalog: [p], verifiers: [verifier({ ...p, environment: 'live' })] }), /invalid_minute_catalog/);
});

test('refund calculations use the immutable total and cannot overflow or credit fractional milliseconds', () => {
  assert.equal(refundedMilliseconds(1_800_000, 1, 997), 1806);
  assert.equal(refundedMilliseconds(1_800_000, 997, 997), 1_800_000);
  assert.equal(refundedMilliseconds(86_400_000, 99_999_999, 100_000_000), 86_400_000);
  assert.equal(refundedMilliseconds(60_000, 0, 1), 0);
  for (const values of [[60_001, 1, 10], [1_800_000, -1, 10], [1_800_000, 11, 10], [1_800_000, 0.5, 10], [1_800_000, 1, 0]])
    assert.throws(() => refundedMilliseconds(values[0]!, values[1]!, values[2]!), /invalid_minute_refund/);
});

integration('new sales are disabled by default, and guests cannot buy', async () => {
  const f = await fixture();
  try {
    const account = await f.account();
    const disabled = new MinutePurchases(f.db, { catalog: f.products, verifiers: f.verifiers });
    await assert.rejects(disabled.createOrder(account, 'stripe', 'synthetic-thirty', randomUUID()), /minute_purchases_unavailable/);
    await assert.rejects(f.order(await f.account(true)), /purchase_requires_account/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count, '0');
  } finally { await f.cleanup(); }
});

integration('catalog snapshots and idempotency bind one account to one immutable quote', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), key = randomUUID();
    const orders = await Promise.all(Array.from({ length: 5 }, () => f.order(account, 'stripe', key)));
    assert.equal(new Set(orders.map(o => o.orderID)).size, 1);
    const old = orders[0]!;
    const repriced = new MinutePurchases(f.db, { catalog: [product('stripe', { minutes: 60, totalMinor: 1991 })],
      verifiers: f.verifiers, salesEnabled: true });
    assert.deepEqual(await repriced.createOrder(account, 'stripe', old.sku, key), old);
    await assert.rejects(f.order(account, 'play', key), /idempotency_conflict/);
    await assert.rejects(f.service.createOrder(account, 'stripe', 'caller-selected-price', randomUUID()), /minute_product_unavailable/);
    await assert.rejects(f.db.query('UPDATE minute_purchase_orders SET allowance_ms=86400000'), /immutable/);
    await assert.rejects(f.db.query('DELETE FROM minute_purchase_orders'), /immutable/);
    await assert.rejects(f.service.status(await f.account(), old.orderID), /purchase_not_found/);
    assert.equal((await f.service.status(account, old.orderID)).state, 'created');
  } finally { await f.cleanup(); }
});

integration('client assertions cannot bypass the injected server verifier or change price, scope, quantity or binding', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account);
    const unverified = f.proof(order);
    await assert.rejects(f.service.reconcile('stripe', { ...unverified.proof, verified: true }), /purchase_verification_failed/);
    for (const changes of [{ environment: 'live' as const }, { merchant: 'acct_other' }, { quantity: 2 }, { refundedMinor: -1 },
      { totalMinor: 0 }, { state: 'pending' as const, refundedMinor: 10 }, { transactionID: '' }, { eventID: '\n' },
      { totalMinor: Number.MAX_SAFE_INTEGER }]) {
      const invalid = f.proof(order, changes);
      await assert.rejects(f.service.reconcile('stripe', invalid.key), /invalid_purchase_evidence/);
    }
    for (const changes of [{ totalMinor: order.totalMinor - 1 }, { currency: 'eur' }, { providerProduct: 'price_other' }]) {
      const mismatch = f.proof(order, changes);
      await assert.rejects(f.service.reconcile('stripe', mismatch.key), /minute_purchase_mismatch/);
    }
    const unmapped = f.proof(order, { orderID: randomUUID() });
    await assert.rejects(f.service.reconcile('stripe', unmapped.key), /unmapped_minute_purchase/);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_events')).rows[0].count, '0');
  } finally { await f.cleanup(); }
});

integration('pending grants nothing; concurrent completion and duplicate notifications grant exactly once', async () => {
  const f = await fixture();
  try {
    for (const provider of ['stripe', 'play'] as const) {
      const account = await f.account(), order = await f.order(account, provider), pending = f.proof(order, { state: 'pending' });
      assert.equal((await f.service.reconcile(provider, pending.key)).state, 'pending');
      assert.equal((await minuteBalance(f.db, account)).availableMilliseconds, 0);
      const completed = f.proof(order, { transactionID: pending.proof.transactionID });
      await Promise.all(Array.from({ length: 8 }, () => f.service.reconcile(provider, completed.key)));
      const otherEvent = f.proof(order, { transactionID: pending.proof.transactionID });
      await f.service.reconcile(provider, otherEvent.key);
      assert.equal((await minuteBalance(f.db, account)).availableMilliseconds, 1_800_000);
      await f.service.reconcile(provider, pending.key);
      assert.equal((await f.service.status(account, order.orderID)).state, 'purchased');
      const rows = (await f.db.query('SELECT * FROM minute_purchase_transactions WHERE account_id=$1', [account])).rows;
      assert.equal(rows.length, 1); assert.notEqual(rows[0].transaction_hash, pending.proof.transactionID);
      assert.equal(rows[0].transaction_hash.length, 64);
    }
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='purchase'")).rows[0].count, '2');
  } finally { await f.cleanup(); }
});

integration('transaction reuse across accounts and event ID reuse with altered evidence are rejected atomically', async () => {
  const f = await fixture();
  try {
    const a = await f.account(), b = await f.account(), oa = await f.order(a, 'play'), ob = await f.order(b, 'play');
    const first = f.proof(oa), reused = f.proof(ob, { transactionID: first.proof.transactionID });
    const results = await Promise.allSettled([f.service.reconcile('play', first.key), f.service.reconcile('play', reused.key)]);
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    assert.equal(results.filter(r => r.status === 'rejected').length, 1);
    assert.equal((await minuteBalance(f.db, a)).balanceMilliseconds + (await minuteBalance(f.db, b)).balanceMilliseconds, 1_800_000);
    const winner = results[0]!.status === 'fulfilled' ? first : reused;
    const altered = f.proof(results[0]!.status === 'fulfilled' ? oa : ob,
      { ...winner.proof, refundedMinor: 1 });
    await assert.rejects(f.service.reconcile('play', altered.key), /purchase_event_conflict/);
    await assert.rejects(f.db.query('UPDATE minute_purchase_events SET refunded_minor=0'), /immutable/);
  } finally { await f.cleanup(); }
});

integration('one order cannot be fulfilled with two different provider transactions', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account);
    await f.service.reconcile('stripe', f.proof(order).key);
    await assert.rejects(f.service.reconcile('stripe', f.proof(order).key), /purchase_transaction_conflict/);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
  } finally { await f.cleanup(); }
});

integration('cumulative partial refunds round once, ignore stale amounts, and cannot debit twice', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), paid = f.proof(order);
    await f.service.reconcile('stripe', paid.key);
    const partial = f.proof(order, { transactionID: paid.proof.transactionID, refundedMinor: 1 });
    const result = await f.service.reconcile('stripe', partial.key);
    assert.equal(result.reversedMilliseconds, 1806);
    const next = f.proof(order, { transactionID: paid.proof.transactionID, refundedMinor: 2 });
    const nextResult = await f.service.reconcile('stripe', next.key);
    assert.equal(nextResult.reversedMilliseconds, 3611);
    await f.service.reconcile('stripe', partial.key);
    await f.service.reconcile('stripe', paid.key);
    assert.equal((await minuteBalance(f.db, account)).availableMilliseconds, 1_800_000 - 3611);
    const full = f.proof(order, { transactionID: paid.proof.transactionID, refundedMinor: order.totalMinor });
    await Promise.all([f.service.reconcile('stripe', full.key), f.service.reconcile('stripe', full.key)]);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
  } finally { await f.cleanup(); }
});

integration('refund evidence arriving before completion is applied in one transaction with no spendable window', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account);
    const refund = f.proof(order, { refundedMinor: 500 });
    await f.service.reconcile('stripe', refund.key);
    const expected = 1_800_000 - refundedMilliseconds(1_800_000, 500, order.totalMinor);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, expected);
    const stalePaid = f.proof(order, { transactionID: refund.proof.transactionID });
    await f.service.reconcile('stripe', stalePaid.key);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, expected);
  } finally { await f.cleanup(); }
});

integration('cancellation before payment and voiding after payment stay terminal despite reordered notifications', async () => {
  const f = await fixture();
  try {
    for (const paidFirst of [false, true]) {
      const account = await f.account(), order = await f.order(account, 'play'), paid = f.proof(order);
      if (paidFirst) await f.service.reconcile('play', paid.key);
      const voided = f.proof(order, { transactionID: paid.proof.transactionID, state: 'voided' });
      assert.equal((await f.service.reconcile('play', voided.key)).state, 'voided');
      await f.service.reconcile('play', paid.key);
      assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
      assert.equal((await f.service.status(account, order.orderID)).grantedMilliseconds, paidFirst ? 1_800_000 : 0);
    }
  } finally { await f.cleanup(); }
});

integration('refund during a call preserves reserved funds and blocks new spending until reconciliation', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), paid = f.proof(order);
    await f.service.reconcile('stripe', paid.key);
    const held = await reserveMinutes(f.db, account, 'held-during-refund', 600_000);
    const voided = f.proof(order, { transactionID: paid.proof.transactionID, state: 'voided' });
    const reversal = await f.service.reconcile('stripe', voided.key);
    assert.equal(reversal.reversedMilliseconds, 1_200_000);
    assert.equal(reversal.reversalOutstandingMilliseconds, 600_000);
    assert.deepEqual(await minuteBalance(f.db, account), { unit: 'milliseconds', balanceMilliseconds: 600_000,
      reservedMilliseconds: 600_000, availableMilliseconds: 0, billingBasis: 'connected-conversation-time' });
    await finishMinuteReservation(f.db, held, null);
    // The trigger protects every reserveMinutes caller, even before hosted routes are integrated.
    await assert.rejects(reserveMinutes(f.db, account, 'new-call-after-refund', 1), /minute_purchase_reconciliation_required/);
    await f.service.reconcileAccount(account);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    assert.equal((await f.service.status(account, order.orderID)).reversalOutstandingMilliseconds, 0);
  } finally { await f.cleanup(); }
});

integration('spent refunded time becomes explicit shortfall; a future purchase recovers it without overdraft', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), paid = f.proof(order);
    await f.service.reconcile('stripe', paid.key);
    const hold = await reserveMinutes(f.db, account, 'already-spent', 1_800_000);
    await finishMinuteReservation(f.db, hold, 1_700_000);
    const refund = f.proof(order, { transactionID: paid.proof.transactionID, refundedMinor: order.totalMinor });
    assert.equal((await f.service.reconcile('stripe', refund.key)).reversalOutstandingMilliseconds, 1_700_000);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 0);
    const second = await f.order(account, 'play');
    await f.service.reconcile('play', f.proof(second).key);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 100_000);
    assert.equal((await f.service.status(account, order.orderID)).reversalOutstandingMilliseconds, 0);
    const resumed = await reserveMinutes(f.db, account, 'paid-again', 100_000);
    await finishMinuteReservation(f.db, resumed, null);
  } finally { await f.cleanup(); }
});

integration('a refund racing a reservation never leaves a negative or underfunded wallet', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), paid = f.proof(order);
    await f.service.reconcile('stripe', paid.key);
    const refund = f.proof(order, { transactionID: paid.proof.transactionID, state: 'voided' });
    const results = await Promise.allSettled([f.service.reconcile('stripe', refund.key), reserveMinutes(f.db, account, 'racing-call', 1_000_000)]);
    assert.equal(results[0]!.status, 'fulfilled');
    const balance = await minuteBalance(f.db, account);
    assert.ok(balance.balanceMilliseconds >= 0 && balance.availableMilliseconds >= 0);
    if (results[1]!.status === 'fulfilled') {
      assert.equal(balance.reservedMilliseconds, 1_000_000);
      await finishMinuteReservation(f.db, results[1].value, 321_000);
      await f.service.reconcileAccount(account);
      assert.equal((await f.service.status(account, order.orderID)).reversalOutstandingMilliseconds, 321_000);
    } else assert.equal(balance.balanceMilliseconds, 0);
  } finally { await f.cleanup(); }
});

integration('failed wallet writes roll back the purchase and event, so retry can fulfill it exactly once', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), proof = f.proof(order);
    await transaction(f.db, sql => appendMinuteEntry(sql, account, 'synthetic-saturated-wallet', 'gift', Number.MAX_SAFE_INTEGER, 0));
    await assert.rejects(f.service.reconcile('stripe', proof.key), /insufficient_minutes/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_events')).rows[0].count, '0');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_transactions')).rows[0].count, '0');
    await transaction(f.db, sql => appendMinuteEntry(sql, account, 'synthetic-clear-wallet', 'forfeit', -Number.MAX_SAFE_INTEGER, 0));
    await f.service.reconcile('stripe', proof.key);
    assert.equal((await minuteBalance(f.db, account)).balanceMilliseconds, 1_800_000);
  } finally { await f.cleanup(); }
});

integration('restricted runtime can fulfill and reverse but cannot rewrite order snapshots or event history', async () => {
  const f = await fixture(), role = `purchase_role_${randomUUID().replaceAll('-', '')}`;
  let runtime: ReturnType<typeof connectDatabase> | undefined;
  try {
    const account = await f.account();
    await f.db.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${f.schema} TO ${role};
      GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ${f.schema} TO ${role}`);
    for (const path of ['minute-runtime-grants.sql', 'minute-purchase-runtime-grants.sql']) {
      const grants = await readFile(new URL(`../operations/${path}`, import.meta.url), 'utf8');
      await f.db.query(grants.replaceAll('mural_runtime', role));
    }
    const url = new URL(databaseURL!); url.searchParams.set('options', `-c search_path=${f.schema} -c role=${role}`);
    runtime = connectDatabase(url.toString());
    const service = new MinutePurchases(runtime, { catalog: f.products, verifiers: f.verifiers, salesEnabled: true });
    const order = await service.createOrder(account, 'stripe', 'synthetic-thirty', randomUUID()), paid = f.proof(order);
    await service.reconcile('stripe', paid.key);
    const hold = await reserveMinutes(runtime, account, 'restricted-call', 60_000);
    await finishMinuteReservation(runtime, hold, null);
    const refund = f.proof(order, { transactionID: paid.proof.transactionID, state: 'voided' });
    await service.reconcile('stripe', refund.key);
    assert.equal((await minuteBalance(runtime, account)).balanceMilliseconds, 0);
    await assert.rejects(runtime.query('UPDATE minute_purchase_orders SET allowance_ms=60000'), /permission denied/);
    await assert.rejects(runtime.query('DELETE FROM minute_purchase_events'), /permission denied/);
    await assert.rejects(runtime.query('DELETE FROM minute_purchase_transactions'), /permission denied/);
    await assert.rejects(runtime.query("UPDATE minute_purchase_transactions SET merchant='other'"), /permission denied/);
    await assert.rejects(f.db.query("UPDATE minute_purchase_transactions SET state='purchased'"), /minute_purchase_history_conflict/);
    await assert.rejects(f.db.query('UPDATE minute_purchase_transactions SET recovered_ms=0'), /minute_purchase_history_conflict/);
  } finally {
    await runtime?.end(); await f.cleanup();
    const db = connectDatabase(databaseURL!); try { await db.query(`DROP ROLE IF EXISTS ${role}`); } finally { await db.end(); }
  }
});
