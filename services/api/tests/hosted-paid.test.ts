import { linkGuestMinutes, finalizeDeferredGuestLinks } from '../src/guest-minutes.js';
import { digest } from '../src/auth.js';
import { reserveMinutes, finishMinuteReservation } from '../src/minutes.js';
import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry, paidAIBalance } from '../src/ledger.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { HostedHelpers, HOSTED_HELPER_MODEL, hostedHelperCost, type HostedResponsesTransport } from '../src/hosted-helpers.js';
import { LiveCreateRejectedError, type LiveProvider, type LiveContext, type VoiceUsage } from '../src/live-provider.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const schema=`paid_voice_${randomUUID().replaceAll('-','')}`,url=databaseURL ? new URL(databaseURL) : null;
url?.searchParams.set('options',`-c search_path=${schema}`);
const db=url ? connectDatabase(url.toString()) : null;
before(async()=> {if(db) {await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);}});
beforeEach(async()=> {if(db) await db.query('TRUNCATE accounts CASCADE');});
after(async()=> {if(db) {try {await db.query(`DROP SCHEMA ${schema} CASCADE`);} finally {await db.end();}}});
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!db && 'Set TEST_DATABASE_URL.'},fn);
async function until(predicate:()=>Promise<boolean>) {
  const deadline=Date.now()+3000;
  while(!await predicate()) {if(Date.now()>deadline) throw new Error('Condition timed out');await new Promise(resolve=>setTimeout(resolve,5));}
}
class Voice implements LiveProvider {
  creates=0; closes=0; fails=false; rejection=false; contexts: (LiveContext|undefined)[]=[];
  listeners=new Map<string,(event:VoiceUsage)=>void>();
  async create(_sdp:string,_language:string,context?:LiveContext) {
    this.creates++;this.contexts.push(context);
    if(this.fails) throw new Error('Uncertain provider result');
    if(this.rejection) throw new LiveCreateRejectedError(429, 'req_runtime_rejection');
    return {sessionID:`live_paid_${this.creates}`,sdp:'v=0\r\nanswer'};
  }
  async attach(id:string,listener:(event:VoiceUsage)=>void) {this.listeners.set(id,listener);return {closeSession:()=>{this.closes++;},disconnect:()=>{this.listeners.delete(id);}};}
  async hangup() {this.closes++;}
  send(id:string,seconds:number,final=false) {this.listeners.get(id)!({type:final?'session.closed':'session.usage.updated',usage:{seconds}});}
}
const response=(extra:Record<string,unknown>={})=>({id:`resp_${randomUUID().replaceAll('-','')}`,model:HOSTED_HELPER_MODEL,
  service_tier:'default',status:'completed',output:[{type:'message',content:[{type:'output_text',text:'Hola.',annotations:[]}]}],
  usage:{input_tokens:100,input_tokens_details:{cached_tokens:20,cache_write_tokens:30},output_tokens:40},...extra});
