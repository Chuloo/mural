import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { accountCloseoutReport } from '../src/account-closeout-admin.js';
import { connectDatabase, transaction, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { AIValuePurchases, makeAIValueProduct } from '../src/ai-value-purchases.js';
import { MinuteReceiptVault } from '../src/minute-provider-delivery.js';
import type { VerifiedMinutePurchase } from '../src/minute-purchases.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function fixture() {
  const schema = `closeout_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query("INSERT INTO accounts(id,email) VALUES($1,'private-synthetic@example.invalid')", [account]);
  await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  return { db, account, async close() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); } };
}

test('closeout inspection rejects invalid selectors before accessing the database', async () => {
  for (const input of ['', '../path', 'not-an-id', undefined])
    await assert.rejects(accountCloseoutReport({} as Database, input as string), { code: 'invalid_account_id' });
});

integration('support closeout report exposes no signup identity and does not mutate the account', async () => {
  const f = await fixture();
  try {
    await f.db.query("INSERT INTO identities(provider,subject,account_id) VALUES('apple','private-provider-subject',$1)", [f.account]);
    const before = (await f.db.query('SELECT * FROM accounts WHERE id=$1', [f.account])).rows;
    const report = await accountCloseoutReport(f.db, f.account);
    assert.equal(report.readyForExistingDeletionChecks, true); assert.equal(report.appleRevocationRequired, true);
    assert.equal(report.manualReviewOnly, true); assert.deepEqual(report.orders, []);
    assert.ok(Object.values(report.blockers).every(value => value === false));
    const serialized = JSON.stringify(report);
    assert.ok(!serialized.includes('private-synthetic')); assert.ok(!serialized.includes('private-provider-subject'));
    assert.deepEqual((await f.db.query('SELECT * FROM accounts WHERE id=$1', [f.account])).rows, before);
    await assert.rejects(accountCloseoutReport(f.db, randomUUID()), { code: 'account_not_found' });
  } finally { await f.close(); }
});

integration('support closeout report distinguishes spend, debt and retained holds without changing them', async () => {
  const f = await fixture();
  try {
    await transaction(f.db, sql => appendEntry(sql, f.account, 'synthetic-credit', 'purchase', 100n, 0n, 'synthetic'));
    let report = await accountCloseoutReport(f.db, f.account);
    assert.equal(report.blockers.cashBalanceRemaining, true); assert.equal(report.readyForExistingDeletionChecks, false);
    await transaction(f.db, sql => appendEntry(sql, f.account, 'synthetic-held-refund', 'reversal', -200n, 10n, 'synthetic'));
    report = await accountCloseoutReport(f.db, f.account);
    assert.equal(report.blockers.cashBalanceRemaining, false); assert.equal(report.blockers.cashDebtRemaining, true);
    assert.equal(report.blockers.cashReserved, true); assert.equal(report.readyForExistingDeletionChecks, false);
    assert.deepEqual((await f.db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1', [f.account])).rows[0],
      { balance_nano: '-100', reserved_nano: '10' });
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count, '2');
  } finally { await f.close(); }
});

integration('support closeout report identifies receiptless orders and distinguishes confirmed full reversal', async () => {
  const f = await fixture();
  try {
    const product = makeAIValueProduct({ provider: 'stripe', environment: 'test', merchant: 'acct_synthetic', sku: 'synthetic',
      providerProduct: 'price_synthetic', currency: 'usd', currencyExponent: 2, aiValueMinor: 200, policyVersion: 1, serviceFeeBasisPoints: 1500,
      processing: { rateBasisPoints: 0, fixedMinor: 70, bufferBasisPoints: 0 },
      exchangeRate: { numerator: '1', denominator: '1', version: 'synthetic' }, estimate: { nanoUSDPerMinute: '100000000', rateVersion: 'synthetic' } });
    let evidence: VerifiedMinutePurchase;
    const adapter = { provider: product.provider, environment: product.environment, merchant: product.merchant, verify: async () => evidence };
    const purchases = new AIValuePurchases(f.db, { catalog: [product], verifiers: [adapter], salesEnabled: true });
    const order = await purchases.createOrder(f.account, 'stripe', product.sku, randomUUID());
    let report = await accountCloseoutReport(f.db, f.account);
    assert.equal(report.blockers.purchaseUnresolved, true);
    assert.deepEqual(report.orders[0], { orderID: order.orderID, stripe: true, play: false, live: false, hasReceipt: false,
      deliveryPending: false, unverified: true, pending: false, voided: false, minuteRefundDue: false });
    const vault = new MinuteReceiptVault(f.db, 'synthetic', new Map([['synthetic', randomBytes(32)]]));
    await vault.save(order.orderID, adapter, 'cs_private_synthetic');
    evidence = { provider: 'stripe', environment: 'test', merchant: product.merchant, orderID: order.orderID, transactionID: 'cs_private_synthetic',
      eventID: 'synthetic-refund', providerProduct: product.providerProduct, quantity: 1, currency: 'usd', totalMinor: product.totalMinor,
      state: 'voided', refundedMinor: product.totalMinor };
    await purchases.reconcile('stripe', {});
    report = await accountCloseoutReport(f.db, f.account);
    assert.equal(report.blockers.purchaseUnresolved, false); assert.equal(report.orders[0]?.voided, true);
    assert.equal(report.orders[0]?.hasReceipt, true); assert.equal(report.orders[0]?.deliveryPending, true);
    assert.equal(report.readyForExistingDeletionChecks, true);
    assert.ok(!JSON.stringify(report).includes('cs_private_synthetic'));
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_events')).rows[0].count, '1');
  } finally { await f.close(); }
});
