import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { AppleHistoryReconciler, MinuteCommerceRunner, PlayVoidReconciler } from '../src/minute-commerce-runner.js';
import { APIException } from '@apple/app-store-server-library';
import { PlayNotificationTransportError } from '../src/google-play-rtdn.js';

const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(done => { resolve = done; }); return { promise, resolve }; };
const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration = (name: string, fn: () => Promise<void>) => test(name, { skip: !databaseURL && 'Set TEST_DATABASE_URL.' }, fn);
for (const firstFails of [false,true]) integration(`a delayed Apple ${firstFails?'failure':'success'} cannot replace a newer page outcome`, async () => {
  const f=await fixture(),entered=deferred(),release=deferred();let work:Promise<unknown>|undefined;
  try {
    const delayed=new Proxy(f.db,{get(target,key){
      if(key==='query')return async (...args:any[])=>{
        if(String(args[0]).startsWith('INSERT INTO provider_reconciliation_health')) {entered.resolve();await release.promise;}
        return Reflect.apply(target.query,target,args);
      };
      const value=Reflect.get(target,key);return typeof value==='function'?value.bind(target):value;
    }});
    const apple=(fails:boolean,more=false)=>({environment:'live' as const,merchant:'chat.mural.ios',pollHistory:async()=>{
      if(fails)throw new APIException(401,null,'private response');
      return {scheduled:0,nextPageToken:more?'next-page':undefined};
    }});
    work=new AppleHistoryReconciler(delayed,apple(firstFails,true)).page().then(value=>value,error=>error);
    await entered.promise;
    const newer=new AppleHistoryReconciler(f.db,apple(!firstFails));
    if(firstFails)await newer.page();else await assert.rejects(newer.page(),{httpStatusCode:401});
    const latest=(await f.db.query('SELECT * FROM provider_reconciliation_health')).rows[0];
    assert.equal(latest.consecutive_failures,firstFails?0:1);
    assert.equal(latest.provider_status,firstFails?null:401);
    release.resolve();await work;
    assert.deepEqual((await f.db.query('SELECT * FROM provider_reconciliation_health')).rows[0],latest);
  } finally {release.resolve();await work;await f.cleanup();}
});
integration('Apple authorization failures survive rollback, identify the scope, and clear only after a successful history page', async () => {
  const f=await fixture();let fail=true;const observed:any[]=[];
  try {
    const apple={environment:'live' as const,merchant:'chat.mural.ios',pollHistory:async()=>{
      if(fail)throw new APIException(401,null,'private provider response');
      return {scheduled:0,nextPageToken:undefined};
    }};
    const history=new AppleHistoryReconciler(f.db,apple);
    const runner=new MinuteCommerceRunner({scheduleReconciliation:async()=>0},
      {runBatch:async()=>({processed:0,completed:0,retried:0})},undefined,
      {onFailure:(code,status,scope)=>observed.push({code,status,scope})},history);
    await runner.runOnce();await runner.runOnce();
    assert.equal((await f.db.query('SELECT count(*) FROM apple_notification_cursors')).rows[0].count,'0');
    const failed=(await f.db.query('SELECT * FROM provider_reconciliation_health')).rows[0];
    assert.equal(failed.consecutive_failures,2);assert.equal(failed.provider_status,401);
    assert.ok(failed.first_failure_at);assert.equal(failed.last_success_at,null);
    assert.deepEqual(observed[0],{code:'apple_history_reconciliation_failed',status:401,scope:{provider:'apple',environment:'live'}});
    assert.doesNotMatch(JSON.stringify(observed),/private/);
    fail=false;await runner.runOnce();
    const recovered=(await f.db.query('SELECT * FROM provider_reconciliation_health')).rows[0];
    assert.equal(recovered.consecutive_failures,0);assert.equal(recovered.first_failure_at,null);
    assert.equal(recovered.provider_status,null);assert.ok(recovered.last_success_at);
    assert.ok((await f.db.query('SELECT completed_through_ms FROM apple_notification_cursors')).rows[0].completed_through_ms);
  } finally {await f.cleanup();}
});
for (const environment of ['live','test'] as const) integration(`Apple ${environment} catch-up covers retention without gaps and resumes after a failed window`, async () => {
  const f=await fixture(),day=86_400_000,started=Date.now();
  const calls:Array<{start:number;end:number;token?:string}>=[];
  let fail=false;
  const source={environment,merchant:'chat.mural.ios',async pollHistory(start:number,end:number,token?:string){
    calls.push({start,end,token});
    assert.ok(end-start<=7*day);
    if(fail)throw new Error('timeout');
    return {scheduled:0,nextPageToken:undefined};
  }};
  const row=async()=>(await f.db.query('SELECT * FROM apple_notification_cursors')).rows[0];
  try {
    assert.equal((await new AppleHistoryReconciler(f.db,source).page()).more,true);
    assert.ok(Math.abs(calls[0]!.start-(started-(environment==='live'?179:29)*day))<5000);
    const first=await row();
    assert.equal(Number(first.completed_through_ms),calls[0]!.end);
    assert.equal(first.page_token,null);
    fail=true;await assert.rejects(new AppleHistoryReconciler(f.db,source).page());
    assert.deepEqual(await row(),first);
    fail=false;
    let more=true,remaining=40;
    while(more && remaining--){more=(await new AppleHistoryReconciler(f.db,source).page()).more;}
    assert.ok(remaining>0);assert.equal(more,false);
    assert.deepEqual(calls[2],calls[1]); // restart retries the uncompleted window
    for(let i=3;i<calls.length;i++){
      assert.equal(calls[i]!.start,calls[i-1]!.end-300_000);
      assert.ok(calls[i]!.end>calls[i-1]!.end);
    }
    assert.ok(Number((await row()).completed_through_ms)>Date.now()-120_000);
    assert.deepEqual(await new AppleHistoryReconciler(f.db,source).page(),{pages:0,scheduled:0,more:false});
  } finally {await f.cleanup();}
});

