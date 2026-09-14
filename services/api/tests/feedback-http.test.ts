import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createApp } from '../src/app.js';
import { connectDatabase, type Database } from '../src/db.js';
import { AIReports, AI_REPORT_BODY_LIMIT, AI_REPORT_CONSENT_VERSION, AI_REPORT_LIMITS, AI_REPORT_PATH, reportNetwork } from '../src/feedback.js';

const config = { hmacKey: 'c'.repeat(64), proxyToken: 'd'.repeat(64), allowLocalLoopback: false };
const headers = (address = '203.0.113.21') => ({ 'x-mural-client-ip': address, 'x-mural-proxy-token': config.proxyToken });
const input = () => ({ reportID: randomUUID(), languageID: 'es', reason: 'incorrect', excerpt: 'Una frase para revisar.', consentVersion: AI_REPORT_CONSENT_VERSION });
const unused = { query: async () => { throw new Error('Database must not be reached.'); } } as unknown as Database;

test('HTTP reporting is unavailable by default and advertises no capability', async () => {
  for (const aiReports of [undefined, new AIReports(unused)]) {
    const app = createApp({ db: unused, auth: {}, aiReports });
    try {
      const capability = await app.inject({ method: 'GET', url: '/v1/feedback/capabilities' });
      assert.equal(capability.statusCode, 200); assert.deepEqual(capability.json(), { aiReports: false });
      const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: input() });
      assert.equal(response.statusCode, 503); assert.deepEqual(response.json(), { error: { code: 'ai_reports_unavailable' } });
      assert.equal(response.headers['cache-control'], 'no-store');
    } finally { await app.close(); }
  }
});

test('HTTP capability does not require an account and report failures never expose database details', async () => {
  const app = createApp({ db: unused, auth: {}, aiReports: new AIReports(unused, config) });
  try {
    const capability = await app.inject({ method: 'GET', url: '/v1/feedback/capabilities' });
    assert.equal(capability.statusCode, 200); assert.deepEqual(capability.json(), { aiReports: true });
    const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: input() });
    assert.equal(response.statusCode, 500); assert.deepEqual(response.json(), { error: { code: 'service_unavailable' } });
    assert.ok(!response.body.includes('Database'));
  } finally { await app.close(); }
});

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const schema = `feedback_http_${randomUUID().replaceAll('-', '')}`;
const databaseURL = url ? new URL(url) : null;
databaseURL?.searchParams.set('options', `-c search_path=${schema}`);
const db = databaseURL ? connectDatabase(databaseURL.toString()) : null;
before(async () => {
  if (!db) return;
  await db.query(`CREATE SCHEMA ${schema}`);
  await db.query(await readFile(new URL('../migrations/010_ai_feedback.sql', import.meta.url), 'utf8'));
});
beforeEach(async () => { if (db) await db.query('TRUNCATE ai_feedback_reports,ai_feedback_limits'); });
after(async () => { if (db) { try { await db.query(`DROP SCHEMA ${schema} CASCADE`); } finally { await db.end(); } } });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
const appWithReports = () => createApp({ db: db!, auth: {}, aiReports: new AIReports(db!, config) });
const reportCount = async () => Number((await db!.query('SELECT count(*) AS total FROM ai_feedback_reports')).rows[0].total);

integration('HTTP accepts only deliberate preview fields and returns a receipt without text or identity', async () => {
  const app = appWithReports();
  try {
    const body = input(), response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: body });
    assert.equal(response.statusCode, 202); assert.deepEqual(response.json(), { accepted: true, reportID: body.reportID });
    assert.equal(response.headers['cache-control'], 'no-store'); assert.equal(response.headers['x-content-type-options'], 'nosniff');
    assert.ok(!response.body.includes(body.excerpt));
    const row = (await db!.query('SELECT * FROM ai_feedback_reports')).rows[0];
    assert.deepEqual(Object.keys(row).sort(), ['consent_version', 'created_at', 'excerpt', 'expires_at', 'id', 'language_id', 'reason']);
    assert.equal(row.excerpt, body.excerpt);
    assert.equal(row.expires_at.getTime() - row.created_at.getTime(), 30 * 24 * 60 * 60 * 1000);
    assert.equal((await app.inject({ method: 'GET', url: AI_REPORT_PATH })).statusCode, 404);
  } finally { await app.close(); }
});

