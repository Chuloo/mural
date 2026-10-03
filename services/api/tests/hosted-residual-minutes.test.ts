import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry } from '../src/ledger.js';
import { appendMinuteEntry } from '../src/minutes.js';
import { conversationBalance } from '../src/conversation-balance.js';
import { HostedVoice } from '../src/hosted-voice.js';
import { HostedHelpers } from '../src/hosted-helpers.js';
import type { LiveProvider, VoiceUsage } from '../src/live-provider.js';
import type { PurchaseEnvironment } from '../src/minute-purchases.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Dedicated test database required.');
const schema=`residual_voice_${randomUUID().replaceAll('-','')}`,url=databaseURL ? new URL(databaseURL) : undefined;
url?.searchParams.set('options',`-c search_path=${schema}`);
const db=url ? connectDatabase(url.toString()) : undefined;
before(async()=> {if(db) {await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);}});
beforeEach(async()=> {if(db) await db.query('TRUNCATE accounts CASCADE');});
after(async()=> {if(db) {try {await db.query(`DROP SCHEMA ${schema} CASCADE`);} finally {await db.end();}}});
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!db && 'Set TEST_DATABASE_URL.'},fn);

class Voice implements LiveProvider {
  creates=0;attaches=0;hangups=0;
  listeners=new Map<string,(usage:VoiceUsage)=>void>();
  async create() {this.creates++;return {sessionID:`live_residual_${this.creates}`,sdp:'v=0\r\nanswer'};}
  async attach(id:string,listener:(usage:VoiceUsage)=>void) {
    this.attaches++;this.listeners.set(id,listener);
    return {closeSession:()=>{},disconnect:()=>{this.listeners.delete(id);}};
  }
  async hangup() {this.hangups++;}
  finish(id:string) {this.listeners.get(id)!({type:'session.closed',usage:{seconds:0}});}
}

