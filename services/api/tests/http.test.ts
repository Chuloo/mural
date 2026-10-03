import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { createApp, type Services } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { randomBytes, randomUUID } from 'node:crypto';
import { SandboxPayments } from '../src/payments.js';
import { appleAppTransactionHeader } from '../src/apple-purchase-scope.js';

test('anonymous Apple catalog limits proof work by socket network and resets at the window boundary', async () => {
  let now = Date.now(), proofs = 0;
  const clock = mock.method(Date, 'now', () => now);
  const db = { query: async () => { throw new Error('Catalog must not authenticate or query a database.'); } } as unknown as Database;
  const app = createApp({ db, auth: {}, minuteCommerce: {
    aiPurchases: { products: () => [], maximumQuantity: () => 1 },
    appleScopes: { verify: async () => { proofs++; return 'test'; } },
  } as unknown as Services['minuteCommerce'] });
  const request = (index: number) => ({ url: '/v1/minutes/products?provider=apple&storefront=USA',
    headers: { [appleAppTransactionHeader]: `synthetic-proof-${index}`, 'x-forwarded-for': `203.0.113.${index % 250 + 1}` } });
  try {
    for (let i = 0; i < 120; i++) assert.equal((await app.inject(request(i))).statusCode, 200);
    assert.equal(proofs, 120);
    const denied = await app.inject(request(120));
    assert.equal(denied.statusCode, 429); assert.equal(denied.headers['retry-after'], '60'); assert.equal(proofs, 120);
    now += 59_999;
    const stillDenied = await app.inject(request(121));
    assert.equal(stillDenied.statusCode, 429); assert.equal(stillDenied.headers['retry-after'], '1'); assert.equal(proofs, 120);
    now++;
    assert.equal((await app.inject(request(122))).statusCode, 200); assert.equal(proofs, 121);
  } finally { await app.close(); clock.mock.restore(); }
});

test('close intents reject excess requests before authentication and provider work', async () => {
  for (const trustedProxy of [false, true]) {
    const account = randomUUID(), key = randomUUID();
    let authentications = 0, providerCalls = 0;
    const db = { query: async () => { authentications++; return { rows: [{ account_id: account }] }; } } as unknown as Database;
    const proxyToken = randomBytes(32).toString('hex');
    const app = createApp({ db, auth: {},
      accounts: trustedProxy ? { admission: { config: { proxyToken, hmacKey: randomBytes(32).toString('hex') } } } as Services['accounts'] : undefined,
      hosted: { minuteFunded: true, closeByKey: async () => { providerCalls++; return { state: 'closed' }; } } as unknown as Services['hosted'],
    });
    const headers = { authorization: `Bearer ${randomBytes(32).toString('base64url')}`,
      ...(trustedProxy ? { 'x-mural-proxy-token': proxyToken, 'x-mural-client-ip': '198.51.100.10' } : {}) };
    const request = (index: number) => ({ method: 'POST' as const,
      url: `/v1/live/requests/${key}/${index % 2 ? '%63lose' : 'close'}`,
      headers: { ...headers, 'x-forwarded-for': `203.0.113.${index % 250 + 1}` },
      payload: {} });
    try {
      for (let i = 0; i < 120; i++) assert.equal((await app.inject(request(i))).statusCode, 200);
      assert.equal(authentications, 120); assert.equal(providerCalls, 120);
      for (const i of [120, 121, 122]) {
        const denied = await app.inject(request(i));
        assert.equal(denied.statusCode, 429);
        assert.deepEqual(denied.json(), { error: { code: 'rate_limit' } });
      }
      assert.equal(authentications, 120); assert.equal(providerCalls, 120);
      if (trustedProxy) {
        const forged = await app.inject({ ...request(123), headers: { ...headers, 'x-mural-proxy-token': 'wrong' } });
        assert.equal(forged.statusCode, 503); assert.equal(authentications, 120);
        const other = await app.inject({ ...request(124), headers: { ...headers, 'x-mural-client-ip': '198.51.100.11' } });
        assert.equal(other.statusCode, 200); assert.equal(providerCalls, 121);
      }
    } finally { await app.close(); }
  }
});

test('unconfigured trial and hosted voice fail closed without database or provider access', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const app = createApp({ db, auth: {} });
  try {
    for (const url of ['/v1/trial/eligibility', '/v1/live/sessions']) {
      const response = await app.inject({ method: 'POST', url, payload: { deviceID: 'self-claimed', remainingSeconds: 600 } });
      assert.equal(response.statusCode, 503);
    }
    const wallet = await app.inject({ method: 'GET', url: '/v1/wallet' });
    assert.equal(wallet.statusCode, 503);
    const health = (await app.inject({ method: 'GET', url: '/healthz' })).json();
    assert.equal(health.hostedVoice, false); assert.equal(health.livePayments, false);
  } finally { await app.close(); await db.end(); }
});
test('Stripe signature validates the original bytes and rejects mutation, old timestamps and live events', () => {
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const raw = Buffer.from(JSON.stringify({ id: 'evt_test', livemode: false, type: 'checkout.session.completed', data: { object: { id: 'cs_test' } } }));
  const signature = payments.stripe.webhooks.generateTestHeaderString({ payload: raw.toString(), secret: payments.webhookSecret });
  assert.equal(payments.verify(raw, signature).id, 'evt_test');
  assert.throws(() => payments.verify(Buffer.from(raw.toString() + ' '), signature));
  const expired = payments.stripe.webhooks.generateTestHeaderString({ payload: raw.toString(), secret: payments.webhookSecret, timestamp: 1 });
  assert.throws(() => payments.verify(raw, expired));
  const live = Buffer.from(raw.toString().replace('"livemode":false', '"livemode":true'));
  const liveSignature = payments.stripe.webhooks.generateTestHeaderString({ payload: live.toString(), secret: payments.webhookSecret });
  assert.throws(() => payments.verify(live, liveSignature));
  assert.throws(() => new SandboxPayments('sk_live_never_allowed', 'whsec_test', new Map(), 'https://example.test'));
});
test('webhook route verifies raw bytes before doing any database work', async () => {
  const db = connectDatabase('postgresql://unused@127.0.0.1:1/unused');
  const payments = new SandboxPayments('sk_test_example_not_a_real_key', 'whsec_example_not_a_real_secret', new Map(), 'http://localhost:8080');
  const app = createApp({ db, auth: {}, payments });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/webhooks/stripe', payload: { id: 'untrusted', privateData: 'must-not-be-echoed' }, headers: { 'stripe-signature': 'invalid' } });
    assert.equal(response.statusCode, 400);
    assert.deepEqual(response.json(), { error: { code: 'invalid_webhook_signature' } });
    assert.equal(response.body.includes('privateData'), false);
  } finally { await app.close(); await db.end(); }
});
