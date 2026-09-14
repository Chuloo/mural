import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, type Database } from '../src/db.js';
import { AIReports, AI_REPORT_CONSENT_VERSION, AI_REPORT_LIMITS as limits, aiReportConfig,
  containsReportCredential, parseAIReport, pruneAIReports, reportNetwork, type TrustedFeedbackNetwork } from '../src/feedback.js';

const config = { hmacKey: 'a'.repeat(64), proxyToken: 'b'.repeat(64), allowLocalLoopback: false };
const headers = (address = '203.0.113.19') => ({ 'x-mural-client-ip': address, 'x-mural-proxy-token': config.proxyToken });
const network = (address = '203.0.113.19') => reportNetwork(headers(address), '127.0.0.1', config);
const body = () => ({ reportID: randomUUID(), languageID: 'es', reason: 'incorrect', excerpt: 'Una frase de prueba.', consentVersion: AI_REPORT_CONSENT_VERSION });
const url = process.env.TEST_DATABASE_URL;
if (url && !new URL(url).pathname.endsWith('_test')) throw new Error('Use an isolated database ending in _test.');
const suffix = randomUUID().replaceAll('-', ''), schema = `feedback_${suffix}`;
const ownerURL = url ? new URL(url) : null;
ownerURL?.searchParams.set('options', `-c search_path=${schema}`);
const db = ownerURL ? connectDatabase(ownerURL.toString()) : null;
before(async () => {
  if (!db) return;
  await db.query(`CREATE SCHEMA ${schema}`);
  await db.query(await readFile(new URL('../migrations/010_ai_feedback.sql', import.meta.url), 'utf8'));
});
beforeEach(async () => { if (db) await db.query('TRUNCATE ai_feedback_reports,ai_feedback_limits'); });
after(async () => { if (db) { try { await db.query(`DROP SCHEMA ${schema} CASCADE`); } finally { await db.end(); } } });
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !db && 'Set TEST_DATABASE_URL for PostgreSQL tests.' }, fn);

test('reporting requires explicit activation and distinct protected secrets', () => {
  assert.equal(aiReportConfig({}), undefined);
  assert.equal(aiReportConfig({ AI_REPORTS_ENABLED: 'false' }), undefined);
  assert.throws(() => aiReportConfig({ AI_REPORTS_ENABLED: 'true' }));
  const env = { AI_REPORTS_ENABLED: 'true', AI_REPORTS_HMAC_KEY: config.hmacKey, AI_REPORTS_PROXY_TOKEN: config.proxyToken };
  assert.deepEqual(aiReportConfig(env), config);
  assert.throws(() => aiReportConfig({ ...env, AI_REPORTS_PROXY_TOKEN: config.hmacKey }));
});

test('disabled reporting and invalid network identity fail before database work', async () => {
  const unused = {} as Database;
  await assert.rejects(new AIReports(unused).submit(body(), network()), { code: 'ai_reports_unavailable' });
  for (const fake of ['203.0.113.19', 'spoofed', '', 'a'.repeat(63)])
    await assert.rejects(new AIReports(unused, config).submit(body(), fake as TrustedFeedbackNetwork), { code: 'ai_reports_unavailable' });
});

test('reports accept only bounded selected text, known reasons and explicit consent', () => {
  const original = body(), parsed = parseAIReport({ ...original, excerpt: '  Una frase.\r\nOtra.  ' });
  assert.equal(parsed.excerpt, 'Una frase.\nOtra.');
  assert.deepEqual(Object.keys(parsed).sort(), ['consentVersion', 'excerpt', 'languageID', 'reason', 'reportID']);
  assert.equal(parseAIReport({ ...body(), excerpt: '🙂'.repeat(1000) }).excerpt.length, 2000);
  for (const invalid of [null, [], { ...body(), reason: 'arbitrary' }, { ...body(), consentVersion: '' },
    { ...body(), reportID: '../invalid' }, { ...body(), languageID: 'anything you want' },
    { ...body(), excerpt: 'x'.repeat(2001) }, { ...body(), excerpt: '\n  ' }, { ...body(), excerpt: 'hidden\u0000text' },
    { ...body(), excerpt: '\uD800' }, { ...body(), excerpt: '\u202ehidden' }, { ...body(), audio: 'audio' },
    { ...body(), accountID: randomUUID() }, { ...body(), accessToken: 'private' }, { ...body(), apiKey: 'private' },
    { ...body(), networkHash: network() }, { ...body(), archive: {} }]) {
    assert.throws(() => parseAIReport(invalid), { code: 'invalid_ai_report' });
  }
});

