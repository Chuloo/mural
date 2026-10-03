import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';
import { Diagnostics, type DiagnosticRecord } from '../src/diagnostics.js';
import { ServiceError } from '../src/errors.js';
import { OpenAIHostedResponses } from '../src/hosted-responses-transport.js';
import type { HostedResponsesRequest } from '../src/hosted-helpers.js';

test('all API failures have references without logging private bodies, query strings, tokens or raw errors', async () => {
  const records: DiagnosticRecord[] = [];
  const diagnostics = new Diagnostics(record => { records.push(record); });
  const app = createApp({ db: {} as Database, auth: {}, diagnostics });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/auth/challenge?privateQuery=secret',
      headers: { authorization: 'Bearer secret-token', 'x-request-id': 'private-identity', 'x-mural-apple-app-transaction':'private.signed.secret-proof' },
      payload: { privateTranscript: 'private-words' } });
    assert.equal(response.statusCode, 503);
    const failure = records.find(record => record.event === 'request_failed')!;
    assert.match(String(response.headers['x-mural-error-reference']), /^[a-f0-9]{12}$/);
    assert.equal(failure.reference, response.headers['x-mural-error-reference']);
    assert.equal(failure.operation, 'POST /v1/auth/challenge');
    assert.equal(failure.reason, 'accounts_unavailable');
    assert.doesNotMatch(JSON.stringify(records), /private|secret|Bearer/);
    const missing = await app.inject('/private-nonexistent-path?secret=hidden');
    assert.equal(missing.statusCode, 404);
    assert.match(String(missing.headers['x-mural-error-reference']), /^[a-f0-9]{12}$/);
    assert.equal(records.at(-1)!.operation, 'GET unmatched');
    assert.doesNotMatch(JSON.stringify(records), /private|secret|hidden/);
  } finally { await app.close(); }
});

test('parallel requests keep provider events tied to their own response reference', async () => {
  const records: DiagnosticRecord[] = [];
  const diagnostics = new Diagnostics(record => { records.push(record); });
  const app = createApp({ db: {} as Database, auth: {}, diagnostics });
  let arrivals = 0, release!: () => void;
  const both = new Promise<void>(resolve => { release = resolve; });
  app.get('/diagnostic-test/:id', async request => {
    if (++arrivals === 2) release();
    await both;
    await new Promise(resolve => setImmediate(resolve));
    diagnostics.record('provider_failed', { operation: (request.params as {id: string}).id }, new ServiceError('provider_create_rejected'));
    throw new ServiceError('provider_create_rejected', 502);
  });
  try {
    const responses = await Promise.all(['one', 'two'].map(id => app.inject(`/diagnostic-test/${id}`)));
    for (const [index, id] of ['one', 'two'].entries()) {
      assert.equal(records.find(record => record.operation === id)?.reference, responses[index]!.headers['x-mural-error-reference']);
    }
    assert.notEqual(responses[0]!.headers['x-mural-error-reference'], responses[1]!.headers['x-mural-error-reference']);
  } finally { await app.close(); }
});

test('diagnostic observers cannot alter successful or failed requests', async () => {
  for (const sink of [() => { throw new Error('private'); }, async () => { throw new Error('private'); }]) {
    const app = createApp({ db: {} as Database, auth: {}, diagnostics: new Diagnostics(sink) });
    try {
      assert.equal((await app.inject('/healthz')).statusCode, 200);
      assert.equal((await app.inject('/v1/minutes')).statusCode, 401);
      await new Promise(resolve => setImmediate(resolve));
    } finally { await app.close(); }
  }
});

test('database categories are useful without retaining SQL, identities, messages or arbitrary fields', () => {
  const records: DiagnosticRecord[] = [];
  const diagnostics = new Diagnostics(record => { records.push(record); });
  diagnostics.record('background_failed', { operation: 'retention.accounts', privatePayload: 'secret' } as any,
    Object.assign(new Error('secret SQL account'), { code: '42501', detail: 'private@example.test' }));
  diagnostics.record('request_failed', { reference: 'private', providerRequestID: 'unsafe\nsecret' }, { code: 'private_code' });
  assert.equal(records[0]!.reason, 'database_permission');
  assert.equal(records[1]!.reason, 'internal');
  assert.doesNotMatch(JSON.stringify(records), /secret|private|SQL|example/);
});

test('provider rejection records status and request ID once without retaining response content or retrying', async () => {
  const records: DiagnosticRecord[] = []; let requests = 0;
  const transport = new OpenAIHostedResponses('x'.repeat(32), async () => {
    requests++;
    return new Response(JSON.stringify({ error: { message: 'private-transcript' } }), {
      status: 429, headers: { 'x-request-id': 'req_trace' },
    });
  }, new Diagnostics(record => { records.push(record); }));
  await assert.rejects(transport.send({} as HostedResponsesRequest, new AbortController().signal));
  assert.equal(requests, 1);
  assert.equal(records.length, 1);
  assert.equal(records[0]!.providerStatus, 429);
  assert.equal(records[0]!.providerRequestID, 'req_trace');
  assert.doesNotMatch(JSON.stringify(records), /private-transcript/);
});