async function fixture(options:{free:number;cash?:bigint;sandboxCash?:bigint;guest?:boolean;paid?:boolean;public?:boolean;sandboxFree?:number}):Promise<{
  account:string;voice:Voice;controller:HostedVoice;create:(environment?:PurchaseEnvironment)=>ReturnType<HostedVoice['create']>;
  balance:(environment?:PurchaseEnvironment)=>ReturnType<typeof conversationBalance>;assertUnfunded:()=>Promise<void>;finish:(session:{sessionID:string;providerSessionID:string})=>Promise<void>;close:()=>Promise<void>;
}> {
  const account=randomUUID(),isPublic=options.public!==false,paid=options.paid!==false && isPublic;
  await transaction(db!,async sql=>{
    await sql.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)',[account,options.guest??false]);
    if(!options.guest) {
      await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
      await appendEntry(sql,account,`cash:${account}`,'purchase',options.cash??0n,0n);
      if(options.sandboxCash) await appendEntry(sql,account,`test-cash:${account}`,'purchase',options.sandboxCash,0n,null,options.sandboxCash);
    }
    await appendMinuteEntry(sql,account,`free:${account}`,'gift',options.free,0,'funded');
    if(options.sandboxFree) await appendMinuteEntry(sql,account,`test-free:${account}`,'gift',options.sandboxFree,0,'sandbox');
  });
  const voice=new Voice();
  const helpers=isPublic ? new HostedHelpers(db!,{send:async()=>{throw new Error('No helper provider call expected.');}}, {
    accountAllowlist:new Set(),aggregateFundingCapNano:0n,publicMinuteAccess:true,publicPaidAccess:paid,
    helperBudgetNanoPerMinute:50_000_000n,maxRequestsPerMinute:6,maxSearchesPerSession:1,maxConcurrentPerSession:2,maxConcurrentGlobal:4,
    postSessionMilliseconds:0,inputFramingTokenAllowance:4096,searchInputTokenAllowance:1_050_000,timeoutMilliseconds:1000
  }) : undefined;
  const controller=new HostedVoice(db!,voice,{accountAllowlist:new Set(isPublic?[]:[account]),lifetimeFundingCapNano:isPublic?0n:2_000_000_000n,
    billingUnit:'milliseconds',publicMinuteAccess:isPublic,publicPaidAccess:paid,helpers:helpers ? {
      paidFundingPolicy:helpers.paidFundingPolicy,closeCashBudget:helpers.closeCashBudget.bind(helpers),reserveSessionBudget:helpers.reserveSessionBudget.bind(helpers)
    } : undefined});
  await controller.start();
  const policy={enabled:paid,estimatedNanoUSDPerMinute:controller.estimatedNanoUSDPerMinute,minimumSessionNanoUSD:controller.minimumPaidSessionNanoUSD};
  return {account,voice,controller,
    create:environment=>controller.create(account,randomUUID(),'v=0\r\noffer','es-ES',undefined,60_000,environment),
    balance:environment=>conversationBalance(db!,account,isPublic,policy,environment),
    async assertUnfunded() {
      assert.equal(voice.creates,0);assert.equal(voice.attaches,0);assert.equal(voice.hangups,0);
      for(const table of ['hosted_sessions','minute_reservations','reservations','hosted_helper_sessions'])
        assert.equal((await db!.query(`SELECT count(*) FROM ${table}`)).rows[0].count,'0',table);
      assert.equal((await db!.query("SELECT count(*) FROM minute_entries WHERE kind<>'gift'")).rows[0].count,'0');
      const minuteWallet=(await db!.query('SELECT balance_ms,reserved_ms FROM minute_wallets WHERE account_id=$1',[account])).rows[0];
      assert.equal(Number(minuteWallet.balance_ms),options.free+(options.sandboxFree??0));assert.equal(Number(minuteWallet.reserved_ms),0);
      if(!options.guest) assert.equal((await db!.query('SELECT reserved_nano FROM wallets WHERE account_id=$1',[account])).rows[0].reserved_nano,'0');
    },
    async finish(session) {
      voice.finish(session.providerSessionID);
      const deadline=Date.now()+3000;
      while((await db!.query('SELECT state FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0].state!=='closed') {
        if(Date.now()>deadline) throw new Error('Final usage was not settled.');
        await new Promise(resolve=>setTimeout(resolve,5));
      }
    },
    close:()=>controller.stop()
  };
}

for(const free of [1,14_999]) for(const paid of [false,true]) integration(`public free ${free}ms without funded cash rejects before provider contact (paid ${paid})`,async()=>{
  const f=await fixture({free,paid});try {
    const balance=await f.balance();
    assert.equal(balance.availableMilliseconds,free);assert.equal(balance.presentation.freeAvailableMilliseconds,free);
    assert.equal(balance.presentation.totalDisplayMilliseconds,free);assert.equal(balance.presentation.availabilityReason,'insufficient_remaining_time');
    await assert.rejects(f.create(),{code:paid?'insufficient_credit':'insufficient_minutes',status:402});
    await f.assertUnfunded();
  }finally{await f.close();}
});

for(const free of [1,14_999]) integration(`guest with ${free}ms preserves its credit and existing sign-in fallback`,async()=>{
  const f=await fixture({free,guest:true});try {
    const balance=await f.balance();assert.equal(balance.availableMilliseconds,free);
    assert.equal(balance.presentation.availabilityReason,'insufficient_remaining_time');assert.ok(!('paid' in balance));
    await assert.rejects(f.create(),{code:'sign_in_required',status:401});await f.assertUnfunded();
  }finally{await f.close();}
});

for(const guest of [false,true]) integration(`public free 15000ms admits and settles the minimum for ${guest?'guest':'member'}`,async()=>{
  const f=await fixture({free:15_000,guest});try {
    assert.equal((await f.balance()).presentation.availabilityReason,'ready');
    const session=await f.create();assert.equal(session.fundingMode,'minutes');assert.equal(session.reservedMilliseconds,15_000);
    assert.equal(f.voice.creates,1);assert.equal((await db!.query('SELECT count(*) FROM reservations')).rows[0].count,'0');
    await f.finish(session);
    const balance=await f.balance();assert.equal(balance.balanceMilliseconds,0);assert.equal(balance.reservedMilliseconds,0);
    assert.equal(balance.presentation.availabilityReason,'insufficient_remaining_time');
  }finally{await f.close();}
});

for(const environment of [undefined,'live'] as const) for(const free of [1,14_999]) integration(`public ${environment??'default'} admission uses paid cash and retains ${free}ms free`,async()=>{
  const f=await fixture({free,cash:2_000_000_000n,sandboxCash:3_000_000_000n});try {
    assert.equal((await f.balance(environment)).presentation.availabilityReason,'ready');
    const session=await f.create(environment);assert.equal(session.fundingMode,'ai-value');assert.equal(session.limitMilliseconds,60_000);
    const stored=(await db!.query('SELECT funding_environment,minute_reservation_id FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0];
    assert.equal(stored.funding_environment,'live');assert.equal(stored.minute_reservation_id,null);
    assert.equal((await db!.query('SELECT count(*) FROM minute_reservations')).rows[0].count,'0');
    await f.finish(session);
    const balance=await f.balance(environment);assert.equal(balance.balanceMilliseconds,free);assert.equal(balance.reservedMilliseconds,0);
    const wallet=(await db!.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0];
    assert.equal(wallet.balance_nano,'4987500000');assert.equal(wallet.sandbox_balance_nano,'3000000000');
  }finally{await f.close();}
});

integration('sandbox cash and sandbox minute remainder cannot fund a public live residual',async()=>{
  const f=await fixture({free:1,sandboxFree:60_000,sandboxCash:2_000_000_000n});try {
    const balance=await f.balance('live');assert.equal(balance.availableMilliseconds,1);
    assert.equal(balance.presentation.availabilityReason,'insufficient_remaining_time');
    await assert.rejects(f.create('live'),{code:'insufficient_credit',status:402});await f.assertUnfunded();
  }finally{await f.close();}
});

for(const free of [1,15_000]) integration(`explicit public sandbox uses only paid test funds with ${free}ms free credit`,async()=>{
  const f=await fixture({free,cash:2_000_000_000n,sandboxCash:3_000_000_000n});try {
    const balance=await f.balance('test');assert.equal(balance.availableMilliseconds,0);assert.equal(balance.presentation.availabilityReason,'ready');
    const session=await f.create('test');assert.equal(session.fundingMode,'ai-value');
    assert.equal((await db!.query('SELECT funding_environment FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0].funding_environment,'test');
    await f.finish(session);
    const wallet=(await db!.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0];
    assert.equal(wallet.balance_nano,'4987500000');assert.equal(wallet.sandbox_balance_nano,'2987500000');
    assert.equal((await f.balance('live')).balanceMilliseconds,free);
    assert.equal((await db!.query('SELECT count(*) FROM minute_reservations')).rows[0].count,'0');
  }finally{await f.close();}
});

integration('explicit public sandbox rejects live cash and free time when test funds are empty',async()=>{
  const f=await fixture({free:15_000,cash:2_000_000_000n});try {
    assert.equal((await f.balance('test')).presentation.availabilityReason,'insufficient_remaining_time');
    await assert.rejects(f.create('test'),{code:'insufficient_credit',status:402});await f.assertUnfunded();
  }finally{await f.close();}
});

for(const environment of [undefined,'live'] as const) integration(`unreconciled legacy free wallet still rejects public ${environment??'default'} paid fallback`,async()=>{
  const f=await fixture({free:1,cash:2_000_000_000n});try {
    await db!.query('UPDATE minute_wallets SET sandbox_reconciled=false WHERE account_id=$1',[f.account]);
    await assert.rejects(f.create(environment),{code:'minute_balance_reconciliation_required',status:409});await f.assertUnfunded();
  }finally{await f.close();}
});

integration('restricted legacy minute admission keeps its existing sub-minimum behavior',async()=>{
  const f=await fixture({free:1,guest:true,public:false});try {
    assert.equal((await f.balance()).presentation.availabilityReason,'ready');
    const session=await f.create();assert.equal(session.fundingMode,'minutes');assert.equal(session.reservedMilliseconds,1);assert.equal(f.voice.creates,1);
    await f.finish(session);assert.equal((await f.balance()).balanceMilliseconds,0);
  }finally{await f.close();}
});
