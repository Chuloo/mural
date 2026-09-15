import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import Stripe from 'stripe';
import { createApp, type Services } from '../src/app.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { authenticate, deleteAccount, digest } from '../src/auth.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { finishMinuteReservation, minuteBalance, reserveMinutes } from '../src/minutes.js';
import { AIValuePurchases, makeAIValueProduct, PurchaseFulfillmentRouter } from '../src/ai-value-purchases.js';
import { paidAIBalance } from '../src/ledger.js';
import { updateAIPricingPolicy } from '../src/ai-top-up-pricing.js';
import { MinutePurchases, type MinuteProduct } from '../src/minute-purchases.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';
import { StripeMinuteProvider, type StripeMinuteTransport } from '../src/stripe-minute-provider.js';
import { PlayMinuteProvider } from '../src/play-minute-provider.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
const proxy = { hmacKey: 'a'.repeat(64), proxyToken: 'b'.repeat(64), allowLocalLoopback: false };
const network = { 'x-mural-client-ip': '192.0.2.112', 'x-mural-proxy-token': proxy.proxyToken };
const stripeSecret = 'whsec_' + 'syntheticfixture'.repeat(3);
const stripeKey = 'sk_test_' + 'syntheticfixture'.repeat(3);
const clone = <T>(value: T): T => structuredClone(value);
const rootURL = '/v1/minutes/orders';
async function fixture(options: { enabled?: boolean; salesEnabled?: boolean; aiValue?: boolean; hosted?: Services['hosted'] } = {}) {
  const schema = `commerce_http_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const vault = new MinuteReceiptVault(db, 'test-key', new Map([['test-key', randomBytes(32)]]));
  const sessions = new Map<string, any>(), refunded = new Map<string, number>();
  const stripeSDK = new Stripe(stripeKey);
  let createCount = 0, playFacts: any;
  const transport: StripeMinuteTransport = {
    account: async () => ({ id: 'acct_httpfixture' }),
    price: async id => ({ id, active: true, livemode: false, currency: 'usd', unit_amount: 997, type: 'one_time' }) as any,
    create: async params => {
      createCount++; const orderID = params.client_reference_id!, id = `cs_test_${orderID.replaceAll('-', '')}`;
      const existing = sessions.get(id); if (existing) return clone(existing);
      const session = { id, status: 'open', mode: 'payment', livemode: false, payment_status: 'unpaid',
        client_reference_id: orderID, metadata: { mural_minute_order: orderID }, currency: 'usd', amount_total: 997,
        payment_intent: `pi_${orderID}`, url: `https://checkout.stripe.com/c/pay/${id}` };
      // Provider IDs are alphanumeric; retain the UUID only in the server metadata.
      session.payment_intent = `pi_${orderID.replaceAll('-', '')}`;
      sessions.set(id, session); return clone(session) as any;
    },
    session: async id => { const session = sessions.get(id); if (!session) throw new Error('Unknown fixture session'); return clone(session); },
    lines: async () => ({ data: [{ quantity: 1, price: { id: 'price_httpfixture' }, currency: 'usd', amount_total: 997 }], has_more: false }) as any,
    sessionsForIntent: async id => ({ data: [...sessions.values()].filter(s => s.payment_intent === id).map(clone), has_more: false }) as any,
    intent: async id => { const session = [...sessions.values()].find(s => s.payment_intent === id)!; return { id, livemode: false,
      status: 'succeeded', currency: 'usd', amount_received: 997, metadata: session.metadata, latest_charge: `ch_${id.slice(3)}` } as any; },
    charge: async id => ({ id, status: 'succeeded', livemode: false, paid: true, captured: true, amount: 997, currency: 'usd',
      payment_intent: `pi_${id.slice(3)}`, disputed: false }) as any,
    refunds: async intent => ({ data: refunded.get(intent) ? [{ id: `re_${intent.slice(3)}`, payment_intent: intent, currency: 'usd', status: 'succeeded', amount: refunded.get(intent) }] : [], has_more: false }) as any,
    verifyEvent: (raw, signature) => stripeSDK.webhooks.constructEvent(raw, signature, stripeSecret),
  };
  const stripe = new StripeMinuteProvider(db, vault, { secretKey: stripeKey, webhookSecret: stripeSecret, accountID: 'acct_httpfixture',
    webOrigin: 'https://mural.example.test', checkoutEnabled: true }, transport);
  const play = new PlayMinuteProvider(db, vault, { packageName: 'chat.mural.httpfixture', bindingKey: Buffer.alloc(32, 8),
    currencyExponents: { usd: 2 }, purchasesEnabled: true }, {
    purchase: async () => clone(playFacts),
    order: async () => ({ orderId: 'GPA.1234-5678-9012-34567', purchaseToken: playFacts.token, state: 'PROCESSED',
      total: { currencyCode: 'USD', units: '9', nanos: 970_000_000 }, lineItems: [{ productId: 'http_thirty',
        total: { currencyCode: 'USD', units: '9', nanos: 970_000_000 }, oneTimePurchaseDetails: { quantity: 1 } }], orderHistory: {} }),
    consume: async () => { playFacts.productLineItem[0].productOfferDetails.consumptionState = 'CONSUMPTION_STATE_CONSUMED'; },
    voided: async () => ({}),
  });
  const catalog: MinuteProduct[] = [{ provider: 'stripe', environment: 'test', merchant: stripe.merchant, sku: 'fixture-thirty',
    providerProduct: 'price_httpfixture', minutes: 30, currency: 'usd', totalMinor: 997 },
  { provider: 'play', environment: 'test', merchant: play.merchant, sku: 'fixture-thirty', providerProduct: 'http_thirty', minutes: 30, currency: 'usd', totalMinor: 997 }];
  const purchases = new MinutePurchases(db, { catalog, verifiers: [stripe, play], salesEnabled: options.salesEnabled ?? true });
  const aiCatalog=catalog.map(p=>makeAIValueProduct({provider:p.provider,environment:p.environment,merchant:p.merchant,
    sku:'fixture-ai',providerProduct:p.providerProduct,currency:p.currency,currencyExponent:2,aiValueMinor:800,
    policyVersion:1,serviceFeeBasisPoints:1500,processing:{rateBasisPoints:0,fixedMinor:77,bufferBasisPoints:0},
    exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'}}));
  const aiPurchases=options.aiValue?new AIValuePurchases(db,{catalog:aiCatalog,verifiers:[stripe,play],salesEnabled:options.salesEnabled??true}):undefined;
  const fulfillment=aiPurchases?new PurchaseFulfillmentRouter(db,purchases,aiPurchases,[stripe,play]):undefined;
  const app = createApp({ db, auth: { googleClientID: 'synthetic-google-client' }, accounts: { admission: new AuthAdmission(db, proxy) },
    ...(options.enabled === false ? {} : { minuteCommerce: { purchases, aiPurchases, fulfillment, stripe, play } }), ...(options.hosted ? { hosted: options.hosted } : {}) });
  return { db, app, purchases, aiPurchases, fulfillment, stripe, play, vault, sessions, refunded,
    get createCount() { return createCount; },
    async account(guest = false) {
      const id = randomUUID(), token = randomBytes(32).toString('base64url');
      await transaction(db, async sql => {
        await sql.query('INSERT INTO accounts(id,email,is_guest) VALUES($1,$2,$3)', [id, 'synthetic@example.test', guest]);
        await sql.query('INSERT INTO wallets(account_id) VALUES($1)', [id]);
        await sql.query("INSERT INTO identities(provider,subject,account_id) VALUES('google',$1,$2)", [randomUUID(), id]);
        await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')", [randomUUID(), id, digest(token)]);
      });
      return { id, token, headers: { ...network, authorization: `Bearer ${token}` } };
    },
    async order(account: { headers: Record<string,string> }, provider: 'stripe' | 'play' = 'stripe', key: string = randomUUID()) {
      const response = await app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': key }, payload: { provider, sku: options.aiValue?'fixture-ai':'fixture-thirty' } });
      assert.equal(response.statusCode, 200, response.body); return response.json();
    },
    stripeEvent(orderID: string, state: 'paid' | 'pending' | 'expired' = 'paid', refund = 0) {
      const session = [...sessions.values()].find(s => s.client_reference_id === orderID)!;
      session.status = state === 'paid' ? 'complete' : state === 'expired' ? 'expired' : 'open';
      session.payment_status = state === 'paid' ? 'paid' : 'unpaid';
      if (refund) refunded.set(session.payment_intent, refund);
      const payload = JSON.stringify({ id: `evt_${randomUUID().replaceAll('-', '')}`, object: 'event', type: state === 'expired' ? 'checkout.session.expired' : 'checkout.session.completed',
        livemode: false, account: stripe.merchant, data: { object: { id: session.id } } }, null, 2);
      return { payload, signature: stripeSDK.webhooks.generateTestHeaderString({ payload, secret: stripeSecret }) };
    },
    async webhook(event: { payload: string; signature: string }) {
      return app.inject({ method: 'POST', url: '/v1/webhooks/stripe/minutes', headers: { ...network, 'content-type': 'application/json', 'stripe-signature': event.signature }, payload: event.payload });
    },
    bindPlay(order: any) {
      const token = `synthetic-http-token.${randomUUID()}`;
      playFacts = { token, orderId: 'GPA.1234-5678-9012-34567', testPurchaseContext: { fopType: 'TEST' },
        purchaseStateContext: { purchaseState: 'PURCHASED' }, obfuscatedExternalAccountId: order.payment.obfuscatedAccountID,
        obfuscatedExternalProfileId: order.payment.obfuscatedProfileID,
        productLineItem: [{ productId: 'http_thirty', productOfferDetails: { quantity: 1, refundableQuantity: 1, consumptionState: 'CONSUMPTION_STATE_YET_TO_BE_CONSUMED' } }] };
      return token;
    },
    async drain() { return new MinuteDeliveryWorker(db, fulfillment??purchases, [stripe,play]).runBatch(); },
    async cleanup() { await app.close(); await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); },
  };
}

test('commerce defaults advertise no products and reject writes before database access when accounts are disabled', async () => {
  let queries = 0;
  const db = { query: async () => { queries++; throw new Error('Unexpected database query'); } } as any;
  const app = createApp({ db, auth: {} });
  try {
    for (const provider of ['stripe','play']) {
      const response = await app.inject({ url: `/v1/minutes/products?provider=${provider}` });
      assert.deepEqual(response.json(), { available: false, billingBasis: 'connected-conversation-time', products: [] });
      assert.equal(response.headers['cache-control'], 'no-store');
    }
    for (const path of [rootURL, `${rootURL}/${randomUUID()}/play`]) {
      const response = await app.inject({ method: 'POST', url: path, payload: {} });
      assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'accounts_unavailable');
    }
    assert.equal((await app.inject({ method: 'POST', url: '/v1/webhooks/stripe/minutes', payload: {} })).statusCode, 503);
    assert.equal(queries, 0);
  } finally { await app.close(); }
});

