import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { MinuteCommerceRunner, PlayVoidReconciler } from '../src/minute-commerce-runner.js';

const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(done => { resolve = done; }); return { promise, resolve }; };
const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
async function fixture() {
  const schema = `commerce_runner_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  return { db, async row() { return (await db.query('SELECT * FROM minute_play_void_cursors')).rows[0]; },
    async cleanup() { await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end(); } };
}

test('runner has no automatic work, coalesces overlap and stops after draining one active delivery', async () => {
  let schedules = 0, deliveries = 0, polls = 0;
  const entered = deferred(), release = deferred();
  const runner = new MinuteCommerceRunner({ scheduleReconciliation: async () => { schedules++; return 0; } },
    { runBatch: async limit => { assert.equal(limit, 1); deliveries++; entered.resolve(); await release.promise; return { processed: 1, completed: 1, retried: 0 }; } },
    { page: async () => { polls++; return { more: false }; } } as any);
  assert.equal(deliveries, 0);
  const first = runner.runOnce(), duplicate = runner.runOnce(); assert.equal(first, duplicate);
  await entered.promise; let stopped = false; const stopping = runner.stop().then(() => { stopped = true; });
  await Promise.resolve(); assert.equal(stopped, false); release.resolve(); await Promise.all([first, duplicate, stopping]);
  await runner.runOnce(); runner.start();
  assert.equal(schedules, 1); assert.equal(deliveries, 1); assert.equal(polls, 0); assert.equal(stopped, true);
});

test('runner bounds each cycle and reports only fixed error codes while continuing independent reconciliation', async () => {
  let deliveries = 0, polls = 0; const failures: string[] = [];
  const runner = new MinuteCommerceRunner({ scheduleReconciliation: async limit => { assert.equal(limit, 31); throw new Error('private DB details'); } },
    { runBatch: async limit => { assert.equal(limit, 1); deliveries++; return { processed: 1, completed: 0, retried: 1 }; } },
    { page: async () => { polls++; return { more: true }; } } as any,
    { deliveryLimit: 3, reconciliationLimit: 31, voidPagesPerRun: 2, onFailure: code => failures.push(code) });
  await runner.runOnce(); await runner.stop(); assert.equal(deliveries, 3); assert.equal(polls, 2);
  assert.deepEqual(failures, ['minute_reconciliation_failed','minute_delivery_failed','minute_delivery_failed','minute_delivery_failed']);
  assert.equal(failures.join().includes('private'), false);
});

test('runner observer failures and provider failures cannot create unhandled background work', async () => {
  const entered = deferred(); let calls = 0;
  const runner = new MinuteCommerceRunner({ scheduleReconciliation: async () => 0 },
    { runBatch: async () => { calls++; entered.resolve(); throw new Error('private token'); } }, undefined,
    { onFailure: () => { throw new Error('observer'); } });
  runner.start(); runner.start(); await entered.promise; await runner.stop(); assert.equal(calls, 1);
  assert.throws(() => new MinuteCommerceRunner({} as any, {} as any, undefined, { intervalMilliseconds: 0 }), { code: 'minute_runner_configuration_invalid' });
});

integration('Play void pages resume their exact persisted window after a failure and restart', async () => {
  const f = await fixture();
  try {
    const requests: any[] = []; let fail = true;
    const play = { environment: 'test' as const, merchant: 'chat.mural.android', pollVoids: async (start: number, end: number, token?: string) => {
      requests.push({ start,end,token });
      if (!token) return { scheduled: 1, nextPageToken: 'next-page' };
      if (fail) throw new Error('Google unavailable');
      return { scheduled: 2 };
    } };
    const first = new PlayVoidReconciler(f.db, play);
    assert.deepEqual(await first.page(), { skipped: false, pages: 1, scheduled: 1, more: true });
    const checkpoint = await f.row(); assert.equal(checkpoint.page_token, 'next-page'); assert.equal(checkpoint.completed_through_ms, null);
    await assert.rejects(first.page()); assert.equal((await f.row()).page_token, 'next-page');
    fail = false; const restarted = new PlayVoidReconciler(f.db, play);
    assert.deepEqual(await restarted.page(), { skipped: false, pages: 1, scheduled: 2, more: false });
    assert.deepEqual(requests[1], requests[2]); assert.equal(requests[0].start, requests[2].start); assert.equal(requests[0].end, requests[2].end);
    const completed = await f.row(); assert.equal(Number(completed.completed_through_ms), requests[0].end); assert.equal(completed.page_token, null);
    assert.equal((await restarted.page()).pages, 0); assert.equal(requests.length, 3);
  } finally { await f.cleanup(); }
});

integration('two reconciler instances cannot fetch or advance the same Play page concurrently', async () => {
  const f = await fixture();
  try {
    const entered = deferred(), release = deferred(); let calls = 0;
    const play = { environment: 'test' as const, merchant: 'chat.mural.android', pollVoids: async () => {
      calls++; entered.resolve(); await release.promise; return { scheduled: 0 };
    } };
    const first = new PlayVoidReconciler(f.db, play), second = new PlayVoidReconciler(f.db, play);
    const work = first.page(); await entered.promise;
    assert.deepEqual(await second.page(), { skipped: true, pages: 0, scheduled: 0, more: false });
    release.resolve(); await work; assert.equal(calls, 1); assert.ok((await f.row()).completed_through_ms);
  } finally { await f.cleanup(); }
});

integration('void history expiration and stalled pagination preserve the checkpoint and require recovery', async () => {
  const f = await fixture();
  try {
    const old = Date.now() - 31 * 86_400_000; let calls = 0;
    await f.db.query(`INSERT INTO minute_play_void_cursors(environment,merchant,completed_through_ms) VALUES('test','chat.mural.android',$1)`, [old]);
    const play = { environment: 'test' as const, merchant: 'chat.mural.android', pollVoids: async () => { calls++; return { scheduled: 0, nextPageToken: 'stuck' }; } };
    const reconciler = new PlayVoidReconciler(f.db, play);
    await assert.rejects(reconciler.page(), { code: 'play_void_cursor_expired' }); assert.equal(calls, 0);
    assert.equal(Number((await f.row()).completed_through_ms), old);
    await f.db.query(`UPDATE minute_play_void_cursors SET completed_through_ms=$1,window_start_ms=$1,window_end_ms=$2,page_token='stuck'`, [Date.now() - 3_600_000, Date.now() - 60_000]);
    await assert.rejects(reconciler.page(), { code: 'play_void_cursor_stalled' }); assert.equal((await f.row()).page_token, 'stuck');
  } finally { await f.cleanup(); }
});

integration('void polling overlaps the prior checkpoint and cannot rewind its durable completed watermark', async () => {
  const f = await fixture();
  try {
    const completed = Date.now() - 3_600_000; let start = 0;
    await f.db.query(`INSERT INTO minute_play_void_cursors(environment,merchant,completed_through_ms) VALUES('test','chat.mural.android',$1)`, [completed]);
    const reconciler = new PlayVoidReconciler(f.db, { environment: 'test', merchant: 'chat.mural.android', pollVoids: async value => { start = value; return { scheduled: 0 }; } });
    await reconciler.page(); assert.equal(start, completed - 300_000);
    await assert.rejects(f.db.query('UPDATE minute_play_void_cursors SET completed_through_ms=$1', [completed]), /immutable_minute_void_checkpoint/);
    await assert.rejects(f.db.query("UPDATE minute_play_void_cursors SET merchant='chat.mural.other'"), /immutable_minute_void_checkpoint/);
  } finally { await f.cleanup(); }
});
