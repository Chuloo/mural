import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { AIValuePurchases, makePlayAIValueProduct, makeRegionalPlayAIValueProduct, PurchaseFulfillmentRouter } from '../src/ai-value-purchases.js';
import { googleMoneyMinor, makeRegionalPlayCatalog, type RegionalPlayCatalogInput } from '../src/play-regional-catalog.js';
import { MinutePurchases } from '../src/minute-purchases.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';
import { PlayMinuteProvider } from '../src/play-minute-provider.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { paidAIBalance } from '../src/ledger.js';
import { createApp } from '../src/app.js';
import { AuthAdmission } from '../src/auth-admission.js';
import { digest } from '../src/auth.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if(databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
const estimate={nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'};
const scope={provider:'play' as const,environment:'test' as const,merchant:'chat.mural.android'};
const common={...scope,providerProduct:'chat.mural.android.minutes.small.v1',policyVersion:1,serviceFeeBasisPoints:1500,estimate};
function product(regionCode='GB',currency='gbp',currencyExponent=2,unitTotalMinor=600,taxMinor=100) {
  const commissionMinor=Math.ceil((unitTotalMinor-taxMinor)*.3);
  return makeRegionalPlayAIValueProduct({...common,sku:`regional-${regionCode}`,aiValueMinor:369,
    play:{pricingBasis:'fixed-usd-allocation',taxBasis:'google-conversion',regionCode,currency,currencyExponent,unitTotalMinor,scheduleVersion:'synthetic-v1',
      commissionBasisPoints:3000,taxMinor,commissionMinor,proceedsMinor:unitTotalMinor-taxMinor-commissionMinor}});
}
const verifier={...scope,verify:async()=>{throw new Error('No provider access.');}};
function money(minor:number,currency='GBP',exponent=2) {
  const divisor=10**exponent;
  return {currencyCode:currency,units:String(Math.floor(minor/divisor)),nanos:(minor%divisor)*10**(9-exponent)};
}

test('regional prices preserve fixed USD allocations across two euro countries and zero/three-decimal currencies',()=>{
  const rows=[product('DE','eur',2,799,128),product('FR','eur',2,849,142),product('JP','jpy',0,1200,100),product('KW','kwd',3,2500,0)];
  for(const row of rows) {
    assert.equal(row.aiValueNanoUSD,'3690000000');assert.equal(row.quote.currency,'usd');assert.equal(row.quote.currencyExponent,2);
    assert.equal(row.quote.totalMinor,425);assert.equal(row.totalMinor,row.quote.play!.unitTotalMinor);
  }
  assert.equal(new AIValuePurchases({} as any,{catalog:rows,verifiers:[verifier],salesEnabled:true}).products('play').length,4);
  assert.throws(()=>new AIValuePurchases({} as any,{catalog:[rows[0]!,{...rows[0]!,sku:'duplicate'}],verifiers:[verifier]}),/invalid_ai_value_catalog/);
  for(const changed of [{...rows[0]!,aiValueNanoUSD:'3690000001'},
    {...rows[0]!,quote:{...rows[0]!.quote,exchangeRateNumerator:'2'}},
    {...rows[0]!,quote:{...rows[0]!.quote,play:{...rows[0]!.quote.play!,commissionMinor:1}}}])
    assert.throws(()=>new AIValuePurchases({} as any,{catalog:[changed],verifiers:[verifier]}),/invalid_ai_value_product/);
});

test('Google regional generator requires complete reviewed markets and explicit exact money precision',()=>{
  const input:RegionalPlayCatalogInput={environment:'test',merchant:scope.merchant,scheduleVersion:'synthetic-v1',policyVersion:1,
    serviceFeeBasisPoints:1500,commissionBasisPoints:3000,estimate,currencyExponents:{gbp:2,jpy:0},regionCodes:['GB','JP'],
    packs:([{pack:'small',amount:600},{pack:'medium',amount:1200},{pack:'large',amount:1800}] as const).map(p=>({pack:p.pack,
      providerProduct:`chat.mural.android.minutes.${p.pack}.v1`,convertedRegionPrices:{
        GB:{regionCode:'GB',price:money(p.amount),taxAmount:money(p.amount/6)},
        JP:{regionCode:'JP',price:money(p.amount*2,'JPY',0),taxAmount:money(0,'JPY',0)}}}))};
  const generated=makeRegionalPlayCatalog(input);
  assert.equal(generated.length,6);
  assert.deepEqual(generated.map(p=>p.aiValueNanoUSD),['3690000000','7660000000','11610000000','3690000000','7660000000','11610000000']);
  assert.throws(()=>makeRegionalPlayCatalog({...input,regionCodes:['GB','DE']}),/invalid_play_regional_price/);
  assert.throws(()=>makeRegionalPlayCatalog({...input,regionCodes:['GB','GB']}),/invalid_play_regional_catalog/);
  assert.throws(()=>makeRegionalPlayCatalog({...input,currencyExponents:{gbp:2}}),/invalid_play_regional_price/);
  assert.equal(googleMoneyMinor({currencyCode:'KWD',nanos:123_000_000},3),123);
  assert.throws(()=>googleMoneyMinor({currencyCode:'JPY',units:'1',nanos:1},0),/invalid_play_regional_price/);
});

test('large enabled catalogs return only a requested market and a bounded legacy list',async()=>{
  const legacy=makePlayAIValueProduct({...common,sku:'legacy-us-small',aiValueMinor:369,
    exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},
    play:{currency:'usd',currencyExponent:2,unitTotalMinor:700,scheduleVersion:'legacy-v1',
      commissionBasisPoints:3000,taxMinor:0,commissionMinor:210,residualMinor:65}});
  const rows=Array.from({length:160},(_,i)=>product(String.fromCharCode(65+Math.floor(i/26))+String.fromCharCode(65+i%26)));
  const ai=new AIValuePurchases({} as any,{catalog:[legacy,...rows],verifiers:[verifier],salesEnabled:true});
  const app=createApp({db:{} as any,auth:{},minuteCommerce:{purchases:new MinutePurchases({} as any),aiPurchases:ai}});
  try {
    const selected=await app.inject('/v1/minutes/products?provider=play&regionCode=DE');
    assert.equal(selected.statusCode,200);assert.equal(selected.json().products.length,1);assert.equal(selected.json().products[0].quote.play.regionCode,'DE');
    assert.ok(Buffer.byteLength(selected.body)<128_000);
    const old=(await app.inject('/v1/minutes/products?provider=play')).json();
    assert.equal(old.products.length,1);assert.equal(old.products[0].sku,legacy.sku);
    assert.equal((await app.inject('/v1/minutes/products?provider=play&regionCode=ZZ')).json().availabilityReason,'unsupported_country');
    for(const region of ['gb','GBR','GB&regionCode=DE']) assert.equal((await app.inject(`/v1/minutes/products?provider=play&regionCode=${region}`)).statusCode,400);
  } finally {await app.close();}
});

