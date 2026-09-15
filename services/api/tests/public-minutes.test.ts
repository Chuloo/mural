import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { createApp } from '../src/app.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { createChallenge, exchangeIdentity } from '../src/auth.js';
import { finalizeDeferredGuestLinks, InstallationGuestMinuteAttestor, linkGuestMinutes } from '../src/guest-minutes.js';
import { appendMinuteEntry, finishMinuteReservation, minuteBalance, reserveMinutes } from '../src/minutes.js';
import { reconcileSandboxMinutes, updateWelcomePolicy, welcomePolicy } from '../src/minutes-admin.js';
import { updateWelcomeFunding, welcomeFunding } from '../src/welcome-funding.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { HostedHelpers, HOSTED_HELPER_MODEL } from '../src/hosted-helpers.js';
import { MinutePurchases, type MinuteProduct, type VerifiedMinutePurchase } from '../src/minute-purchases.js';
import type { VoiceUsage } from '../src/live-provider.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL && 'Set TEST_DATABASE_URL.'},fn);
const install=()=>randomBytes(32).toString('base64url');
async function fixture(daily=150,total=300) {
  const schema=`public_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);
  url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString()); await db.query(`CREATE SCHEMA ${schema}`); await migrate(db);
  await updateWelcomeFunding(db,{...await welcomeFunding(db),reserveCostPerMinuteMinor:15,dailyBudgetMinor:daily,lifetimeBudgetMinor:total},'test-operator','Synthetic public grant budget');
  await updateWelcomePolicy(db,{...await welcomePolicy(db),welcomeEnabled:true,welcomeMinutes:10,
    dailyWelcomeBudgetMinutes:0,lifetimeWelcomeBudgetMinutes:0},'test-operator','USD is the only grant ceiling');
  const listeners=new Map<string,(event:VoiceUsage)=>void>(); let creates=0,failCreate=false,helperCalls=0;
  const provider={
    async create() { creates++; if(failCreate)throw new Error('Synthetic uncertain create'); return {sessionID:`live_${randomUUID()}`,sdp:'v=0\r\nsynthetic'}; },
    async attach(id:string,onUsage:(event:VoiceUsage)=>void) { listeners.set(id,onUsage); return {closeSession(){},disconnect(){listeners.delete(id);}}; },
    async hangup() {},
  };
  const helpers=new HostedHelpers(db,{async send(){helperCalls++;return {id:`resp_${randomUUID().replaceAll('-','')}`,model:HOSTED_HELPER_MODEL,
    service_tier:'default',status:'completed',output:[{type:'message',content:[{type:'output_text',text:'Synthetic meaning.',annotations:[]}]}],
    usage:{input_tokens:100,input_tokens_details:{cached_tokens:0,cache_write_tokens:0},output_tokens:30}};}},{
    publicMinuteAccess:true,accountAllowlist:new Set(),aggregateFundingCapNano:0n,helperBudgetNanoPerMinute:50_000_000n,
    maxRequestsPerMinute:6,maxSearchesPerSession:0,maxConcurrentPerSession:2,maxConcurrentGlobal:10,postSessionMilliseconds:120_000,
    inputFramingTokenAllowance:4096,searchInputTokenAllowance:1_050_000,timeoutMilliseconds:1000,
  });
  const hosted=new HostedVoice(db,provider,{publicMinuteAccess:true,billingUnit:'milliseconds',accountAllowlist:new Set(),lifetimeFundingCapNano:0n,helpers});
  await hosted.start();
  const proxy={hmacKey:'c'.repeat(64),proxyToken:'d'.repeat(64),allowLocalLoopback:false};
  const headers={'x-mural-client-ip':'192.0.2.41','x-mural-proxy-token':proxy.proxyToken};
  const app=createApp({db,auth:{googleClientID:'synthetic-client'},hosted,hostedHelpers:helpers,
    guestMinuteAttestor:new InstallationGuestMinuteAttestor(),accounts:{admission:new AuthAdmission(db,proxy)}});
  return {db,hosted,helpers,app,headers,get creates(){return creates;},get helperCalls(){return helperCalls;},set failCreate(value:boolean){failCreate=value;},
    async guest(token=install()) {const result=await app.inject({method:'POST',url:'/v1/guest/minutes',headers,payload:{installationToken:token}});
      assert.equal(result.statusCode,200); return result.json();},
    async member() {const challenge=await createChallenge(db);return exchangeIdentity(db,'google','synthetic-id-token',challenge.challengeID,{},async()=>
      ({provider:'google',subject:randomUUID(),email:'synthetic@example.test'}));},
    async gift(account:string,amount:number) {await transaction(db,sql=>appendMinuteEntry(sql,account,`gift:${randomUUID()}`,'gift',amount,0));},
    async finish(account:string,sessionID:string,seconds:number) {
      const row=(await db.query('SELECT provider_session_id FROM hosted_sessions WHERE id=$1',[sessionID])).rows[0];
      listeners.get(row.provider_session_id)!({type:'session.closed',usage:{seconds}});
      const until=Date.now()+2000;
      while((await hosted.status(account,sessionID)).state!=='closed') {if(Date.now()>until)throw new Error('Settlement timeout');await new Promise(r=>setTimeout(r,5));}
    },
    async purchase(account:string,environment:'live'|'test',minutes=30) {
      const product:MinuteProduct={provider:'stripe',environment,merchant:'acct_synthetic',sku:'synthetic-minutes',providerProduct:'price_synthetic',minutes,currency:'usd',totalMinor:997};
      let proof:VerifiedMinutePurchase;
      const service=new MinutePurchases(db,{catalog:[product],verifiers:[{provider:'stripe',environment,merchant:product.merchant,async verify(){return proof;}}],salesEnabled:true});
      const order=await service.createOrder(account,'stripe',product.sku,randomUUID());
      proof={provider:'stripe',environment,merchant:product.merchant,orderID:order.orderID,transactionID:randomUUID(),eventID:randomUUID(),
        providerProduct:product.providerProduct,quantity:1,currency:product.currency,totalMinor:997,state:'purchased',refundedMinor:0};
      await service.reconcile('stripe',{});
      return {async refund(){proof={...proof,refundedMinor:997,eventID:randomUUID()};return service.reconcile('stripe',{});}};
    },
    async cleanup(){await app.close();await hosted.stop();await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}
  };
}

test('installation proof stores only a scoped hash and rejects identity overrides',async()=>{
  const proof=new InstallationGuestMinuteAttestor(),token=install();
  const first=await proof.verify({installationToken:token});
  assert.deepEqual(await proof.verify({installationToken:token}),first);assert.ok(!first.deviceReference.includes(token));
  for(const bad of [{},null,{installationToken:'a'.repeat(42)},{installationToken:token,accountID:randomUUID()},{installationToken:'!'.repeat(43)}])
    await assert.rejects(proof.verify(bad),{code:'invalid_trial_proof'});
});

integration('USD budget exhaustion is a normal response and signup remains available with no hidden minute cap',async()=>{
  const f=await fixture();
  try {
    const tokens=[install(),install()];const results=await Promise.all(tokens.map(t=>f.guest(t)));
    assert.equal(results.filter(r=>r.available).length,1);
    assert.deepEqual(results.find(r=>!r.available),{available:false,reason:'temporarily_unavailable',remainingMilliseconds:0});
    assert.equal((await f.db.query('SELECT count(*) FROM accounts')).rows[0].count,'1');
    assert.equal((await f.db.query('SELECT sum(reserve_cost_minor) FROM welcome_funding_allocations')).rows[0].sum,'150');
    const member=await f.member();await f.purchase(member.accountID,'live');
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,1_800_000);
    assert.equal((await f.guest()).available,false);
  }finally{await f.cleanup();}
});

integration('installation renewal preserves spent allowance after token expiry and trial shutdown',async()=>{
  const f=await fixture();
  try {
    const installation=install(),guest=await f.guest(installation);
    const live=await f.hosted.create(guest.guestID,randomUUID(),'v=0','es-ES');await f.finish(guest.guestID,live.sessionID,0);
    await f.db.query("UPDATE auth_sessions SET expires_at=now()-interval '1 second' WHERE account_id=$1",[guest.guestID]);
    await updateWelcomePolicy(f.db,{...await welcomePolicy(f.db),welcomeEnabled:false},'test-operator','Pause new grants');
    const renewed=await f.guest(installation);
    assert.equal(renewed.guestID,guest.guestID);assert.notEqual(renewed.accessToken,guest.accessToken);
    assert.equal(renewed.remainingMilliseconds,585_000);assert.equal(renewed.resumed,true);
    assert.equal((await f.guest()).available,false);
    const capability=await f.app.inject({url:'/v1/live/capabilities',headers:{...f.headers,authorization:`Bearer ${renewed.accessToken}`}});
    assert.equal(capability.json().hostedMinutes,true);
    const account=await f.app.inject({url:'/v1/account',headers:{...f.headers,authorization:`Bearer ${renewed.accessToken}`}});
    assert.equal(account.statusCode,401);
  }finally{await f.cleanup();}
});

integration('purchased minutes add to remaining free time and guest linking preserves the exact allowance once',async()=>{
  const f=await fixture();
  try {
    const installation=install(),guest=await f.guest(installation),member=await f.member();
    await f.purchase(member.accountID,'live');
    const live=await f.hosted.create(guest.guestID,randomUUID(),'v=0','fr-FR');
    await assert.rejects(linkGuestMinutes(f.db,member.accountID,guest.accessToken),{code:'finish_guest_conversation_first'});
    await f.finish(guest.guestID,live.sessionID,37.123);
    const headers={...f.headers,authorization:`Bearer ${member.accessToken}`};
    for(let retry=0;retry<2;retry++) {
      const linked=await f.app.inject({method:'POST',url:'/v1/minutes/link-guest',headers,payload:{guestAccessToken:guest.accessToken}});
      assert.equal(linked.statusCode,200);assert.equal(linked.json().transferredMilliseconds,562_877);assert.equal(linked.json().alreadyLinked,retry===1);
    }
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,2_362_877);
    assert.deepEqual(await f.guest(installation),{available:false,reason:'sign_in_required',remainingMilliseconds:0});
    assert.equal((await f.db.query('SELECT sum(allowance_ms) FROM welcome_funding_allocations')).rows[0].sum,'600000');
  }finally{await f.cleanup();}
});

integration('an existing member login retires a duplicate guest without adding another trial or blocking its paid balance',async()=>{
  const f=await fixture(300,300);
  try {
    const member=await f.member(),original=await f.guest();
    await linkGuestMinutes(f.db,member.accountID,original.accessToken);await f.purchase(member.accountID,'live');
    const duplicate=await f.guest();
    const live=await f.hosted.create(duplicate.guestID,randomUUID(),'v=0','es-ES');
    await assert.rejects(linkGuestMinutes(f.db,member.accountID,duplicate.accessToken),{code:'finish_guest_conversation_first'});
    await f.finish(duplicate.guestID,live.sessionID,15);
    const before=await minuteBalance(f.db,member.accountID,true);
    const linked=await linkGuestMinutes(f.db,member.accountID,duplicate.accessToken);
    assert.deepEqual(linked,{transferredMilliseconds:0,alreadyLinked:false,outcome:'member_trial_already_claimed'});
    assert.deepEqual(await linkGuestMinutes(f.db,member.accountID,duplicate.accessToken),{...linked,alreadyLinked:true});
    assert.deepEqual(await minuteBalance(f.db,member.accountID,true),before);
    const own=await f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES');await f.finish(member.accountID,own.sessionID,15);
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,before.availableMilliseconds-15_000);
  }finally{await f.cleanup();}
});

integration('public calls and earned teaching continue across old test caps with an empty allowlist',async()=>{
  const f=await fixture();
  try {
    const member=await f.member();await f.gift(member.accountID,1_200_000);
    for(let attempt=0;attempt<2;attempt++) {
      const live=await f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES');
      assert.equal(live.minimumChargeMilliseconds,15_000);
      const meaning=await f.helpers.request(member.accountID,live.sessionID,{requestID:randomUUID(),purpose:'meaning',instructions:'Translate.',input:'Hola.'});
      assert.equal(meaning.text,'Synthetic meaning.');
      await f.finish(member.accountID,live.sessionID,600);
    }
    assert.equal(f.creates,2);assert.equal(f.helperCalls,2);assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,0);
    await assert.rejects(f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES'),{code:'insufficient_minutes'});
  }finally{await f.cleanup();}
});

integration('an uncertain guest session blocks its owner while another funded account can converse',async()=>{
  const f=await fixture();
  try {
    const guest=await f.guest();f.failCreate=true;
    await assert.rejects(f.hosted.create(guest.guestID,randomUUID(),'v=0','es-ES'),{code:'provider_session_unconfirmed'});
    f.failCreate=false;const member=await f.member();await f.gift(member.accountID,60_000);
    await assert.rejects(f.hosted.create(guest.guestID,randomUUID(),'v=0','es-ES'),{code:'live_session_unresolved'});
    const live=await f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES');await f.finish(member.accountID,live.sessionID,15);
    assert.equal((await minuteBalance(f.db,guest.guestID,true)).reservedMilliseconds,600_000);
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,45_000);
  }finally{await f.cleanup();}
});

integration('sandbox consumption and refunds cannot hide or seize subsequently funded minutes',async()=>{
  const f=await fixture();
  try {
    const member=await f.member(),fake=await f.purchase(member.accountID,'test');
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,0);
    await assert.rejects(f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES'),{code:'insufficient_minutes'});assert.equal(f.creates,0);
    const test=await reserveMinutes(f.db,member.accountID,randomUUID(),1_800_000);await finishMinuteReservation(f.db,test,1_500_000);
    await f.purchase(member.accountID,'live');await fake.refund();
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,1_800_000);
    assert.equal((await f.db.query('SELECT sandbox_balance_ms FROM minute_wallets WHERE account_id=$1',[member.accountID])).rows[0].sandbox_balance_ms,'0');
    const live=await f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES');await f.finish(member.accountID,live.sessionID,60);
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,1_740_000);
    const journal=(await f.db.query('SELECT sum(sandbox_delta_ms) FROM minute_entries WHERE account_id=$1',[member.accountID])).rows[0];
    assert.equal(journal.sum,'0');
  }finally{await f.cleanup();}
});

integration('public settlement preserves sandbox balance and live refunds retain their cutoff',async()=>{
  const f=await fixture();
  try {
    const member=await f.member();await f.purchase(member.accountID,'test');const funded=await f.purchase(member.accountID,'live');
    const live=await f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES');await funded.refund();await f.hosted.tick();
    assert.equal((await f.hosted.status(member.accountID,live.sessionID)).state,'closing');
    await f.finish(member.accountID,live.sessionID,60);
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,0);
    assert.equal((await f.db.query('SELECT sandbox_balance_ms FROM minute_wallets WHERE account_id=$1',[member.accountID])).rows[0].sandbox_balance_ms,'1800000');
    await assert.rejects(f.db.query('UPDATE hosted_sessions SET public_minutes=false WHERE id=$1',[live.sessionID]),/immutable/);
  }finally{await f.cleanup();}
});

integration('ambiguous legacy sandbox balances require audited reconciliation without changing the total',async()=>{
  const f=await fixture();
  try {
    const member=await f.member();await f.gift(member.accountID,60_000);
    await f.db.query('UPDATE minute_wallets SET sandbox_reconciled=false WHERE account_id=$1',[member.accountID]);
    await assert.rejects(f.hosted.create(member.accountID,randomUUID(),'v=0','es-ES'),{code:'minute_balance_reconciliation_required'});
    await reconcileSandboxMinutes(f.db,{accountID:member.accountID,expectedBalanceMilliseconds:60_000,sandboxMilliseconds:0,actor:'test-operator',reason:'Reviewed fully spent historical test grant'});
    assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,60_000);
    await assert.rejects(f.db.query('DELETE FROM minute_sandbox_reconciliations'),/immutable/);
  }finally{await f.cleanup();}
});

integration('opt-in deferred login permits member gifts while guest closure remains pending and rejects new guest calls',async()=>{
 const f=await fixture(300,300);try{
  const installation=install(),guest=await f.guest(installation),live=await f.hosted.create(guest.guestID,randomUUID(),'v=0','es-ES');
  const member=await f.member();await f.gift(member.accountID,1800000);
  const headers={...f.headers,authorization:`Bearer ${member.accessToken}`};
  const url='/v1/minutes/link-guest';
  const legacy=await f.app.inject({method:'POST',url,headers,payload:{guestAccessToken:guest.accessToken}});
  assert.equal(legacy.statusCode,409);
  for(const payload of [{deferPending:'yes',guestAccessToken:guest.accessToken},{deferPending:true,accountID:member.accountID}])
   assert.equal((await f.app.inject({method:'POST',url,headers,payload})).statusCode,400);
  const accepted=await f.app.inject({method:'POST',url,headers,payload:{guestAccessToken:guest.accessToken,deferPending:true}});
  assert.equal(accepted.statusCode,200);assert.equal(accepted.json().pending,true);
  await assert.rejects(f.hosted.create(guest.guestID,randomUUID(),'v=0','es-ES'),{code:'sign_in_to_continue'});
  assert.equal((await f.guest(installation)).reason,'sign_in_required');
  const guestRecord=(await f.db.query('SELECT close_requested_at,provider_cost_nano,reserved_ms FROM hosted_sessions WHERE id=$1',[live.sessionID])).rows[0];
  assert.ok(guestRecord.close_requested_at);assert.equal(guestRecord.provider_cost_nano,null);assert.equal(guestRecord.reserved_ms,'600000');
  const own=await f.hosted.create(member.accountID,randomUUID(),'v=0','fr-FR');
  assert.equal((await f.app.inject({method:'POST',url,headers,payload:{deferPending:true,guestAccountID:guest.guestID}})).json().pending,true);
  await f.finish(guest.guestID,live.sessionID,37.123);
  // The member's separate active reservation must not block the old guest transfer.
  await finalizeDeferredGuestLinks(f.db);
  const done=await f.app.inject({method:'POST',url,headers,payload:{deferPending:true,guestAccountID:guest.guestID}});
  assert.equal(done.statusCode,200);assert.equal(done.json().pending,false);assert.equal(done.json().transferredMilliseconds,562877);
  await f.finish(member.accountID,own.sessionID,15);
  assert.equal((await minuteBalance(f.db,member.accountID,true)).availableMilliseconds,2347877);
  assert.equal((await f.db.query('SELECT count(*) FROM welcome_funding_allocations')).rows[0].count,'1');
 }finally{await f.cleanup();}
});