integration('enabled accounts with absent commerce still cannot order, and disabled sales expose an empty catalog', async () => {
  const f = await fixture({ enabled: false });
  try {
    const account = await f.account();
    const response = await f.app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': randomUUID() }, payload: { provider: 'stripe', sku: 'fixture-thirty' } });
    assert.equal(response.statusCode, 503); assert.equal(response.json().error.code, 'minute_purchases_unavailable');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count, '0');
  } finally { await f.cleanup(); }
  const disabled = await fixture({ salesEnabled: false });
  try {
    assert.equal((await disabled.app.inject({ url: '/v1/minutes/products?provider=stripe', headers: network })).json().available, false);
  } finally { await disabled.cleanup(); }
});

integration('catalog is public while guests and unauthenticated callers cannot create, inspect or verify orders', async () => {
  const f = await fixture();
  try {
    const catalog = await f.app.inject({ url: '/v1/minutes/products?provider=play', headers: network });
    assert.deepEqual(catalog.json(), { available: true, billingBasis: 'connected-conversation-time', products: [{ sku: 'fixture-thirty',
      providerProduct: 'http_thirty', minutes: 30, currency: 'usd', totalMinor: 997, environment: 'test' }] });
    const guest = await f.account(true);
    for (const headers of [network, guest.headers]) {
      for (const [method, url, payload] of [['POST', rootURL, { provider: 'play', sku: 'fixture-thirty' }],
        ['GET', `${rootURL}/${randomUUID()}`, undefined], ['POST', `${rootURL}/${randomUUID()}/play`, { purchaseToken: 'anything' }]] as const) {
        const response = await f.app.inject({ method, url, headers: { ...headers, 'idempotency-key': randomUUID() }, ...(payload ? { payload } : {}) });
        assert.equal(response.statusCode, 401); assert.equal(response.json().error.code, 'sign_in_required');
      }
    }
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count, '0');
  } finally { await f.cleanup(); }
});

