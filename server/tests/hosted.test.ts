import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { WebSocketServer, type WebSocket } from 'ws';
import type Stripe from 'stripe';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { OpenAILiveProvider } from '../src/live-provider.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { applyStripeEvent } from '../src/payments.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function until(predicate: () => Promise<boolean> | boolean) {
  const deadline = Date.now() + 3_000;
  while (!(await predicate())) { if (Date.now() > deadline) throw new Error('Timed out waiting for test condition'); await new Promise(resolve => setTimeout(resolve, 5)); }
}
async function fixture(cap = 2_000_000_000n) {
  const schema = `voice_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]); await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 2_000_000_000n, 0n));
  const sockets = new Map<string, WebSocket>();
  let creates = 0, hangups = 0, closes = 0, respondToClose = false, rejectCreate = false, seconds = 0, now = Date.now();
  const payloads: unknown[] = [];
  const server = createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer test-no-real-provider-key');
    if (request.url === '/v1/live/sessions') {
      creates++; const chunks = []; for await (const chunk of request) chunks.push(chunk);
      if (rejectCreate) { response.writeHead(502); response.end('private-provider-error'); return; }
      payloads.push(JSON.parse(Buffer.concat(chunks).toString()));
      response.writeHead(201, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ session: { id: `live_fake_${creates}` }, transport: { type: 'webrtc', sdp: 'v=0\r\nfake-answer' } }));
    } else if (request.url?.endsWith('/hangup')) {
      hangups++; response.writeHead(200); response.end();
      if (respondToClose) for (const socket of sockets.values()) socket.send(JSON.stringify({ type: 'session.closed', usage: { seconds } }));
    } else { response.writeHead(404); response.end(); }
  });
  const websocket = new WebSocketServer({ server });
  websocket.on('connection', (socket, request) => {
    assert.equal(request.headers.authorization, 'Bearer test-no-real-provider-key');
    const id = decodeURIComponent(request.url!.split('/')[4]!); sockets.set(id, socket);
    socket.on('message', data => {
      const event = JSON.parse(data.toString()); assert.equal(event.type, 'session.close'); closes++;
      if (respondToClose) socket.send(JSON.stringify({ type: 'session.closed', usage: { seconds } }));
    });
    socket.on('close', () => { if (sockets.get(id) === socket) sockets.delete(id); });
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as { port: number };
  const provider = new OpenAILiveProvider('test-no-real-provider-key', { testOrigin: `http://127.0.0.1:${address.port}`, timeoutMilliseconds: 300 });
  let controller = new HostedVoice(db, provider, { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap,
    now: () => now, closeGraceMilliseconds: 20 });
  await controller.start();
  return { db, account, payloads, provider, get controller() { return controller; },
    get creates() { return creates; }, get hangups() { return hangups; }, get closes() { return closes; },
    set closeReplies(value: boolean) { respondToClose = value; }, set seconds(value: number) { seconds = value; },
    set rejectCreate(value: boolean) { rejectCreate = value; },
    advance(ms: number) { now += ms; },
    send(id: string, event: unknown) { sockets.get(id)!.send(JSON.stringify(event)); },
    disconnect(id: string) { sockets.get(id)!.terminate(); },
    async restart() { await controller.stop(); controller = new HostedVoice(db, provider,
      { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap, now: () => now, closeGraceMilliseconds: 20 }); await controller.start(); },
    async wallet() { return (await db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1', [account])).rows[0]; },
    async cleanup() {
      await controller.stop(); for (const socket of sockets.values()) socket.terminate();
      await new Promise<void>(resolve => websocket.close(() => resolve()));
      await new Promise<void>(resolve => server.close(() => resolve()));
      await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end();
    }
  };
}
integration('real HTTP/WebSocket adapter meters snapshots once, releases hold, and retains no conversation content', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'voice-request-one', 'v=0\r\nsensitive-offer', 'es-ES');
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    assert.equal((f.payloads[0] as any).session.store, false);
    assert.equal((f.payloads[0] as any).session.delegation.type, 'client');
    f.send(live.providerSessionID, { type: 'session.output_transcript.delta', delta: 'private-test-sentence' });
    f.send(live.providerSessionID, { type: 'session.input_audio.append', audio: 'private-audio-marker' });
    for (const seconds of [12, 12, 10, 20]) f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).observedMilliseconds === 20_000);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 20 }, session: { id: live.providerSessionID, instructions: 'private-instructions' } });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 20 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.wallet(), { balance_nano: '1983333333', reserved_nano: '0' });
    const records = (await f.db.query('SELECT row_to_json(h) AS row FROM hosted_sessions h')).rows;
    const all = JSON.stringify(records);
    for (const marker of ['private-test-sentence', 'private-audio-marker', 'private-instructions', 'sensitive-offer']) assert.equal(all.includes(marker), false);
    assert.equal((await f.db.query("SELECT id FROM ledger WHERE kind='settle'")).rowCount, 1);
    await assert.rejects(f.controller.create(f.account, 'voice-request-one', 'v=0', 'es-ES'), { code: 'live_request_already_created' });
    assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('concurrent starts and a second worker cannot create duplicate funded sessions', async () => {
  const f = await fixture();
  try {
    const second = new HostedVoice(f.db, f.provider, { accountAllowlist: new Set([f.account]), lifetimeFundingCapNano: 2_000_000_000n });
    await assert.rejects(second.start(), { code: 'voice_worker_already_running' });
    const results = await Promise.allSettled([f.controller.create(f.account, 'same-offer-key', 'v=0', 'fr-FR'),
      f.controller.create(f.account, 'same-offer-key', 'v=0', 'fr-FR'), f.controller.create(f.account, 'different-key', 'v=0', 'fr-FR')]);
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1); assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('600-second deadline forces close and HTTP fallback; missing final usage keeps hold and blocks further funding', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'timeout-offer-key', 'v=0', 'nb-NO');
    f.advance(600_001); await f.controller.tick(); await until(() => f.closes > 0);
    f.advance(21); await f.controller.tick(); assert.ok(f.hangups > 0);
    assert.equal((await f.controller.status(f.account, live.sessionID)).state, 'incomplete');
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    await assert.rejects(f.controller.create(f.account, 'next-offer-key', 'v=0', 'nb-NO'), { code: 'live_session_unresolved' });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 601 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    const status = await f.controller.status(f.account, live.sessionID);
    assert.equal(status.chargedNanoUSD, '500000000'); assert.equal(status.providerCostNanoUSD, '500833334');
    assert.equal((await f.wallet()).reserved_nano, '0');
  } finally { await f.cleanup(); }
});
integration('recovery reattaches the saved provider ID and closes without creating again', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'recovery-offer-key', 'v=0', 'en-US');
    f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds: 30 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).observedMilliseconds === 30_000);
    await f.restart(); assert.equal(f.creates, 1);
    f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds: 30 } });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 40 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.equal((await f.wallet()).balance_nano, '1966666666');
  } finally { await f.cleanup(); }
});
integration('a refund during speech triggers closure and reconciles usage without restoring reversed credits', async () => {
  const f = await fixture();
  try {
    const order = randomUUID();
    await f.db.query(`INSERT INTO checkout_orders(id,account_id,idempotency_key,product,currency,total_minor,credit_nano,stripe_price_id,stripe_session_id,payment_intent_id,state)
      VALUES($1,$2,$3,'seed','usd',230,'2000000000','price_fake','cs_fake','pi_fake','paid')`, [order, f.account, randomUUID()]);
    const live = await f.controller.create(f.account, 'refund-offer-key', 'v=0', 'es-ES');
    await applyStripeEvent(f.db, { id: `evt_${randomUUID()}`, type: 'charge.refunded', livemode: false,
      data: { object: { id: 'ch_fake', payment_intent: 'pi_fake', amount_refunded: 230 } } } as unknown as Stripe.Event);
    f.seconds = 20; f.closeReplies = true; await f.controller.tick();
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.wallet(), { balance_nano: '-16666667', reserved_nano: '0' });
    assert.equal((await f.db.query("SELECT close_reason FROM hosted_sessions WHERE id=$1", [live.sessionID])).rows[0].close_reason, 'funding_reversed');
  } finally { await f.cleanup(); }
});
integration('sideband loss never accepts client usage or releases the reservation on an HTTP hangup alone', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'loss-offer-key', 'v=0', 'es-ES');
    f.disconnect(live.providerSessionID);
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'incomplete');
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    await f.controller.tick();
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 1 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.equal((await f.controller.status(f.account, live.sessionID)).chargedNanoUSD, '12500000');
  } finally { await f.cleanup(); }
});
integration('operator allowlist and lifetime funding cap are enforced before a provider create', async () => {
  const f = await fixture(500_000_000n);
  try {
    await assert.rejects(f.controller.create(randomUUID(), 'not-allowed-key', 'v=0', 'es-ES'), { code: 'hosted_voice_not_ready' });
    assert.equal(f.creates, 0);
    const live = await f.controller.create(f.account, 'capped-first-key', 'v=0', 'es-ES');
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 600 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    await assert.rejects(f.controller.create(f.account, 'capped-second-key', 'v=0', 'es-ES'), { code: 'hosted_funding_cap_reached' });
    assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('uncertain billed creation is never retried and its hold survives worker recovery', async () => {
  const f = await fixture();
  try {
    f.rejectCreate = true;
    await assert.rejects(f.controller.create(f.account, 'uncertain-key', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
    assert.equal(f.creates, 1); assert.equal((await f.wallet()).reserved_nano, '500000000');
    await f.restart();
    await assert.rejects(f.controller.create(f.account, 'uncertain-key', 'v=0', 'es-ES'), { code: 'live_request_already_created' });
    await assert.rejects(f.controller.create(f.account, 'new-after-uncertain', 'v=0', 'es-ES'), { code: 'live_session_unresolved' });
    assert.equal(f.creates, 1);
    const row = (await f.db.query('SELECT state,provider_session_id,charged_nano FROM hosted_sessions')).rows[0];
    assert.deepEqual(row, { state: 'incomplete', provider_session_id: null, charged_nano: null });
  } finally { await f.cleanup(); }
});
integration('regressing final usage does not settle or refund a hold; trusted reconciliation can finish later', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'regressed-final-key', 'v=0', 'es-ES');
    f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds: 30 } });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 20 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'incomplete');
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    await f.controller.tick();
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 35 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.equal((await f.controller.status(f.account, live.sessionID)).chargedNanoUSD, '29166667');
  } finally { await f.cleanup(); }
});