const input=()=>({requestID:randomUUID(),purpose:'meaning',instructions:'Translate into English.',input:'Hola.'});
async function fixture(options:{cash?:bigint;free?:number;paid?:boolean;cancel?:boolean;guest?:boolean}={}) {
  const account=randomUUID();
  await db!.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)',[account,options.guest??false]);
  await db!.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
  await transaction(db!,async sql=>{
    await appendEntry(sql,account,`seed:${account}`,'purchase',options.cash??2_000_000_000n,0n);
    await appendMinuteEntry(sql,account,`minutes:${account}`,'gift',options.free??0,0);
  });
  const voice=new Voice();let attempts=0;
  const transport:HostedResponsesTransport & {handler:()=>Promise<unknown>}={handler:async()=>response(),async send(){attempts++;return this.handler();}};
  const helpers=new HostedHelpers(db!,transport,{accountAllowlist:new Set(),aggregateFundingCapNano:0n,publicMinuteAccess:true,
    publicPaidAccess:options.paid??true,helperBudgetNanoPerMinute:50_000_000n,maxRequestsPerMinute:6,maxSearchesPerSession:1,
    maxConcurrentPerSession:2,maxConcurrentGlobal:4,postSessionMilliseconds:120_000,inputFramingTokenAllowance:4096,
    searchInputTokenAllowance:1_050_000,timeoutMilliseconds:1000});
  const admission={paidFundingPolicy:helpers.paidFundingPolicy,closeCashBudget:helpers.closeCashBudget.bind(helpers),
    async reserveSessionBudget(sql:any,owner:string,id:string) {await helpers.reserveSessionBudget(sql,owner,id);
      if(options.cancel) await sql.query("UPDATE hosted_sessions SET state='closing',close_requested_at=now() WHERE id=$1",[id]);}};
  const controller=new HostedVoice(db!,voice,{accountAllowlist:new Set(),lifetimeFundingCapNano:0n,billingUnit:'milliseconds',
    publicMinuteAccess:true,publicPaidAccess:options.paid??true,helpers:admission});
  await controller.start();
  return {account,voice,helpers,transport,controller,get attempts(){return attempts;},
    create:(duration?:number)=>controller.create(account,randomUUID(),'v=0\r\noffer','es-ES',undefined,duration),
    async emit(session:{sessionID:string;providerSessionID:string},seconds:number,final=false) {
      voice.send(session.providerSessionID,seconds,final);
      await until(async()=> {const row=(await db!.query('SELECT observed_ms,state FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0];
        return Number(row.observed_ms)===Math.ceil(seconds*1000) && (!final || row.state!=='active');});
    },
    async expire(id:string,executor=helpers) {await db!.query("UPDATE hosted_sessions SET helper_closed_at=helper_closed_at WHERE id=$1",[id]);
      // The test advances only the database clock used by expiry through a temporary narrow trigger override.
      await db!.query('ALTER TABLE hosted_helper_sessions DISABLE TRIGGER hosted_helper_budget_immutable');
      try {await db!.query("UPDATE hosted_helper_sessions SET expires_at=now()-interval '1 second' WHERE session_id=$1",[id]);}
      finally {await db!.query('ALTER TABLE hosted_helper_sessions ENABLE TRIGGER hosted_helper_budget_immutable');}
      await executor.expireBudgets();
    },
    async balance() {return paidAIBalance(db!,account);},
    async invariant() {const result=(await db!.query(`SELECT w.reserved_nano,
      (SELECT COALESCE(sum(reserved_nano),0) FROM reservations WHERE account_id=w.account_id AND state='open')+
      (SELECT COALESCE(sum(b.cash_pool_nano),0) FROM hosted_helper_sessions b JOIN hosted_sessions h ON h.id=b.session_id
        WHERE h.account_id=w.account_id) AS expected FROM wallets w WHERE account_id=$1`,[account])).rows[0];
      assert.equal(result.reserved_nano,result.expected);},
    close:()=>controller.stop()};
}

integration('paid admission defaults off and cannot spend sandbox or unreviewed cash',async()=>{
  const f=await fixture({paid:false});try {
    await assert.rejects(f.create(),{code:'insufficient_minutes'});assert.equal(f.voice.creates,0);
  }finally{await f.close();}
  const g=await fixture();try {
    await transaction(db!,sql=>appendEntry(sql,g.account,'sandbox-only','purchase',5_000_000_000n,0n,null,5_000_000_000n));
    await db!.query('UPDATE wallets SET cash_provenance_verified=false WHERE account_id=$1',[g.account]);
    await assert.rejects(g.create(),{code:'cash_balance_reconciliation_required'});
    await db!.query('UPDATE wallets SET cash_provenance_verified=true,balance_nano=sandbox_balance_nano WHERE account_id=$1',[g.account]);
    await assert.rejects(g.create(),{code:'insufficient_credit'});assert.equal(g.voice.creates,0);
  }finally{await g.close();}
});
integration('paid sessions require a member even if a guest has a cash wallet',async()=>{
  const f=await fixture({guest:true});try {
    await assert.rejects(f.create(),{code:'sign_in_required'});
    // Actual guest records have no currency wallet. Their exhausted allowance must still request sign-in.
    await db!.query('DELETE FROM wallets WHERE account_id=$1',[f.account]);
    await assert.rejects(f.create(),{code:'sign_in_required'});assert.equal(f.voice.creates,0);
  }finally{await f.close();}
});
integration('free minutes are selected before paid value and never convert mid-conversation',async()=>{
  const f=await fixture({free:65_000});try {
    const session=await f.create(3_600_000);assert.equal(session.fundingMode,'minutes');assert.equal(session.reservedMilliseconds,65_000);
    assert.equal((await f.balance()).reservedNanoUSD,'0');await f.emit(session,65,true);
    assert.equal((await f.balance()).balanceNanoUSD,'2000000000');assert.equal(f.voice.creates,1);
    const paid=await f.create(60_000);assert.equal(paid.fundingMode,'ai-value');assert.equal(paid.limitMilliseconds,60_000);
    await f.invariant();
  }finally{await f.close();}
});
integration('paid limits accept one to sixty minutes and use fifteen minutes by default',async()=>{
  const f=await fixture({cash:10_000_000_000n});try {
    for(const invalid of [0,59_999,60_000.5,3_600_001,NaN,Infinity]) await assert.rejects(f.create(invalid),{code:'invalid_session_duration'});
    const session=await f.create(3_600_000);assert.equal(session.limitMilliseconds,3_600_000);
    assert.equal(session.voiceReservedNanoUSD,'3005000000');assert.equal(session.helperReservedNanoUSD,'3000000000');
    await f.emit(session,1,true);await f.expire(session.sessionID);
    const next=await f.create();assert.equal(next.limitMilliseconds,900_000);await f.invariant();
  }finally{await f.close();}
});
integration('a small funded balance shrinks the paid duration and exposes the minimum hold',async()=>{
  const f=await fixture({cash:30_000_000n});try {
    assert.equal(f.controller.minimumPaidSessionNanoUSD,30_000_000n);
    const session=await f.create();assert.equal(session.limitMilliseconds,15_000);assert.equal(session.reservedNanoUSD,'30000000');
    await f.emit(session,0,true);assert.equal((await f.controller.status(f.account,session.sessionID)).chargedNanoUSD,'12500000');
    await f.expire(session.sessionID);assert.equal((await f.balance()).availableNanoUSD,'17500000');await f.invariant();
    await assert.rejects(f.create(),{code:'insufficient_credit'});
  }finally{await f.close();}
});
integration('known voice and helper usage debit their exact separate costs and release unused holds',async()=>{
  const f=await fixture();try {
    const session=await f.create(180_000);await f.emit(session,120);
    const request=await f.helpers.request(f.account,session.sessionID,input());assert.equal(request.costNanoUSD,'65900');
    await f.invariant();await f.emit(session,125,true);await f.invariant();
    const status=await f.controller.status(f.account,session.sessionID);
    assert.equal(status.chargedVoiceNanoUSD,'104166667');assert.equal(status.chargedHelperNanoUSD,'65900');
    assert.equal(status.chargedNanoUSD,'104232567');
    await f.expire(session.sessionID);await f.invariant();
    assert.deepEqual(await f.balance(),{balanceNanoUSD:'1895767433',reservedNanoUSD:'0',availableNanoUSD:'1895767433',cashProvenanceVerified:true});
  }finally{await f.close();}
});
integration('unknown helper usage keeps only its request hold after the voice and helper window end',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000),request=input();f.transport.handler=async()=>{throw new Error('Connection lost');};
    await assert.rejects(f.helpers.request(f.account,session.sessionID,request),{code:'helper_response_uncertain'});
    await assert.rejects(f.helpers.request(f.account,session.sessionID,request),{code:'helper_request_already_attempted'});assert.equal(f.attempts,1);
    const held=(await db!.query('SELECT hold_nano FROM hosted_helper_requests WHERE request_id=$1',[request.requestID])).rows[0].hold_nano;
    await f.emit(session,10,true);await f.expire(session.sessionID);await f.invariant();
    assert.equal((await f.balance()).reservedNanoUSD,held);
    assert.equal((await f.controller.status(f.account,session.sessionID)).helperPendingNanoUSD,held);
    const next=await f.create(60_000);assert.equal(next.fundingMode,'ai-value');await f.invariant();
  }finally{await f.close();}
});
integration('late known helper completion after expiry releases its unused hold without reopening the pool',async()=>{
  const f=await fixture();try {
    let finish!:(value:unknown)=>void;f.transport.handler=()=>new Promise(resolve=>{finish=resolve;});
    const session=await f.create(60_000),pending=f.helpers.request(f.account,session.sessionID,input());
    await until(async()=>f.attempts===1);await f.emit(session,10,true);await f.expire(session.sessionID);
    finish(response());await pending;await f.invariant();assert.equal((await f.balance()).reservedNanoUSD,'0');
  }finally{await f.close();}
});
integration('cash refunds stop the call and deny new helpers while sandbox refunds cannot consume paid backing',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000);
    await transaction(db!,sql=>appendEntry(sql,f.account,'test-refund','reversal',-5_000_000_000n,0n,null,-5_000_000_000n));
    await f.helpers.request(f.account,session.sessionID,input());await f.controller.tick();assert.equal(f.voice.closes,0);
    await transaction(db!,sql=>appendEntry(sql,f.account,'real-refund','reversal',-2_000_000_000n,0n));
    await assert.rejects(f.helpers.request(f.account,session.sessionID,input()),{code:'helper_session_funding_unavailable'});
    await f.controller.tick();assert.ok(f.voice.closes>0);await f.emit(session,10,true);await f.expire(session.sessionID);await f.invariant();
    assert.ok(BigInt((await f.balance()).balanceNanoUSD)<0n);
  }finally{await f.close();}
});
integration('uncertain paid creation keeps its voice hold and cannot start a replacement',async()=>{
  const f=await fixture();try {
    f.voice.fails=true;await assert.rejects(f.create(60_000),{code:'provider_session_unconfirmed'});
    const current=await f.controller.current(f.account);assert.equal(current.session?.state,'incomplete');
    await assert.rejects(f.create(),{code:'live_session_unresolved'});assert.equal(f.voice.creates,1);await f.invariant();
  }finally{await f.close();}
});
integration('cancellation before the provider boundary releases both paid holds without charging',async()=>{
  const f=await fixture({cancel:true});try {
    await assert.rejects(f.create(),{code:'live_session_cancelled'});assert.equal(f.voice.creates,0);
    assert.equal((await f.balance()).reservedNanoUSD,'0');assert.equal((await f.balance()).balanceNanoUSD,'2000000000');await f.invariant();
  }finally{await f.close();}
});
integration('voice overrun preserves the authorized hold and records actual cost for reconciliation',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000);await f.emit(session,90,true);
    const status=await f.controller.status(f.account,session.sessionID);assert.equal(status.state,'incomplete');assert.equal(status.chargedNanoUSD,null);
    const record=(await db!.query('SELECT provider_cost_nano,usage FROM hosted_cash_reconciliation WHERE session_id=$1',[session.sessionID])).rows[0];
    assert.equal(record.provider_cost_nano,'75000000');assert.deepEqual(record.usage,{milliseconds:90000});
    assert.equal((await f.balance()).balanceNanoUSD,'2000000000');await f.invariant();
  }finally{await f.close();}
});
integration('helper overrun preserves its hold and records authoritative counters without charging beyond it',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000),request=input();
    f.transport.handler=async()=>response({usage:{input_tokens:1_000_000,input_tokens_details:{cached_tokens:0,cache_write_tokens:0},output_tokens:40}});
    await assert.rejects(f.helpers.request(f.account,session.sessionID,request),{code:'helper_response_uncertain'});
    const record=(await db!.query('SELECT * FROM hosted_cash_reconciliation WHERE request_id=$1',[request.requestID])).rows[0];
    assert.equal(BigInt(record.provider_cost_nano),hostedHelperCost({inputTokens:1_000_000,cachedInputTokens:0,cacheWriteTokens:0,outputTokens:40,searchCalls:0}));
    assert.equal((await f.balance()).balanceNanoUSD,'2000000000');await f.invariant();
  }finally{await f.close();}
});
integration('paid funding mode, duration and helper reservation bindings are immutable',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000);await f.helpers.request(f.account,session.sessionID,input());
    await assert.rejects(db!.query("UPDATE hosted_sessions SET funding_mode='legacy' WHERE id=$1",[session.sessionID]),/immutable/);
    await assert.rejects(db!.query('UPDATE hosted_sessions SET limit_ms=120000 WHERE id=$1',[session.sessionID]),/immutable/);
    await assert.rejects(db!.query('UPDATE hosted_helper_sessions SET cash_funded=false,cash_pool_nano=0 WHERE session_id=$1',[session.sessionID]),/immutable/);
    await assert.rejects(db!.query('UPDATE hosted_helper_requests SET cash_reservation_id=NULL WHERE session_id=$1',[session.sessionID]),/immutable/);
    await f.invariant();
  }finally{await f.close();}
});
integration('a refused helper answer still settles its verified provider cost exactly once',async()=>{
  const f=await fixture();try {
    const session=await f.create(60_000),request=input();
    f.transport.handler=async()=>response({output:[{type:'message',content:[{type:'refusal',refusal:'Unavailable.'}]}]});
    await assert.rejects(f.helpers.request(f.account,session.sessionID,request),{code:'helper_output_refused'});
    await assert.rejects(f.helpers.request(f.account,session.sessionID,request),{code:'helper_request_already_attempted'});
    assert.equal((await f.balance()).balanceNanoUSD,'1999934100');assert.equal(f.attempts,1);await f.invariant();
  }finally{await f.close();}
});
integration('the restricted runtime settles paid helpers while provenance and funding history stay protected',async()=>{
  const f=await fixture(),role=`paid_runtime_${randomUUID().replaceAll('-','')}`;
  await db!.query(`CREATE ROLE ${role};GRANT USAGE ON SCHEMA ${schema} TO ${role};
    GRANT SELECT,UPDATE ON accounts TO ${role};GRANT SELECT,INSERT,UPDATE ON hosted_sessions TO ${role};
    GRANT SELECT,INSERT ON minute_wallets,minute_entries,minute_reservations TO ${role};
    GRANT UPDATE(balance_ms,reserved_ms,sandbox_balance_ms) ON minute_wallets TO ${role};
    GRANT SELECT ON minute_purchase_transactions TO ${role};
    GRANT UPDATE ON wallets TO ${role}`);
  for(const file of ['minute-runtime-grants.sql','hosted-helper-runtime-grants.sql','actual-value-runtime-grants.sql']) {
    const grants=await readFile(new URL(`../operations/${file}`,import.meta.url),'utf8');await db!.query(grants.replaceAll('mural_runtime',role));
  }
  const runtimeURL=new URL(databaseURL!);runtimeURL.searchParams.set('options',`-c search_path=${schema} -c role=${role}`);
  const runtime=connectDatabase(runtimeURL.toString());
  let hosted:HostedVoice|undefined;
  try {
    const gateway=new HostedHelpers(runtime,f.transport,{accountAllowlist:new Set(),aggregateFundingCapNano:0n,publicMinuteAccess:true,publicPaidAccess:true,
      helperBudgetNanoPerMinute:50_000_000n,maxRequestsPerMinute:6,maxSearchesPerSession:1,maxConcurrentPerSession:2,maxConcurrentGlobal:4,
      postSessionMilliseconds:120_000,inputFramingTokenAllowance:4096,searchInputTokenAllowance:1_050_000,timeoutMilliseconds:1000});
    await f.controller.stop();
    hosted=new HostedVoice(runtime,f.voice,{accountAllowlist:new Set(),lifetimeFundingCapNano:0n,billingUnit:'milliseconds',publicMinuteAccess:true,publicPaidAccess:true,helpers:gateway});
    await hosted.start();
    f.voice.rejection=true;
    const before=await f.balance();
    await assert.rejects(hosted.create(f.account,randomUUID(),'v=0','es-ES'),{code:'provider_create_rejected'});
    assert.deepEqual(await f.balance(),before);await f.invariant();
    const rejected=(await db!.query('SELECT * FROM hosted_sessions WHERE provider_rejection_status IS NOT NULL')).rows[0];
    assert.equal(rejected.provider_rejection_status,429);assert.equal(rejected.state,'closed');
    assert.equal((await db!.query('SELECT cash_pool_nano FROM hosted_helper_sessions WHERE session_id=$1',[rejected.id])).rows[0].cash_pool_nano,'0');
    f.voice.rejection=false;
    const session=await hosted.create(f.account,randomUUID(),'v=0\r\npaid-runtime','es-ES',undefined,60_000);
    const request=input();await gateway.request(f.account,session.sessionID,request);await f.invariant();
    await assert.rejects(runtime.query('UPDATE wallets SET cash_provenance_verified=true'),/permission denied/);
    await assert.rejects(runtime.query("UPDATE hosted_sessions SET funding_mode='legacy'"),/immutable/);
    await assert.rejects(runtime.query('UPDATE hosted_sessions SET limit_ms=120000'),/immutable/);
    await assert.rejects(runtime.query('UPDATE hosted_sessions SET account_id=$1',[randomUUID()]),/immutable/);
    await assert.rejects(runtime.query('UPDATE hosted_helper_requests SET cash_reservation_id=NULL'),/permission denied/);
    await assert.rejects(runtime.query('UPDATE hosted_helper_sessions SET cash_funded=false'),/permission denied/);
    await f.emit(session,15,true);await f.expire(session.sessionID,gateway);await f.invariant();
  }finally {await hosted?.stop();await runtime.end();await f.close();await db!.query(`DROP OWNED BY ${role};DROP ROLE ${role}`);}
});
integration('a legacy cash experiment quarantines only its owner before a later public paid deployment',async()=>{
  const f=await fixture();let legacy:HostedVoice|undefined;
  const other=randomUUID();await db!.query('INSERT INTO accounts(id) VALUES($1)',[other]);
  await db!.query('INSERT INTO wallets(account_id) VALUES($1)',[other]);
  try {
    await f.controller.stop();
    await transaction(db!,sql=>appendEntry(sql,f.account,'legacy-sandbox','purchase',1_000_000_000n,0n,null,1_000_000_000n));
    legacy=new HostedVoice(db!,f.voice,{accountAllowlist:new Set([f.account]),lifetimeFundingCapNano:2_000_000_000n,billingUnit:'nanoUSD'});
    await legacy.start();
    const session=await legacy.create(f.account,randomUUID(),'v=0\r\nlegacy','es-ES');
    assert.equal((await f.balance()).cashProvenanceVerified,false);
    assert.equal((await paidAIBalance(db!,other)).cashProvenanceVerified,true);
    await f.emit(session,120,true);await legacy.stop();legacy=undefined;
    // Legacy behavior remains available in its restricted mode; public mode cannot silently spend its derived balance.
    await f.controller.start();await assert.rejects(f.create(),{code:'cash_balance_reconciliation_required'});
    assert.equal(f.voice.creates,1);assert.equal((await f.balance()).availableNanoUSD,'0');
  }finally{await legacy?.stop();await f.close();}
});