test('common provider, bearer, JWT and Mural token shapes are rejected without reflecting them', () => {
  const samples = ['sk-' + 'example-not-a-key'.repeat(3), 'Bearer ' + 'z'.repeat(43), 'z'.repeat(43),
    'eyJ' + 'a'.repeat(15) + '.' + 'b'.repeat(20) + '.' + 'c'.repeat(20)];
  for (const excerpt of samples) {
    assert.equal(containsReportCredential(excerpt), true);
    assert.throws(() => parseAIReport({ ...body(), excerpt }), { code: 'ai_report_contains_credential', message: 'ai_report_contains_credential' });
  }
  assert.equal(containsReportCredential('Una frase breve para revisar.'), false);
});

test('network reference comes from a trusted proxy and rotates daily without storing an IP', () => {
  const now = new Date('2026-09-13T12:00:00Z');
  const one = reportNetwork(headers('2001:db8:1:2::3'), '127.0.0.1', config, now);
  const same = reportNetwork(headers('2001:db8:1:2::4'), '127.0.0.1', config, now);
  const tomorrow = reportNetwork(headers('2001:db8:1:2::3'), '127.0.0.1', config, new Date('2026-09-14T12:00:00Z'));
  assert.match(one, /^[a-f0-9]{64}$/); assert.equal(one, same); assert.notEqual(one, tomorrow);
  assert.throws(() => reportNetwork({ ...headers(), 'x-mural-proxy-token': 'wrong', 'x-forwarded-for': '198.51.100.4' }, '127.0.0.1', config), { code: 'ai_reports_unavailable' });
  assert.throws(() => reportNetwork({}, '127.0.0.1', config), { code: 'ai_reports_unavailable' });
});

integration('deliberate report stores only previewed fields with fixed thirty-day expiry', async () => {
  const input = body(), service = new AIReports(db!, config);
  assert.deepEqual(await service.submit(input, network()), { accepted: true, reportID: input.reportID });
  const row = (await db!.query('SELECT * FROM ai_feedback_reports')).rows[0];
  assert.deepEqual(Object.keys(row).sort(), ['consent_version', 'created_at', 'excerpt', 'expires_at', 'id', 'language_id', 'reason']);
  assert.equal(row.excerpt, input.excerpt); assert.equal(row.consent_version, AI_REPORT_CONSENT_VERSION);
  assert.equal(row.expires_at.getTime() - row.created_at.getTime(), 30 * 24 * 60 * 60 * 1000);
  const counters = (await db!.query("SELECT identifier FROM ai_feedback_limits WHERE scope LIKE 'network_%'")).rows;
  assert.ok(counters.every(row => /^[a-f0-9]{64}$/.test(row.identifier)));
});

integration('explicit retry is idempotent and never returns or replaces existing content', async () => {
  const input = body(), service = new AIReports(db!, config);
  const first = await service.submit(input, network());
  assert.deepEqual(await service.submit(input, network()), first);
  assert.deepEqual(await service.submit({ ...input, excerpt: 'Different text is not returned.' }, network()), first);
  const reports = (await db!.query('SELECT excerpt FROM ai_feedback_reports')).rows;
  assert.equal(reports.length, 1); assert.equal(reports[0].excerpt, input.excerpt);
});

integration('persistent network limit survives service instances and denied attempts preserve other networks quota', async () => {
  const one = new AIReports(db!, config), two = new AIReports(db!, config);
  for (let index = 0; index < limits.networkHourly; index++) await one.submit(body(), network());
  await assert.rejects(two.submit(body(), network()), { code: 'ai_report_rate_limit', status: 429 });
  await assert.rejects(two.submit(body(), network()), { code: 'ai_report_rate_limit', status: 429 });
  const rows = (await db!.query('SELECT scope,hits FROM ai_feedback_limits')).rows;
  assert.equal(rows.find(row => row.scope === 'network_hour').hits, limits.networkHourly + 1);
  assert.equal(rows.find(row => row.scope === 'global_hour').hits, limits.networkHourly);
  assert.equal(rows.find(row => row.scope === 'global_day').hits, limits.networkHourly);
  assert.equal(rows.find(row => row.scope === 'network_day').hits, limits.networkHourly);
  await two.submit(body(), network('198.51.100.8'));
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM ai_feedback_reports')).rows[0].total), limits.networkHourly + 1);
});

