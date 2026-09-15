import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { recoverHostedStartup } from '../src/hosted-startup-recovery.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL && 'Set TEST_DATABASE_URL.'},fn);
async function fixture() {
 const schema=`startup_recovery_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
 url.searchParams.set('options',`-c search_path=${schema}`);
 const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
 const account=randomUUID(),session=randomUUID(),reservation=randomUUID();
 await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)',[account]);
 await transaction(db,async sql=>{
  await appendMinuteEntry(sql,account,'seed','gift',6000000,0);
  await sql.query("INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,'failed-start',600000)",[reservation,account]);
  await appendMinuteEntry(sql,account,'reserve','reserve',0,600000);
  await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,rate_version,state,deadline,
    funding_exposure_nano,minimum_charge_ms,provider_attempted_at,close_requested_at,funding_mode)
    VALUES($1,$2,'failed-start',$3,600000,'test-rate','incomplete',now()-interval '2 minutes',500000000,15000,now()-interval '12 minutes',now()-interval '11 minutes','minutes')`,[session,account,reservation]);
 });
 await db.query(`INSERT INTO hosted_helper_sessions(session_id,reserved_ms,per_minute_nano,budget_nano,liability_nano,request_limit,search_limit,
   concurrency_limit,post_session_ms,framing_tokens,search_input_tokens,timeout_ms,rate_version,activation_pending,expires_at,earned_time)
   VALUES($1,600000,60000000,600000000,600000000,60,0,2,120000,4096,1050000,1000,'test-rate',true,now()+interval '2 minutes',true)`,[session]);
 const input={sessionID:session,accountID:account,expectedReservedMilliseconds:600000,actor:'test-operator',reason:'Support recovery of a synthetic failed startup; provider cost remains unknown.'};
 return {db,schema,account,session,reservation,input,
  async snapshot(){return (await db.query(`SELECT w.balance_ms,w.reserved_ms,h.state,h.charged_ms,h.provider_cost_nano,h.funding_exposure_nano,h.provider_rejection_status
     FROM minute_wallets w JOIN hosted_sessions h ON h.account_id=w.account_id WHERE h.id=$1`,[session])).rows[0];},
  async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}
integration('operator recovery restores only the hold, retains unknown cost and exposure, and is idempotent',async()=>{
 const f=await fixture();try {
  const before=await f.snapshot();assert.equal(before.reserved_ms,'600000');
  assert.deepEqual(await recoverHostedStartup(f.db,f.input),{recovered:true,alreadyRecovered:false,releasedMilliseconds:600000,providerCostStatus:'unconfirmed'});
  const helper=(await f.db.query('SELECT liability_nano,post_close_budget_nano FROM hosted_helper_sessions WHERE session_id=$1',[f.session])).rows[0];
  assert.equal(helper.liability_nano,'0');assert.equal(helper.post_close_budget_nano,'0');
  const after=await f.snapshot();assert.equal(after.balance_ms,'6000000');assert.equal(after.reserved_ms,'0');assert.equal(after.state,'closed');assert.equal(after.charged_ms,'0');
  await assert.rejects(f.db.query("UPDATE hosted_startup_recoveries SET reason='changed'"));
  await assert.rejects(f.db.query('DELETE FROM hosted_startup_recoveries'));
  assert.equal(after.provider_cost_nano,null);assert.equal(after.funding_exposure_nano,'500000000');assert.equal(after.provider_rejection_status,null);
  assert.equal((await recoverHostedStartup(f.db,f.input)).alreadyRecovered,true);
  assert.equal((await f.db.query('SELECT count(*) FROM hosted_startup_recoveries')).rows[0].count,'1');
  assert.equal((await f.db.query("SELECT count(*) FROM minute_entries WHERE reference LIKE 'startup-support:%'")).rows[0].count,'1');
 }finally{await f.cleanup();}
});
integration('operator recovery refuses fresh, observed, provider-identified, or mismatched sessions',async()=>{
 const f=await fixture();try{
  await assert.rejects(recoverHostedStartup(f.db,{...f.input,expectedReservedMilliseconds:1}),{code:'startup_recovery_review_again'});
  for(const change of ["deadline=now()+interval '1 minute'","observed_ms=1","provider_session_id='synthetic-provider-id'","state='active'"]){
   await f.db.query(`UPDATE hosted_sessions SET ${change} WHERE id=$1`,[f.session]);
   await assert.rejects(recoverHostedStartup(f.db,f.input),{code:'startup_recovery_review_again'});
   await f.db.query("UPDATE hosted_sessions SET deadline=now()-interval '2 minutes',observed_ms=0,provider_session_id=NULL,state='incomplete' WHERE id=$1",[f.session]);
  }
  assert.equal((await f.snapshot()).reserved_ms,'600000');
  assert.equal((await f.db.query('SELECT count(*) FROM hosted_startup_recoveries')).rows[0].count,'0');
 }finally{await f.cleanup();}
});
integration('runtime role cannot invoke support recovery or alter its audit records',async()=>{
 const f=await fixture();const role=`recovery_runtime_${randomUUID().replaceAll('-','')}`;
 let runtime:ReturnType<typeof connectDatabase>|undefined;
 try{
  await f.db.query(`CREATE ROLE ${role}`);
  await f.db.query(`GRANT USAGE ON SCHEMA ${f.schema} TO ${role}`);
  await f.db.query(`GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA ${f.schema} TO ${role}`);
  const grants=await readFile(new URL('../operations/hosted-startup-recovery-runtime-grants.sql',import.meta.url),'utf8');
  await f.db.query(grants.replaceAll('mural_runtime',role));
  const url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${f.schema} -c role=${role}`);runtime=connectDatabase(url.toString());
  await assert.rejects(recoverHostedStartup(runtime,f.input),{code:'42501'});
  assert.equal((await f.snapshot()).reserved_ms,'600000');
 }finally{await runtime?.end();await f.cleanup();const owner=connectDatabase(databaseURL!);await owner.query(`DROP ROLE ${role}`);await owner.end();}
});
