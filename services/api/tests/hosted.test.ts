import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { WebSocketServer, type WebSocket } from 'ws';
import type Stripe from 'stripe';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { LiveCreateFailure, LiveCreateRejectedError, OpenAILiveProvider } from '../src/live-provider.js';
import { Diagnostics, type DiagnosticRecord } from '../src/diagnostics.js';
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
async function fixture(cap = 2_000_000_000n, minuteAllowance?: number, helperBudget?: bigint, paid = false) {
  const schema = `voice_test_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const account = randomUUID();
  await db.query('INSERT INTO accounts(id) VALUES($1)', [account]); await db.query('INSERT INTO wallets(account_id) VALUES($1)', [account]);
  await transaction(db, sql => appendEntry(sql, account, `seed:${account}`, 'purchase', 2_000_000_000n, 0n));
  if (minuteAllowance !== undefined) {
    if (!paid) {
      await db.query('UPDATE accounts SET is_guest=true WHERE id=$1', [account]);
      await db.query('DELETE FROM wallets WHERE account_id=$1', [account]);
    }
    await transaction(db, sql => appendMinuteEntry(sql, account, `minute-seed:${account}`, 'gift', minuteAllowance, 0));
  }
  const sockets = new Map<string, WebSocket>();
  let creates = 0, hangups = 0, closes = 0, respondToClose = false, rejectCreate = false, cancelBeforeProvider = false, seconds = 0, now = Date.now(), setupDelay = 0;
  let rejectionStatus = 502, malformedSuccess = false, dropCreate = false;
  const diagnostics: unknown[] = [];
  const lifecycle: DiagnosticRecord[] = [];
  const logger = new Diagnostics(record => { lifecycle.push(record); });
  const payloads: unknown[] = [];
  const server = createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer test-no-real-provider-key');
    if (request.url === '/v1/live/sessions') {
      creates++; const chunks = []; for await (const chunk of request) chunks.push(chunk);
      now += setupDelay;
      if (dropCreate) { request.socket.destroy(); return; }
      if (rejectCreate) { response.writeHead(rejectionStatus, { 'x-request-id': 'req_test_rejection' }); response.end('private-provider-error'); return; }
      if (malformedSuccess) { response.writeHead(200); response.end('private-malformed-success'); return; }
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
  const provider = new OpenAILiveProvider('test-no-real-provider-key', { testOrigin: `http://127.0.0.1:${address.port}`, timeoutMilliseconds: 300, diagnostics: logger });
  const helpers = helperBudget === undefined ? undefined : new HostedHelpers(db,
    { send: async () => { throw new Error('No helper network call expected.'); } }, {
      accountAllowlist: new Set([account]), aggregateFundingCapNano: cap, helperBudgetNanoPerMinute: helperBudget,
      publicMinuteAccess: paid, publicPaidAccess: paid,
      maxRequestsPerMinute: 6, maxSearchesPerSession: 0, maxConcurrentPerSession: 2, maxConcurrentGlobal: 4,
      postSessionMilliseconds: 120_000, inputFramingTokenAllowance: 4096, searchInputTokenAllowance: 1_050_000,
      timeoutMilliseconds: 1000,
    });
  const helperAdmission = { paidFundingPolicy: helpers?.paidFundingPolicy, closeCashBudget: helpers?.closeCashBudget.bind(helpers), async reserveSessionBudget(sql: any, owner: string, id: string) {
    await helpers?.reserveSessionBudget(sql, owner, id);
    if (cancelBeforeProvider) await sql.query("UPDATE hosted_sessions SET state='closing',close_requested_at=now() WHERE id=$1", [id]);
  } };
  let controller = new HostedVoice(db, provider, { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap,
    publicMinuteAccess: paid, publicPaidAccess: paid, diagnostics: logger, onStartupFailure: diagnostic => diagnostics.push(diagnostic),
    billingUnit: minuteAllowance === undefined ? 'nanoUSD' : 'milliseconds',
    helpers: helperAdmission, now: () => now, closeGraceMilliseconds: 20 });
  await controller.start();
  return { db, account, payloads, diagnostics, lifecycle, provider, get controller() { return controller; },
    get creates() { return creates; }, get hangups() { return hangups; }, get closes() { return closes; },
    set closeReplies(value: boolean) { respondToClose = value; }, set seconds(value: number) { seconds = value; },
    set rejectCreate(value: boolean) { rejectCreate = value; },
    set rejectionStatus(value: number) { rejectionStatus = value; },
    set malformedSuccess(value: boolean) { malformedSuccess = value; },
    set dropCreate(value: boolean) { dropCreate = value; },
    set cancelBeforeProvider(value: boolean) { cancelBeforeProvider = value; },
    set setupDelay(value: number) { setupDelay = value; }, get now() { return now; },
    advance(ms: number) { now += ms; },
    send(id: string, event: unknown) { sockets.get(id)!.send(JSON.stringify(event)); },
    disconnect(id: string) { sockets.get(id)!.terminate(); },
    async restart() { await controller.stop(); controller = new HostedVoice(db, provider,
      { accountAllowlist: new Set([account]), lifetimeFundingCapNano: cap,
        publicMinuteAccess: paid, publicPaidAccess: paid, diagnostics: logger, onStartupFailure: diagnostic => diagnostics.push(diagnostic),
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
integration('a cancelled context injection during close still drains trusted final usage', async () => {
  const f = await fixture(2_000_000_000n, 600_000);
  try {
    const live = await f.controller.create(f.account, 'closing-context-cancelled', 'v=0', 'fr-FR', undefined, 60_000);
    await f.controller.close(f.account, live.sessionID);
    f.send(live.providerSessionID, { type: 'error', error: { type: 'server_error', code: 'context_injection_incomplete' } });
    await new Promise(resolve => setTimeout(resolve, 25));
    assert.equal((await f.controller.status(f.account, live.sessionID)).state, 'closing');
    assert.equal((await f.minutes()).reserved_ms, '60000');
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 42 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '558000', reserved_ms: '0' });
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

for (const mode of ['legacy', 'minutes', 'paid'] as const) {
  integration(`explicit HTTP rejection releases ${mode} funding and permits a new attempt without reusing its key`, async () => {
    const f = await fixture(2_000_000_000n, mode === 'legacy' ? undefined : mode === 'paid' ? 0 : 600_000,
      mode === 'legacy' ? undefined : 50_000_000n, mode === 'paid');
    try {
      f.rejectCreate = true; f.rejectionStatus = 429;
      const before = mode === 'minutes' ? await f.minutes() : await f.wallet();
      await assert.rejects(f.controller.create(f.account, 'explicit-rejection-key', 'v=0', 'es-ES'), {
        code: 'provider_create_rejected', providerStatus: 429, requestID: 'req_test_rejection'
      });
      assert.deepEqual(mode === 'minutes' ? await f.minutes() : await f.wallet(), before);
      const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
      assert.equal(row.state, 'closed'); assert.equal(row.provider_rejection_status, 429);
      assert.equal(row.provider_rejection_request_id, 'req_test_rejection');
      assert.equal(row.provider_cost_nano, '0'); assert.equal(row.funding_exposure_nano, '0');
      assert.equal(row.provider_session_id, null);
      assert.equal(JSON.stringify(row).includes('private-provider-error'), false);
      assert.deepEqual(f.diagnostics, [{ category: 'http_rejected', providerStatus: 429, requestID: 'req_test_rejection' }]);
      if (mode !== 'legacy') {
        assert.ok(row.provider_attempted_at);
        const helper = (await f.db.query('SELECT * FROM hosted_helper_sessions')).rows[0];
        assert.equal(helper.post_close_budget_nano, '0'); assert.equal(helper.liability_nano, '0');
        assert.equal(helper.cash_pool_nano, '0');
      }
      await assert.rejects(f.db.query('UPDATE hosted_sessions SET provider_rejection_status=NULL WHERE id=$1', [row.id]));
      await assert.rejects(f.controller.create(f.account, 'explicit-rejection-key', 'v=0', 'es-ES'), { code: 'live_request_already_created' });
      assert.equal(f.creates, 1);
      await f.restart(); f.rejectCreate = false;
      const next = await f.controller.create(f.account, 'new-after-http-rejection', 'v=0', 'es-ES');
      assert.equal(f.creates, 2); assert.equal((await f.controller.status(f.account, next.sessionID)).state, 'active');
    } finally { await f.cleanup(); }
  });
}
for (const failure of ['timeout-status', 'server-status', 'transport', 'malformed-success'] as const) {
  integration(`${failure} remains uncertain and keeps minute and helper holds`, async () => {
    const f = await fixture(2_000_000_000n, 600_000, 50_000_000n);
    try {
      f.rejectCreate = ['timeout-status', 'server-status'].includes(failure);
      f.rejectionStatus = failure === 'timeout-status' ? 408 : 503;
      f.dropCreate = failure === 'transport'; f.malformedSuccess = failure === 'malformed-success';
      await assert.rejects(f.controller.create(f.account, 'uncertain-create-failure', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
      assert.equal((await f.minutes()).reserved_ms, '600000');
      const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
      assert.equal(row.state, 'incomplete'); assert.equal(row.provider_rejection_status, null);
      const helper = (await f.db.query('SELECT * FROM hosted_helper_sessions')).rows[0];
      assert.equal(helper.liability_nano, '500000000'); assert.equal(helper.post_close_budget_nano, null);
      assert.equal(JSON.stringify(f.diagnostics).includes('private-'), false);
      await f.restart();
      await assert.rejects(f.controller.create(f.account, 'blocked-new-offer', 'v=0', 'es-ES'), { code: 'live_session_unresolved' });
      assert.equal(f.creates, 1);
    } finally { await f.cleanup(); }
  });
}
integration('a settlement database failure rolls back all releases and logs only its category', async () => {
  const f = await fixture(2_000_000_000n, 600_000, 50_000_000n);
  try {
    await f.db.query(`CREATE FUNCTION reject_rejection_test() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN IF NEW.provider_rejection_status IS NOT NULL THEN RAISE EXCEPTION 'private-database-error'; END IF; RETURN NEW; END $$`);
    await f.db.query('CREATE TRIGGER reject_rejection_test BEFORE UPDATE ON hosted_sessions FOR EACH ROW EXECUTE FUNCTION reject_rejection_test()');
    f.rejectCreate = true; f.rejectionStatus = 400;
    await assert.rejects(f.controller.create(f.account, 'rejected-but-db-fails', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
    assert.deepEqual(await f.minutes(), { balance_ms: '600000', reserved_ms: '600000' });
    assert.equal((await f.db.query("SELECT count(*) AS total FROM minute_entries WHERE kind='settle'")).rows[0].total, '0');
    assert.equal((await f.db.query('SELECT state FROM minute_reservations')).rows[0].state, 'open');
    assert.equal((await f.db.query('SELECT liability_nano FROM hosted_helper_sessions')).rows[0].liability_nano, '500000000');
    assert.deepEqual(f.diagnostics.at(-1), { category: 'rejection_settlement_failed' });
    assert.equal(JSON.stringify(f.diagnostics).includes('private-'), false);
    await assert.rejects(f.controller.create(f.account, 'db-failure-retry', 'v=0', 'es-ES'), { code: 'live_session_unresolved' });
  } finally { await f.cleanup(); }
});
test('rejection diagnostics reject unsafe metadata and never include provider body text', () => {
  const error = new LiveCreateRejectedError(401, 'private value with spaces');
  assert.equal(error.requestID, undefined); assert.equal(error.message, 'provider_create_rejected');
  for (const status of [200, 408, 500, NaN]) assert.throws(() => new LiveCreateRejectedError(status));
  assert.equal(new LiveCreateFailure('http_uncertain', 502, 'req_safe').requestID, 'req_safe');
});

integration('an attach rejection after successful create keeps its confirmed provider hold', async () => {
  const f = await fixture(2_000_000_000n, 600_000, 50_000_000n);
  try {
    f.provider.attach = async () => { throw new LiveCreateRejectedError(403, 'req_attach_rejection'); };
    await assert.rejects(f.controller.create(f.account, 'attach-failure-offer', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
    const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
    assert.equal(row.provider_session_id, 'live_fake_1'); assert.equal(row.state, 'incomplete');
    assert.equal(row.provider_rejection_status, null); assert.equal((await f.minutes()).reserved_ms, '600000');
    assert.equal(f.hangups, 1);
    assert.deepEqual(f.diagnostics, [{ category: 'provider_attach_failed' }]);
  } finally { await f.cleanup(); }
});
integration('the real HTTP adapter recognizes explicit client rejection status without retaining its body', async () => {
  const f = await fixture();
  try {
    f.rejectCreate = true;
    for (const status of [400, 401, 403, 404, 409, 422, 429]) {
      f.rejectionStatus = status;
      await assert.rejects(f.provider.create('v=0', 'es-ES'), error => {
        assert.ok(error instanceof LiveCreateRejectedError); assert.equal(error.providerStatus, status);
        assert.equal(error.requestID, 'req_test_rejection');
        assert.equal(JSON.stringify(error).includes('private-provider-error'), false); return true;
      });
    }
  } finally { await f.cleanup(); }
});

integration('a late provider result cannot reopen a session closed by operator recovery', async () => {
  const f = await fixture(2_000_000_000n, 600_000);
  try {
    const create = f.provider.create.bind(f.provider);
    f.provider.create = async (...args) => {
      const result = await create(...args);
      // Simulate the operator's committed closure while this network result was delayed.
      await f.db.query("UPDATE hosted_sessions SET state='closed',charged_ms=0,close_reason='operator_funded_startup_recovery' WHERE account_id=$1", [f.account]);
      return result;
    };
    await assert.rejects(f.controller.create(f.account, 'late-provider-result', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
    const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
    assert.equal(row.state, 'closed'); assert.equal(row.close_reason, 'operator_funded_startup_recovery');
    assert.equal(row.provider_session_id, null); assert.equal(f.hangups, 1);
    assert.deepEqual(f.diagnostics, [{ category: 'persist_provider_session_failed' }]);
  } finally { await f.cleanup(); }
});

integration('signed-in International English uses free minutes and settles the conversation', async () => {
  const f = await fixture(2_000_000_000n, 600_000, 50_000_000n, true);
  try {
    f.closeReplies = true; f.seconds = 24;
    const cashBefore = await f.wallet();
    const live = await f.controller.create(f.account, 'member-english-free', 'v=0', 'en');
    assert.equal(live.fundingMode, 'minutes');
    assert.equal(f.creates, 1);
    assert.deepEqual(await f.minutes(), { balance_ms: '600000', reserved_ms: '600000' });
    await f.controller.close(f.account, live.sessionID);
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    assert.deepEqual(await f.minutes(), { balance_ms: '576000', reserved_ms: '0' });
    assert.deepEqual(await f.wallet(), cashBefore);
  } finally { await f.cleanup(); }
});

integration('voice lifecycle logging distinguishes requested closure from confirmed settlement', async () => {
  const f = await fixture();
  try {
    const live = await f.controller.create(f.account, 'logged-session', 'v=0', 'es-ES');
    await Promise.all(Array.from({ length: 4 }, () => f.controller.close(f.account, live.sessionID)));
    assert.ok(f.lifecycle.some(record => record.event === 'voice_active'));
    assert.equal(f.lifecycle.filter(record => record.event === 'voice_close_requested').length, 1);
    assert.equal(f.lifecycle.filter(record => record.event === 'voice_closed').length, 0);
    f.send(live.providerSessionID, { type: 'session.closed', usage: { seconds: 12 } });
    await until(async () => (await f.controller.status(f.account, live.sessionID)).state === 'closed');
    await until(() => f.lifecycle.some(record => record.event === 'voice_closed'));
    const records = f.lifecycle.filter(record => record.event.startsWith('voice_'));
    assert.ok(records.every(record => record.sessionReference === live.sessionID.replaceAll('-', '').slice(0, 12)));
    assert.doesNotMatch(JSON.stringify(records), new RegExp(`${f.account}|${live.providerSessionID}|${live.sessionID}|v=0`));
    assert.equal((await f.wallet()).reserved_nano, '0');
  } finally { await f.cleanup(); }
});

integration('close logging survives attach failure and concurrent requests without releasing the hold', async () => {
  const f = await fixture(2_000_000_000n, 600_000);
  try {
    f.provider.attach = async () => { throw new Error('connection unavailable'); };
    await assert.rejects(f.controller.create(f.account, 'logged-attach-failure', 'v=0', 'es-ES'), { code: 'provider_session_unconfirmed' });
    const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
    assert.equal(row.close_requested_at, null);
    await Promise.all(Array.from({ length: 4 }, () => f.controller.requestClose(row.id, 'worker_recovery')));
    await f.controller.requestClose(row.id, 'worker_recovery');
    const records = f.lifecycle.filter(record => record.event === 'voice_close_requested');
    assert.equal(records.length, 1);
    const record = records[0]; assert.ok(record);
    assert.equal(record.operation, 'voice.close.worker_recovery');
    assert.equal(record.sessionReference, row.id.replaceAll('-', '').slice(0, 12));
    const after = (await f.db.query('SELECT * FROM hosted_sessions WHERE id=$1', [row.id])).rows[0];
    assert.equal(after.state, 'incomplete');
    assert.equal(after.close_reason, 'create_or_attach_uncertain');
    assert.ok(after.close_requested_at);
    assert.equal((await f.minutes()).reserved_ms, '600000');
    assert.equal(f.lifecycle.filter(record => record.event === 'voice_closed').length, 0);
  } finally { await f.cleanup(); }
});

integration('missing and already closed sessions do not emit close-request diagnostics', async () => {
  const f = await fixture();
  try {
    await f.controller.requestClose(randomUUID(), 'worker_recovery');
    f.rejectCreate = true; f.rejectionStatus = 403;
    await assert.rejects(f.controller.create(f.account, 'logged-rejected-session', 'v=0', 'es-ES'));
    const row = (await f.db.query('SELECT * FROM hosted_sessions')).rows[0];
    assert.equal(row.state, 'closed');
    await f.controller.requestClose(row.id, 'worker_recovery');
    assert.equal(f.lifecycle.filter(record => record.event === 'voice_close_requested').length, 0);
    assert.equal((await f.wallet()).reserved_nano, '0');
  } finally { await f.cleanup(); }
});