integration('global hourly quota admits at most its final slot under concurrent distinct networks', async () => {
  const service = new AIReports(db!, config);
  await service.submit(body(), network());
  await db!.query("UPDATE ai_feedback_limits SET hits=$1 WHERE scope='global_hour'", [limits.globalHourly - 1]);
  const outcomes = await Promise.allSettled(Array.from({ length: 8 }, (_, index) => service.submit(body(), network(`198.51.100.${index + 1}`))));
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(outcomes.filter(result => result.status === 'rejected').length, 7);
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM ai_feedback_reports')).rows[0].total), 2);
  assert.equal((await db!.query("SELECT hits FROM ai_feedback_limits WHERE scope='global_day'")).rows[0].hits, 2);
});

integration('daily network and global quotas remain independent of hourly limits', async () => {
  const service = new AIReports(db!, config);
  await service.submit(body(), network());
  await db!.query("UPDATE ai_feedback_limits SET hits=$1 WHERE scope='network_day'", [limits.networkDaily]);
  await assert.rejects(service.submit(body(), network()), { code: 'ai_report_rate_limit' });
  assert.equal((await db!.query("SELECT hits FROM ai_feedback_limits WHERE scope='global_day'")).rows[0].hits, 1);
  await service.submit(body(), network('198.51.100.15'));
  await db!.query("UPDATE ai_feedback_limits SET hits=$1 WHERE scope='global_day'", [limits.globalDaily]);
  await assert.rejects(service.submit(body(), network('198.51.100.16')), { code: 'ai_report_rate_limit' });
  assert.equal(Number((await db!.query('SELECT count(*) AS total FROM ai_feedback_reports')).rows[0].total), 2);
});

integration('retention cleanup deletes only expired reports and counters', async () => {
  const service = new AIReports(db!, config), old = body(), current = body();
  await service.submit(old, network()); await service.submit(current, network());
  await db!.query("UPDATE ai_feedback_reports SET created_at=now()-interval '744 hours',expires_at=now()-interval '24 hours' WHERE id=$1", [old.reportID]);
  await db!.query("UPDATE ai_feedback_limits SET expires_at=now()-interval '1 minute' WHERE scope='network_hour'");
  assert.deepEqual(await pruneAIReports(db!), { reports: 1, limits: 1 });
  assert.deepEqual((await db!.query('SELECT id FROM ai_feedback_reports')).rows.map(row => row.id), [current.reportID]);
  assert.deepEqual(await pruneAIReports(db!), { reports: 0, limits: 0 });
});

integration('runtime submits without reading reports and can only prune expired content', async () => {
  const role = `feedback_runtime_${suffix}`;
  await db!.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${schema} TO ${role}`);
  const grants = await readFile(new URL('../operations/feedback-runtime-grants.sql', import.meta.url), 'utf8');
  await db!.query(grants.replaceAll('mural_runtime', role));
  const runtimeURL = new URL(url!); runtimeURL.searchParams.set('options', `-c search_path=${schema} -c role=${role}`);
  const runtime = connectDatabase(runtimeURL.toString());
  try {
    const input = body(), service = new AIReports(runtime, config);
    await service.submit(input, network()); await service.submit(input, network());
    for (const statement of ['SELECT excerpt FROM ai_feedback_reports', 'DELETE FROM ai_feedback_reports',
      "UPDATE ai_feedback_reports SET excerpt='changed'", 'DELETE FROM ai_feedback_limits'])
      await assert.rejects(runtime.query(statement), /permission denied/);
    await assert.rejects(runtime.query(`INSERT INTO ai_feedback_reports(id,language_id,reason,excerpt,consent_version,created_at)
      VALUES($1,'es','other','test','ai-report-v1',now())`, [randomUUID()]), /permission denied/);
    assert.deepEqual(await pruneAIReports(runtime), { reports: 0, limits: 0 });
    await db!.query("UPDATE ai_feedback_reports SET created_at=now()-interval '744 hours',expires_at=now()-interval '24 hours'");
    assert.deepEqual(await pruneAIReports(runtime), { reports: 1, limits: 0 });
    const privileges = (await db!.query("SELECT has_function_privilege($1,'prune_ai_feedback()','EXECUTE') AS allowed", [role])).rows[0];
    assert.equal(privileges.allowed, true);
  } finally {
    await runtime.end(); await db!.query(`DROP OWNED BY ${role}; DROP ROLE ${role}`);
  }
});
