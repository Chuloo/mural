import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { HelperSessionLimitError } from '../src/errors.js';
import { HostedHelpers, HOSTED_HELPER_MODEL, HOSTED_HELPER_RATE_VERSION, parseHostedHelperInput, hostedHelperBody,
  hostedHelperCost, hostedHelperExposure, type HostedHelperConfig, type HostedResponsesRequest, type HostedResponsesTransport } from '../src/hosted-helpers.js';

const schemaValue = { type: 'object', properties: { nextGoal: { type: 'string' } }, required: ['nextGoal'], additionalProperties: false };
const input = (extra: Record<string, unknown> = {}) => ({ requestID: randomUUID(), purpose: 'meaning', instructions: 'Private teaching instructions.', input: 'Private learner passage.', ...extra });
const config = (accounts: string[], extra: Partial<HostedHelperConfig> = {}): HostedHelperConfig => ({
  accountAllowlist: new Set(accounts), aggregateFundingCapNano: 10_000_000_000n, helperBudgetNanoPerMinute: 100_000_000n,
  maxRequestsPerMinute: 10, maxSearchesPerSession: 1, maxConcurrentPerSession: 2, maxConcurrentGlobal: 4,
  postSessionMilliseconds: 120_000, inputFramingTokenAllowance: 4096, searchInputTokenAllowance: 1_050_000,
  timeoutMilliseconds: 1000, ...extra
});
const response = (extra: Record<string, unknown> = {}) => ({ id: `resp_${randomUUID().replaceAll('-', '')}`,
  model: HOSTED_HELPER_MODEL, service_tier: 'default', status: 'completed',
  output: [{ type: 'message', content: [{ type: 'output_text', text: 'Private generated meaning.', annotations: [] }] }],
  usage: { input_tokens: 100, input_tokens_details: { cached_tokens: 20, cache_write_tokens: 30 }, output_tokens: 40 }, ...extra });
class FakeResponses implements HostedResponsesTransport {
  calls: HostedResponsesRequest[] = [];
  signals: AbortSignal[] = [];
  handler: (body: HostedResponsesRequest, signal: AbortSignal, onText?: (text: string) => void) => Promise<unknown> = async () => response();
  send(body: HostedResponsesRequest, signal: AbortSignal, onText?: (text: string) => void) { this.calls.push(body); this.signals.push(signal); return this.handler(body, signal, onText); }
}
const unused = {} as Database;

test('helper input allows only bounded transient teaching fields and the Android schema subset', () => {
  const parsed = parseHostedHelperInput(input({ purpose: 'assessment', schema: schemaValue }));
  assert.equal(parsed.schema?.type, 'object');
  for (const invalid of [null, [], input({ model: 'different' }), input({ apiKey: 'secret' }), input({ accountID: randomUUID() }),
    input({ requestID: '../path' }), input({ purpose: 'arbitrary' }), input({ search: true }), input({ purpose: 'assessment' }),
    input({ schema: schemaValue }), input({ purpose: 'assessment', schema: { ...schemaValue, $ref: 'https://untrusted.test' } }),
    input({ purpose: 'assessment', schema: { ...schemaValue, additionalProperties: true } }), input({ purpose: 'assessment', schema: { ...schemaValue, required: [] } }),
    input({ instructions: 'x'.repeat(16_385) }), input({ input: '🙂'.repeat(7000) }), input({ input: '\uD800' }), input({ input: 'text\u0000' })])
    assert.throws(() => parseHostedHelperInput(invalid), { code: 'invalid_hosted_helper_request' });
  const mutableSchema = structuredClone(schemaValue);
  const source = input({ purpose: 'assessment', schema: mutableSchema });
  const snapshot = parseHostedHelperInput(source);
  mutableSchema.properties.nextGoal.type = 'integer';
  assert.equal(((snapshot.schema?.properties as any).nextGoal as any).type, 'string');
});

test('helper body fixes provider, persistence, tier, output and tools independently of the caller', () => {
  const simple = hostedHelperBody(parseHostedHelperInput(input()));
  assert.equal(simple.model, HOSTED_HELPER_MODEL); assert.equal(simple.store, false); assert.equal(simple.background, false);
  assert.equal(simple.stream, false); assert.equal(simple.service_tier, 'default'); assert.equal(simple.max_output_tokens, 1400);
  assert.equal(simple.tools, undefined); assert.equal(simple.tool_choice, 'none'); assert.deepEqual(simple.prompt_cache_options, { mode: 'explicit' });
  const assessment = hostedHelperBody(parseHostedHelperInput(input({ purpose: 'assessment', schema: schemaValue })));
  assert.equal(assessment.max_output_tokens, 2200); assert.equal(assessment.text?.format.strict, true);
  const search = hostedHelperBody(parseHostedHelperInput(input({ purpose: 'topic', search: true })));
  assert.deepEqual(search.tools, [{ type: 'web_search', search_context_size: 'low' }]); assert.equal(search.max_tool_calls, 1);
});

