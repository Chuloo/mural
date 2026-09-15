import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import type { Database } from '../src/db.js';
import { startupDiagnostic, type StartupDiagnostic } from '../src/startup-diagnostics.js';

test('conversation rejection returns a generated reference and retains the original failure contract', async () => {
  const diagnostics: StartupDiagnostic[] = [];
  const app = createApp({ db: {} as Database, auth: {}, onStartupDiagnostic: d => { diagnostics.push(d); } });
  try {
    const response = await app.inject({ method: 'POST', url: '/v1/live/sessions',
      headers: { 'x-request-id': 'private-identity', authorization: 'Bearer private-token' },
      payload: { sdp: 'private-sdp', instructions: 'private-conversation' } });
    assert.equal(response.statusCode, 503);
    assert.deepEqual(response.json(), { error: { code: 'hosted_voice_not_ready' } });
    const reference = response.headers['x-mural-error-reference'];
    assert.match(String(reference), /^[a-f0-9]{12}$/);
    assert.deepEqual(diagnostics, [{ reference, operation: 'start', status: 503, reason: 'hosted_voice_not_ready' }]);
    assert.doesNotMatch(JSON.stringify(diagnostics), /private/);
  } finally { await app.close(); }
});

test('an observer failure cannot change account authentication or start a provider request', async () => {
  const app = createApp({ db: {} as Database, auth: {}, onStartupDiagnostic: () => { throw new Error('observer'); } });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/minutes' });
    assert.equal(response.statusCode, 401);
    assert.equal(response.json().error.code, 'sign_in_required');
    assert.match(String(response.headers['x-mural-error-reference']), /^[a-f0-9]{12}$/);
  } finally { await app.close(); }
});

test('diagnostics contain fixed operation names and allowlisted failure categories only', () => {
  const id = '06db25ce-e250-424f-8ba9-a3ed0d982a39';
  const secret = { code: '42501', message: 'private-query-with-user-data', detail: 'private-provider-key' };
  assert.deepEqual(startupDiagnostic('GET', '/v1/live/sessions/:id', id, 500, 'service_unavailable', secret),
    { reference: '06db25cee250', operation: 'status', status: 500, reason: 'database_permission' });
  assert.equal(startupDiagnostic('GET', '/v1/account', id, 500, 'service_unavailable', secret), undefined);
  assert.equal(startupDiagnostic('GET', '/v1/minutes', 'private-identity', 500, 'service_unavailable', secret), undefined);
  assert.equal(startupDiagnostic('GET', '/v1/minutes', id, 500, 'private-provider-key', {})?.reason, 'internal');
});

test('a rejected asynchronous observer cannot become an unhandled rejection', async () => {
  const app = createApp({ db: {} as Database, auth: {}, onStartupDiagnostic: async () => { throw new Error('observer'); } });
  try {
    const response = await app.inject({ method: 'GET', url: '/v1/minutes' });
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(response.statusCode, 401);
    assert.equal(response.json().error.code, 'sign_in_required');
    assert.match(String(response.headers['x-mural-error-reference']), /^[a-f0-9]{12}$/);
  } finally { await app.close(); }
});