integration('Tagalog locale creates and settles voice while aliases cannot reserve credit', async () => {
  const f = await fixture();
  try {
    const before = await f.wallet();
    for (const language of ['tl', 'fil', 'fil-PH', 'tgl-PH', 'tl_PH', '__proto__']) {
      await assert.rejects(f.controller.create(f.account, `tagalog-invalid-${language}`, 'v=0', language), { code: 'invalid_live_offer' });
    }
    assert.equal(f.creates, 0);
    assert.deepEqual(await f.wallet(), before);
    assert.equal((await f.db.query('SELECT count(*) FROM reservations')).rows[0].count, '0');
    assert.equal((await f.db.query('SELECT count(*) FROM hosted_sessions')).rows[0].count, '0');
    const live = await f.controller.create(f.account, 'tagalog-valid-offer', 'v=0', 'tl-PH');
    assert.equal(f.creates, 1);
    assert.match((f.payloads[0] as { session: { instructions: string } }).session.instructions, /Speak only Tagalog as spoken in the Philippines/);
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    await f.controller.close(f.account, live.sessionID);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 20 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.equal((await f.wallet()).reserved_nano, '0');
    assert.equal((await f.controller.status(f.account, live.sessionID)).chargedNanoUSD, '16666667');
  } finally { await f.cleanup(); }
});