test('helper pricing handles cache reads, cache writes and the long-context threshold', () => {
  assert.equal(hostedHelperCost({ inputTokens: 100, cachedInputTokens: 20, cacheWriteTokens: 30, outputTokens: 40, searchCalls: 1 }), 10_065_900n);
  assert.equal(hostedHelperCost({ inputTokens: 272_000, cachedInputTokens: 0, cacheWriteTokens: 0, outputTokens: 10, searchCalls: 0 }), 54_412_000n);
  assert.equal(hostedHelperCost({ inputTokens: 272_001, cachedInputTokens: 0, cacheWriteTokens: 0, outputTokens: 10, searchCalls: 0 }), 108_818_400n);
  for (const usage of [{ inputTokens: -1, cachedInputTokens: 0, cacheWriteTokens: 0, outputTokens: 1, searchCalls: 0 },
    { inputTokens: 10, cachedInputTokens: 8, cacheWriteTokens: 8, outputTokens: 1, searchCalls: 0 }])
    assert.throws(() => hostedHelperCost(usage), { code: 'helper_usage_invalid' });
});

test('helper configuration has no implicit launch budget and fails closed outside the allowlist', async () => {
  const account = randomUUID(), transport = new FakeResponses();
  for (const extra of [{ helperBudgetNanoPerMinute: 0n }, { aggregateFundingCapNano: 0n }, { maxRequestsPerMinute: 61 },
    { searchInputTokenAllowance: 999 }, { postSessionMilliseconds: 120_001 }, { maxConcurrentGlobal: 0 }, { timeoutMilliseconds: 0 }])
    assert.throws(() => new HostedHelpers(unused, transport, config([account], extra)), { code: 'invalid_hosted_helper_configuration' });
  await assert.rejects(new HostedHelpers(unused, transport, config([account])).request(randomUUID(), randomUUID(), input()), { code: 'hosted_helpers_not_ready' });
  assert.equal(transport.calls.length, 0);
});

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const schema = `helpers_${randomUUID().replaceAll('-', '')}`, url = databaseURL ? new URL(databaseURL) : null;
url?.searchParams.set('options', `-c search_path=${schema}`);
const db = url ? connectDatabase(url.toString()) : null;
before(async () => { if (db) { await db.query(`CREATE SCHEMA ${schema}`); await migrate(db); } });
beforeEach(async () => { if (db) await db.query('TRUNCATE accounts CASCADE'); });
after(async () => { if (db) { try { await db.query(`DROP SCHEMA ${schema} CASCADE`); } finally { await db.end(); } } });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL.' }, fn);
async function seed(reservedMilliseconds = 600_000, earnedTime = false) {
  const account = randomUUID(), sessionID = randomUUID(), reservationID = randomUUID();
  await db!.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [account]);
  await transaction(db!, async sql => {
    await appendMinuteEntry(sql, account, `seed:${account}`, 'gift', reservedMilliseconds, 0);
    await appendMinuteEntry(sql, account, `reserve:${account}`, 'reserve', 0, reservedMilliseconds);
    await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,$3,$4)', [reservationID, account, randomUUID(), reservedMilliseconds]);
    await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,rate_version,state,deadline,funding_exposure_nano,minimum_charge_ms)
      VALUES($1,$2,$3,$4,$5,'test','active',now()+interval '10 minutes',500000000,$6)`, [sessionID, account, randomUUID(), reservationID, reservedMilliseconds, earnedTime ? 15_000 : 0]);
  });
  const transport = new FakeResponses();
  return { account, sessionID, reservationID, transport, controller: (extra: Partial<HostedHelperConfig> = {}) => new HostedHelpers(db!, transport, config([account], extra)) };
}
async function waitFor(check: () => boolean | Promise<boolean>) {
  const until = Date.now() + 2000;
  while (!(await check())) { if (Date.now() > until) throw new Error('Test condition timed out.'); await new Promise(resolve => setTimeout(resolve, 5)); }
}

integration('streamed meaning reserves before deltas and settles once after terminal usage', async () => {
  const f = await seed(), request = input(), seen: string[] = [];
  f.transport.handler = async (body, _signal, onText) => {
    assert.equal(body.stream, true);
    assert.equal((await db!.query('SELECT state FROM hosted_helper_requests')).rows[0].state, 'pending');
    onText?.('Private'); onText?.('Private generated meaning.');
    return response();
  };
  const result = await f.controller().request(f.account, f.sessionID, request, text => seen.push(text));
  assert.deepEqual(seen, ['Private', 'Private generated meaning.']);
  assert.equal(result.costNanoUSD, '65900');
  assert.deepEqual((await db!.query('SELECT state,cost_nano FROM hosted_helper_requests')).rows, [{ state: 'settled', cost_nano: '65900' }]);
  await assert.rejects(f.controller().request(f.account, f.sessionID, request, () => {}), { code: 'helper_request_already_attempted' });
  assert.equal(f.transport.calls.length, 1);
});
integration('lost stream completion is uncertain and cannot be automatically charged again', async () => {
  const f = await seed(), request = input();
  f.transport.handler = async (_body, _signal, onText) => { onText?.('Partial'); throw new Error('disconnected'); };
  await assert.rejects(f.controller().request(f.account, f.sessionID, request, () => {}), { code: 'helper_response_uncertain' });
  await assert.rejects(f.controller().request(f.account, f.sessionID, request, () => {}), { code: 'helper_request_already_attempted' });
  assert.equal(f.transport.calls.length, 1);
  assert.equal((await db!.query('SELECT state FROM hosted_helper_requests')).rows[0].state, 'uncertain');
});

integration('helper funding and one-shot attempt are committed before the provider call, with no content stored', async () => {
  const f = await seed();
  f.transport.handler = async () => {
    assert.equal((await db!.query("SELECT state FROM hosted_helper_requests")).rows[0].state, 'pending');
    assert.equal(await hostedHelperExposure(db!), 1_000_000_000n);
    return response();
  };
  const result = await f.controller().request(f.account, f.sessionID, input());
  assert.equal(result.text, 'Private generated meaning.'); assert.equal(result.costNanoUSD, '65900');
  assert.equal(result.rateVersion, HOSTED_HELPER_RATE_VERSION); assert.equal(f.transport.calls.length, 1);
  const records = JSON.stringify((await db!.query(`SELECT row_to_json(h) AS row FROM hosted_helper_requests h
    UNION ALL SELECT row_to_json(s) FROM hosted_helper_sessions s`)).rows);
  for (const marker of ['Private', 'instructions', 'schema', 'transcript']) assert.ok(!records.includes(marker));
  assert.equal((await db!.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1', [f.account])).rows[0].reserved_ms, '600000');
  assert.equal((await db!.query('SELECT state,cost_nano FROM hosted_helper_requests')).rows[0].state, 'settled');
});

integration('ownership, deleted accounts and a reversed minute reservation cannot authorize a provider call', async () => {
  const f = await seed(), other = await seed();
  const gateway = new HostedHelpers(db!, f.transport, config([f.account, other.account]));
  await assert.rejects(gateway.request(other.account, f.sessionID, input()), { code: 'live_session_not_found' });
  await db!.query("UPDATE minute_reservations SET state='released' WHERE id=$1", [f.reservationID]);
  await assert.rejects(gateway.request(f.account, f.sessionID, input()), { code: 'helper_session_funding_unavailable' });
  await db!.query('UPDATE accounts SET deleted_at=now() WHERE id=$1', [other.account]);
  await assert.rejects(gateway.request(other.account, other.sessionID, input()), { code: 'live_session_not_found' });
  assert.equal(f.transport.calls.length, 0);
});

integration('dollar-funded voice sessions cannot use minute-funded helpers', async () => {
  const account = randomUUID(), sessionID = randomUUID(), reservationID = randomUUID(), transport = new FakeResponses();
  await db!.query('INSERT INTO accounts(id) VALUES($1)', [account]);
  await db!.query("INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version) VALUES($1,$2,'test',500000000,'test')", [reservationID, account]);
  await db!.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,reservation_id,rate_version,state,deadline,funding_exposure_nano)
    VALUES($1,$2,'test',$3,'test','active',now()+interval '10 minutes',500000000)`, [sessionID, account, reservationID]);
  await assert.rejects(new HostedHelpers(db!, transport, config([account])).request(account, sessionID, input()), { code: 'helper_minute_session_required' });
  assert.equal(transport.calls.length, 0);
});