integration('order creation trusts the server quote and authenticated account; retries preserve one purchase binding', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), other = await f.account(), key = randomUUID();
    for (const extra of [{ accountID: other.id }, { minutes: 1000 }, { totalMinor: 1 }, { providerProduct: 'cheap-price' }]) {
      const response = await f.app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': key },
        payload: { provider: 'stripe', sku: 'fixture-thirty', ...extra } });
      assert.equal(response.statusCode, 400); assert.equal(response.json().error.code, 'invalid_request');
    }
    const first = await f.order(account, 'stripe', key), second = await f.order(account, 'stripe', key);
    assert.deepEqual(second, first); assert.equal(first.minutes, 30); assert.equal(first.totalMinor, 997); assert.equal(f.createCount, 1);
    assert.equal((await f.db.query('SELECT account_id FROM minute_purchase_orders WHERE id=$1', [first.orderID])).rows[0].account_id, account.id);
    const conflict = await f.app.inject({ method: 'POST', url: rootURL, headers: { ...account.headers, 'idempotency-key': key }, payload: { provider: 'play', sku: 'fixture-thirty' } });
    assert.equal(conflict.statusCode, 409); assert.equal(conflict.json().error.code, 'idempotency_conflict');
    const forbidden = await f.app.inject({ url: `${rootURL}/${first.orderID}`, headers: other.headers });
    assert.equal(forbidden.statusCode, 404); assert.equal(forbidden.json().error.code, 'purchase_not_found');
    const status = await f.app.inject({ url: `${rootURL}/${first.orderID}`, headers: account.headers });
    assert.equal(status.statusCode, 200); assert.equal(status.json().state, 'created'); assert.equal(status.body.includes(account.token), false);
  } finally { await f.cleanup(); }
});

