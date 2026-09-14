import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type Stripe from 'stripe';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry, reserve, settle, release } from '../src/ledger.js';
import { applyStripeEvent, SandboxPayments } from '../src/payments.js';
import { createApp } from '../src/app.js';
import { authenticate, createChallenge, digest, exchangeIdentity, deleteAccount } from '../src/auth.js';
import { NANO_USD } from '../src/pricing.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Integration tests require a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL to run PostgreSQL integration tests.' }, fn);
async function account(balance = 0n) {
  const id = randomUUID();
  await db!.query('INSERT INTO accounts(id,email) VALUES($1,$2)', [id, 'test@example.test']);
  await db!.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
  if (balance) await transaction(db!, sql => appendEntry(sql, id, `seed:${id}`, 'purchase', balance, 0n));
  return id;
}
async function wallet(id: string) {
  const row = (await db!.query('SELECT * FROM wallets WHERE account_id=$1', [id])).rows[0];
  return { balance: BigInt(row.balance_nano), reserved: BigInt(row.reserved_nano) };
}
function event(type: string, object: Record<string, unknown>, id = `evt_${randomUUID()}`): Stripe.Event {
  return { id, type, livemode: false, data: { object } } as unknown as Stripe.Event;
}
async function order(id: string) {
  const orderID = randomUUID(), sessionID = `cs_test_${randomUUID()}`, intentID = `pi_${randomUUID()}`;
  await db!.query(`INSERT INTO checkout_orders(id,account_id,idempotency_key,product,currency,total_minor,credit_nano,stripe_price_id,stripe_session_id)
    VALUES($1,$2,$3,'ai-10-usd','usd',1150,$4,'price_test',$5)`, [orderID, id, randomUUID(), (10n * NANO_USD).toString(), sessionID]);
  const paid = { id: sessionID, client_reference_id: orderID, mode: 'payment', currency: 'usd', amount_total: 1150, payment_status: 'paid', payment_intent: intentID };
  return { orderID, sessionID, intentID, paid };
}
integration('concurrent reservations cannot overspend one wallet', async () => {
  const id = await account(10n * NANO_USD);
  const results = await Promise.allSettled([reserve(db!, id, 'a', 6n * NANO_USD), reserve(db!, id, 'b', 6n * NANO_USD)]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.deepEqual(await wallet(id), { balance: 10n * NANO_USD, reserved: 6n * NANO_USD });
});
integration('idempotent reserve and settle record one charge and release unused allowance', async () => {
  const id = await account(10n * NANO_USD);
  const reservation = await reserve(db!, id, 'request-one', 2n * NANO_USD);
  assert.equal(await reserve(db!, id, 'request-one', 2n * NANO_USD), reservation);
  await assert.rejects(reserve(db!, id, 'request-one', NANO_USD));
  await settle(db!, reservation, 500_000_000n); await settle(db!, reservation, 500_000_000n);
  assert.deepEqual(await wallet(id), { balance: 9_500_000_000n, reserved: 0n });
  await assert.rejects(settle(db!, reservation, 600_000_000n));
});
integration('over-reservation usage is rejected; release is idempotent', async () => {
  const id = await account(NANO_USD), reservation = await reserve(db!, id, 'bounded', NANO_USD);
  await assert.rejects(settle(db!, reservation, NANO_USD + 1n));
  await release(db!, reservation); await release(db!, reservation);
  assert.deepEqual(await wallet(id), { balance: NANO_USD, reserved: 0n });
});
integration('journal cannot be edited or deleted in PostgreSQL', async () => {
  const id = await account(NANO_USD);
  await assert.rejects(db!.query('UPDATE ledger SET balance_delta_nano=0 WHERE account_id=$1', [id]));
  await assert.rejects(db!.query('DELETE FROM ledger WHERE account_id=$1', [id]));
});
integration('duplicate webhook deliveries and distinct paid event IDs credit exactly once', async () => {
  const id = await account(), item = await order(id), paid = event('checkout.session.completed', item.paid);
  await Promise.all([applyStripeEvent(db!, paid), applyStripeEvent(db!, paid)]);
  await applyStripeEvent(db!, event('checkout.session.async_payment_succeeded', item.paid));
  assert.equal((await wallet(id)).balance, 10n * NANO_USD);
  const journal = await db!.query("SELECT * FROM ledger WHERE account_id=$1 AND kind='purchase'", [id]);
  assert.equal(journal.rowCount, 1);
});
integration('wrong amount and unpaid checkouts never fund a wallet', async () => {
  const id = await account(), item = await order(id);
  await assert.rejects(applyStripeEvent(db!, event('checkout.session.completed', { ...item.paid, amount_total: 1 })));
  await applyStripeEvent(db!, event('checkout.session.completed', { ...item.paid, payment_status: 'unpaid' }));
  assert.equal((await wallet(id)).balance, 0n);
});
integration('refund before payment retries; partial/full refunds and paid replay cannot double-credit', async () => {
  const id = await account(), item = await order(id);
  const refund = (amount: number) => event('charge.refunded', { id: `ch_${item.orderID}`, payment_intent: item.intentID, amount_refunded: amount });
  await assert.rejects(applyStripeEvent(db!, refund(575)));
  await applyStripeEvent(db!, event('checkout.session.completed', item.paid));
  await applyStripeEvent(db!, refund(575)); await applyStripeEvent(db!, refund(575));
  assert.equal((await wallet(id)).balance, 5n * NANO_USD);
  await applyStripeEvent(db!, refund(1150));
  await applyStripeEvent(db!, event('checkout.session.completed', item.paid));
  assert.equal((await wallet(id)).balance, 0n);
});
integration('a chargeback after consumption makes the wallet unavailable without erasing history', async () => {
  const id = await account(), item = await order(id);
  await applyStripeEvent(db!, event('checkout.session.completed', item.paid));
  const held = await reserve(db!, id, 'usage', 8n * NANO_USD); await settle(db!, held, 8n * NANO_USD);
  await applyStripeEvent(db!, event('charge.dispute.created', { id: `dp_${item.orderID}`, payment_intent: item.intentID }));
  assert.equal((await wallet(id)).balance, -8n * NANO_USD);
  await assert.rejects(reserve(db!, id, 'more-usage', 1n));
});
integration('nonce challenges are single-use and account sessions are revoked on deletion', async () => {
  const challenge = await createChallenge(db!);
  const subject = randomUUID();
  const verified = async () => ({ provider: 'google' as const, subject, email: 'identity@example.test' });
  const session = await exchangeIdentity(db!, 'google', 'test-only-token', challenge.challengeID, {}, verified);
  await assert.rejects(exchangeIdentity(db!, 'google', 'test-only-token', challenge.challengeID, {}, verified));
  assert.equal(await authenticate(db!, `Bearer ${session.accessToken}`), session.accountID);
  const stored = (await db!.query('SELECT token_hash FROM auth_sessions WHERE account_id=$1', [session.accountID])).rows[0];
  assert.equal(stored.token_hash, digest(session.accessToken)); assert.notEqual(stored.token_hash, session.accessToken);
  await deleteAccount(db!, session.accountID);
  await assert.rejects(authenticate(db!, `Bearer ${session.accessToken}`));
  assert.equal((await db!.query('SELECT email FROM accounts WHERE id=$1', [session.accountID])).rowCount, 0);
});
integration('a signed raw HTTP webhook funds its mapped order and replay remains idempotent', async () => {
  const id = await account(), item = await order(id);
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const app = createApp({ db: db!, auth: {}, payments });
  try {
    const payload = JSON.stringify(event('checkout.session.completed', item.paid), null, 2);
    const signature = payments.stripe.webhooks.generateTestHeaderString({ payload, secret: payments.webhookSecret });
    for (const url of ['/v1/webhooks/stripe', '/v1/webhooks/%73tripe']) {
      const response = await app.inject({ method: 'POST', url, payload,
        headers: { 'content-type': 'application/json', 'stripe-signature': signature } });
      assert.equal(response.statusCode, 200);
    }
    assert.equal((await wallet(id)).balance, 10n * NANO_USD);
  } finally { await app.close(); }
});
integration('different provider subjects with the same email remain separate accounts', async () => {
  const accounts: string[] = [];
  for (const provider of ['google', 'apple'] as const) {
    const challenge = await createChallenge(db!);
    const session = await exchangeIdentity(db!, provider, 'test-only-token', challenge.challengeID, {},
      async () => ({ provider, subject: randomUUID(), email: 'shared@example.test' }));
    accounts.push(session.accountID);
  }
  assert.notEqual(accounts[0], accounts[1]);
});

integration('checkout retries retrieve the mapped session after Stripe idempotency expiry', async () => {
  const id = await account(), item = await order(id);
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 0 }]]), 'http://localhost:8080');
  const stored = (await db!.query('SELECT idempotency_key FROM checkout_orders WHERE id=$1', [item.orderID])).rows[0];
  await db!.query("UPDATE checkout_orders SET create_attempted_at=now()-interval '3 days' WHERE id=$1", [item.orderID]);
  let creates = 0, retrieves = 0;
  payments.stripe.checkout.sessions.create = (async () => { creates++; throw new Error('Must not recreate'); }) as never;
  payments.stripe.checkout.sessions.retrieve = (async (sessionID: string) => {
    retrieves++; assert.equal(sessionID, item.sessionID);
    return { ...item.paid, livemode: false, status: 'open', url: 'https://checkout.stripe.com/test' };
  }) as never;
  const response = await payments.checkout(db!, id, 'ai-10-usd', stored.idempotency_key);
  assert.equal(response.checkoutURL, 'https://checkout.stripe.com/test');
  assert.equal(creates, 0); assert.equal(retrieves, 1);
  await applyStripeEvent(db!, event('checkout.session.completed', item.paid));
  assert.equal((await wallet(id)).balance, 10n * NANO_USD);
});
integration('checkout disables adaptive currency pricing so the quoted USD amount stays fixed', async () => {
  const id = await account();
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 66 }]]), 'http://localhost:8080');
  payments.stripe.prices.retrieve = (async () => ({ active: true, livemode: false, currency: 'usd', unit_amount: 1216, type: 'one_time' })) as never;
  payments.stripe.checkout.sessions.create = (async (request: Stripe.Checkout.SessionCreateParams) => {
    assert.deepEqual(request.adaptive_pricing, { enabled: false });
    assert.deepEqual(request.line_items, [{ price: 'price_test', quantity: 1 }]);
    return { id: `cs_fixed_usd_${randomUUID()}`, client_reference_id: request.client_reference_id, livemode: false, status: 'open',
      mode: 'payment', currency: 'usd', amount_total: 1216, adaptive_pricing: { enabled: false }, url: 'https://checkout.stripe.com/fixed-usd' };
  }) as never;
  payments.stripe.checkout.sessions.expire = (async () => { throw new Error('Unexpected test checkout expiry'); }) as never;
  const response = await payments.checkout(db!, id, 'ai-10-usd', 'fixed-usd-quote');
  assert.deepEqual(response.quote, { currency: 'USD', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 66 });
});
integration('expired mapped checkout and old uncertain creates never create another payable session', async () => {
  const id = await account(), item = await order(id);
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 0 }]]), 'http://localhost:8080');
  const key = (await db!.query('SELECT idempotency_key FROM checkout_orders WHERE id=$1', [item.orderID])).rows[0].idempotency_key;
  payments.stripe.checkout.sessions.create = (async () => { throw new Error('Unexpected create'); }) as never;
  payments.stripe.checkout.sessions.retrieve = (async () => ({ ...item.paid, livemode: false, status: 'expired', url: null })) as never;
  await assert.rejects(payments.checkout(db!, id, 'ai-10-usd', key), { code: 'checkout_no_longer_open' });
  await db!.query("UPDATE checkout_orders SET stripe_session_id=NULL,create_attempted_at=now()-interval '25 hours' WHERE id=$1", [item.orderID]);
  payments.stripe.prices.retrieve = (async () => ({ active: true, livemode: false, currency: 'usd', unit_amount: 1150, type: 'one_time' })) as never;
  await assert.rejects(payments.checkout(db!, id, 'ai-10-usd', key), { code: 'checkout_reconciliation_required' });
});
integration('account deletion preserves pending payments and funded value; deleted accounts cannot reserve or buy', async () => {
  const pending = await account(), item = await order(pending);
  await assert.rejects(deleteAccount(db!, pending), { code: 'unresolved_billing' });
  await applyStripeEvent(db!, event('checkout.session.completed', item.paid));
  await assert.rejects(deleteAccount(db!, pending), { code: 'unresolved_billing' });
  assert.equal((await wallet(pending)).balance, 10n * NANO_USD);
  const empty = await account(); await deleteAccount(db!, empty);
  await assert.rejects(reserve(db!, empty, 'late-request', 1n), { code: 'account_not_found' });
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 0 }]]), 'http://localhost:8080');
  await assert.rejects(payments.checkout(db!, empty, 'ai-10-usd', 'late-request'), { code: 'account_not_found' });
});