integration('duplicate IDs across concurrent controllers can never create a second provider charge', async () => {
  const f = await seed(), request = input();
  const outcomes = await Promise.allSettled([f.controller().request(f.account, f.sessionID, request), f.controller().request(f.account, f.sessionID, request)]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(f.transport.calls.length, 1);
  await assert.rejects(f.controller().request(f.account, f.sessionID, { ...request, input: 'Changed text.' }), { code: 'helper_request_already_attempted' });
  assert.equal((await db!.query('SELECT count(*) AS total FROM hosted_helper_requests')).rows[0].total, '1');
});

integration('session helper budget and limits are immutable when later configuration changes', async () => {
  const f = await seed(60_000);
  await f.controller({ maxRequestsPerMinute: 1, helperBudgetNanoPerMinute: 20_000_000n }).request(f.account, f.sessionID, input());
  await assert.rejects(f.controller({ maxRequestsPerMinute: 60, helperBudgetNanoPerMinute: 1_000_000_000n }).request(f.account, f.sessionID, input()), { code: 'helper_session_limit' });
  const row = (await db!.query('SELECT budget_nano,request_limit FROM hosted_helper_sessions')).rows[0];
  assert.deepEqual(row, { budget_nano: '20000000', request_limit: 1 });
  await assert.rejects(db!.query('UPDATE hosted_helper_sessions SET budget_nano=budget_nano+1,liability_nano=liability_nano+1'), /immutable/);
  await assert.rejects(db!.query('UPDATE hosted_helper_requests SET hold_nano=hold_nano+1'), /immutable/);
});

integration('voice admission reserves a fixed helper budget before provider setup and adopts the actual activation deadline once', async () => {
  const f = await seed();
  await db!.query("UPDATE hosted_sessions SET state='creating',deadline=now()+interval '30 seconds' WHERE id=$1", [f.sessionID]);
  const gateway = f.controller({ maxRequestsPerMinute: 1, helperBudgetNanoPerMinute: 20_000_000n });
  await transaction(db!, sql => gateway.reserveSessionBudget(sql, f.account, f.sessionID));
  const reserved = (await db!.query('SELECT * FROM hosted_helper_sessions')).rows[0];
  assert.equal(reserved.activation_pending, true); assert.equal(reserved.budget_nano, '200000000'); assert.equal(f.transport.calls.length, 0);
  assert.equal(gateway.available, true); assert.equal(gateway.allows(f.account), true); assert.equal(gateway.allows(randomUUID()), false);
  await db!.query("UPDATE hosted_sessions SET state='active',deadline=now()+interval '10 minutes' WHERE id=$1", [f.sessionID]);
  const activated = (await db!.query('SELECT b.*,h.deadline FROM hosted_helper_sessions b JOIN hosted_sessions h ON h.id=b.session_id')).rows[0];
  assert.equal(activated.activation_pending, false);
  assert.equal(activated.expires_at.getTime(), activated.deadline.getTime() + 120_000);
  assert.ok(activated.expires_at.getTime() - reserved.expires_at.getTime() > 569_000);
  assert.equal(activated.budget_nano, reserved.budget_nano); assert.equal(activated.request_limit, reserved.request_limit);
  await transaction(db!, sql => f.controller({ helperBudgetNanoPerMinute: 1_000_000_000n }).reserveSessionBudget(sql, f.account, f.sessionID));
  await f.controller().request(f.account, f.sessionID, input());
  assert.equal((await db!.query('SELECT budget_nano FROM hosted_helper_sessions')).rows[0].budget_nano, reserved.budget_nano);
  await assert.rejects(db!.query("UPDATE hosted_helper_sessions SET expires_at=expires_at+interval '1 hour'"), /immutable/);
  await assert.rejects(db!.query('UPDATE hosted_helper_sessions SET activation_pending=true'), /immutable/);
  await db!.query("UPDATE hosted_sessions SET deadline=deadline+interval '1 hour' WHERE id=$1", [f.sessionID]);
  assert.equal((await db!.query('SELECT expires_at FROM hosted_helper_sessions')).rows[0].expires_at.getTime(), activated.expires_at.getTime());
});

integration('a helper reservation participates in the caller transaction rollback', async () => {
  const f = await seed(), gateway = f.controller();
  await assert.rejects(transaction(db!, async sql => {
    await gateway.reserveSessionBudget(sql, f.account, f.sessionID);
    throw new Error('Voice admission cancelled.');
  }), /Voice admission cancelled/);
  assert.equal(await hostedHelperExposure(db!), 0n); assert.equal(f.transport.calls.length, 0);
});

integration('concurrent session budgets share the same aggregate funding cap atomically', async () => {
  const one = await seed(), two = await seed(), transport = new FakeResponses();
  const gateway = new HostedHelpers(db!, transport, config([one.account, two.account], { aggregateFundingCapNano: 2_000_000_000n }));
  const outcomes = await Promise.allSettled([gateway.request(one.account, one.sessionID, input()), gateway.request(two.account, two.sessionID, input())]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1); assert.equal(transport.calls.length, 1);
  assert.equal(await hostedHelperExposure(db!), 1_000_000_000n);
  assert.equal((outcomes.find(result => result.status === 'rejected') as PromiseRejectedResult).reason.code, 'hosted_funding_cap_reached');
});

integration('concurrency and search counts are persistent while actual settled cost releases per-request room', async () => {
  const f = await seed(); let finish: (value: unknown) => void = () => {};
  f.transport.handler = () => new Promise(resolve => { finish = resolve; });
  const gateway = f.controller({ maxConcurrentPerSession: 1 }), pending = gateway.request(f.account, f.sessionID, input());
  await waitFor(() => f.transport.calls.length === 1);
  await assert.rejects(f.controller().request(f.account, f.sessionID, input()), { code: 'helper_concurrency_limit' });
  finish(response()); await pending;
  f.transport.handler = async () => response({ output: [{ type: 'web_search_call' }, { type: 'message', content: [{ type: 'output_text', text: 'Current topic.', annotations: [] }] }] });
  const result = await gateway.request(f.account, f.sessionID, input({ purpose: 'topic', search: true }));
  assert.equal(result.usage.searchCalls, 1);
  await assert.rejects(gateway.request(f.account, f.sessionID, input({ purpose: 'delegation', search: true })), { code: 'helper_session_limit' });
});

integration('small helper allowance cannot make an unfunded search request', async () => {
  const f = await seed(60_000);
  await assert.rejects(f.controller({ helperBudgetNanoPerMinute: 10_000_000n }).request(f.account, f.sessionID, input({ purpose: 'topic', search: true })), { code: 'helper_budget_exhausted' });
  assert.equal(f.transport.calls.length, 0); assert.equal(await hostedHelperExposure(db!), 0n);
});

integration('global concurrency is enforced across distinct owners and controller instances', async () => {
  const one = await seed(), two = await seed(), transport = new FakeResponses(); let finish: (value: unknown) => void = () => {};
  transport.handler = () => new Promise(resolve => { finish = resolve; });
  const settings = config([one.account, two.account], { maxConcurrentGlobal: 1 });
  const first = new HostedHelpers(db!, transport, settings).request(one.account, one.sessionID, input());
  await waitFor(() => transport.calls.length === 1);
  await assert.rejects(new HostedHelpers(db!, transport, settings).request(two.account, two.sessionID, input()), { code: 'helper_concurrency_limit' });
  finish(response()); await first;
  transport.handler = async () => response();
  await new HostedHelpers(db!, transport, settings).request(two.account, two.sessionID, input());
  assert.equal(transport.calls.length, 2);
});

integration('uncertain attempts are never retried and retain funding after their active concurrency deadline', async () => {
  const f = await seed(); f.transport.handler = async () => { throw new Error('Private provider response and credentials.'); };
  const gateway = f.controller({ maxConcurrentPerSession: 1, maxConcurrentGlobal: 1 }), request = input();
  await assert.rejects(gateway.request(f.account, f.sessionID, request), { code: 'helper_response_uncertain', message: 'helper_response_uncertain' });
  await assert.rejects(gateway.request(f.account, f.sessionID, request), { code: 'helper_request_already_attempted' });
  await assert.rejects(gateway.request(f.account, f.sessionID, input()), { code: 'helper_concurrency_limit' });
  const previous = (await db!.query('SELECT hold_nano,state FROM hosted_helper_requests')).rows[0];
  assert.equal(previous.state, 'uncertain');
  // Advance only the test database clock boundary; the original hold remains immutable.
  await db!.query('ALTER TABLE hosted_helper_requests DISABLE TRIGGER hosted_helper_attempt_immutable');
  await db!.query("UPDATE hosted_helper_requests SET created_at=now()-interval '10 seconds',active_until=now()-interval '1 second'");
  await db!.query('ALTER TABLE hosted_helper_requests ENABLE TRIGGER hosted_helper_attempt_immutable');
  f.transport.handler = async () => response();
  await gateway.request(f.account, f.sessionID, input());
  assert.equal(f.transport.calls.length, 2);
  assert.deepEqual((await db!.query("SELECT hold_nano,state FROM hosted_helper_requests WHERE request_id=$1", [request.requestID])).rows[0], previous);
});

integration('provider timeout aborts once and leaves a durable uncertain hold', async () => {
  const f = await seed(); f.transport.handler = () => new Promise(() => {});
  await assert.rejects(f.controller({ timeoutMilliseconds: 50 }).request(f.account, f.sessionID, input()), { code: 'helper_response_uncertain' });
  assert.equal(f.transport.calls.length, 1); assert.equal(f.transport.signals[0]?.aborted, true);
  assert.equal((await db!.query('SELECT state FROM hosted_helper_requests')).rows[0].state, 'uncertain');
});

integration('malformed provider usage cannot release funding; known refusals and incomplete outputs settle only trusted usage', async () => {
  const f = await seed(); f.transport.handler = async () => response({ usage: null });
  await assert.rejects(f.controller().request(f.account, f.sessionID, input()), { code: 'helper_response_uncertain' });
  assert.equal((await db!.query('SELECT cost_nano FROM hosted_helper_requests')).rows[0].cost_nano, null);
  const other = await seed();
  for (const [payload, code] of [[response({ status: 'incomplete' }), 'helper_output_incomplete'],
    [response({ output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'Private refusal.' }] }] }), 'helper_output_refused']] as const) {
    other.transport.handler = async () => payload;
    await assert.rejects(other.controller().request(other.account, other.sessionID, input()), { code });
  }
  assert.equal((await db!.query("SELECT count(*) AS total FROM hosted_helper_requests WHERE state='settled'")).rows[0].total, '2');
});