integration('Play HTTP submission rejects identity overrides and malformed or oversized tokens before persistence', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), other = await f.account(), order = await f.order(account, 'play');
    const url = `${rootURL}/${order.orderID}/play`, token = f.bindPlay(order);
    for (const payload of [{ purchaseToken: token, accountID: other.id }, { purchaseToken: null }, { purchaseToken: '' },
      { purchaseToken: 'x'.repeat(4097) }, { purchaseToken: { token } }]) {
      const result = await f.app.inject({ method: 'POST', url, headers: account.headers, payload });
      assert.equal(result.statusCode, 400); assert.equal(result.json().error.code, 'invalid_request');
    }
    const tooLarge = await f.app.inject({ method: 'POST', url, headers: account.headers, payload: { purchaseToken: 'x'.repeat(9000) } });
    assert.equal(tooLarge.statusCode, 413); assert.equal(tooLarge.json().error.code, 'invalid_request');
    const malformed = await f.app.inject({ method: 'POST', url, headers: account.headers, payload: { purchaseToken: 'private\nmalformed-token' } });
    assert.equal(malformed.statusCode, 400); assert.deepEqual(malformed.json(), { error: { code: 'invalid_request' } });
    assert.equal((await f.db.query('SELECT count(*) FROM minute_provider_receipts')).rows[0].count, '0');
    const wrongAccount = await f.app.inject({ method: 'POST', url, headers: other.headers, payload: { purchaseToken: token } });
    assert.equal(wrongAccount.statusCode, 404); assert.deepEqual(wrongAccount.json(), { error: { code: 'purchase_not_found' } });
    const first = await f.app.inject({ method: 'POST', url, headers: account.headers, payload: { purchaseToken: token } });
    assert.equal(first.statusCode, 200); assert.equal(first.json().grantedMilliseconds, 1_800_000);
    const replay = await f.app.inject({ method: 'POST', url, headers: account.headers, payload: { purchaseToken: token } });
    assert.deepEqual(replay.json(), first.json()); await f.drain();
    assert.equal((await minuteBalance(f.db, account.id)).balanceMilliseconds, 1_800_000);
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='purchase'")).rows[0].count, '1');
    assert.equal(first.body.includes(token), false);
  } finally { await f.cleanup(); }
});

integration('Stripe minute webhook preserves signed raw bytes, rejects mutation and replays settlement safely', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account), event = f.stripeEvent(order.orderID);
    const corrupted = await f.webhook({ ...event, payload: event.payload + ' ' });
    assert.equal(corrupted.statusCode, 502); assert.equal(corrupted.json().error.code, 'purchase_verification_failed');
    assert.equal((await minuteBalance(f.db, account.id)).balanceMilliseconds, 0);
    assert.deepEqual((await f.webhook(event)).json(), { received: true });
    assert.deepEqual((await f.webhook(event)).json(), { received: true });
    await f.drain(); assert.equal((await minuteBalance(f.db, account.id)).balanceMilliseconds, 1_800_000);
    const refund = f.stripeEvent(order.orderID, 'paid', 997);
    assert.equal((await f.webhook(refund)).statusCode, 200); assert.equal((await f.webhook(refund)).statusCode, 200);
    assert.equal((await minuteBalance(f.db, account.id)).balanceMilliseconds, 0);
    assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE kind='purchase'")).rows[0].count, '1');
    const missing = await f.app.inject({ method: 'POST', url: '/v1/webhooks/stripe/minutes', headers: network, payload: { private: 'do-not-echo' } });
    assert.equal(missing.statusCode, 400); assert.deepEqual(missing.json(), { error: { code: 'invalid_webhook_signature' } });
  } finally { await f.cleanup(); }
});