integration('a pending guest transfer cannot freeze the verified member paid wallet or change its charge',async()=>{
 const f=await fixture();try{
  const guest=randomUUID(),token=randomBytes(32).toString('base64url');
  await db!.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)',[guest]);
  await db!.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')",[randomUUID(),guest,digest(token)]);
  await transaction(db!,sql=>appendMinuteEntry(sql,guest,`guest-seed:${guest}`,'welcome',600000,0));
  await db!.query('INSERT INTO minute_welcome_claims(proof_reference,account_id,allowance_ms) VALUES($1,$2,600000)',[`test:${guest}`,guest]);
  const hold=await reserveMinutes(db!,guest,'guest-unsettled',600000);
  const before=await f.balance();await linkGuestMinutes(db!,f.account,token,true);
  assert.deepEqual(await f.balance(),before);
  const paid=await f.create(60000);assert.equal(paid.fundingMode,'ai-value');
  assert.equal((await linkGuestMinutes(db!,f.account,undefined,true,guest)).pending,true);
  await f.emit(paid,30,true);await f.expire(paid.sessionID);
  assert.equal((await f.balance()).balanceNanoUSD,'1975000000');
  assert.equal((await db!.query('SELECT reserved_ms FROM minute_wallets WHERE account_id=$1',[guest])).rows[0].reserved_ms,'600000');
  await finishMinuteReservation(db!,hold,600000);await finalizeDeferredGuestLinks(db!);
  assert.equal((await f.balance()).balanceNanoUSD,'1975000000');await f.invariant();
 }finally{await f.close();}
});