integration('a newly created checkout whose mapping conflicts is expired and never returned', async () => {
  const id = await account(), expired: string[] = [];
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 0 }]]), 'http://localhost:8080');
  payments.stripe.prices.retrieve = (async () => ({ active: true, livemode: false, currency: 'usd', unit_amount: 1150, type: 'one_time' })) as never;
  payments.stripe.checkout.sessions.create = (async (request: { client_reference_id: string }) => {
    await db!.query('UPDATE checkout_orders SET stripe_session_id=$2 WHERE id=$1', [request.client_reference_id, `cs_conflict_${randomUUID()}`]);
    return { id: 'cs_unmapped_fake', client_reference_id: request.client_reference_id, livemode: false, status: 'open',
      mode: 'payment', currency: 'usd', amount_total: 1150, url: 'https://checkout.stripe.com/must-not-return' };
  }) as never;
  payments.stripe.checkout.sessions.expire = (async (session: string) => { expired.push(session); return {}; }) as never;
  await assert.rejects(payments.checkout(db!, id, 'ai-10-usd', 'mapping-conflict-key'), { code: 'checkout_mapping_conflict' });
  assert.deepEqual(expired, ['cs_unmapped_fake']);
});
integration('racing checkout creation and deletion cannot leave a payable order on a deleted account', async () => {
  const id = await account();
  const payments = new SandboxPayments('sk_test_fake', 'whsec_fake', new Map([['ai-10-usd',
    { id: 'ai-10-usd', priceID: 'price_test', aiMinor: 1000, serviceFeeMinor: 150, paymentFeeMinor: 0 }]]), 'http://localhost:8080');
  payments.stripe.prices.retrieve = (async () => ({ active: true, livemode: false, currency: 'usd', unit_amount: 1150, type: 'one_time' })) as never;
  payments.stripe.checkout.sessions.create = (async (request: { client_reference_id: string }) => ({ id: `cs_race_${randomUUID()}`,
    client_reference_id: request.client_reference_id, livemode: false, status: 'open', mode: 'payment', currency: 'usd', amount_total: 1150,
    url: 'https://checkout.stripe.com/race-test' })) as never;
  const outcomes = await Promise.allSettled([payments.checkout(db!, id, 'ai-10-usd', 'deletion-race-key'), deleteAccount(db!, id)]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  const owner = (await db!.query('SELECT deleted_at FROM accounts WHERE id=$1', [id])).rows[0];
  const deleted = !owner || owner.deleted_at;
  const orders = (await db!.query('SELECT id FROM checkout_orders WHERE account_id=$1', [id])).rowCount;
  assert.equal(Boolean(deleted && orders), false);
});
