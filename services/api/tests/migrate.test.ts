import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { connectDatabase, type Database } from '../src/db.js';
import { migrate } from '../src/migrate.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if(databaseURL&&!new URL(databaseURL).pathname.endsWith('_test'))throw new Error('Dedicated test database required.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
const validateSQL='ALTER TABLE hosted_sessions VALIDATE CONSTRAINT hosted_rejection_evidence';
const grossValidateSQL='ALTER TABLE minute_stripe_paid_totals VALIDATE CONSTRAINT minute_stripe_paid_totals_gross_minor_check';
async function fixture(){
 const schema=`migration_test_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
 url.searchParams.set('options',`-c search_path=${schema}`);
 const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);
 return {db,schema,async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}
async function validated(db:Database,gross=false){
 return (await db.query(`SELECT convalidated FROM pg_constraint WHERE conrelid=$1::regclass
   AND conname=$2`,gross?['minute_stripe_paid_totals','minute_stripe_paid_totals_gross_minor_check']:['hosted_sessions','hosted_rejection_evidence'])).rows[0]?.convalidated;
}
async function history(db:Database){return (await db.query('SELECT name,applied_at FROM schema_migrations ORDER BY name')).rows;}
// Fault injection wraps real PostgreSQL queries; every schema change and lock is real.
function intercept(db:Database,hook:(text:unknown,run:()=>Promise<unknown>)=>Promise<unknown>):Database{
 return new Proxy(db,{get(target,property){
  if(property==='connect')return async()=>{
   const client=await target.connect();
   return new Proxy(client,{get(connection,key){
    if(key==='query')return (...args:unknown[])=>hook(args[0],()=>Reflect.apply(connection.query,connection,args));
    const value=Reflect.get(connection,key);return typeof value==='function'?value.bind(connection):value;
   }});
  };
  const value=Reflect.get(target,property);return typeof value==='function'?value.bind(target):value;
 }});
}
integration('fresh migrations validate after committing DDL and permit a concurrent writer lock during the scan',async()=>{
 const f=await fixture();let scans=0;
 try{
  await migrate(intercept(f.db,async(text,run)=>{
   if(text!==validateSQL&&text!==grossValidateSQL)return run();
   const gross=text===grossValidateSQL;
   scans++;
   assert.equal(await validated(f.db,gross),false,'DDL and migration history must already be visible to another connection');
   assert.ok((await history(f.db)).some(row=>row.name==='019_live_create_rejection.sql'));
   const writer=await f.db.connect();
   try{
    await writer.query('BEGIN');await writer.query("SET LOCAL lock_timeout='500ms'");
    await writer.query(`LOCK TABLE ${gross?'minute_stripe_paid_totals':'hosted_sessions'} IN ROW EXCLUSIVE MODE`);
    // VALIDATE uses SHARE UPDATE EXCLUSIVE, compatible with normal insert/update locks.
    return await run();
   }finally{await writer.query('ROLLBACK');writer.release();}
  }));
  assert.equal(scans,2);assert.equal(await validated(f.db),true);assert.equal(await validated(f.db,true),true);
 }finally{await f.cleanup();}
});
integration('a crash after schema commit leaves validation retryable without replaying applied migrations',async()=>{
 const f=await fixture();let interrupted=false;
 try{
  await assert.rejects(migrate(intercept(f.db,async(text,run)=>{
   const result=await run();
   if(text==='COMMIT'&&!interrupted){interrupted=true;throw new Error('Synthetic crash after schema commit');}
   return result;
  })),/Synthetic crash/);
  assert.equal(await validated(f.db),false);assert.equal(await validated(f.db,true),false);
  const committed=await history(f.db);assert.ok(committed.some(row=>row.name.startsWith('021_')));
  await migrate(f.db);assert.equal(await validated(f.db),true);assert.equal(await validated(f.db,true),true);assert.deepEqual(await history(f.db),committed);
 }finally{await f.cleanup();}
});
integration('already deployed validated 019-021 history is unchanged and its constraint is not scanned again',async()=>{
 const f=await fixture();
 try{
  await f.db.query('CREATE TABLE schema_migrations(name text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT now())');
  const path=new URL('../migrations/',import.meta.url);
  for(const file of (await readdir(path)).filter(name=>name.endsWith('.sql')&&name<'022').sort()){
   // Reproduce the previously deployed 019: its constraint was validated in the DDL transaction.
   let sql=await readFile(new URL(file,path),'utf8');
   if(file==='019_live_create_rejection.sql')sql=sql.replace(')) NOT VALID;','));');
   await f.db.query(sql);await f.db.query('INSERT INTO schema_migrations(name) VALUES($1)',[file]);
  }
  const before=await history(f.db);assert.equal(await validated(f.db),true);
  await migrate(intercept(f.db,async(text,run)=>{assert.notEqual(text,validateSQL);return run();}));
  const after=await history(f.db);assert.deepEqual(after.filter(row=>row.name<'022'),before);
  assert.equal(await validated(f.db),true);assert.equal(await validated(f.db,true),true);
 }finally{await f.cleanup();}
});
integration('failed validation preserves applied history and enforces new writes until repaired and retried',async()=>{
 const f=await fixture();
 try{
  await migrate(f.db);
  const account=randomUUID(),reservation=randomUUID(),session=randomUUID();
  await f.db.query('INSERT INTO accounts(id) VALUES($1)',[account]);
  await f.db.query(`INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version)
    VALUES($1,$2,'migration-fixture',1,'test')`,[reservation,account]);
  await f.db.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,reservation_id,rate_version,state,deadline,funding_exposure_nano)
    VALUES($1,$2,'migration-fixture',$3,'test','creating',now()+interval '1 minute',1)`,[session,account,reservation]);
  const definition=(await f.db.query(`SELECT pg_get_constraintdef(oid) AS definition FROM pg_constraint
    WHERE conrelid='hosted_sessions'::regclass AND conname='hosted_rejection_evidence'`)).rows[0].definition;
  await f.db.query('ALTER TABLE hosted_sessions DROP CONSTRAINT hosted_rejection_evidence');
  await f.db.query("UPDATE hosted_sessions SET provider_rejection_request_id='orphan-evidence' WHERE id=$1",[session]);
  await f.db.query(`ALTER TABLE hosted_sessions ADD CONSTRAINT hosted_rejection_evidence ${definition} NOT VALID`);
  const before=await history(f.db);
  await assert.rejects(migrate(f.db),{code:'23514'});
  assert.equal(await validated(f.db),false);assert.deepEqual(await history(f.db),before);
  await assert.rejects(f.db.query("UPDATE hosted_sessions SET provider_rejection_request_id='still-invalid' WHERE id=$1",[session]),{code:'23514'});
  await f.db.query('UPDATE hosted_sessions SET provider_rejection_request_id=NULL WHERE id=$1',[session]);
  await migrate(f.db);assert.equal(await validated(f.db),true);assert.deepEqual(await history(f.db),before);
 }finally{await f.cleanup();}
});
integration('concurrent migration runners serialize schema changes and converge on a validated constraint',async()=>{
 const f=await fixture();
 try{
  await Promise.all([migrate(f.db),migrate(f.db),migrate(f.db)]);
  const files=(await readdir(new URL('../migrations/',import.meta.url))).filter(name=>name.endsWith('.sql')).sort();
  assert.deepEqual((await history(f.db)).map(row=>row.name),files);assert.equal(await validated(f.db),true);assert.equal(await validated(f.db,true),true);
 }finally{await f.cleanup();}
});

integration('failure validating the second constraint retries both scans without replaying schema migrations',async()=>{
 const f=await fixture();let interrupted=false;
 try{
  await assert.rejects(migrate(intercept(f.db,async(text,run)=>{
   if(text===grossValidateSQL&&!interrupted){interrupted=true;throw new Error('Synthetic validation interruption');}
   return run();
  })),/Synthetic validation interruption/);
  const before=await history(f.db);
  assert.equal(await validated(f.db),false);assert.equal(await validated(f.db,true),false);
  await migrate(f.db);assert.equal(await validated(f.db),true);assert.equal(await validated(f.db,true),true);
  assert.deepEqual(await history(f.db),before);
 }finally{await f.cleanup();}
});