integration('provider limit violations record the actual liability and stop new helper admissions', async () => {
  const f = await seed();
  f.transport.handler = async () => response({ usage: { input_tokens: 3_000_000,
    input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 }, output_tokens: 3000 } });
  await assert.rejects(f.controller().request(f.account, f.sessionID, input()), { code: 'helper_provider_limit_exceeded' });
  assert.ok(await hostedHelperExposure(db!) > 1_000_000_000n);
  await assert.rejects(f.controller().request(f.account, f.sessionID, input()), { code: 'helper_provider_reconciliation_required' });
  assert.equal(f.transport.calls.length, 1);
});

integration('meaning, lookup and final assessment work briefly after close; new conversation work does not', async () => {
  const f = await seed();
  await db!.query("UPDATE hosted_sessions SET state='closed' WHERE id=$1", [f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=1000 WHERE id=$1", [f.reservationID]);
  for (const purpose of ['meaning', 'lookup', 'assessment']) {
    await f.controller().request(f.account, f.sessionID, input({ purpose, ...(purpose === 'assessment' ? { schema: schemaValue } : {}) }));
  }
  for (const purpose of ['topic', 'typed_reply', 'help', 'delegation'])
    await assert.rejects(f.controller().request(f.account, f.sessionID, input({ purpose })), { code: 'helper_session_window_closed' });
  const row = (await db!.query('SELECT helper_closed_at FROM hosted_sessions')).rows[0];
  assert.ok(row.helper_closed_at instanceof Date);
});

integration('post-conversation access ends at its original window even when a new gateway raises the setting', async () => {
  const f = await seed(), gateway = f.controller({ postSessionMilliseconds: 50 });
  await gateway.request(f.account, f.sessionID, input());
  await db!.query("UPDATE hosted_sessions SET state='closed' WHERE id=$1", [f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=1000 WHERE id=$1", [f.reservationID]);
  await new Promise(resolve => setTimeout(resolve, 75));
  await assert.rejects(f.controller({ postSessionMilliseconds: 120_000 }).request(f.account, f.sessionID, input()), { code: 'helper_session_window_closed' });
  assert.equal(f.transport.calls.length, 1);
});

integration('expiry frees unused budget but keeps settled cost and unresolved holds', async () => {
  const f = await seed(), gateway = f.controller({ postSessionMilliseconds: 0 });
  await gateway.request(f.account, f.sessionID, input());
  f.transport.handler = async () => { throw new Error('Unknown bill'); };
  await assert.rejects(gateway.request(f.account, f.sessionID, input()), { code: 'helper_response_uncertain' });
  const expected = BigInt((await db!.query("SELECT sum(CASE WHEN state='settled' THEN cost_nano ELSE hold_nano END) AS total FROM hosted_helper_requests")).rows[0].total);
  await db!.query("UPDATE hosted_sessions SET state='closed' WHERE id=$1", [f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=1000 WHERE id=$1", [f.reservationID]);
  assert.equal(await gateway.expireBudgets(), 1); assert.equal(await gateway.expireBudgets(), 0);
  assert.equal(await hostedHelperExposure(db!), expected);
  await assert.rejects(gateway.request(f.account, f.sessionID, input()), { code: 'helper_session_window_closed' });
});

integration('helper runtime privileges allow settlement but forbid rewriting budgets, attempts or entitlements', async () => {
  const f = await seed(600_000, true), role = `helper_runtime_${randomUUID().replaceAll('-', '')}`;
  await db!.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${schema} TO ${role}`);
  const grants = await readFile(new URL('../operations/hosted-helper-runtime-grants.sql', import.meta.url), 'utf8');
  await db!.query(grants.replaceAll('mural_runtime', role));
  // SELECT FOR UPDATE on an owned voice row requires an UPDATE privilege, already held by the voice runtime.
  await db!.query(`GRANT UPDATE(state,deadline,charged_ms) ON hosted_sessions TO ${role}`);
  const runtimeURL = new URL(databaseURL!); runtimeURL.searchParams.set('options', `-c search_path=${schema} -c role=${role}`);
  const runtime = connectDatabase(runtimeURL.toString());
  try {
    const gateway = new HostedHelpers(runtime, f.transport, config([f.account]));
    await runtime.query("UPDATE hosted_sessions SET state='creating',deadline=now()+interval '30 seconds' WHERE id=$1", [f.sessionID]);
    await transaction(runtime, sql => gateway.reserveSessionBudget(sql, f.account, f.sessionID));
    await runtime.query("UPDATE hosted_sessions SET state='active',deadline=now()+interval '10 minutes' WHERE id=$1", [f.sessionID]);
    assert.equal((await runtime.query('SELECT activation_pending FROM hosted_helper_sessions')).rows[0].activation_pending, false);
    await gateway.request(f.account, f.sessionID, input());
    await runtime.query("UPDATE hosted_sessions SET state='closed',charged_ms=15000 WHERE id=$1", [f.sessionID]);
    assert.equal((await runtime.query('SELECT post_close_budget_nano,liability_nano FROM hosted_helper_sessions')).rows[0].liability_nano,'25000000');
    for (const statement of ['DELETE FROM hosted_helper_requests', 'TRUNCATE hosted_helper_requests',
      'UPDATE hosted_helper_requests SET hold_nano=1', 'UPDATE hosted_helper_sessions SET budget_nano=1',
      "UPDATE hosted_helper_sessions SET expires_at=now()+interval '1 hour'", 'UPDATE hosted_helper_sessions SET activation_pending=true',
      'UPDATE hosted_helper_sessions SET earned_time=false', 'UPDATE hosted_helper_sessions SET post_close_budget_nano=999999999',
      'UPDATE hosted_helper_sessions SET requests_per_minute=60',
      'UPDATE minute_reservations SET amount_ms=999999']) await assert.rejects(runtime.query(statement), /permission denied/);
  } finally { await runtime.end(); await db!.query(`DROP OWNED BY ${role}; DROP ROLE ${role}`); }
});

integration('earned helpers cannot front-load a ten-minute reservation and grow only from authoritative voice time', async () => {
  const f = await seed(600_000,true), gateway=f.controller({helperBudgetNanoPerMinute:50_000_000n});
  await transaction(db!,sql=>gateway.reserveSessionBudget(sql,f.account,f.sessionID));
  const large=input({instructions:'a'.repeat(16_384),input:'b'.repeat(24_576)});
  await assert.rejects(gateway.request(f.account,f.sessionID,large),{code:'helper_budget_exhausted'});
  assert.equal(f.transport.calls.length,0);
  assert.equal(await hostedHelperExposure(db!),500_000_000n);
  await db!.query('UPDATE hosted_sessions SET observed_ms=30000 WHERE id=$1',[f.sessionID]);
  await gateway.request(f.account,f.sessionID,large);
  assert.equal(f.transport.calls.length,1);
  await db!.query("UPDATE hosted_sessions SET state='closed',charged_ms=30000 WHERE id=$1",[f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=30000 WHERE id=$1",[f.reservationID]);
  assert.equal(await hostedHelperExposure(db!),25_000_000n);
  await f.controller({helperBudgetNanoPerMinute:1_000_000_000n}).request(f.account,f.sessionID,input());
  assert.equal(await hostedHelperExposure(db!),25_000_000n);
  await assert.rejects(db!.query('UPDATE hosted_helper_sessions SET post_close_budget_nano=budget_nano'),/immutable/);
});

integration('earned request limits persist across gateways and do not borrow unconsumed voice time', async () => {
  const f=await seed(600_000,true), gateway=f.controller({maxRequestsPerMinute:6,helperBudgetNanoPerMinute:50_000_000n});
  await gateway.request(f.account,f.sessionID,input());
  await gateway.request(f.account,f.sessionID,input());
  await assert.rejects(f.controller({maxRequestsPerMinute:60}).request(f.account,f.sessionID,input()),{code:'helper_session_limit'});
  await db!.query('UPDATE hosted_sessions SET observed_ms=30000 WHERE id=$1',[f.sessionID]);
  await gateway.request(f.account,f.sessionID,input());
  assert.equal(f.transport.calls.length,3);
});

integration('earned request retry metadata uses the persisted policy and never records a rejected attempt', async () => {
  const f = await seed(600_000, true), gateway = f.controller({ maxRequestsPerMinute: 6, helperBudgetNanoPerMinute: 50_000_000n });
  await gateway.request(f.account, f.sessionID, input({ purpose: 'typed_reply' }));
  await gateway.request(f.account, f.sessionID, input());
  await db!.query('UPDATE hosted_sessions SET observed_ms=6000 WHERE id=$1', [f.sessionID]);
  const request = input(), before = (await db!.query('SELECT liability_nano FROM hosted_helper_sessions')).rows[0];
  const rejects = { code: 'helper_session_limit', status: 429, retryable: true, retryAfterMilliseconds: 15_001 };
  await assert.rejects(f.controller({ maxRequestsPerMinute: 24 }).request(f.account, f.sessionID, request), rejects);
  await assert.rejects(gateway.request(f.account, f.sessionID, request), rejects);
  assert.equal(f.transport.calls.length, 2);
  assert.equal((await db!.query('SELECT count(*) AS total FROM hosted_helper_requests')).rows[0].total, '2');
  assert.deepEqual((await db!.query('SELECT liability_nano FROM hosted_helper_sessions')).rows[0], before);
  await db!.query('UPDATE hosted_sessions SET observed_ms=20001 WHERE id=$1', [f.sessionID]);
  await gateway.request(f.account, f.sessionID, request);
  await assert.rejects(gateway.request(f.account, f.sessionID, request), { code: 'helper_request_already_attempted' });
  assert.equal(f.transport.calls.length, 3);
});

integration('twenty-four shared requests per minute retain the same earned dollar budget', async () => {
  const f = await seed(600_000, true), gateway = f.controller({ maxRequestsPerMinute: 24, helperBudgetNanoPerMinute: 50_000_000n });
  await db!.query('UPDATE hosted_sessions SET observed_ms=6000 WHERE id=$1', [f.sessionID]);
  for (let i = 0; i < 6; i++) await gateway.request(f.account, f.sessionID, input({ purpose: i % 2 ? 'typed_reply' : 'meaning' }));
  await assert.rejects(gateway.request(f.account, f.sessionID, input()),
    { code: 'helper_session_limit', retryable: true, retryAfterMilliseconds: 10_001 });
  const policy = (await db!.query('SELECT per_minute_nano,budget_nano,requests_per_minute,request_limit,concurrency_limit FROM hosted_helper_sessions')).rows[0];
  assert.deepEqual(policy, { per_minute_nano: '50000000', budget_nano: '500000000', requests_per_minute: 24, request_limit: 240, concurrency_limit: 2 });
  await db!.query('UPDATE hosted_sessions SET observed_ms=15001 WHERE id=$1', [f.sessionID]);
  const large = input({ instructions: 'a'.repeat(16_384), input: 'b'.repeat(24_576) });
  await assert.rejects(gateway.request(f.account, f.sessionID, large), { code: 'helper_budget_exhausted' });
  assert.equal(f.transport.calls.length, 6);
});

integration('confirmed close makes an exhausted earned request allowance non-retryable', async () => {
  const f = await seed(600_000, true), gateway = f.controller({ maxRequestsPerMinute: 6 });
  await gateway.request(f.account, f.sessionID, input());
  await gateway.request(f.account, f.sessionID, input());
  await db!.query("UPDATE hosted_sessions SET state='closed',charged_ms=15000 WHERE id=$1", [f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=15000 WHERE id=$1", [f.reservationID]);
  await assert.rejects(gateway.request(f.account, f.sessionID, input()),
    { code: 'helper_session_limit', retryable: false, retryAfterMilliseconds: undefined });
  assert.equal(f.transport.calls.length, 2);
});

integration('earned request retry is withheld when the active deadline cannot accommodate new capacity', async () => {
  const f = await seed(600_000, true), gateway = f.controller({ maxRequestsPerMinute: 6 });
  await gateway.request(f.account, f.sessionID, input());
  await gateway.request(f.account, f.sessionID, input());
  await db!.query("UPDATE hosted_sessions SET observed_ms=6000,deadline=now()+interval '5 seconds' WHERE id=$1", [f.sessionID]);
  await assert.rejects(gateway.request(f.account, f.sessionID, input()),
    { code: 'helper_session_limit', retryable: false, retryAfterMilliseconds: undefined });
  assert.equal(f.transport.calls.length, 2);
});

integration('final count and search ceilings never advise waiting for more voice time', async () => {
  const f = await seed(15_000, true), gateway = f.controller({ maxRequestsPerMinute: 6 });
  await gateway.request(f.account, f.sessionID, input());
  await gateway.request(f.account, f.sessionID, input());
  await assert.rejects(gateway.request(f.account, f.sessionID, input()),
    { code: 'helper_session_limit', retryable: false, retryAfterMilliseconds: undefined });
  // Both ceilings are exhausted; search is a hard limit even if count capacity could grow.
  const g = await seed(600_000, true), noSearch = g.controller({ maxRequestsPerMinute: 6, maxSearchesPerSession: 0 });
  await noSearch.request(g.account, g.sessionID, input());
  await noSearch.request(g.account, g.sessionID, input());
  await assert.rejects(noSearch.request(g.account, g.sessionID, input({ purpose: 'topic', search: true })),
    { code: 'helper_session_limit', retryable: false, retryAfterMilliseconds: undefined });
  assert.equal(f.transport.calls.length, 2); assert.equal(g.transport.calls.length, 2);
});

test('helper admission retry delay rejects unbounded or malformed values', () => {
  for (const delay of [0, 999, 60_001, 1000.5, NaN, Infinity]) assert.throws(() => new HelperSessionLimitError(delay));
  assert.equal(new HelperSessionLimitError(1000).retryable, true);
  assert.equal(new HelperSessionLimitError(60_000).retryable, true);
  assert.equal(new HelperSessionLimitError().retryable, false);
});

integration('sub-minimum residues cannot spend the full fifteen-second helper allowance', async () => {
  const f=await seed(2_000,true), gateway=f.controller({helperBudgetNanoPerMinute:50_000_000n});
  await assert.rejects(gateway.request(f.account,f.sessionID,input()),{code:'helper_budget_exhausted'});
  assert.equal(f.transport.calls.length,0);
  await db!.query("UPDATE hosted_sessions SET state='closed',charged_ms=2000 WHERE id=$1",[f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=2000 WHERE id=$1",[f.reservationID]);
  await assert.rejects(gateway.request(f.account,f.sessionID,input({purpose:'assessment',schema:schemaValue})),{code:'helper_budget_exhausted'});
  assert.equal(f.transport.calls.length,0);
});

integration('final close and expiry preserve pending and uncertain earned-helper costs without refreshing their window', async () => {
  const f=await seed(600_000,true), gateway=f.controller({postSessionMilliseconds:0,helperBudgetNanoPerMinute:50_000_000n});
  let finish: (value:unknown)=>void=()=>{};
  f.transport.handler=()=>new Promise(resolve=>{finish=resolve;});
  const request=input(), pending=gateway.request(f.account,f.sessionID,request);
  await waitFor(()=>f.transport.calls.length===1);
  const hold=BigInt((await db!.query('SELECT hold_nano FROM hosted_helper_requests')).rows[0].hold_nano);
  await db!.query("UPDATE hosted_sessions SET state='closed',charged_ms=15000 WHERE id=$1",[f.sessionID]);
  await db!.query("UPDATE minute_reservations SET state='settled',used_ms=15000 WHERE id=$1",[f.reservationID]);
  assert.equal(await hostedHelperExposure(db!),12_500_000n);
  await gateway.expireBudgets(); assert.equal(await hostedHelperExposure(db!),hold);
  finish({usage:null});
  await assert.rejects(pending,{code:'helper_response_uncertain'});
  await f.controller().expireBudgets(); assert.equal(await hostedHelperExposure(db!),hold);
  await assert.rejects(f.controller().request(f.account,f.sessionID,request),{code:'helper_request_already_attempted'});
  await assert.rejects(f.controller().request(f.account,f.sessionID,input()),{code:'helper_session_window_closed'});
  assert.equal(f.transport.calls.length,1);
});
