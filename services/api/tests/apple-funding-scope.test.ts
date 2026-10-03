import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import {Environment,type AppTransaction,type JWSTransactionDecodedPayload,type ResponseBodyV2DecodedPayload} from '@apple/app-store-server-library';
import {connectDatabase,transaction} from '../src/db.js';
import {migrate} from '../src/migrate.js';
import {AppleMinuteProvider,type AppleMinuteTransport} from '../src/apple-minute-provider.js';
import {ApplePurchaseScopes,appleSignedEnvironment,appleAppTransactionHeader} from '../src/apple-purchase-scope.js';
import {AIValuePurchases,makeAppleAIValueProduct,PurchaseFulfillmentRouter} from '../src/ai-value-purchases.js';
import {MinutePurchases,type PurchaseEnvironment} from '../src/minute-purchases.js';
import {MinuteReceiptVault,MinuteDeliveryWorker} from '../src/minute-provider-delivery.js';
import {createApp} from '../src/app.js';
import {AuthAdmission} from '../src/auth-admission.js';
import {digest,deleteAccount} from '../src/auth.js';
import {HostedVoice} from '../src/hosted-voice.js';
import {HostedHelpers,HOSTED_HELPER_MODEL} from '../src/hosted-helpers.js';
import type {LiveProvider,VoiceUsage} from '../src/live-provider.js';
import {paidAIBalance,appendEntry,reservePaidInTransaction,settlePaidInTransaction} from '../src/ledger.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if(databaseURL && !new URL(databaseURL).pathname.endsWith('_test'))throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
const signed=(payload:unknown)=>`e30.${Buffer.from(JSON.stringify(payload)).toString('base64url')}.verified`;
const mode=(environment:PurchaseEnvironment)=>environment==='test'?Environment.SANDBOX:Environment.PRODUCTION;
const proof=(environment:PurchaseEnvironment)=>signed({bundleId:'chat.mural.ios',appAppleId:6816001011,receiptType:mode(environment),receiptCreationDate:1700000000000});
function product(environment:PurchaseEnvironment){return makeAppleAIValueProduct({provider:'apple',environment,merchant:'chat.mural.ios',sku:'small-us-v1',
  providerProduct:'chat.mural.ios.minutes.small.v1',aiValueMinor:369,policyVersion:1,serviceFeeBasisPoints:1500,
  estimate:{nanoUSDPerMinute:'100000000',rateVersion:'test-estimate'},apple:{storefront:'USA',currency:'usd',currencyExponent:2,
    unitTotalMinor:700,scheduleVersion:'test-schedule',commissionBasisPoints:3000,taxMinor:0,commissionMinor:210,
    proceedsMinor:490,proceedsUSDMinor:490,residualUSDMinor:65}});}
