import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { WebSocketServer, type WebSocket } from 'ws';
import type Stripe from 'stripe';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { OpenAILiveProvider } from '../src/live-provider.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { applyStripeEvent } from '../src/payments.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { MinutePurchases, type VerifiedMinutePurchase } from '../src/minute-purchases.js';
import { HostedHelpers } from '../src/hosted-helpers.js';
import { digest, signOut, authenticate } from '../src/auth.js';

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function until(predicate: () => Promise<boolean> | boolean) {
  const deadline = Date.now() + 3_000;
  while (!(await predicate())) { if (Date.now() > deadline) throw new Error('Timed out waiting for test condition'); await new Promise(resolve => setTimeout(resolve, 5)); }
}
async function fixture(cap = 2_000_000_000n, minuteAllowance?: number, helperBudget?: bigint) {
  const schema = `voice_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]); await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 2_000_000_000n, 0n));
  if (minuteAllowance !== undefined) {
    await db.query('UPDATE accounts SET is_guest=true WHERE id=$1', [account]);
    await db.query('DELETE FROM wallets WHERE account_id=$1', [account]);
    await transaction(db, sql => appendMinuteEntry(sql, account, `minute-seed:${account}`, 'gift', minuteAllowance, 0));
  }
  const sockets = new Map<string, WebSocket>();
  let creates = 0, hangups = 0, closes = 0, respondToClose = false, rejectCreate = false, cancelBeforeProvider = false, seconds = 0, now = Date.now(), setupDelay = 0;
  const payloads: unknown[] = [];
  const server = createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer test-no-real-provider-key');
    if (request.url === '/v1/live/sessions') {
      creates++; const chunks = []; for await (const chunk of request) chunks.push(chunk);
      now += setupDelay;
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
  const helpers = helperBudget === undefined ? undefined : new HostedHelpers(db,
    { send: async () => { throw new Error('No helper network call expected.'); } }, {
      accountAllowlist: new Set([account]), aggregateFundingCapNano: cap, helperBudgetNanoPerMinute: helperBudget,
      maxRequestsPerMinute: 6, maxSearchesPerSession: 0, maxConcurrentPerSession: 2, maxConcurrentGlobal: 4,
      postSessionMilliseconds: 120_000, inputFramingTokenAllowance: 4096, searchInputTokenAllowance: 1_050_000,
      timeoutMilliseconds: 1000,
    });
  const helperAdmission = { async reserveSessionBudget(sql: any, owner: string, id: string) {
    await helpers?.reserveSessionBudget(sql, owner, id);
    if (cancelBeforeProvider) await sql.query("UPDATE hosted_sessions SET state='closing',close_requested_at=now() WHERE id=$1", [id]);
  } };
  let controller = new HostedVoice(db, provider, { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap,
    billingUnit: minuteAllowance === undefined ? 'nanoUSD' : 'milliseconds',
    helpers: helperAdmission, now: () => now, closeGraceMilliseconds: 20 });
  await controller.start();
  return { db, account, payloads, provider, get controller() { return controller; },
    get creates() { return creates; }, get hangups() { return hangups; }, get closes() { return closes; },
    set closeReplies(value: boolean) { respondToClose = value; }, set seconds(value: number) { seconds = value; },
    set rejectCreate(value: boolean) { rejectCreate = value; },
    set cancelBeforeProvider(value: boolean) { cancelBeforeProvider = value; },
    set setupDelay(value: number) { setupDelay = value; }, get now() { return now; },
    advance(ms: number) { now += ms; },
    send(id: string, event: unknown) { sockets.get(id)!.send(JSON.stringify(event)); },
    disconnect(id: string) { sockets.get(id)!.terminate(); },
    async restart() { await controller.stop(); controller = new HostedVoice(db, provider,
      { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap,
        billingUnit: minuteAllowance === undefined ? 'nanoUSD' : 'milliseconds', helpers: helperAdmission, now: () => now, closeGraceMilliseconds: 20 }); await controller.start(); },
    async wallet() { return (await db.query('SELECT balance_nano,reserved_nano FROM wallets WHERE account_id=$1', [account])).rows[0]; },
    async minutes() { return (await db.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1', [account])).rows[0]; },
    async cleanup() {
      await controller.stop(); for (const socket of sockets.values()) socket.terminate();
      await new Promise<void>(resolve => websocket.close(() => resolve()));
      await new Promise<void>(resolve => server.close(() => resolve()));
      await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end();
    }
  };
}
integration('minute admission reserves helpers before any provider call and rolls back an unfunded conversation', async () => {
  const f = await fixture(1_000_000_000n, 600_000, 60_000_000n);
  try {
    await assert.rejects(f.controller.create(f.account, 'unfunded-helper-budget', 'v=0', 'es-ES'), { code: 'hosted_funding_cap_reached' });
    assert.equal(f.creates, 0);
    assert.deepEqual(await f.minutes(), { balance_ms: '600000', reserved_ms: '0' });
    for (const table of ['hosted_sessions', 'hosted_helper_sessions', 'minute_reservations'])
      assert.equal((await f.db.query(`SELECT count(*) AS total FROM ${table}`)).rows[0].total, '0');
  } finally { await f.cleanup(); }
});
integration('sign-out records a durable stop before revocation so a discarded bearer cannot strand live audio', async () => {
  const f = await fixture();
  try {
    const token = randomBytes(32).toString('base64url'), authorization = `Bearer ${token}`;
    await f.db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')", [randomUUID(), f.account, digest(token)]);
    const live = await f.controller.create(f.account, 'sign-out-live-session', 'v=0', 'es-ES');
    await signOut(f.db, authorization);
    await assert.rejects(authenticate(f.db, authorization), { code: 'sign_in_required' });
    const row = (await f.db.query('SELECT close_reason,close_requested_at FROM hosted_sessions WHERE id=$1', [live.sessionID])).rows[0];
    assert.equal(row.close_reason, 'sign_out'); assert.ok(row.close_requested_at);
    f.seconds = 3; f.closeReplies = true; await f.controller.tick();
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.ok(f.closes > 0); assert.equal((await f.wallet()).reserved_nano, '0');
  } finally { await f.cleanup(); }
});
integration('voice admission counts existing helper liability and activates its original budget with the real deadline', async () => {
  const f = await fixture(1_000_000_000n, 1_200_000, 50_000_000n);
  try {
    f.setupDelay = 12_000;
    const live = await f.controller.create(f.account, 'funded-helper-budget', 'v=0', 'es-ES');
    const budget = (await f.db.query('SELECT * FROM hosted_helper_sessions WHERE session_id=$1', [live.sessionID])).rows[0];
    assert.equal(budget.budget_nano, '500000000'); assert.equal(budget.liability_nano, '500000000');
    assert.equal(budget.activation_pending, false);
    assert.equal(budget.expires_at.getTime(), new Date(live.deadline).getTime() + 120_000);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 15 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    await assert.rejects(f.controller.create(f.account, 'must-include-helper-liability', 'v=0', 'es-ES'), { code: 'hosted_funding_cap_reached' });
    assert.equal(f.creates, 1); assert.equal((await f.minutes()).reserved_ms, '0');
  } finally { await f.cleanup(); }
});
integration('real HTTP/WebSocket adapter meters snapshots once, releases hold, and retains no conversation content', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'voice-request-one', 'v=0\r\nsensitive-offer', 'es-ES',
      { instructions: 'private-teaching-instructions', history: [{ type:'message', role:'user', content:[{type:'input_text',text:'private-history'}] }] });
    assert.equal((await f.wallet()).reserved_nano, '500000000');
    assert.equal((f.payloads[0] as any).session.store, false);
    assert.equal((f.payloads[0] as any).session.delegation.type, 'client');
    assert.ok((f.payloads[0] as any).session.instructions.includes('private-teaching-instructions'));
    assert.equal((f.payloads[0] as any).session.input[0].content[0].text, 'private-history');
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
    for (const marker of ['private-test-sentence', 'private-audio-marker', 'private-instructions', 'sensitive-offer', 'private-teaching-instructions', 'private-history']) assert.equal(all.includes(marker), false);
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
integration('minute mode snapshots a 15-second minimum and settles it once from trusted final usage', async () => {
  const f = await fixture(2_000_000_000n, 90_000);
  try {
    const live = await f.controller.create(f.account, 'minute-first-key', 'v=0', 'es-ES');
    assert.equal(live.reservedMilliseconds, 90_000);
    assert.equal(live.minimumChargeMilliseconds, 15_000);
    assert.equal(live.billingPolicy, 'connected-time-15s-minimum-v1');
    assert.deepEqual(await f.minutes(), { balance_ms: '90000', reserved_ms: '90000' });
    assert.equal(await f.wallet(), undefined);
    for (const seconds of [5, 5, 3]) f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds } });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 5.25 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '75000', reserved_ms: '0' });
    const status = await f.controller.status(f.account, live.sessionID);
    assert.equal(status.chargedMilliseconds, 15_000);
    assert.equal(status.minimumChargeMilliseconds, 15_000);
    assert.equal(status.billingPolicy, 'connected-time-15s-minimum-v1');
    assert.equal(status.providerCostNanoUSD, '12500000');
    assert.equal((await f.db.query("SELECT 1 FROM minute_entries WHERE kind='settle'")).rowCount, 1);
    await assert.rejects(f.controller.create(f.account, 'minute-first-key', 'v=0', 'es-ES'), { code: 'live_request_already_created' });
    assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('minute deadline closes at the available remainder, keeps an uncertain hold, and absorbs cutoff overrun', async () => {
  const f = await fixture(2_000_000_000n, 20_000);
  try {
    const live = await f.controller.create(f.account, 'minute-timeout-key', 'v=0', 'de-DE');
    f.advance(20_001); await f.controller.tick(); await until(() => f.closes > 0);
    f.advance(21); await f.controller.tick(); assert.ok(f.hangups > 0);
    assert.deepEqual(await f.minutes(), { balance_ms: '20000', reserved_ms: '20000' });
    await assert.rejects(f.controller.create(f.account, 'minute-next-key', 'v=0', 'de-DE'), { code: 'live_session_unresolved' });
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 21 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '0', reserved_ms: '0' });
    assert.equal((await f.controller.status(f.account, live.sessionID)).chargedMilliseconds, 20_000);
    await assert.rejects(f.controller.create(f.account, 'minute-empty-key', 'v=0', 'de-DE'), { code: 'insufficient_minutes' });
    assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('minute-funded recovery closes the original provider session and settles its reservation once', async () => {
  const f = await fixture(2_000_000_000n, 1_800_000);
  try {
    const live = await f.controller.create(f.account, 'minute-recovery-key', 'v=0', 'fr-FR');
    assert.equal(live.reservedMilliseconds, 600_000);
    await f.restart(); assert.equal(f.creates, 1);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 40 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '1760000', reserved_ms: '0' });
    assert.equal((await f.db.query("SELECT 1 FROM minute_entries WHERE kind='settle'")).rowCount, 1);
  } finally { await f.cleanup(); }
});
integration('minute mode never releases a hold after an uncertain provider create', async () => {
  const f = await fixture(2_000_000_000n, 60_000);
  try {
    f.rejectCreate = true;
    await assert.rejects(f.controller.create(f.account, 'minute-uncertain-key', 'v=0', 'it-IT'), { code: 'provider_session_unconfirmed' });
    await f.restart();
    assert.deepEqual(await f.minutes(), { balance_ms: '60000', reserved_ms: '60000' });
    await assert.rejects(f.controller.create(f.account, 'minute-uncertain-key', 'v=0', 'it-IT'), { code: 'live_request_already_created' });
    assert.equal(f.creates, 1);
  } finally { await f.cleanup(); }
});
integration('short minute remainders receive a separate setup window and authoritative usage cutoff', async () => {
  const f = await fixture(2_000_000_000n, 2_000);
  try {
    f.setupDelay = 3_000;
    const live = await f.controller.create(f.account, 'minute-short-setup', 'v=0', 'pt-BR');
    assert.equal(new Date(live.deadline).getTime() - f.now, 2_000);
    f.send(live.providerSessionID, { type: 'session.usage.updated', usage: { seconds: 2 } });
    await until(() => f.closes > 0);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 2 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '0', reserved_ms: '0' });
  } finally { await f.cleanup(); }
});
integration('zero-second finalized sessions consume the minimum and cannot restart a ten-minute grant indefinitely', async () => {
  const f = await fixture(2_000_000_000n, 600_000, 50_000_000n);
  try {
    for (let index=0; index<40; index++) {
      const live = await f.controller.create(f.account, `zero-duration-${index}`, 'v=0', 'es-ES');
      f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 0 } });
      await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
      assert.equal((await f.controller.status(f.account, live.sessionID)).chargedMilliseconds, 15_000);
    }
    assert.deepEqual(await f.minutes(), { balance_ms: '0', reserved_ms: '0' });
    assert.equal((await f.db.query('SELECT sum(provider_cost_nano) AS total FROM hosted_sessions')).rows[0].total, '500000000');
    assert.equal((await f.db.query('SELECT sum(liability_nano) AS total FROM hosted_helper_sessions')).rows[0].total, '500000000');
    await assert.rejects(f.controller.create(f.account, 'zero-duration-exhausted', 'v=0', 'es-ES'), { code: 'insufficient_minutes' });
    assert.equal(f.creates, 40);
  } finally { await f.cleanup(); }
});
integration('the final sub-minimum residue is charged once without a negative minute wallet', async () => {
  const f = await fixture(2_000_000_000n, 2_000, 50_000_000n);
  try {
    const live = await f.controller.create(f.account, 'zero-small-residue', 'v=0', 'es-ES');
    assert.equal(live.minimumChargeMilliseconds, 15_000);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 0 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.equal((await f.controller.status(f.account, live.sessionID)).chargedMilliseconds, 2_000);
    assert.equal((await f.db.query('SELECT liability_nano FROM hosted_helper_sessions')).rows[0].liability_nano, '1666666');
    assert.deepEqual(await f.minutes(), { balance_ms: '0', reserved_ms: '0' });
  } finally { await f.cleanup(); }
});
integration('pre-provider cancellation releases all funding and records no minimum or provider charge', async () => {
  const f = await fixture(2_000_000_000n, 600_000, 50_000_000n);
  try {
    f.cancelBeforeProvider = true;
    await assert.rejects(f.controller.create(f.account, 'cancel-before-attempt', 'v=0', 'es-ES'), { code: 'live_session_cancelled' });
    assert.equal(f.creates, 0);
    assert.deepEqual(await f.minutes(), { balance_ms: '600000', reserved_ms: '0' });
    const row = (await f.db.query('SELECT state,charged_ms,provider_cost_nano,funding_exposure_nano,provider_attempted_at FROM hosted_sessions')).rows[0];
    assert.deepEqual(row, { state:'closed', charged_ms:'0', provider_cost_nano:'0', funding_exposure_nano:'0', provider_attempted_at:null });
    assert.equal((await f.db.query('SELECT liability_nano FROM hosted_helper_sessions')).rows[0].liability_nano, '0');
    await f.restart(); assert.equal(f.creates, 0);
  } finally { await f.cleanup(); }
});
integration('worker recovery cancels an admitted minute session that durably never attempted provider creation', async () => {
  const f=await fixture(2_000_000_000n,60_000), id=randomUUID(), reservation=randomUUID();
  try {
    await transaction(f.db,async sql=>{
      await appendMinuteEntry(sql,f.account,'orphan-minute-reserve','reserve',0,60_000);
      await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,$3,60000)',[reservation,f.account,'orphan']);
      await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,
        rate_version,state,deadline,funding_exposure_nano,minimum_charge_ms)
        VALUES($1,$2,'orphan',$3,60000,'test','creating',now()+interval '1 minute',50000000,15000)`,[id,f.account,reservation]);
    });
    await f.restart();
    assert.equal(f.creates,0);
    assert.deepEqual(await f.minutes(),{balance_ms:'60000',reserved_ms:'0'});
    const status=await f.controller.status(f.account,id);
    assert.equal(status.state,'closed');assert.equal(status.chargedMilliseconds,0);assert.equal(status.providerCostNanoUSD,'0');
  } finally { await f.cleanup(); }
});
integration('a short close releases unearned helper liability before another conversation is admitted', async () => {
  const f = await fixture(1_000_000_000n, 600_000, 50_000_000n);
  try {
    const first = await f.controller.create(f.account, 'earned-first', 'v=0', 'es-ES');
    f.send(first.providerSessionID, { type: 'session.closed', usage: { seconds: 1 } });
    await until(async () => (await f.controller.status(f.account, first.sessionID)).state === 'closed');
    const budget = (await f.db.query('SELECT budget_nano,post_close_budget_nano,liability_nano FROM hosted_helper_sessions')).rows[0];
    assert.deepEqual(budget, { budget_nano:'500000000',post_close_budget_nano:'12500000',liability_nano:'12500000' });
    const second = await f.controller.create(f.account, 'earned-second', 'v=0', 'es-ES');
    assert.equal(second.reservedMilliseconds, 585_000);
    assert.equal(f.creates, 2);
  } finally { await f.cleanup(); }
});
integration('existing connected-time sessions retain their immutable original minimum policy', async () => {
  const f = await fixture(2_000_000_000n, 60_000);
  try {
    const live = await f.controller.create(f.account, 'legacy-minute-policy', 'v=0', 'es-ES');
    await assert.rejects(f.db.query('UPDATE hosted_sessions SET minimum_charge_ms=0 WHERE id=$1', [live.sessionID]), /immutable/);
    // Represent a session that existed before migration014. Its copied policy must not be replaced on recovery.
    await f.db.query('ALTER TABLE hosted_sessions DISABLE TRIGGER hosted_minimum_immutable');
    await f.db.query('UPDATE hosted_sessions SET minimum_charge_ms=0 WHERE id=$1', [live.sessionID]);
    await f.db.query('ALTER TABLE hosted_sessions ENABLE TRIGGER hosted_minimum_immutable');
    await f.restart();
    f.send(live.providerSessionID, { type:'session.closed',usage:{seconds:1} });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    const status = await f.controller.status(f.account, live.sessionID);
    assert.equal(status.minimumChargeMilliseconds, 0); assert.equal(status.billingPolicy,'connected-time-only-v1');
    assert.equal(status.chargedMilliseconds,1000);
    assert.deepEqual(await f.minutes(),{balance_ms:'59000',reserved_ms:'0'});
  } finally { await f.cleanup(); }
});
integration('refunding a minute purchase during speech closes and recovers released time atomically', async () => {
  const f = await fixture(2_000_000_000n, 0);
  try {
    await f.db.query('UPDATE accounts SET is_guest=false WHERE id=$1', [f.account]);
    const scope = { provider: 'stripe' as const, environment: 'test' as const, merchant: 'acct_test' };
    let evidence: VerifiedMinutePurchase;
    const purchases = new MinutePurchases(f.db, { salesEnabled: true,
      catalog: [{ ...scope, sku: 'minute-test', providerProduct: 'price_test', minutes: 30, currency: 'usd', totalMinor: 300 }],
      verifiers: [{ ...scope, async verify() { return evidence; } }] });
    const order = await purchases.createOrder(f.account, 'stripe', 'minute-test', 'refund-live-order');
    evidence = { ...scope, orderID: order.orderID, transactionID: 'cs_minute_test', eventID: 'evt_paid', providerProduct: 'price_test',
      quantity: 1, currency: 'usd', totalMinor: 300, state: 'purchased', refundedMinor: 0 };
    await purchases.reconcile('stripe', {});
    const live = await f.controller.create(f.account, 'minute-refund-live', 'v=0', 'en-US');
    evidence = { ...evidence, eventID: 'evt_refunded', refundedMinor: 300, state: 'voided' };
    await purchases.reconcile('stripe', {});
    assert.deepEqual(await f.minutes(), { balance_ms: '600000', reserved_ms: '600000' });
    f.seconds = 5; f.closeReplies = true; await f.controller.tick();
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '0', reserved_ms: '0' });
    const status = await purchases.status(f.account, order.orderID);
    assert.equal(status.reversalOutstandingMilliseconds, 15_000);
    assert.equal(status.reversedMilliseconds, 1_785_000);
    assert.equal((await f.db.query('SELECT close_reason FROM hosted_sessions WHERE id=$1', [live.sessionID])).rows[0].close_reason, 'funding_reversed');
  } finally { await f.cleanup(); }
});

