import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AccessRequests, ACCESS_REQUEST_LIMITS as limits, accessRequestConfig, normalizeAccessEmail, pruneAccessRequests, deleteAccessRequest } from '../src/access-requests.js';
import { exportAccessRequests } from '../src/access-requests-admin.js';

const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use a dedicated database ending in _test.');
const db = url ? connectDatabase(url) : null;
before(async () => { if (db) await migrate(db); });
beforeEach(async () => { if (db) await db.query('TRUNCATE access_requests,access_request_limits'); });
after(async () => { if (db) await db.end(); });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);
const config = { hmacKey: 'a'.repeat(64), proxyToken: 'b'.repeat(64) };
const body = (email = 'learner@example.test') => ({ email, consentVersion: 'waitlist-v1', source: 'website', website: '' });
const headers = (address = '203.0.113.1') => ({ origin: 'https://mural.chat', 'content-type': 'application/json',
  'x-mural-client-ip': address, 'x-mural-proxy-token': config.proxyToken });
function app() { return createApp({ db: db!, auth: {}, accessRequests: new AccessRequests(db!, config) }); }
async function count() { return Number((await db!.query('SELECT count(*) AS count FROM access_requests')).rows[0].count); }

test('access requests require explicit activation and separate secrets; local origins are exact loopback only', () => {
  assert.equal(accessRequestConfig({}), undefined);
  assert.throws(() => accessRequestConfig({ ACCESS_REQUESTS_ENABLED: 'true' }));
  const env = { ACCESS_REQUESTS_ENABLED: 'true', ACCESS_REQUEST_HMAC_KEY: config.hmacKey, ACCESS_REQUEST_PROXY_TOKEN: config.proxyToken };
  assert.deepEqual(accessRequestConfig(env), config);
  for (const origin of ['https://evil.test', 'http://localhost:5173/path', 'http://localhost:5173/', 'https://localhost:5173', 'http://localhost.evil.test'])
    assert.throws(() => accessRequestConfig({ ...env, ACCESS_REQUEST_LOCAL_ORIGIN: origin }));
  assert.equal(accessRequestConfig({ ...env, ACCESS_REQUEST_LOCAL_ORIGIN: 'http://localhost:5173' })?.localOrigin, 'http://localhost:5173');
  assert.throws(() => accessRequestConfig({ ...env, ACCESS_REQUEST_PROXY_TOKEN: config.hmacKey }));
});
test('email normalization accepts ordinary plus addresses and rejects unsafe or unsupported forms', () => {
  assert.equal(normalizeAccessEmail('  Learner+Mural@Example.COM  '), 'learner+mural@example.com');
  for (const email of ['a@localhost', 'a..b@example.com', '.a@example.com', 'a.@example.com', 'a@-example.com',
    'a@example-.com', 'a@example..com', 'a@b@c.com', 'a\nb@example.com', 'é@example.com', 'x'.repeat(65) + '@example.com', 'a@' + 'b'.repeat(64) + '.com'])
    assert.throws(() => normalizeAccessEmail(email));
});
test('disabled access endpoint fails closed without database work', async () => {
  const unused = connectDatabase('postgresql://unused@127.0.0.1:1/unused'), service = createApp({ db: unused, auth: {} });
  try {
    assert.equal((await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body() })).statusCode, 503);
  } finally { await service.close(); await unused.end(); }
});
integration('HTTP access creation and duplicate refresh have identical generic responses and retain only declared fields', async () => {
  const service = app();
  try {
    const first = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body(' Learner@Example.Test ') });
    await db!.query("UPDATE access_requests SET requested_at=now()-interval '2 months'");
    const second = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body() });
    assert.equal(first.statusCode, 202); assert.equal(second.statusCode, 202);
    assert.deepEqual(first.json(), { accepted: true }); assert.equal(first.body, second.body);
    assert.equal(first.headers['access-control-allow-origin'], 'https://mural.chat');
    assert.equal(first.headers['access-control-allow-credentials'], undefined);
    assert.equal(first.headers['cache-control'], 'no-store');
    const row = (await db!.query('SELECT * FROM access_requests')).rows[0];
    assert.deepEqual(Object.keys(row).sort(), ['consent_version', 'email', 'requested_at', 'source']);
    assert.equal(row.email, 'learner@example.test'); assert.equal(await count(), 1);
    assert.ok(Date.now() - row.requested_at.getTime() < 10_000);
    assert.equal(row.consent_version, 'waitlist-v1'); assert.equal(row.source, 'website');
  } finally { await service.close(); }
});
integration('origin and preflight rules run before parsing or storing body data', async () => {
  const service = app();
  try {
    for (const origin of [undefined, 'null', 'https://mural.chat.evil.test', 'http://mural.chat', 'https://www.mural.chat']) {
      const h: Record<string, string> = headers(); if (origin === undefined) delete h.origin; else h.origin = origin;
      const response = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: h, payload: '{broken' });
      assert.equal(response.statusCode, 403); assert.equal(response.headers['access-control-allow-origin'], undefined);
    }
    const preflight = await service.inject({ method: 'OPTIONS', url: '/v1/access-requests', headers: {
      origin: 'https://mural.chat', 'access-control-request-method': 'POST', 'access-control-request-headers': 'content-type' } });
    assert.equal(preflight.statusCode, 204); assert.equal(preflight.body, '');
    assert.equal(preflight.headers['access-control-allow-methods'], 'POST');
    const bad = await service.inject({ method: 'OPTIONS', url: '/v1/access-requests', headers: {
      origin: 'https://mural.chat', 'access-control-request-method': 'DELETE' } });
    assert.equal(bad.statusCode, 400);
    const unsafeHeaders = await service.inject({ method: 'OPTIONS', url: '/v1/access-requests', headers: {
      origin: 'https://mural.chat', 'access-control-request-method': 'POST', 'access-control-request-headers': 'content-type,x-mural-proxy-token' } });
    assert.equal(unsafeHeaders.statusCode, 400);
    assert.equal(await count(), 0);
  } finally { await service.close(); }
});
integration('invalid schema, consent, source, JSON, media type and oversized bodies never persist', async () => {
  const service = app();
  try {
    for (const payload of [{ ...body(), consentVersion: 'unknown' }, { ...body(), source: 'mobile' }, { ...body(), extra: 'private' },
      { ...body(), email: ['a@example.com'] }, { ...body(), website: 123 }, [], null]) {
      const response = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: JSON.stringify(payload) });
      assert.equal(response.statusCode, 400);
      assert.deepEqual(response.json(), { error: { code: 'invalid_access_request' } });
    }
    assert.equal((await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: '{broken' })).statusCode, 400);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: JSON.stringify({ ...body(), email: 'x'.repeat(1500) }) })).statusCode, 413);
    assert.equal((await service.inject({ method: 'POST', url: '/v1/access-requests', headers: { ...headers(), 'content-type': 'text/plain' }, payload: 'email=private' })).statusCode, 415);
    assert.equal(await count(), 0);
  } finally { await service.close(); }
});
integration('spoofed forwarding headers cannot replace the proxy secret; IPv6 address variants share one limit', async () => {
  const service = app();
  try {
    const spoof = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: { ...headers(),
      'x-mural-proxy-token': 'wrong', 'x-forwarded-for': '192.0.2.1' }, payload: body() });
    assert.equal(spoof.statusCode, 503);
    const ips = ['2001:db8:1234:1::1', '2001:0db8:1234:0001:0:0:0:1', '2001:db8:1234:1::2'];
    for (let i = 0; i < 6; i++) {
      const result = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(ips[i % 3]!), payload: body() });
      assert.equal(result.statusCode, i < 5 ? 202 : 429);
    }
    const buckets = (await db!.query("SELECT identifier,expires_at-window_start AS lifetime FROM access_request_limits WHERE scope='ip_hour'")).rows;
    assert.equal(buckets.length, 1); assert.match(buckets[0].identifier, /^[a-f0-9]{64}$/);
    assert.ok(!JSON.stringify(buckets).includes('2001:'));
  } finally { await service.close(); }
});
integration('durable rate limits survive app instances, commit rejected attempts, and allow other addresses', async () => {
  const first = app(), second = app();
  try {
    for (let i = 0; i < 5; i++) assert.equal((await first.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body() })).statusCode, 202);
    const limited = await second.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body() });
    assert.equal(limited.statusCode, 429); assert.equal(limited.headers['retry-after'], '3600');
    assert.equal((await db!.query("SELECT hits FROM access_request_limits WHERE scope='ip_hour'")).rows[0].hits, 6);
    assert.equal((await second.inject({ method: 'POST', url: '/v1/access-requests', headers: headers('203.0.113.2'), payload: body() })).statusCode, 202);
    assert.equal(await count(), 1);
  } finally { await first.close(); await second.close(); }
});
integration('simultaneous submissions respect per-address allowance and deduplicate atomically', async () => {
  const store = new AccessRequests(db!, config);
  const results = await Promise.allSettled(Array.from({ length: 8 }, () => store.submit(body(), '192.0.2.20')));
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 5);
  assert.equal(await count(), 1);
  assert.equal((await db!.query("SELECT hits FROM access_request_limits WHERE scope='new_day'")).rows[0].hits, 1);
});
integration('network rejections cannot drain shared waitlist quotas, including concurrent attempts at the last allowance', async () => {
  const store = new AccessRequests(db!, config);
  await store.submit(body(), '192.0.2.20');
  await db!.query("UPDATE access_request_limits SET hits=5 WHERE scope='ip_hour'");
  await db!.query("UPDATE access_request_limits SET hits=$1 WHERE scope='global_day'", [limits.requestsDaily - 1]);
  await db!.query("UPDATE access_request_limits SET hits=$1 WHERE scope='global_hour'", [limits.requestsHourly - 1]);
  const denied = await Promise.allSettled(Array.from({ length: 32 }, () => store.submit(body(), '192.0.2.20')));
  assert.equal(denied.filter(result => result.status === 'rejected').length, 32);
  const counters = (await db!.query("SELECT scope,hits FROM access_request_limits WHERE scope IN ('global_day','global_hour') ORDER BY scope")).rows;
  assert.deepEqual(counters, [{ scope: 'global_day', hits: limits.requestsDaily - 1 }, { scope: 'global_hour', hits: limits.requestsHourly - 1 }]);
  await store.submit(body('other@example.test'), '192.0.2.21');
  assert.equal(await count(), 2);
});
integration('an exhausted waitlist hour does not consume the next hours daily allowance', async () => {
  const store = new AccessRequests(db!, config);
  await store.submit(body(), '192.0.2.20');
  await db!.query("UPDATE access_request_limits SET hits=$1 WHERE scope='global_hour'", [limits.requestsHourly]);
  for (let i = 0; i < 10; i++) await assert.rejects(store.submit(body(), `192.0.2.${30 + i}`), { code: 'access_request_rate_limit' });
  assert.equal((await db!.query("SELECT hits FROM access_request_limits WHERE scope='global_day'")).rows[0].hits, 1);
});
integration('honeypot returns success without storing email and still consumes the abuse budget', async () => {
  const store = new AccessRequests(db!, config);
  await store.submit({ ...body(), website: 'https://spam.test' }, '192.0.2.21');
  assert.equal(await count(), 0);
  assert.equal((await db!.query("SELECT hits FROM access_request_limits WHERE scope='ip_hour'")).rows[0].hits, 1);
});
integration('global request and daily signup caps stop distinct addresses without leaking existing membership', async () => {
  const store = new AccessRequests(db!, config);
  await store.submit(body(), '192.0.2.22');
  await db!.query("UPDATE access_request_limits SET hits=$1 WHERE scope='global_day'", [limits.requestsDaily]);
  await assert.rejects(store.submit(body('new@example.test'), '192.0.2.23'), { code: 'access_request_rate_limit' });
  await db!.query("UPDATE access_request_limits SET hits=0 WHERE scope='global_day'");
  await db!.query("UPDATE access_request_limits SET hits=$1 WHERE scope='new_day'", [limits.newAddressesDaily]);
  for (const email of ['learner@example.test', 'new@example.test'])
    await assert.rejects(store.submit(body(email), '192.0.2.24'), { code: 'access_requests_full' });
  assert.equal(await count(), 1);
});
integration('storage cap remains bounded under concurrent requests', async () => {
  assert.equal(limits.retainedAddresses, 100_000);
  await db!.query("INSERT INTO access_requests(email,consent_version,source) SELECT 'seed'||n||'@example.test','waitlist-v1','website' FROM generate_series(1,$1::integer) n", [limits.retainedAddresses - 1]);
  const store = new AccessRequests(db!, config);
  const results = await Promise.allSettled([store.submit(body('last@example.test'), '192.0.2.25'), store.submit(body('extra@example.test'), '192.0.2.26')]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1); assert.equal(await count(), limits.retainedAddresses);
});
integration('launch traffic above the old daily and hourly limits can still join without resetting counters', async () => {
  const service = app();
  try {
    await new AccessRequests(db!, config).submit(body(), '192.0.2.50');
    await db!.query("UPDATE access_request_limits SET hits=500 WHERE scope='new_day'");
    await db!.query("UPDATE access_request_limits SET hits=1001 WHERE scope='global_day'");
    await db!.query("UPDATE access_request_limits SET hits=201 WHERE scope='global_hour'");
    const response = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers('192.0.2.51'), payload: body('launch@example.test') });
    assert.equal(response.statusCode, 202); assert.deepEqual(response.json(), { accepted: true });
    assert.equal(await count(), 2);
    assert.equal((await db!.query("SELECT hits FROM access_request_limits WHERE scope='new_day'")).rows[0].hits, 501);
  } finally { await service.close(); }
});
integration('private export excludes expired rows, protects file permissions, and deletion and pruning are idempotent', async () => {
  const store = new AccessRequests(db!, config), dir = await mkdtemp(join(tmpdir(), 'mural-access-test-'));
  try {
    await store.submit(body(), '192.0.2.27');
    await db!.query("INSERT INTO access_requests(email,requested_at,consent_version,source) VALUES('expired@example.test',now()-interval '13 months','waitlist-v1','website')");
    await db!.query("UPDATE access_request_limits SET expires_at=now()-interval '1 minute' WHERE scope='ip_hour'");
    const path = join(dir, 'requests.json'); await exportAccessRequests(db!, path);
    assert.equal((await stat(path)).mode & 0o777, 0o600);
    const archive = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(archive.requests.length, 1); assert.equal(archive.requests[0].email, 'learner@example.test');
    await assert.rejects(exportAccessRequests(db!, path));
    assert.deepEqual(await pruneAccessRequests(db!), { requests: 1, limits: 1 });
    await deleteAccessRequest(db!, ' LEARNER@example.test '); await deleteAccessRequest(db!, 'learner@example.test');
    assert.equal(await count(), 0);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('database errors return a generic unavailable response without input or database detail', async () => {
  const unused = connectDatabase('postgresql://unused@127.0.0.1:1/unused'), service = createApp({ db: unused, auth: {}, accessRequests: new AccessRequests(unused, config) });
  try {
    const response = await service.inject({ method: 'POST', url: '/v1/access-requests', headers: headers(), payload: body('private@example.test') });
    assert.equal(response.statusCode, 503); assert.deepEqual(response.json(), { error: { code: 'access_requests_unavailable' } });
  } finally { await service.close(); await unused.end(); }
});
