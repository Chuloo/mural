import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {connectDatabase} from '../src/db.js';
import {migrate} from '../src/migrate.js';
import {AppleMinuteProvider,appleMinorUnits,applePriceMatches,appleRefundParts,type AppleMinuteTransport} from '../src/apple-minute-provider.js';
import {AIValuePurchases,makeAppleAIValueProduct,PurchaseFulfillmentRouter} from '../src/ai-value-purchases.js';
import {MinutePurchases} from '../src/minute-purchases.js';
import {MinuteReceiptVault,MinuteDeliveryWorker} from '../src/minute-provider-delivery.js';
import {paidAIBalance} from '../src/ledger.js';
import {AppleHistoryReconciler} from '../src/minute-commerce-runner.js';
import {Environment,type JWSTransactionDecodedPayload,type ResponseBodyV2DecodedPayload} from '@apple/app-store-server-library';

const databaseURL=process.env.TEST_DATABASE_URL;
if(databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
function product(){return makeAppleAIValueProduct({provider:'apple',environment:'test',merchant:'chat.mural.ios',sku:'small-us-v1',
  providerProduct:'chat.mural.ios.minutes.small.v1',aiValueMinor:369,policyVersion:1,serviceFeeBasisPoints:1500,
  estimate:{nanoUSDPerMinute:'100000000',rateVersion:'test-estimate'},apple:{storefront:'USA',currency:'usd',currencyExponent:2,
    unitTotalMinor:700,scheduleVersion:'test-schedule',commissionBasisPoints:3000,taxMinor:0,commissionMinor:210,
    proceedsMinor:490,proceedsUSDMinor:490,residualUSDMinor:65}});}
class FakeApple implements AppleMinuteTransport {
  value:JWSTransactionDecodedPayload={}; notificationValue:ResponseBodyV2DecodedPayload={}; fail=false;
  async transaction(signed:string){if(signed!=='valid.signed.transaction')throw new Error('signature invalid');return structuredClone(this.value);}
  async notification(signed:string){if(signed!=='valid.signed.notification')throw new Error('signature invalid');return structuredClone(this.notificationValue);}
  async latest(_id:string){if(this.fail)throw new Error('provider offline');return 'valid.signed.transaction';}
  async history(_start:number,_end:number,_pageToken?:string):Promise<{notifications:string[];nextPageToken?:string}>{return {notifications:[]};}
  bind(orderID:string,quantity:number){this.value={bundleId:'chat.mural.ios',environment:Environment.SANDBOX,type:'Consumable',inAppOwnershipType:'PURCHASED',
    transactionId:'1000000000001',appAccountToken:orderID,productId:'chat.mural.ios.minutes.small.v1',quantity,currency:'USD',price:7000*quantity,
    storefront:'USA',purchaseDate:1_700_000_000_000,signedDate:1_700_000_000_001};}
}
async function fixture(){
  const schema=`apple_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  const vault=new MinuteReceiptVault(db,'test',new Map([['test',randomBytes(32)]])),transport=new FakeApple();
  const apple=new AppleMinuteProvider(db,vault,{bundleID:'chat.mural.ios',appAppleID:6816001011,environment:'test',signingKey:'unused-test',
    keyID:'TESTKEY123',issuerID:randomUUID(),rootCertificates:[],purchasesEnabled:true},transport);
  const p=product(),ai=new AIValuePurchases(db,{catalog:[p],verifiers:[apple],salesEnabled:true,quantityEnabled:['apple']});
  const legacy=new MinutePurchases(db,{verifiers:[apple]}),router=new PurchaseFulfillmentRouter(db,legacy,ai,[apple]);
  const account=randomUUID();await db.query('INSERT INTO accounts(id) VALUES($1)',[account]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
  return {db,vault,transport,apple,ai,router,account,p,async order(quantity=1){const o=await ai.createOrder(account,'apple',p.sku,randomUUID(),quantity,
    {storefront:'USA',scheduleVersion:'test-schedule'});transport.bind(o.orderID,quantity);return o;},
    async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}
test('Apple milliunits convert exactly',()=>{
  assert.equal(appleMinorUnits(14000,2),1400);assert.equal(appleMinorUnits(3300000,0),3300);assert.equal(appleMinorUnits(1234,3),1234);
  for(const [amount,exponent] of [[1,2],[0,2],[-1,2],[1.5,2],[Number.MAX_SAFE_INTEGER,2],[1000,4]])assert.throws(()=>appleMinorUnits(amount!,exponent!));
});
test('Apple sandbox unit-price compatibility never relaxes live total-price verification',()=>{
  for(const quantity of [1,2,10]) {
    assert.equal(applePriceMatches(7000*quantity,2,quantity,700*quantity,'live'),true);
    assert.equal(applePriceMatches(7000,2,quantity,700*quantity,'test'),true);
    assert.equal(applePriceMatches(7000,2,quantity,700*quantity,'live'),quantity===1);
    assert.equal(applePriceMatches(6000,2,quantity,700*quantity,'test'),false);
  }
});
integration('recorded sandbox quantity-two unit-price receipt recovers once after verification failure',async()=>{
  const f=await fixture();try {
    const order=await f.order(2);
    f.transport.value.price=7000;
    await f.vault.save(order.orderID,f.apple,'1000000000001');
    const worker=new MinuteDeliveryWorker(f.db,f.router,[f.apple]);
    assert.equal((await worker.runBatch()).completed,1);
    await f.router.reconcile('apple',{kind:'recovery',accountID:f.account,signedTransaction:'valid.signed.transaction'});
    assert.equal((await f.ai.status(f.account,order.orderID)).grantedNanoUSD,'7380000000');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
  }finally{await f.cleanup();}
});
test('Apple refunds distinguish full, partial, reversed and ambiguous evidence',()=>{
  assert.equal(appleRefundParts({}),0);assert.equal(appleRefundParts({revocationDate:100}),100000);
  assert.equal(appleRefundParts({revocationDate:100,revocationType:'REFUND_PRORATED',revocationPercentage:40000}),40000);
  for(const value of [{revocationDate:100,revocationType:'REFUND_PRORATED'},{revocationDate:100,revocationPercentage:100001},
    {revocationDate:100,revocationType:'REFUND_FULL',revocationPercentage:50},{revocationPercentage:40000}])assert.throws(()=>appleRefundParts(value));
});
for(const quantity of [1,2,10]) integration(`Apple quantity ${quantity}: durable delivery, replay, partial refund, reversal and stale snapshot`,async()=>{
  const f=await fixture();try{
    const order=await f.order(quantity);assert.equal(order.totalMinor,700*quantity);
    const original={...f.transport.value},request={kind:'client',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'};
    const results=await Promise.all(Array.from({length:4},()=>f.router.reconcile('apple',request)));
    assert.ok(results.every(r=>r.fulfillmentRecorded));
    assert.equal((await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0].sandbox_balance_nano,order.aiValueNanoUSD);
    assert.equal((await paidAIBalance(f.db,f.account)).availableNanoUSD,'0');
    f.transport.value={...original,signedDate:original.signedDate!+2,revocationDate:original.signedDate!+1,revocationType:'REFUND_PRORATED',revocationPercentage:40000};
    const refunded=await f.router.reconcile('apple',request);assert.equal((refunded as any).reversedNanoUSD,(BigInt(order.aiValueNanoUSD)*4n/10n).toString());
    f.transport.value={...original,signedDate:original.signedDate!+3};
    assert.equal((await f.router.reconcile('apple',request) as any).reversedNanoUSD,'0');
    f.transport.value={...original,signedDate:original.signedDate!+2,revocationDate:original.signedDate!+1,revocationType:'REFUND_PRORATED',revocationPercentage:40000};
    assert.equal((await f.router.reconcile('apple',request) as any).reversedNanoUSD,'0');
    f.transport.value={...original,signedDate:original.signedDate!+4,revocationDate:original.signedDate!+4,revocationPercentage:100000};
    assert.equal((await f.router.reconcile('apple',request) as any).reversedNanoUSD,order.aiValueNanoUSD);
    assert.equal((await f.db.query('SELECT balance_nano FROM wallets WHERE account_id=$1',[f.account])).rows[0].balance_nano,'0');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
  }finally{await f.cleanup();}
});
integration('Apple rejects forged identity, environment, product, quantity, price and storefront before granting',async()=>{
  const f=await fixture();try{
    const order=await f.order(2),good={...f.transport.value};
    for(const patch of [{environment:Environment.PRODUCTION},{bundleId:'other.app'},{type:'Auto-Renewable Subscription'},
      {inAppOwnershipType:'FAMILY_SHARED'},{productId:'other'},{quantity:1},{price:6000},{price:14001},{currency:'NOK'},
      {storefront:'NOR'},{appAccountToken:randomUUID()},{appAccountToken:undefined},{signedDate:undefined},{offerType:1}]){
      f.transport.value={...good,...patch};await assert.rejects(f.router.reconcile('apple',{kind:'client',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'}));
    }
    f.transport.value=good;
    await assert.rejects(f.apple.verify({kind:'client',accountID:randomUUID(),signedTransaction:'valid.signed.transaction'}));
    await assert.rejects(f.apple.verify({kind:'client',accountID:f.account,signedTransaction:'forged.signed.transaction'}));
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('verified Apple charges with mismatched terms stay recoverable without a grant',async()=>{
  const f=await fixture();try{
    const order=await f.order(2);f.transport.value.price=15000;
    await assert.rejects(f.router.reconcile('apple',{kind:'client',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'}));
    assert.equal(await f.vault.read(order.orderID,f.apple),'1000000000001');
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_provider_jobs WHERE order_id=$1',[order.orderID])).rows[0].count,'1');
    const worker=new MinuteDeliveryWorker(f.db,f.router,[f.apple]);await worker.runBatch();
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('transaction ID recovery verifies fresh Apple evidence and account ownership before granting',async()=>{
  const f=await fixture();try {
    const order=await f.order(10),id=f.transport.value.transactionId!;
    f.transport.value.price=7000;
    for(const transactionID of ['../transactions/1','', '1'.repeat(65),'999999']) {
      await assert.rejects(f.router.reconcile('apple',{kind:'recovery',accountID:f.account,transactionID}));
    }
    await assert.rejects(f.router.reconcile('apple',{kind:'recovery',accountID:randomUUID(),transactionID:id}));
    assert.equal((await f.db.query('SELECT count(*) FROM minute_provider_receipts')).rows[0].count,'0');
    const good={...f.transport.value};
    f.transport.value.environment=Environment.PRODUCTION;
    await assert.rejects(f.router.reconcile('apple',{kind:'recovery',accountID:f.account,transactionID:id}));
    f.transport.value=good;
    f.transport.fail=true;
    await assert.rejects(f.router.reconcile('apple',{kind:'recovery',accountID:f.account,transactionID:id}));
    f.transport.fail=false;
    const result=await f.router.reconcile('apple',{kind:'recovery',accountID:f.account,transactionID:id});
    assert.equal(result.fulfillmentRecorded,true);
    await f.router.reconcile('apple',{kind:'recovery',accountID:f.account,transactionID:id});
    assert.equal((await f.ai.status(f.account,order.orderID)).grantedNanoUSD,'36900000000');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
  }finally{await f.cleanup();}
});

integration('Apple history checkpoints complete pages, preserves a failed page and rejects an expired coverage gap',async()=>{
  const f=await fixture();try{
    const calls:{start:number;end:number;token?:string}[]=[];let fail=true;
    const source={environment:'test' as const,merchant:f.apple.merchant,async pollHistory(start:number,end:number,token?:string){
      calls.push({start,end,token});if(token && fail)throw new Error('notification verification failed');
      return {scheduled:1,nextPageToken:token?undefined:'page-two'};
    }};
    const cursor=new AppleHistoryReconciler(f.db,source);
    assert.equal((await cursor.page()).more,true);
    await assert.rejects(cursor.page());
    assert.equal((await f.db.query('SELECT page_token FROM apple_notification_cursors')).rows[0].page_token,'page-two');
    fail=false;assert.equal((await cursor.page()).more,false);
    assert.equal(calls[1]!.start,calls[0]!.start);assert.equal(calls[2]!.end,calls[0]!.end);
    assert.deepEqual(await cursor.page(),{pages:0,scheduled:0,more:false});
    // A clock adjustment must never move the completed checkpoint backwards.
    const future=Date.now()+60_000;
    await f.db.query("UPDATE apple_notification_cursors SET completed_through_ms=$1,next_poll_after=now()-interval '1 second'",[future]);
    assert.deepEqual(await cursor.page(),{pages:0,scheduled:0,more:false});
    assert.equal(Number((await f.db.query('SELECT completed_through_ms FROM apple_notification_cursors')).rows[0].completed_through_ms),future);
    await f.db.query("UPDATE apple_notification_cursors SET completed_through_ms=$1,next_poll_after=now()-interval '1 second'",[Date.now()-31*86_400_000]);
    await assert.rejects(cursor.page(),{code:'apple_history_cursor_expired'});
    assert.equal(calls.length,3);
  }finally{await f.cleanup();}
});

integration('Apple history re-verifies notifications before scheduling current transaction recovery',async()=>{
  const f=await fixture();try{
    const order=await f.order();
    f.transport.notificationValue={notificationUUID:randomUUID(),version:'2.0',signedDate:1_700_000_000_002,notificationType:'ONE_TIME_CHARGE',
      data:{bundleId:'chat.mural.ios',appAppleId:6816001011,environment:Environment.SANDBOX,signedTransactionInfo:'valid.signed.transaction'}};
    f.transport.history=async()=>({notifications:['forged.signed.notification']});
    const cursor=new AppleHistoryReconciler(f.db,f.apple);
    await assert.rejects(cursor.page());assert.equal((await f.db.query('SELECT count(*) FROM apple_notification_cursors')).rows[0].count,'0');
    f.transport.history=async()=>({notifications:['valid.signed.notification']});
    await cursor.page();assert.equal(await f.vault.read(order.orderID,f.apple),'1000000000001');
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
    const worker=new MinuteDeliveryWorker(f.db,f.router,[f.apple]);await worker.runBatch();
    assert.equal((await f.ai.status(f.account,order.orderID)).grantedNanoUSD,order.aiValueNanoUSD);
  }finally{await f.cleanup();}
});

integration('Apple provider timeout leaves an encrypted durable retry; notification replay and consumption request never invent refund',async()=>{
  const f=await fixture();try{
    const order=await f.order(2);f.transport.fail=true;
    await assert.rejects(f.router.reconcile('apple',{kind:'client',accountID:f.account,signedTransaction:'valid.signed.transaction'}));
    assert.equal(await f.vault.read(order.orderID,f.apple),'1000000000001');f.transport.fail=false;
    const worker=new MinuteDeliveryWorker(f.db,f.router,[f.apple]);await worker.runBatch();
    assert.equal((await f.ai.status(f.account,order.orderID)).grantedNanoUSD,order.aiValueNanoUSD);
    f.transport.notificationValue={notificationUUID:randomUUID(),version:'2.0',signedDate:1_700_000_000_002,notificationType:'CONSUMPTION_REQUEST',
      data:{bundleId:'chat.mural.ios',appAppleId:6816001011,environment:Environment.SANDBOX,signedTransactionInfo:'valid.signed.transaction'}};
    await f.apple.notify('valid.signed.notification');await f.apple.notify('valid.signed.notification');await worker.runBatch();
    assert.equal((await f.db.query('SELECT count(*) FROM apple_purchase_notifications')).rows[0].count,'1');
    assert.equal((await f.ai.status(f.account,order.orderID)).reversedNanoUSD,'0');
  }finally{await f.cleanup();}
});