integration('Apple finishes a recent paginated window when the clock advances between pages', async () => {
  const f=await fixture(),started=Date.now();let now=started;
  const clock=mock.method(Date,'now',()=>now),calls:Array<{start:number;end:number;token?:string}>=[];
  try {
    await f.db.query("INSERT INTO apple_notification_cursors(environment,merchant,completed_through_ms,next_poll_after) VALUES('live','chat.mural.ios',$1,now()-interval '1 second')",[started-600_000]);
    const cursor=new AppleHistoryReconciler(f.db,{environment:'live',merchant:'chat.mural.ios',pollHistory:async(start,end,token)=>{
      calls.push({start,end,token});return {scheduled:0,nextPageToken:token?undefined:'last-page'};
    }});
    assert.equal((await cursor.page()).more,true);
    now+=60_000;
    assert.equal((await cursor.page()).more,false);
    assert.equal(calls[1]!.start,calls[0]!.start);assert.equal(calls[1]!.end,calls[0]!.end);
    const row=(await f.db.query('SELECT * FROM apple_notification_cursors')).rows[0];
    assert.equal(Number(row.completed_through_ms),started-60_000);
    assert.equal(row.page_token,null);
    assert.deepEqual(await cursor.page(),{pages:0,scheduled:0,more:false});
    assert.equal(calls.length,2);
  } finally {clock.mock.restore();await f.cleanup();}
});

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

test('runner reports only fixed Play transport reason and HTTP status', async () => {
  const failures: Array<[string, number | undefined]> = [];
  const runner = new MinuteCommerceRunner({ scheduleReconciliation: async () => 0 },
    { runBatch: async () => ({ processed: 0, completed: 0, retried: 0 }) }, undefined,
    { onFailure: (code, status) => failures.push([code, status]) }, undefined,
    { poll: async () => { throw new PlayNotificationTransportError('play_notification_unavailable', 403); },
      isOperational: () => false });
  await runner.runOnce();
  assert.deepEqual(failures, [['play_notification_unavailable', 403]]);
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