class Apple implements AppleMinuteTransport {
  readonly transactions=new Map<string,JWSTransactionDecodedPayload>();
  readonly notifications=new Map<string,ResponseBodyV2DecodedPayload>();
  scopeVerifications=0;
  constructor(readonly environment:PurchaseEnvironment){}
  async appTransaction(jws:string):Promise<AppTransaction>{
    this.scopeVerifications++;if(jws!==proof(this.environment))throw new Error('Invalid Apple signature/app/scope');
    return JSON.parse(Buffer.from(jws.split('.')[1]!,'base64url').toString());
  }
  async transaction(jws:string){const value=[...this.transactions.values()].find(v=>signed(v)===jws);if(!value)throw new Error('Invalid signature');return structuredClone(value);}
  async latest(id:string){const value=this.transactions.get(id);if(!value)throw new Error('Foreign transaction');return signed(value);}
  async notification(jws:string){const value=this.notifications.get(jws);if(!value)throw new Error('Invalid notification signature');return structuredClone(value);}
  async history(){return {notifications:[]};}
  bind(orderID:string,quantity=1){const value:JWSTransactionDecodedPayload={bundleId:'chat.mural.ios',environment:mode(this.environment),type:'Consumable',
    inAppOwnershipType:'PURCHASED',transactionId:String(1000000+this.transactions.size),appAccountToken:orderID,
    productId:'chat.mural.ios.minutes.small.v1',quantity,currency:'USD',price:7000*quantity,storefront:'USA',purchaseDate:1700000000000,signedDate:1700000000001};
    this.transactions.set(value.transactionId!,value);return value;}
}
class Voice implements LiveProvider {
  creates=0;readonly listeners=new Map<string,(event:VoiceUsage)=>void>();
  async create(){this.creates++;return {sessionID:`live_scope_${this.creates}`,sdp:'v=0\r\nanswer'};}
  async attach(id:string,listener:(event:VoiceUsage)=>void){this.listeners.set(id,listener);return {closeSession:()=>{},disconnect:()=>{this.listeners.delete(id);}};}
  async hangup(){}
}
async function until(predicate:()=>Promise<boolean>){const deadline=Date.now()+3000;while(!await predicate()){if(Date.now()>deadline)throw new Error('Condition timed out');await new Promise(r=>setTimeout(r,5));}}
async function fixture(){
  const schema=`apple_scope_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  const vault=new MinuteReceiptVault(db,'test',new Map([['test',randomBytes(32)]]));
  const liveTransport=new Apple('live'),testTransport=new Apple('test');
  const config={bundleID:'chat.mural.ios',appAppleID:6816001011,signingKey:'unused',keyID:'TESTKEY123',issuerID:randomUUID(),rootCertificates:[],purchasesEnabled:true};
  const live=new AppleMinuteProvider(db,vault,{...config,environment:'live',allowLive:true},liveTransport);
  const sandbox=new AppleMinuteProvider(db,vault,{...config,environment:'test'},testTransport);
  const scopes=new ApplePurchaseScopes(live,sandbox),catalog=[product('live'),product('test')];
  const ai=new AIValuePurchases(db,{catalog,verifiers:[live,sandbox],salesEnabled:true,quantityEnabled:['apple']});
  const legacy=new MinutePurchases(db,{verifiers:[live]}),fulfillment=new PurchaseFulfillmentRouter(db,legacy,ai,[live,sandbox]);
  const account=randomUUID(),token=randomBytes(32).toString('base64url');
  await transaction(db,async sql=>{await sql.query('INSERT INTO accounts(id) VALUES($1)',[account]);await sql.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
    await sql.query("INSERT INTO identities(provider,subject,account_id) VALUES('google',$1,$2)",[randomUUID(),account]);
    await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')",[randomUUID(),account,digest(token)]);});
  const voice=new Voice(),helpers=new HostedHelpers(db,{send:async()=>({id:'resp_scope',model:HOSTED_HELPER_MODEL,service_tier:'default',status:'completed',
    output:[{type:'message',content:[{type:'output_text',text:'Hola.',annotations:[]}]}],usage:{input_tokens:100,input_tokens_details:{cached_tokens:20,cache_write_tokens:30},output_tokens:40}})},
    {accountAllowlist:new Set(),aggregateFundingCapNano:0n,publicMinuteAccess:true,publicPaidAccess:true,helperBudgetNanoPerMinute:50000000n,
      maxRequestsPerMinute:6,maxSearchesPerSession:0,maxConcurrentPerSession:2,maxConcurrentGlobal:4,postSessionMilliseconds:120000,
      inputFramingTokenAllowance:4096,searchInputTokenAllowance:1050000,timeoutMilliseconds:1000});
  const hosted=new HostedVoice(db,voice,{accountAllowlist:new Set(),lifetimeFundingCapNano:0n,billingUnit:'milliseconds',publicMinuteAccess:true,publicPaidAccess:true,helpers});
  await hosted.start();
  const proxy={hmacKey:'a'.repeat(64),proxyToken:'b'.repeat(64),allowLocalLoopback:false};
  const app=createApp({db,auth:{googleClientID:'synthetic-google-client'},accounts:{admission:new AuthAdmission(db,proxy)},hosted,hostedHelpers:helpers,
    minuteCommerce:{purchases:legacy,aiPurchases:ai,fulfillment,apple:live,appleSandbox:sandbox,appleScopes:scopes}});
  const headers=(environment?:PurchaseEnvironment)=>({authorization:`Bearer ${token}`,'x-mural-client-ip':'192.0.2.113','x-mural-proxy-token':proxy.proxyToken,
    ...(environment?{[appleAppTransactionHeader]:proof(environment)}:{})});
  return {db,schema,url:url.toString(),app,account,headers,live,sandbox,liveTransport,testTransport,scopes,ai,fulfillment,vault,hosted,helpers,voice,
    async order(environment:PurchaseEnvironment,quantity=1,key=randomUUID()){
      const response=await app.inject({method:'POST',url:'/v1/minutes/orders',headers:{...headers(environment),'idempotency-key':key},
        payload:{provider:'apple',sku:'small-us-v1',quantity,storefront:'USA',scheduleVersion:'test-schedule'}});
      assert.equal(response.statusCode,200,response.body);const order=response.json();
      const value=(environment==='test'?testTransport:liveTransport).bind(order.orderID,quantity);return {order,value};
    },
    async cleanup(){await app.close();await hosted.stop();await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}

test('unsigned environment hints never verify a funding scope; only verified matching Apple proof is cached',async()=>{
  const provider={environment:'test',appTransactionScope:async(jws:string)=>{if(jws!==proof('test'))throw new Error('signature invalid');return 'test';}} as any;
  const scopes=new ApplePurchaseScopes(provider);
  assert.equal(appleSignedEnvironment(proof('test'),'receiptType'),'test');
  assert.equal(await scopes.verify(proof('test')),'test');assert.equal(await scopes.verify(proof('test')),'test');
  for(const bad of [proof('test').replace('.verified','.forged'),signed({receiptType:'Xcode'}),signed({receiptType:'Sandbox',bundleId:'wrong.app'}),'Sandbox',undefined])
    await assert.rejects(scopes.verify(bad));
});

integration('public API requires Apple proof, isolates same-SKU catalog/order scopes and durable recovery/account ownership',async()=>{
  const f=await fixture();try{
    assert.equal((await f.app.inject({url:'/v1/minutes/products?provider=apple&storefront=USA',headers:f.headers()})).statusCode,502);
    for(const environment of ['test','live'] as const){
      const response=await f.app.inject({url:'/v1/minutes/products?provider=apple&storefront=USA',headers:f.headers(environment)});
      assert.equal(response.statusCode,200);assert.equal(response.json().products.length,1);assert.equal(response.json().products[0].environment,environment);
    }
    const key=randomUUID(),{order,value}=await f.order('test',2,key);
    const conflicting=await f.app.inject({method:'POST',url:'/v1/minutes/orders',headers:{...f.headers('live'),'idempotency-key':key},
      payload:{provider:'apple',sku:'small-us-v1',quantity:2,storefront:'USA',scheduleVersion:'test-schedule'}});
    assert.equal(conflicting.statusCode,409);
    const delivered=await f.app.inject({method:'POST',url:`/v1/minutes/orders/${order.orderID}/apple`,headers:f.headers('test'),payload:{transactionID:value.transactionId}});
    assert.equal(delivered.statusCode,200,delivered.body);assert.equal(delivered.json().grantedNanoUSD,'7380000000');
    const wrongScope=await f.app.inject({method:'POST',url:`/v1/minutes/orders/${order.orderID}/apple`,headers:f.headers('live'),payload:{transactionID:value.transactionId}});
    assert.equal(wrongScope.statusCode,502);
    const worker=new MinuteDeliveryWorker(f.db,f.fulfillment,[f.live,f.sandbox]);assert.equal((await worker.runBatch()).completed,1);
    const recovered=await f.app.inject({method:'POST',url:'/v1/minutes/apple/recover',headers:f.headers('test'),payload:{transactionID:value.transactionId}});
    assert.equal(recovered.statusCode,200);assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
    const stranger=randomUUID();await f.db.query('INSERT INTO accounts(id) VALUES($1)',[stranger]);await f.db.query('INSERT INTO wallets(account_id) VALUES($1)',[stranger]);
    await assert.rejects(f.fulfillment.reconcile('apple',{kind:'recovery',environment:'test',accountID:stranger,transactionID:value.transactionId}),{code:'purchase_verification_failed'});
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,'0');assert.equal((await paidAIBalance(f.db,f.account,'test')).availableNanoUSD,'7380000000');
    assert.equal(f.testTransport.scopeVerifications,1);assert.equal(f.liveTransport.scopeVerifications,1);
  }finally{await f.cleanup();}
});

integration('payment reads share trusted-network limits before Apple verification and database authentication',async()=>{
  const f=await fixture();try{
    let scopeChecks=0,authChecks=0;
    const verify=f.scopes.verify.bind(f.scopes),query=f.db.query.bind(f.db);
    f.scopes.verify=async proof=>{scopeChecks++;return verify(proof);};
    f.db.query=((...args:unknown[])=>{
      if(typeof args[0]==='string'&&args[0].includes('FROM auth_sessions'))authChecks++;
      return (query as (...args:unknown[])=>unknown)(...args);
    }) as typeof f.db.query;
    const routes=['/v1/minutes','/v1/minutes/products?provider=apple&storefront=USA','/v1/wallet'];
    const encoded=['/v1/%6dinutes','/v1/minutes/%70roducts?provider=apple&storefront=USA','/v1/%77allet'];
    for(let i=0;i<120;i++){
      const response=await f.app.inject({url:routes[i%3]!,headers:{...f.headers('test'),'x-forwarded-for':`203.0.113.${i+1}`}});
      assert.equal(response.statusCode,200,response.body);
    }
    assert.equal(scopeChecks,120);assert.equal(authChecks,80);
    for(const url of [...routes,...encoded]){
      const response=await f.app.inject({url,headers:{...f.headers('test'),[appleAppTransactionHeader]:'unverified-new-proof',
        authorization:`Bearer ${randomBytes(32).toString('base64url')}`,'x-forwarded-for':'198.51.100.200'}});
      assert.equal(response.statusCode,429,response.body);assert.deepEqual(response.json(),{error:{code:'rate_limit'}});
      assert.ok(Number(response.headers['retry-after'])>=1&&Number(response.headers['retry-after'])<=60);
    }
    assert.equal(scopeChecks,120);assert.equal(authChecks,80);
    const forged=await f.app.inject({url:routes[1]!,headers:{...f.headers('test'),'x-mural-proxy-token':'wrong','x-mural-client-ip':'192.0.2.114'}});
    assert.equal(forged.statusCode,503);assert.equal(scopeChecks,120);assert.equal(authChecks,80);
    const other=await f.app.inject({url:routes[2]!,headers:{...f.headers('test'),'x-mural-client-ip':'192.0.2.114'}});
    assert.equal(other.statusCode,200,other.body);assert.equal(scopeChecks,121);assert.equal(authChecks,81);
  }finally{await f.cleanup();}
});

integration('verified TestFlight purchases fund public hosted calls/helpers without using or converting real paid value',async()=>{
  const f=await fixture();try{
    const {order,value}=await f.order('test');await f.fulfillment.reconcile('apple',{kind:'client',environment:'test',accountID:f.account,orderID:order.orderID,transactionID:value.transactionId});
    const liveCall=await f.app.inject({method:'POST',url:'/v1/live/sessions',headers:{...f.headers(),'idempotency-key':randomUUID()},payload:{sdp:'v=0\r\noffer',language:'es-ES',requestedMilliseconds:60000}});
    assert.equal(liveCall.statusCode,402);assert.equal(f.voice.creates,0);
    await transaction(f.db,sql=>appendEntry(sql,f.account,'real-funds','purchase',2000000000n,0n));
    const before=await paidAIBalance(f.db,f.account);
    const response=await f.app.inject({method:'POST',url:'/v1/live/sessions',headers:{...f.headers('test'),'idempotency-key':randomUUID()},payload:{sdp:'v=0\r\noffer',language:'es-ES',requestedMilliseconds:60000}});
    assert.equal(response.statusCode,200,response.body);const session=response.json();
    assert.equal((await f.db.query('SELECT funding_environment FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0].funding_environment,'test');
    const helper=await f.helpers.request(f.account,session.sessionID,{requestID:randomUUID(),purpose:'meaning',instructions:'Translate into English.',input:'Hola.'});assert.equal(helper.text,'Hola.');
    f.voice.listeners.get(session.providerSessionID)!({type:'session.closed',usage:{seconds:20}});
    await until(async()=> (await f.db.query('SELECT state FROM hosted_sessions WHERE id=$1',[session.sessionID])).rows[0].state==='closed');
    await f.db.query('ALTER TABLE hosted_helper_sessions DISABLE TRIGGER hosted_helper_budget_immutable');
    await f.db.query("UPDATE hosted_helper_sessions SET expires_at=now()-interval '1 second' WHERE session_id=$1",[session.sessionID]);
    await f.db.query('ALTER TABLE hosted_helper_sessions ENABLE TRIGGER hosted_helper_budget_immutable');await f.helpers.expireBudgets();
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,before.availableNanoUSD);
    const testBalance=await paidAIBalance(f.db,f.account,'test');assert.ok(BigInt(testBalance.availableNanoUSD)<3690000000n);assert.equal(testBalance.reservedNanoUSD,'0');
    assert.equal((await f.db.query("SELECT count(*) FROM reservations WHERE funding_environment<>'test'")).rows[0].count,'0');
    await assert.rejects(f.db.query("UPDATE hosted_sessions SET funding_environment='live' WHERE id=$1",[session.sessionID]));
    value.revocationDate=1700000000002;value.signedDate=1700000000003;value.revocationType='REFUND_PRORATED';value.revocationPercentage=50000;
    await f.fulfillment.reconcile('apple',{kind:'recovery',environment:'test',accountID:f.account,transactionID:value.transactionId});
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,'2000000000');
  }finally{await f.cleanup();}
});

integration('verified foreign sandbox notifications advance without granting; forged notifications and cross-scope settlement fail closed',async()=>{
  const f=await fixture();try{
    const value=f.testTransport.bind(randomUUID());
    const notification:ResponseBodyV2DecodedPayload={notificationUUID:randomUUID(),version:'2.0',signedDate:1700000000002,notificationType:'ONE_TIME_CHARGE',
      data:{bundleId:'chat.mural.ios',appAppleId:6816001011,environment:Environment.SANDBOX,signedTransactionInfo:signed(value)}};
    const jws=signed(notification);f.testTransport.notifications.set(jws,notification);
    const response=await f.app.inject({method:'POST',url:'/v1/webhooks/apple',headers:f.headers(),payload:{signedPayload:jws}});assert.equal(response.statusCode,200,response.body);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
    assert.equal((await f.app.inject({method:'POST',url:'/v1/webhooks/apple',headers:f.headers(),payload:{signedPayload:jws.replace('.verified','.forged')}})).statusCode,502);
    await transaction(f.db,sql=>appendEntry(sql,f.account,'scope-seed','purchase',2000000000n,0n,null,1000000000n));
    const liveReservation=randomUUID();await transaction(f.db,sql=>reservePaidInTransaction(sql,f.account,liveReservation,'live-hold',200000000n,'test-rate','live'));
    assert.equal((await paidAIBalance(f.db,f.account,'test')).availableNanoUSD,'1000000000');
    const reservation=randomUUID();await transaction(f.db,sql=>reservePaidInTransaction(sql,f.account,reservation,'test-hold',100000000n,'test-rate','test'));
    await assert.rejects(f.db.query("UPDATE reservations SET funding_environment='live' WHERE id=$1",[reservation]));
    await transaction(f.db,sql=>settlePaidInTransaction(sql,f.account,reservation,50000000n));
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,'800000000');
    assert.equal((await paidAIBalance(f.db,f.account,'test')).availableNanoUSD,'950000000');
    await transaction(f.db,sql=>settlePaidInTransaction(sql,f.account,liveReservation,100000000n));
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,'900000000');
    assert.equal((await paidAIBalance(f.db,f.account,'test')).availableNanoUSD,'950000000');
  }finally{await f.cleanup();}
});

integration('sandbox-only users can delete accounts while paid balances and in-flight funding remain protected',async()=>{
  const f=await fixture();try{
    const {order,value}=await f.order('test');await f.fulfillment.reconcile('apple',{kind:'client',environment:'test',accountID:f.account,orderID:order.orderID,transactionID:value.transactionId});
    const wallet=await f.app.inject({url:'/v1/wallet',headers:f.headers()});assert.equal(wallet.json().availableNanoUSD,'0');
    const testWallet=await f.app.inject({url:'/v1/wallet',headers:f.headers('test')});assert.equal(testWallet.json().availableNanoUSD,'3690000000');
    const hold=randomUUID();await transaction(f.db,sql=>reservePaidInTransaction(sql,f.account,hold,'pending-test',100000000n,'test-rate','test'));
    await assert.rejects(deleteAccount(f.db,f.account),{code:'unresolved_billing'});
    await transaction(f.db,sql=>settlePaidInTransaction(sql,f.account,hold,0n));
    await transaction(f.db,sql=>appendEntry(sql,f.account,'live-protection','purchase',100000000n,0n));
    await assert.rejects(deleteAccount(f.db,f.account),{code:'unresolved_billing'});
    await transaction(f.db,sql=>appendEntry(sql,f.account,'live-refund','reversal',-100000000n,0n));
    assert.deepEqual(await deleteAccount(f.db,f.account),{retainedFinancialRecords:true});
    const account=(await f.db.query('SELECT deleted_at FROM accounts WHERE id=$1',[f.account])).rows[0];assert.ok(account.deleted_at);
    const balance=(await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0];
    assert.equal(balance.balance_nano,'0');assert.equal(balance.sandbox_balance_nano,'0');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE reference=$1",[`sandbox-deletion:${f.account}`])).rows[0].count,'1');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_provider_receipts WHERE order_id=$1',[order.orderID])).rows[0].count,'1');
    await assert.rejects(paidAIBalance(f.db,f.account,'test'),{code:'account_not_found'});
    value.revocationDate=1700000000002;value.signedDate=1700000000003;value.revocationType='REFUND_FULL';value.revocationPercentage=100000;
    await f.fulfillment.reconcile('apple',{kind:'stored',orderID:order.orderID});
    const refunded=(await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0];
    assert.equal(BigInt(refunded.balance_nano)-BigInt(refunded.sandbox_balance_nano),0n);assert.equal(refunded.sandbox_balance_nano,'-3690000000');
  }finally{await f.cleanup();}
});


integration('actual runtime grants support dual Apple purchases, scoped settlement, notifications and refunds',async()=>{
  const f=await fixture(),role=`apple_runtime_${randomUUID().replaceAll('-','')}`;
  let runtime:ReturnType<typeof connectDatabase>|undefined;
  try {
    await f.db.query(`CREATE ROLE ${role};GRANT USAGE ON SCHEMA ${f.schema} TO ${role};GRANT SELECT,UPDATE ON accounts TO ${role}`);
    for(const file of ['minute-runtime-grants.sql','minute-purchase-runtime-grants.sql','minute-provider-runtime-grants.sql','actual-value-runtime-grants.sql','apple-purchase-runtime-grants.sql']) {
      const grants=await readFile(new URL(`../operations/${file}`,import.meta.url),'utf8');await f.db.query(grants.replaceAll('mural_runtime',role));
    }
    const url=new URL(f.url);url.searchParams.set('options',`-c search_path=${f.schema} -c role=${role}`);runtime=connectDatabase(url.toString());
    const vault=new MinuteReceiptVault(runtime,'test',new Map([['test',randomBytes(32)]]));
    const live=new AppleMinuteProvider(runtime,vault,f.live.config,f.liveTransport),sandbox=new AppleMinuteProvider(runtime,vault,f.sandbox.config,f.testTransport);
    const ai=new AIValuePurchases(runtime,{catalog:f.ai.products('apple'),verifiers:[live,sandbox],salesEnabled:true,quantityEnabled:['apple']});
    const legacy=new MinutePurchases(runtime,{verifiers:[live]}),fulfillment=new PurchaseFulfillmentRouter(runtime,legacy,ai,[live,sandbox]);
    const order=await ai.createOrder(f.account,'apple','small-us-v1',randomUUID(),1,{storefront:'USA',scheduleVersion:'test-schedule'},undefined,'test');
    assert.equal((await sandbox.prepare(f.account,order.orderID)).appAccountToken,order.orderID);
    const value=f.testTransport.bind(order.orderID);
    await fulfillment.reconcile('apple',{kind:'client',environment:'test',accountID:f.account,orderID:order.orderID,transactionID:value.transactionId});
    const hold=randomUUID();await transaction(runtime,sql=>reservePaidInTransaction(sql,f.account,hold,'runtime-test-hold',100000000n,'test-rate','test'));
    await assert.rejects(runtime.query("UPDATE reservations SET funding_environment='live' WHERE id=$1",[hold]),{code:'42501'});
    await transaction(runtime,sql=>settlePaidInTransaction(sql,f.account,hold,50000000n));
    assert.equal((await paidAIBalance(runtime,f.account,'test')).availableNanoUSD,'3640000000');
    assert.equal((await paidAIBalance(runtime,f.account)).availableNanoUSD,'0');
    value.revocationDate=1700000000002;value.signedDate=1700000000003;value.revocationType='REFUND_FULL';value.revocationPercentage=100000;
    const notification:ResponseBodyV2DecodedPayload={notificationUUID:randomUUID(),version:'2.0',signedDate:1700000000004,notificationType:'REFUND',
      data:{bundleId:'chat.mural.ios',appAppleId:6816001011,environment:Environment.SANDBOX,signedTransactionInfo:signed(value)}};
    const jws=signed(notification);f.testTransport.notifications.set(jws,notification);await sandbox.notify(jws);
    const worker=new MinuteDeliveryWorker(runtime,fulfillment,[live,sandbox]);assert.equal((await worker.runBatch()).completed,1);
    assert.equal((await paidAIBalance(runtime,f.account)).availableNanoUSD,'0');
    assert.equal((await paidAIBalance(runtime,f.account,'test')).balanceNanoUSD,'-50000000');
    await assert.rejects(runtime.query('UPDATE wallets SET cash_provenance_verified=false WHERE account_id=$1',[f.account]),/permission denied/);
  }finally {
    await runtime?.end();await f.db.query(`DROP OWNED BY ${role};DROP ROLE ${role}`);await f.cleanup();
  }
});