integration('the HTTP error boundary maps only the known refund-debt database exception to safe 409', async () => {
  let handler: (account: string) => Promise<unknown> = async () => {};
  const hosted = { available: true, create: (account: string) => handler(account) } as unknown as Services['hosted'];
  const f = await fixture({ hosted });
  try {
    const account = await f.account(), order = await f.order(account); await f.webhook(f.stripeEvent(order.orderID));
    const hold = await reserveMinutes(f.db, account.id, 'held-for-refund', 60_000);
    await f.webhook(f.stripeEvent(order.orderID, 'paid', 997)); await finishMinuteReservation(f.db, hold, null);
    handler = id => reserveMinutes(f.db, id, 'blocked-by-refund', 1);
    const request = { method: 'POST' as const, url: '/v1/live/sessions', headers: { ...account.headers, 'idempotency-key': 'http-refund-block' }, payload: { sdp: 'v=0', language: 'es-ES' } };
    const response = await f.app.inject(request);
    assert.equal(response.statusCode, 409); assert.deepEqual(response.json(), { error: { code: 'minute_purchase_reconciliation_required' } });
    handler = async () => { throw Object.assign(new Error('private database detail'), { code: 'P0001' }); };
    const hidden = await f.app.inject(request);
    assert.equal(hidden.statusCode, 500); assert.deepEqual(hidden.json(), { error: { code: 'service_unavailable' } });
  } finally { await f.cleanup(); }
});

integration('deletion rejects unconfirmed, pending and funded minute orders without dropping identity or sessions', async () => {
  const f = await fixture();
  try {
    for (const state of ['unconfirmed','pending','paid'] as const) {
      const account = await f.account(), order = await f.order(account);
      if (state !== 'unconfirmed') await f.webhook(f.stripeEvent(order.orderID, state));
      const result = await f.app.inject({ method: 'DELETE', url: '/v1/account', headers: account.headers, payload: {} });
      assert.equal(result.statusCode, 409); assert.equal(result.json().error.code, 'unresolved_billing');
      assert.equal(await authenticate(f.db, account.headers.authorization), account.id);
      const row = (await f.db.query('SELECT email,deleted_at FROM accounts WHERE id=$1', [account.id])).rows[0];
      assert.equal(row.deleted_at, null); assert.equal(row.email, 'synthetic@example.test');
    }
  } finally { await f.cleanup(); }
});

integration('deletion blocks outstanding refund debt even when the minute wallet is empty', async () => {
  const f = await fixture();
  try {
    const account = await f.account(), order = await f.order(account); await f.webhook(f.stripeEvent(order.orderID));
    const hold = await reserveMinutes(f.db, account.id, 'spend-before-refund', 1_800_000); await finishMinuteReservation(f.db, hold, 1_800_000);
    await f.webhook(f.stripeEvent(order.orderID, 'paid', 997));
    assert.equal((await minuteBalance(f.db, account.id)).balanceMilliseconds, 0);
    await assert.rejects(deleteAccount(f.db, account.id), { code: 'unresolved_billing', status: 409 });
    assert.equal((await f.db.query('SELECT deleted_at FROM accounts WHERE id=$1', [account.id])).rows[0].deleted_at, null);
  } finally { await f.cleanup(); }
});

integration('resolved canceled or spent minute orders retain only an opaque account tombstone on deletion', async () => {
  const f = await fixture();
  try {
    for (const state of ['expired','spent','refunded'] as const) {
      const account = await f.account(), order = await f.order(account);
      await f.webhook(f.stripeEvent(order.orderID, state === 'expired' ? 'expired' : 'paid'));
      if (state === 'spent') {
        const hold = await reserveMinutes(f.db, account.id, 'spent-purchase', 1_800_000); await finishMinuteReservation(f.db, hold, 1_800_000);
      }
      if (state === 'refunded') await f.webhook(f.stripeEvent(order.orderID, 'paid', 997));
      const response = await f.app.inject({ method: 'DELETE', url: '/v1/account', headers: account.headers, payload: {} });
      assert.equal(response.statusCode, 200, response.body); assert.equal(response.json().deleted, true); assert.match(response.json().retained, /opaque account ID/);
      const row = (await f.db.query('SELECT email,deleted_at FROM accounts WHERE id=$1', [account.id])).rows[0];
      assert.equal(row.email, null); assert.ok(row.deleted_at);
      for (const table of ['identities','auth_sessions']) assert.equal((await f.db.query(`SELECT 1 FROM ${table} WHERE account_id=$1`, [account.id])).rowCount, 0);
      assert.equal((await f.db.query('SELECT 1 FROM minute_purchase_orders WHERE id=$1', [order.orderID])).rowCount, 1);
      await assert.rejects(authenticate(f.db, account.headers.authorization), { code: 'sign_in_required' });
    }
  } finally { await f.cleanup(); }
});