integration('HTTP rejects invented proxy identities and ignores untrusted forwarded addresses', async () => {
  const app = appWithReports();
  try {
    for (const fakeHeaders of [{}, { 'x-forwarded-for': '198.51.100.10' },
      { ...headers(), 'x-mural-proxy-token': 'wrong' }, { ...headers(), 'x-mural-client-ip': 'not-an-IP' }]) {
      const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: fakeHeaders, payload: input() });
      assert.equal(response.statusCode, 503); assert.deepEqual(response.json(), { error: { code: 'ai_reports_unavailable' } });
    }
    assert.equal(await reportCount(), 0);
    const trustedHeaders = { ...headers(), 'x-forwarded-for': '198.51.100.10', 'x-real-ip': '198.51.100.11' };
    const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: trustedHeaders, payload: input() });
    assert.equal(response.statusCode, 202);
    const references = (await db!.query("SELECT identifier FROM ai_feedback_limits WHERE scope LIKE 'network_%'")).rows;
    const expected = reportNetwork(headers(), '127.0.0.1', config);
    assert.ok(references.length === 2 && references.every(row => row.identifier === expected));
  } finally { await app.close(); }
});

integration('HTTP rejects extra history, identity, credentials, missing consent and overlong excerpts', async () => {
  const app = appWithReports();
  try {
    for (const invalid of [{ ...input(), audio: 'private audio' }, { ...input(), archive: 'private archive' },
      { ...input(), accountID: randomUUID() }, { ...input(), accessToken: 'private token' },
      { ...input(), apiKey: 'private key' }, { ...input(), networkHash: 'a'.repeat(64) },
      { ...input(), consentVersion: undefined }, { ...input(), excerpt: 'x'.repeat(2001) },
      { ...input(), excerpt: '\uD800' }, { ...input(), excerpt: 'sk-' + 'example-not-a-key'.repeat(3) }]) {
      const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: invalid });
      assert.equal(response.statusCode, 400);
      assert.ok(['invalid_ai_report', 'ai_report_contains_credential'].includes(response.json().error.code));
      assert.ok(!response.body.includes('private') && !response.body.includes('sk-'));
    }
    assert.equal(await reportCount(), 0);
    assert.equal(Number((await db!.query('SELECT count(*) AS total FROM ai_feedback_limits')).rows[0].total), 0);
  } finally { await app.close(); }
});

integration('HTTP applies its smaller body limit and rejects malformed JSON before storing a report', async () => {
  const app = appWithReports();
  try {
    const cases = [
      { payload: JSON.stringify({ ...input(), excerpt: 'x'.repeat(AI_REPORT_BODY_LIMIT) }), type: 'application/json', status: 413 },
      { payload: '{"excerpt":', type: 'application/json', status: 400 },
      { payload: JSON.stringify(input()), type: 'text/plain', status: 400 },
      { payload: 'audio', type: 'audio/wav', status: 415 },
      { payload: '[]', type: 'application/json', status: 400 }
    ];
    for (const entry of cases) {
      const response = await app.inject({ method: 'POST', url: AI_REPORT_PATH,
        headers: { ...headers(), 'content-type': entry.type }, payload: entry.payload });
      assert.equal(response.statusCode, entry.status);
      assert.deepEqual(Object.keys(response.json()), ['error']);
      assert.ok(!response.body.includes('excerpt'));
    }
    assert.equal(await reportCount(), 0);
  } finally { await app.close(); }
});

integration('HTTP explicit retry is idempotent while durable limits survive app recreation and encoded routes', async () => {
  const first = appWithReports(), second = appWithReports();
  try {
    const body = input();
    const accepted = await first.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: body });
    const retry = await second.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: body });
    assert.equal(retry.statusCode, 202); assert.deepEqual(retry.json(), accepted.json());
    assert.equal(await reportCount(), 1);
    for (let index = 2; index < AI_REPORT_LIMITS.networkHourly; index++) {
      assert.equal((await first.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers(), payload: input() })).statusCode, 202);
    }
    for (const path of [AI_REPORT_PATH, '/v1/feedback/%61i']) {
      const limited = await second.inject({ method: 'POST', url: path,
        headers: { ...headers(), 'x-forwarded-for': '198.51.100.40' }, payload: input() });
      assert.equal(limited.statusCode, 429); assert.equal(limited.headers['retry-after'], '3600');
      assert.deepEqual(limited.json(), { error: { code: 'ai_report_rate_limit' } });
    }
    assert.equal((await second.inject({ method: 'POST', url: AI_REPORT_PATH, headers: headers('198.51.100.41'), payload: input() })).statusCode, 202);
    assert.equal(await reportCount(), AI_REPORT_LIMITS.networkHourly);
  } finally { await first.close(); await second.close(); }
});