async function fixture(regionCode='GB',currency='gbp',exponent=2,amount=600,tax=100) {
  const schema=`play_regions_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  await db.query("UPDATE deployment_environment SET environment='test'");
  const row=product(regionCode,currency,exponent,amount,tax),vault=new MinuteReceiptVault(db,'fixture',new Map([['fixture',randomBytes(32)]]));
  let purchase:any,paidOrder:any,consumes=0;
  const play=new PlayMinuteProvider(db,vault,{packageName:scope.merchant,bindingKey:randomBytes(32),currencyExponents:{[currency]:exponent},purchasesEnabled:true},{
    purchase:async()=>structuredClone(purchase),order:async()=>structuredClone(paidOrder),voided:async()=>({}),
    consume:async()=>{consumes++;purchase.productLineItem[0].productOfferDetails.consumptionState='CONSUMPTION_STATE_CONSUMED';}});
  const ai=new AIValuePurchases(db,{catalog:[row],verifiers:[play],salesEnabled:true});
  const router=new PurchaseFulfillmentRouter(db,new MinutePurchases(db),ai,[play]);
  const account=randomUUID();await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,false)',[account]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
  const selection={regionCode,scheduleVersion:'synthetic-v1'},key=randomUUID();
  const order=await ai.createOrder(account,'play',row.sku,key,1,undefined,selection),payment=await play.prepare(account,order.orderID);
  const token=`fixture-${randomUUID()}`;
  purchase={orderId:'GPA.1234-5678-9012-34567',regionCode,testPurchaseContext:{fopType:'TEST'},purchaseStateContext:{purchaseState:'PURCHASED'},
    obfuscatedExternalAccountId:payment.obfuscatedAccountID,obfuscatedExternalProfileId:payment.obfuscatedProfileID,
    productLineItem:[{productId:row.providerProduct,productOfferDetails:{quantity:1,refundableQuantity:1,consumptionState:'CONSUMPTION_STATE_YET_TO_BE_CONSUMED'}}]};
  const m=(value:number)=>money(value,currency.toUpperCase(),exponent);
  paidOrder={orderId:purchase.orderId,purchaseToken:token,state:'PROCESSED',orderDetails:{taxInclusive:true},total:m(amount),tax:m(tax),
    lineItems:[{productId:row.providerProduct,listingPrice:m(amount),total:m(amount),tax:m(tax),oneTimePurchaseDetails:{quantity:1}}],orderHistory:{}};
  return {db,ai,play,vault,router,row,account,key,order,selection,m,purchase,paidOrder,get consumes(){return consumes;},
    request:{kind:'client',accountID:account,orderID:order.orderID,purchaseToken:token},
    cleanup:async()=>{await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}

integration('regional orders bind immutable market selection, verify provider region and credit/consume once',async()=>{
  const f=await fixture();try {
    await assert.rejects(f.ai.createOrder(f.account,'play',f.row.sku,randomUUID()),/purchase_quote_changed/);
    await assert.rejects(f.ai.createOrder(f.account,'play',f.row.sku,randomUUID(),1,undefined,{...f.selection,scheduleVersion:'stale'}),/purchase_quote_changed/);
    await assert.rejects(f.ai.createOrder(f.account,'play',f.row.sku,f.key,1,undefined,{...f.selection,regionCode:'DE'}),/idempotency_conflict/);
    assert.deepEqual(await f.ai.createOrder(f.account,'play',f.row.sku,f.key,1,undefined,f.selection),f.order);
    f.purchase.regionCode='DE';await assert.rejects(f.play.verify(f.request),/play_purchase_region_mismatch/);
    f.purchase.regionCode='GB';f.paidOrder.lineItems[0].listingPrice=f.m(599);await assert.rejects(f.play.verify(f.request),/play_order_not_reconciled/);
    f.paidOrder.lineItems[0].listingPrice=f.m(600);f.paidOrder.lineItems[0].oneTimePurchaseDetails.offerId='discount';
    await assert.rejects(f.play.verify(f.request),/play_order_not_reconciled/);delete f.paidOrder.lineItems[0].oneTimePurchaseDetails.offerId;
    await Promise.all([f.router.reconcile('play',f.request),f.router.reconcile('play',f.request)]);
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'3690000000');
    const worker=new MinuteDeliveryWorker(f.db,f.router,[f.play]);assert.equal((await worker.runBatch()).completed,1);assert.equal(f.consumes,1);
    await f.router.reconcile('play',f.request);await worker.runBatch();assert.equal(f.consumes,1);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'1');
    const after=new AIValuePurchases(f.db,{catalog:[],verifiers:[f.play],salesEnabled:true});
    assert.deepEqual(await after.createOrder(f.account,'play',f.row.sku,f.key,1,undefined,f.selection),f.order);
  } finally {await f.cleanup();}
});

integration('tax-exclusive purchases reconcile listed price plus exact tax and reverse only the saved allocation',async()=>{
  const f=await fixture('US','usd',2,700,0);try {
    f.paidOrder.orderDetails={}; // Protobuf false may be omitted.
    f.paidOrder.total=f.m(770);f.paidOrder.tax=f.m(70);f.paidOrder.lineItems[0].total=f.m(770);f.paidOrder.lineItems[0].tax=f.m(70);
    await f.router.reconcile('play',f.request);assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'3690000000');
    f.paidOrder.state='PARTIALLY_REFUNDED';f.paidOrder.orderHistory.partialRefundEvents=[{state:'PROCESSED_SUCCESSFULLY',refundDetails:{total:f.m(385),tax:f.m(35)}}];
    assert.equal((await f.router.reconcile('play',f.request) as any).reversedNanoUSD,'1845000000');
    f.paidOrder.state='REFUNDED';f.paidOrder.orderHistory.refundEvent={refundDetails:{total:f.m(385),tax:f.m(35)}};
    assert.equal((await f.router.reconcile('play',f.request) as any).reversedNanoUSD,'3690000000');
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
  } finally {await f.cleanup();}
});

integration('zero-decimal regional checkout uses local precision, not USD quote precision',async()=>{
  const f=await fixture('JP','jpy',0,1200,100);try {
    await f.router.reconcile('play',f.request);assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'3690000000');
    f.paidOrder.total=f.m(1201);await assert.rejects(f.play.verify(f.request),/play_order_not_reconciled/);
  } finally {await f.cleanup();}
});


integration('regional HTTP checkout requires the exact server market and schedule while legacy request fields remain unchanged',async()=>{
  const f=await fixture();
  const proxy={hmacKey:'a'.repeat(64),proxyToken:'b'.repeat(64),allowLocalLoopback:false};
  const token=randomBytes(32).toString('base64url');
  const app=createApp({db:f.db,auth:{googleClientID:'synthetic-client'},accounts:{admission:new AuthAdmission(f.db,proxy)},
    minuteCommerce:{purchases:new MinutePurchases(f.db),aiPurchases:f.ai,play:f.play,fulfillment:f.router}});
  try {
    await f.db.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '1 hour')",[randomUUID(),f.account,digest(token)]);
    const headers={authorization:`Bearer ${token}`,'x-mural-client-ip':'192.0.2.150','x-mural-proxy-token':proxy.proxyToken};
    const request={provider:'play',sku:f.row.sku,regionCode:'GB',scheduleVersion:'synthetic-v1'};
    const post=(payload:Record<string,unknown>)=>app.inject({method:'POST',url:'/v1/minutes/orders',headers:{...headers,'idempotency-key':randomUUID()},payload});
    const created=await post(request);assert.equal(created.statusCode,200,created.body);
    assert.equal(created.json().totalMinor,600);assert.equal(created.json().quote.totalMinor,425);
    assert.equal(created.json().quote.play.regionCode,'GB');assert.equal(created.json().quote.play.currencyExponent,2);
    assert.equal((await post({...request,regionCode:'DE'})).statusCode,409);
    assert.equal((await post({...request,scheduleVersion:'stale'})).statusCode,409);
    assert.equal((await post({provider:'play',sku:f.row.sku})).statusCode,409);
    assert.equal((await post({...request,regionCode:'gb'})).statusCode,400);
    assert.equal((await post({...request,totalMinor:1})).statusCode,400);
  } finally {await app.close();await f.cleanup();}
});


test('Console tax estimates are labeled and RSD whole displayed units retain two minor digits',()=>{
  const converted={regionCode:'RS',price:{currencyCode:'RSD',units:'849'},consoleTax:{rateBasisPoints:2000,hasLocationOverrides:false}};
  const rows=makeRegionalPlayCatalog({environment:'test',merchant:scope.merchant,scheduleVersion:'synthetic-rs',policyVersion:1,
    serviceFeeBasisPoints:1500,commissionBasisPoints:3000,estimate,currencyExponents:{rsd:2},regionCodes:['RS'],
    packs:(['small','medium','large'] as const).map(pack=>({pack,providerProduct:`minutes.${pack}`,convertedRegionPrices:{RS:converted}}))});
  const price=rows[0]!.quote.play!;
  assert.equal(price.unitTotalMinor,84900);assert.equal(price.currencyExponent,2);assert.equal(price.taxMinor,14150);
  assert.equal(price.pricingBasis,'fixed-usd-allocation');
  if(price.pricingBasis!=='fixed-usd-allocation') throw new Error('Missing regional quote.');
  assert.equal(price.taxBasis,'console-rate-estimate');assert.equal(price.taxRateBasisPoints,2000);assert.equal(price.hasLocationOverrides,false);
});

test('shared Android wire fixture is generated by the regional server factory',async()=>{
  const generated=makeRegionalPlayAIValueProduct({...common,sku:'play-gb-small-synthetic-regional-v1',aiValueMinor:369,
    play:{pricingBasis:'fixed-usd-allocation',regionCode:'GB',currency:'gbp',currencyExponent:2,unitTotalMinor:649,
      scheduleVersion:'synthetic-regional-v1',taxBasis:'console-rate-estimate',taxRateBasisPoints:2000,hasLocationOverrides:false,
      taxMinor:109,commissionBasisPoints:3000,commissionMinor:162,proceedsMinor:378}});
  const {provider:_provider,merchant:_merchant,...wire}=generated;
  const saved=JSON.parse(await readFile(new URL('../../../shared/fixtures/cross-platform/play-regional-catalog.json',import.meta.url),'utf8'));
  assert.deepEqual(saved,{available:true,maximumQuantity:1,billingBasis:'actual-ai-usage',regionCode:'GB',products:[wire]});
});

integration('Google-funded coupon requires exact attribution and refunds use cumulative paid fractions',async()=>{
  const f=await fixture();try {
    f.paidOrder.total=f.m(400);f.paidOrder.tax=f.m(67);f.paidOrder.lineItems[0].total=f.m(400);f.paidOrder.lineItems[0].tax=f.m(67);
    await assert.rejects(f.play.verify(f.request),/play_order_not_reconciled/);
    f.paidOrder.pointsDetails={pointsCouponValue:f.m(200)};
    await f.router.reconcile('play',f.request);assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'3690000000');
    f.paidOrder.state='PARTIALLY_REFUNDED';
    f.paidOrder.orderHistory.partialRefundEvents=[{state:'PROCESSED_SUCCESSFULLY',refundDetails:{total:f.m(100),tax:f.m(17)}}];
    assert.equal((await f.router.reconcile('play',f.request) as any).reversedNanoUSD,'922500000');
    f.paidOrder.state='REFUNDED';f.paidOrder.orderHistory.refundEvent={refundDetails:{total:f.m(300),tax:f.m(50)}};
    assert.equal((await f.router.reconcile('play',f.request) as any).reversedNanoUSD,'3690000000');
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
  } finally {await f.cleanup();}
});

integration('fully Google-funded coupon never grants fees and a provider void still reverses all credit',async()=>{
  const f=await fixture();try {
    f.paidOrder.total=f.m(0);f.paidOrder.tax=f.m(0);f.paidOrder.lineItems[0].total=f.m(0);f.paidOrder.lineItems[0].tax=f.m(0);
    f.paidOrder.pointsDetails={pointsCouponValue:f.m(600)};
    await f.router.reconcile('play',f.request);assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'3690000000');
    f.paidOrder.state='REFUNDED';f.paidOrder.orderHistory.refundEvent={refundDetails:{total:f.m(0),tax:f.m(0)}};
    assert.equal((await f.router.reconcile('play',f.request) as any).reversedNanoUSD,'3690000000');
  } finally {await f.cleanup();}
});


integration('database regional quote constraint rejects missing country, altered local price and altered USD allocation',async()=>{
  const f=await fixture();try {
    for(const kind of ['missing-region','wrong-local-price','wrong-usd-allocation']) {
      const id=randomUUID(),q=structuredClone(f.row.quote);
      if(kind==='missing-region') delete (q.play as any).regionCode;
      if(kind==='wrong-local-price') q.play!.unitTotalMinor++;
      await assert.rejects(transaction(f.db,async sql=>{
        await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind,quantity)
          VALUES($1,$2,$3,'play','test',$4,$5,$6,$7,$8,NULL,'ai_value',1)`,[id,f.account,randomUUID(),scope.merchant,f.row.sku,f.row.providerProduct,f.row.currency,f.row.totalMinor]);
        await sql.query(`INSERT INTO ai_value_purchase_quotes(order_id,ai_value_nano,ai_value_minor,policy_version,service_fee_basis_points,service_fee_minor,processing_estimate_minor,processing_buffer_minor,quote)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,[id,kind==='wrong-usd-allocation'?'3690000001':f.row.aiValueNanoUSD,
            q.aiValueMinor,q.policyVersion,q.serviceFeeBasisPoints,q.serviceFeeMinor,q.processingEstimateMinor,q.processingBufferMinor,JSON.stringify(q)]);
      }),/ai_value_quote_required/);
      assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders WHERE id=$1',[id])).rows[0].count,'0');
    }
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count,'1');
  } finally {await f.cleanup();}
});