integration('Tagalog locale creates and settles voice while aliases cannot reserve credit or minutes', async () => {
  for (const minuteAllowance of [undefined, 90_000]) {
    const f = await fixture(2_000_000_000n, minuteAllowance);
    try {
      const beforeWallet = await f.wallet(), beforeMinutes = await f.minutes();
      for (const language of ['tl', 'fil', 'fil-PH', 'tgl-PH', 'tl_PH', '__proto__']) {
        await assert.rejects(f.controller.create(f.account, `tagalog-invalid-${language}`, 'v=0', language), { code: 'invalid_live_offer' });
      }
      assert.equal(f.creates, 0);
      assert.deepEqual(await f.wallet(), beforeWallet);
      assert.deepEqual(await f.minutes(), beforeMinutes);
      for (const table of ['reservations', 'minute_reservations', 'hosted_sessions'])
        assert.equal((await f.db.query(`SELECT count(*) FROM ${table}`)).rows[0].count, '0');
      const live = await f.controller.create(f.account, 'tagalog-valid-offer', 'v=0', 'tl-PH');
      assert.equal(f.creates, 1);
      assert.match((f.payloads[0] as { session: { instructions: string } }).session.instructions, /Speak only Tagalog as spoken in the Philippines/);
      if (minuteAllowance === undefined) assert.equal((await f.wallet()).reserved_nano, '500000000');
      else assert.deepEqual(await f.minutes(), { balance_ms: '90000', reserved_ms: '90000' });
      await f.controller.close(f.account, live.sessionID);
      f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 20 } });
      await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
      const status = await f.controller.status(f.account, live.sessionID);
      if (minuteAllowance === undefined) {
        assert.equal((await f.wallet()).reserved_nano, '0');
        assert.equal(status.chargedNanoUSD, '16666667');
      } else {
        assert.deepEqual(await f.minutes(), { balance_ms: '70000', reserved_ms: '0' });
        assert.equal(status.chargedMilliseconds, 20_000);
      }
    } finally { await f.cleanup(); }
  }
});
