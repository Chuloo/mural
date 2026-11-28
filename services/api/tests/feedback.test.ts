import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase } from '../src/db.js';
import { pruneAIReports } from '../src/feedback.js';

// Reports are saved on the learner's phone and the submission route is gone. Rows stored before
// that removal keep the thirty-day expiry the published disclosure promised, and the runtime role
// still cannot read them. Both stay until the tables are empty and a human drops them.
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

/** A row as the removed route stored it: the migration's own defaults give it a fixed expiry. */
const storedReport = async (id = randomUUID()) => {
  await db!.query(`INSERT INTO ai_feedback_reports(id,language_id,reason,excerpt,consent_version)
    VALUES($1,'es','incorrect','Una frase de prueba.','ai-report-v1')`, [id]);
  return id;
};
const expire = async (id: string) => {
  await db!.query("UPDATE ai_feedback_reports SET created_at=now()-interval '744 hours',expires_at=now()-interval '24 hours' WHERE id=$1", [id]);
};

integration('retention cleanup deletes only expired reports and counters', async () => {
  const old = await storedReport(), current = await storedReport();
  await db!.query(`INSERT INTO ai_feedback_limits(scope,identifier,window_start,expires_at,hits)
    VALUES('network_hour',$1,now(),now()-interval '1 minute',1)`, ['a'.repeat(64)]);
  await expire(old);
  assert.deepEqual(await pruneAIReports(db!), { reports: 1, limits: 1 });
  assert.deepEqual((await db!.query('SELECT id FROM ai_feedback_reports')).rows.map(row => row.id), [current]);
  assert.deepEqual(await pruneAIReports(db!), { reports: 0, limits: 0 });
});

integration('the runtime role cannot read stored reports and can only prune expired content', async () => {
  const role = `feedback_runtime_${suffix}`;
  await db!.query(`CREATE ROLE ${role}; GRANT USAGE ON SCHEMA ${schema} TO ${role}`);
  const grants = await readFile(new URL('../operations/feedback-runtime-grants.sql', import.meta.url), 'utf8');
  await db!.query(grants.replaceAll('mural_runtime', role));
  const runtimeURL = new URL(url!); runtimeURL.searchParams.set('options', `-c search_path=${schema} -c role=${role}`);
  const runtime = connectDatabase(runtimeURL.toString());
  try {
    const retained = await storedReport();
    for (const statement of ['SELECT excerpt FROM ai_feedback_reports', 'DELETE FROM ai_feedback_reports',
      "UPDATE ai_feedback_reports SET excerpt='changed'", 'DELETE FROM ai_feedback_limits'])
      await assert.rejects(runtime.query(statement), /permission denied/);
    assert.deepEqual(await pruneAIReports(runtime), { reports: 0, limits: 0 });
    await expire(retained);
    assert.deepEqual(await pruneAIReports(runtime), { reports: 1, limits: 0 });
    const privileges = (await db!.query("SELECT has_function_privilege($1,'prune_ai_feedback()','EXECUTE') AS allowed", [role])).rows[0];
    assert.equal(privileges.allowed, true);
  } finally {
    await runtime.end(); await db!.query(`DROP OWNED BY ${role}; DROP ROLE ${role}`);
  }
});
