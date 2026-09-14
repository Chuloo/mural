import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { SandboxPayments } from '../src/payments.js';

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
