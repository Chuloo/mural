import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createApp, type Services } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { digest } from '../src/auth.js';
import { parseHostedHelperInput } from '../src/hosted-helpers.js';
import { HelperSessionLimitError, ServiceError } from '../src/errors.js';

test('hosted HTTP routes advertise no usable conversation path without both configured services', async () => {
  const db = { query: async () => { throw new Error('No database access expected.'); } } as unknown as Database;
  for (const hosted of [undefined, { available: true, minuteFunded: true } as Services['hosted']]) {
    const app = createApp({ db, auth: {}, hosted });
    try {
      const result = await app.inject('/v1/live/capabilities');
      assert.deepEqual(result.json(), { hostedMinutes: false });
      const helper = await app.inject({ method: 'POST', url: `/v1/live/sessions/${randomUUID()}/helpers`, payload: {} });
      assert.equal(helper.statusCode, 503);
    } finally { await app.close(); }
  }
});

test('JSON and streaming helpers share the network limit before authentication or provider admission', async () => {
  for (const trustedProxy of [false, true]) {
    const account = randomUUID(), session = randomUUID();
    let authentications = 0, helpers = 0;
    const db = { query: async () => { authentications++; return { rows: [{ account_id: account }] }; } } as unknown as Database;
    const proxyToken = randomBytes(32).toString('hex');
    const app = createApp({ db, auth: {},
      accounts: trustedProxy ? { admission: { config: { proxyToken, hmacKey: randomBytes(32).toString('hex') } } } as Services['accounts'] : undefined,
      hosted: { minuteFunded: true } as Services['hosted'],
      hostedHelpers: { request: async (_account: string, _session: string, _body: unknown, onText?: (text: string) => void) => {
        helpers++; onText?.('Hello.'); return { text: 'Hello.' };
      } } as unknown as Services['hostedHelpers'],
    });
    const headers = { authorization: `Bearer ${randomBytes(32).toString('base64url')}`,
      ...(trustedProxy ? { 'x-mural-proxy-token': proxyToken, 'x-mural-client-ip': '198.51.100.10' } : {}) };
    const request = (index: number) => ({ method: 'POST' as const,
      url: `/v1/live/sessions/${session}/${index % 2 ? '%68elpers' : 'helpers'}`,
      headers: { ...headers, accept: index % 2 ? 'text/event-stream' : 'application/json',
        'x-forwarded-for': `203.0.113.${index % 250 + 1}` }, payload: {} });
    try {
      for (let i = 0; i < 120; i++) assert.equal((await app.inject(request(i))).statusCode, 200);
      assert.equal(authentications, 120); assert.equal(helpers, 120);
      for (const i of [120, 121]) {
        const denied = await app.inject(request(i));
        assert.equal(denied.statusCode, 429);
        assert.deepEqual(denied.json(), { error: { code: 'rate_limit' } });
        assert.match(String(denied.headers['content-type']), /^application\/json/);
      }
      assert.equal(authentications, 120); assert.equal(helpers, 120);
      if (trustedProxy) {
        const forged = await app.inject({ ...request(122), headers: { ...headers, 'x-mural-proxy-token': 'wrong', 'x-mural-client-ip': '198.51.100.11' } });
        assert.equal(forged.statusCode, 503); assert.equal(authentications, 120);
        const otherNetwork = await app.inject({ ...request(123), headers: { ...headers, 'x-mural-client-ip': '198.51.100.11' } });
        assert.equal(otherNetwork.statusCode, 200); assert.equal(helpers, 121);
      }
    } finally { await app.close(); }
  }
});

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
test('hosted HTTP authenticates guest ownership, recovers uncertain sessions and bounds helper bodies', {
  skip: !databaseURL && 'Set TEST_DATABASE_URL.',
}, async () => {
  const schema = `hosted_http_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  const guest = randomUUID(), other = randomUUID(), sessionID = randomUUID();
  const token = randomBytes(32).toString('base64url'), otherToken = randomBytes(32).toString('base64url');
  for (const [account, bearer] of [[guest, token], [other, otherToken]]) {
    await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [account]);
    await db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')", [randomUUID(), account, digest(bearer!)]);
  }
  const status = { sessionID, state: 'incomplete', deadline: new Date().toISOString(), observedMilliseconds: 0,
    reservedMilliseconds: 600_000, chargedMilliseconds: null, billingBasis: 'connected-conversation-time', providerCostNanoUSD: null };
  const calls: string[] = [];
  let helperFailure: Error | undefined, failAfterPartial = false;
  const hosted = { available: true, minuteFunded: true, allows: (id: string) => id === guest,
    current: async (id: string) => { calls.push(`current:${id}`); return { session: id === guest ? status : null }; },
  } as unknown as Services['hosted'];
  const hostedHelpers = { allows: (id: string) => id === guest,
    request: async (id: string, session: string, raw: unknown, onText?: (text: string) => void) => {
      const body = parseHostedHelperInput(raw);
      if (id !== guest || session !== sessionID) throw new ServiceError('live_session_not_found', 404);
      if (helperFailure) throw helperFailure;
      calls.push(`helper:${id}`);
      onText?.('Good'); onText?.('Good morning.');
      if (failAfterPartial) throw new ServiceError('helper_response_uncertain', 502);
      return { requestID: body.requestID, text: 'Good morning.', sources: [], usage: { inputTokens: 2, cachedInputTokens: 0,
        cacheWriteTokens: 0, outputTokens: 2, searchCalls: 0 }, costNanoUSD: '2800', rateVersion: 'fixture' };
    },
  } as unknown as Services['hostedHelpers'];
  const app = createApp({ db, auth: {}, hosted, hostedHelpers });
  const headers = { authorization: `Bearer ${token}` }, otherHeaders = { authorization: `Bearer ${otherToken}` };
  const body = { requestID: randomUUID(), purpose: 'meaning', instructions: 'Translate into English.', input: 'Buenos días.' };
  try {
    assert.deepEqual((await app.inject({ url: '/v1/live/capabilities', headers })).json(), { hostedMinutes: true, experimental: true });
    assert.deepEqual((await app.inject({ url: '/v1/live/capabilities', headers: otherHeaders })).json(), { hostedMinutes: false, experimental: true });
    assert.equal((await app.inject('/v1/live/sessions/current')).statusCode, 401);
    assert.equal(calls.length, 0);
    const recovered = await app.inject({ url: '/v1/live/sessions/current', headers });
    assert.deepEqual(recovered.json(), { session: status }); assert.equal(recovered.headers['cache-control'], 'no-store');
    assert.deepEqual((await app.inject({ url: '/v1/live/sessions/current', headers: otherHeaders })).json(), { session: null });
    const endpoint = `/v1/live/sessions/${sessionID}/helpers`;
    assert.equal((await app.inject({ method: 'POST', url: endpoint, payload: body })).statusCode, 401);
    assert.equal((await app.inject({ method: 'POST', url: endpoint, headers: otherHeaders, payload: body })).statusCode, 404);
    assert.equal((await app.inject({ method: 'POST', url: endpoint, headers, payload: { ...body, model: 'override' } })).statusCode, 400);
    assert.equal((await app.inject({ method: 'POST', url: endpoint, headers, payload: { ...body, input: 'x'.repeat(65_536) } })).statusCode, 413);
    const translated = await app.inject({ method: 'POST', url: endpoint, headers, payload: body });
    assert.equal(translated.statusCode, 200); assert.equal(translated.json().text, 'Good morning.');
    assert.equal(calls.filter(call => call.startsWith('helper:')).length, 1);
    const streamHeaders = { ...headers, accept: 'text/event-stream' };
    const streamed = await app.inject({ method: 'POST', url: endpoint, headers: streamHeaders, payload: body });
    assert.equal(streamed.statusCode, 200); assert.match(String(streamed.headers['content-type']), /^text\/event-stream/);
    const events = streamed.body.trim().split('\n\n').map(line => JSON.parse(line.slice(6)));
    assert.deepEqual(events.slice(0, 2), [{ type: 'mural.meaning.delta', delta: 'Good' }, { type: 'mural.meaning.delta', delta: ' morning.' }]);
    assert.equal(events[2].type, 'mural.meaning.completed'); assert.equal(events[2].result.text, 'Good morning.');
    for (const accept of ['Text/Event-Stream', 'application/json, TEXT/EVENT-STREAM;Q=0.5', 'text/event-stream;q=1.000']) {
      const negotiated = await app.inject({ method: 'POST', url: endpoint, headers: { ...headers, accept }, payload: body });
      assert.equal(negotiated.statusCode, 200); assert.match(String(negotiated.headers['content-type']), /^text\/event-stream/);
      assert.match(negotiated.body, /mural.meaning.completed/);
    }
    for (const accept of ['text/event-stream;q=0', 'text/event-stream;Q=0.000, application/json', 'text/event-stream;q=2', 'text/event-stream;q=invalid', 'application/json', '*/*']) {
      const negotiated = await app.inject({ method: 'POST', url: endpoint, headers: { ...headers, accept }, payload: body });
      assert.equal(negotiated.statusCode, 200); assert.match(String(negotiated.headers['content-type']), /^application\/json/);
      assert.equal(negotiated.json().text, 'Good morning.');
    }
    failAfterPartial = true;
    const interrupted = await app.inject({ method: 'POST', url: endpoint, headers: streamHeaders, payload: body });
    assert.match(interrupted.body, /mural.meaning.error/); assert.doesNotMatch(interrupted.body, /mural.meaning.completed/);
    failAfterPartial = false;
    helperFailure = new HelperSessionLimitError(10_001);
    const streamDenied = await app.inject({ method: 'POST', url: endpoint, headers: streamHeaders, payload: body });
    assert.equal(streamDenied.statusCode, 429); assert.equal(streamDenied.json().error.retryable, true);
    const waiting = await app.inject({ method: 'POST', url: endpoint, headers, payload: body });
    assert.equal(waiting.statusCode, 429); assert.equal(waiting.headers['retry-after'], '11');
    assert.deepEqual(waiting.json(), { error: { code: 'helper_session_limit', retryable: true, retryAfterMilliseconds: 10_001 } });
    helperFailure = new HelperSessionLimitError();
    const exhausted = await app.inject({ method: 'POST', url: endpoint, headers, payload: body });
    assert.equal(exhausted.statusCode, 429); assert.equal(exhausted.headers['retry-after'], undefined);
    assert.deepEqual(exhausted.json(), { error: { code: 'helper_session_limit', retryable: false } });
    for (const [code, status] of [['helper_response_uncertain', 502], ['helper_request_already_attempted', 409],
      ['helper_concurrency_limit', 429], ['helper_budget_exhausted', 429], ['rate_limit', 429]] as const) {
      helperFailure = Object.assign(new ServiceError(code, status), { retryable: true, retryAfterMilliseconds: 1000 });
      const ineligible = await app.inject({ method: 'POST', url: endpoint, headers, payload: body });
      assert.equal(ineligible.statusCode, status); assert.equal(ineligible.headers['retry-after'], undefined);
      assert.deepEqual(ineligible.json(), { error: { code } });
    }
    await db.query('UPDATE auth_sessions SET revoked_at=now() WHERE account_id=$1', [guest]);
    assert.equal((await app.inject({ url: '/v1/live/sessions/current', headers })).statusCode, 401);
  } finally { await app.close(); await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); }
});