integration('signup-only deletion removes empty wallets and all signup data completely', async () => {
  const f = await fixture();
  try {
    const account = await f.account();
    await f.db.query('INSERT INTO minute_wallets(account_id) VALUES($1)', [account.id]);
    assert.deepEqual(await deleteAccount(f.db, account.id), { retainedFinancialRecords: false });
    for (const [table,column] of [['accounts','id'],['wallets','account_id'],['minute_wallets','account_id'],['identities','account_id'],['auth_sessions','account_id']])
      assert.equal((await f.db.query(`SELECT 1 FROM ${table} WHERE ${column}=$1`, [account.id])).rowCount, 0);
  } finally { await f.cleanup(); }
});

integration('order creation racing deletion cannot produce an unowned payable order', async () => {
  const f = await fixture();
  try {
    const account = await f.account();
    const results = await Promise.allSettled([deleteAccount(f.db, account.id), f.purchases.createOrder(account.id, 'stripe', 'fixture-thirty', randomUUID())]);
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
    const rows = (await f.db.query('SELECT o.id,a.deleted_at FROM minute_purchase_orders o JOIN accounts a ON a.id=o.account_id WHERE o.account_id=$1', [account.id])).rows;
    if (rows.length) assert.equal(rows[0].deleted_at, null);
  } finally { await f.cleanup(); }
});


integration('AI-value HTTP catalog, owned quote, signed settlement and refund preserve exact fee snapshot',async()=>{
  const f=await fixture({aiValue:true});try{
    const member=await f.account(),other=await f.account();
    const catalog=(await f.app.inject({url:'/v1/minutes/products?provider=stripe',headers:network})).json();
    assert.equal(catalog.billingBasis,'actual-ai-usage');assert.equal(catalog.products[0].entitlementKind,'ai_value');
    assert.equal(catalog.products[0].aiValueNanoUSD,'8000000000');assert.equal(catalog.products[0].estimatedMilliseconds,4_800_000);
    assert.equal(catalog.products[0].merchant,undefined);assert.equal(catalog.products[0].minutes,undefined);
    assert.equal(catalog.products[0].quote.serviceFeeMinor,120);assert.equal(catalog.products[0].quote.processingEstimateMinor,77);
    const key=randomUUID(),order=await f.order(member,'stripe',key);
    assert.equal(order.aiValueNanoUSD,'8000000000');assert.equal(order.quote.totalMinor,997);
    assert.equal((await f.app.inject({url:`${rootURL}/${order.orderID}`,headers:other.headers})).statusCode,404);
    const created=(await f.app.inject({url:`${rootURL}/${order.orderID}`,headers:member.headers})).json();
    assert.equal(created.state,'created');assert.equal(created.entitlementKind,'ai_value');
    await updateAIPricingPolicy(f.db,{version:1,serviceFeeBasisPoints:1100},'synthetic operator','verify immutable HTTP quote');
    const repeated=await f.order(member,'stripe',key);assert.deepEqual(repeated.quote,order.quote);
    const event=f.stripeEvent(order.orderID);assert.equal((await f.webhook(event)).statusCode,200);assert.equal((await f.webhook(event)).statusCode,200);
    const paid=(await f.app.inject({url:`${rootURL}/${order.orderID}`,headers:member.headers})).json();
    assert.equal(paid.grantedNanoUSD,'8000000000');assert.equal(paid.fulfillmentRecorded,true);
    assert.equal((await paidAIBalance(f.db,member.id)).availableNanoUSD,'0'); // Test receipt cannot pay for public voice.
    assert.equal((await f.webhook(f.stripeEvent(order.orderID,'paid',997))).statusCode,200);
    const refunded=(await f.app.inject({url:`${rootURL}/${order.orderID}`,headers:member.headers})).json();
    assert.equal(refunded.reversedNanoUSD,'8000000000');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_entries WHERE account_id=$1',[member.id])).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('AI-value Play HTTP recovery verifies binding and restores no fixed minutes after reinstall',async()=>{
  const f=await fixture({aiValue:true});try{
    const member=await f.account(),other=await f.account(),order=await f.order(member,'play'),token=f.bindPlay(order);
    const path='/v1/minutes/play/recover';
    const denied=await f.app.inject({method:'POST',url:path,headers:other.headers,payload:{purchaseToken:token}});
    assert.notEqual(denied.statusCode,200);
    for (let count=0;count<2;count++) {
      const result=await f.app.inject({method:'POST',url:path,headers:member.headers,payload:{purchaseToken:token}});
      assert.equal(result.statusCode,200,result.body);assert.equal(result.json().orderID,order.orderID);
      assert.equal(result.json().grantedNanoUSD,'8000000000');assert.equal(result.json().entitlementKind,'ai_value');
    }
    assert.equal((await f.db.query('SELECT count(*) FROM ledger WHERE account_id=$1',[member.id])).rows[0].count,'1');
    await f.drain();assert.equal((await f.db.query('SELECT count(*) FROM minute_entries WHERE account_id=$1',[member.id])).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('account deletion blocks pending AI orders and paid balances, but retains resolved refunded orders without identity',async()=>{
  const f=await fixture({aiValue:true});try{
    const member=await f.account(),order=await f.order(member);
    await assert.rejects(deleteAccount(f.db,member.id),{code:'unresolved_billing'});
    await f.webhook(f.stripeEvent(order.orderID));await assert.rejects(deleteAccount(f.db,member.id),{code:'unresolved_billing'});
    await f.webhook(f.stripeEvent(order.orderID,'paid',997));
    const result=await deleteAccount(f.db,member.id);assert.equal(result.retainedFinancialRecords,true);
    const tombstone=(await f.db.query('SELECT email,deleted_at FROM accounts WHERE id=$1',[member.id])).rows[0];
    assert.equal(tombstone.email,null);assert.ok(tombstone.deleted_at);
    assert.equal((await f.db.query('SELECT count(*) FROM identities WHERE account_id=$1',[member.id])).rows[0].count,'0');
    assert.equal((await f.db.query('SELECT count(*) FROM ai_value_purchase_transactions WHERE account_id=$1',[member.id])).rows[0].count,'1');
  }finally{await f.cleanup();}
});

for (const aiValue of [false, true]) {
  integration(`durable Stripe key recovery is owner-bound for ${aiValue ? 'AI value' : 'minute'} orders`, async () => {
    const f = await fixture({ aiValue });
    try {
      const member = await f.account(), other = await f.account(), key = randomUUID();
      const order = await f.order(member, 'stripe', key);
      const path = `${rootURL}/by-key/${key}?provider=stripe`;
      const found = await f.app.inject({ url: path, headers: member.headers });
      assert.equal(found.statusCode, 200, found.body); assert.deepEqual(found.json(), { orderID: order.orderID });
      assert.equal(found.headers['cache-control'], 'no-store');
      const denied = await f.app.inject({ url: path, headers: other.headers });
      assert.equal(denied.statusCode, 404); assert.equal(denied.json().error.code, 'purchase_not_found');
      const otherOrder = await f.order(other, 'stripe', key);
      assert.deepEqual((await f.app.inject({ url: path, headers: other.headers })).json(), { orderID: otherOrder.orderID });
      assert.deepEqual((await f.app.inject({ url: path, headers: member.headers })).json(), { orderID: order.orderID });
      const ordinaryStatus = await f.app.inject({ url: `${rootURL}/${order.orderID}`, headers: member.headers });
      assert.equal(ordinaryStatus.statusCode, 200); assert.equal(ordinaryStatus.json().orderID, order.orderID);
      assert.equal(f.createCount, 2); // Lookup never contacts Stripe or creates another order.
      const playKey = randomUUID(); await f.order(member, 'play', playKey);
      const wrongProvider = await f.app.inject({ url: `${rootURL}/by-key/${playKey}?provider=stripe`, headers: member.headers });
      assert.equal(wrongProvider.statusCode, 404); assert.equal(wrongProvider.json().error.code, 'purchase_not_found');
    } finally { await f.cleanup(); }
  });
}
integration('Stripe key recovery validates inputs, membership and trusted admission before exposing an order', async () => {
  const f = await fixture();
  try {
    const member = await f.account(), guest = await f.account(true), key = randomUUID();
    const order = await f.order(member, 'stripe', key), path = `${rootURL}/by-key/${key}?provider=stripe`;
    for (const headers of [network, guest.headers]) {
      const result = await f.app.inject({ url: path, headers });
      assert.equal(result.statusCode, 401); assert.equal(result.json().error.code, 'sign_in_required');
    }
    for (const suffix of ['', '?provider=play', '?provider=stripe&provider=stripe']) {
      const result = await f.app.inject({ url: `${rootURL}/by-key/${key}${suffix}`, headers: member.headers });
      assert.equal(result.statusCode, 400); assert.equal(result.json().error.code, 'invalid_purchase_provider');
    }
    for (const bad of ['short', 'bad%20key', 'injection%27marker']) {
      const result = await f.app.inject({ url: `${rootURL}/by-key/${bad}?provider=stripe`, headers: member.headers });
      assert.equal(result.statusCode, 400); assert.equal(result.json().error.code, 'invalid_minute_order');
    }
    const extra = await f.app.inject({ url: `${path}&accountID=${member.id}`, headers: member.headers });
    assert.equal(extra.statusCode, 400); assert.equal(extra.json().error.code, 'invalid_request');
    const untrusted = await f.app.inject({ url: path, headers: { authorization: member.headers.authorization } });
    assert.equal(untrusted.statusCode, 503); assert.equal(untrusted.json().error.code, 'accounts_proxy_not_ready');
    const maxKey = 'A'.repeat(128), maxOrder = await f.order(member, 'stripe', maxKey);
    assert.deepEqual((await f.app.inject({ url: `${rootURL}/by-key/${maxKey}?provider=stripe`, headers: member.headers })).json(), { orderID: maxOrder.orderID });
    assert.equal((await f.app.inject({ url: `${rootURL}/by-key/${'A'.repeat(129)}?provider=stripe`, headers: member.headers })).statusCode, 414);
    await f.db.query("UPDATE auth_rate_limits SET hits=600 WHERE operation='account' AND scope='network'");
    const limited = await f.app.inject({ url: path, headers: member.headers });
    assert.equal(limited.statusCode, 429); assert.equal(limited.json().error.code, 'rate_limit');
    assert.equal(limited.headers['retry-after'], '3600'); assert.equal(limited.body.includes(order.orderID), false);
  } finally { await f.cleanup(); }
});
integration('lost create responses remain recoverable after the Stripe idempotency window and with sales disabled', async () => {
  const f = await fixture({ aiValue: true }); let disabled: ReturnType<typeof createApp> | undefined;
  try {
    const member = await f.account(), key = randomUUID();
    const order = await f.aiPurchases!.createOrder(member.id, 'stripe', 'fixture-ai', key);
    // An old request may have reached Stripe without its response reaching Mural. Never recreate it blindly.
    await f.db.query("INSERT INTO minute_stripe_checkout_attempts(order_id,started_at) VALUES($1,now()-interval '25 hours')", [order.orderID]);
    await assert.rejects(f.stripe.checkout(member.id, order.orderID), { code: 'checkout_reconciliation_required' });
    disabled = createApp({ db: f.db, auth: { googleClientID: 'synthetic-google-client' }, accounts: { admission: new AuthAdmission(f.db, proxy) },
      minuteCommerce: { purchases: new MinutePurchases(f.db, { salesEnabled: false }),
        aiPurchases: new AIValuePurchases(f.db, { salesEnabled: false }), stripe: f.stripe } });
    const found = await disabled.inject({ url: `${rootURL}/by-key/${key}?provider=stripe`, headers: member.headers });
    assert.equal(found.statusCode, 200, found.body); assert.deepEqual(found.json(), { orderID: order.orderID });
    const status = await disabled.inject({ url: `${rootURL}/${order.orderID}`, headers: member.headers });
    assert.equal(status.statusCode, 200); assert.equal(status.json().state, 'created');
    const unknown = await disabled.inject({ url: `${rootURL}/by-key/${randomUUID()}?provider=stripe`, headers: member.headers });
    assert.equal(unknown.statusCode, 404); assert.equal(f.createCount, 0);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count, '1');
  } finally { await disabled?.close(); await f.cleanup(); }
});
integration('mapped expired Stripe sessions reconcile to voided without a webhook or customer retry', async () => {
  const f = await fixture({ aiValue: true });
  try {
    const member = await f.account(), key = randomUUID(), order = await f.order(member, 'stripe', key);
    f.stripeEvent(order.orderID, 'expired'); // Change authoritative provider state; deliberately do not deliver this webhook.
    const result = await f.drain(); assert.equal(result.completed, 1); assert.equal(result.retried, 0);
    const recovered = await f.app.inject({ url: `${rootURL}/by-key/${key}?provider=stripe`, headers: member.headers });
    assert.deepEqual(recovered.json(), { orderID: order.orderID });
    const status = await f.app.inject({ url: `${rootURL}/${order.orderID}`, headers: member.headers });
    assert.equal(status.json().state, 'voided'); assert.equal(status.json().grantedNanoUSD, '0');
    assert.equal((await paidAIBalance(f.db, member.id)).balanceNanoUSD, '0'); assert.equal(f.createCount, 1);
  } finally { await f.cleanup(); }
});
