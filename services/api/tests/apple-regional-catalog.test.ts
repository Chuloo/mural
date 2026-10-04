import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash,randomBytes,randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {Environment,type JWSTransactionDecodedPayload} from '@apple/app-store-server-library';
import {AIValuePurchases,makeAppleAIValueProduct,makeRegionalAppleAIValueProduct,PurchaseFulfillmentRouter} from '../src/ai-value-purchases.js';
import {appleDecimalMinor,type RegionalApplePriceSnapshot} from '../src/apple-market-prices.js';
import {makeRegionalAppleCatalog,type RegionalAppleCatalogInput} from '../src/apple-regional-catalog.js';
import {AppleMinuteProvider,type AppleMinuteTransport} from '../src/apple-minute-provider.js';
import {MinutePurchases} from '../src/minute-purchases.js';
import {MinuteReceiptVault,MinuteDeliveryWorker} from '../src/minute-provider-delivery.js';
import {connectDatabase,transaction} from '../src/db.js';
import {migrate} from '../src/migrate.js';
import {paidAIBalance} from '../src/ledger.js';
import {createApp} from '../src/app.js';
import {Diagnostics,type DiagnosticRecord} from '../src/diagnostics.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if(databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
const source={kind:'app-store-connect-export' as const,sourceURL:'https://appstoreconnect.apple.com/apps/6816001011/distribution/iap/6816506070',
  capturedAt:'2026-10-04T00:00:00Z',sha256:'a'.repeat(64)};
const common={provider:'apple' as const,environment:'live' as const,merchant:'chat.mural.ios',providerProduct:'chat.mural.ios.minutes.small.v1',
  aiValueMinor:369,policyVersion:1,serviceFeeBasisPoints:1500,estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'}};
function product(storefront='FRA',regionCode='FR',currency='eur',currencyExponent=2,customerPrice='7.99',proceeds='5.65958',pack:'small'|'medium'|'large'='small') {
  return makeRegionalAppleAIValueProduct({...common,sku:`apple-${storefront.toLowerCase()}-${pack}-synthetic-v1`,
    providerProduct:`chat.mural.ios.minutes.${pack}.v1`,aiValueMinor:{small:369,medium:766,large:1161}[pack],
    apple:{pricingBasis:'fixed-usd-allocation',storefront,regionCode,currency,currencyExponent,
      unitTotalMinor:appleDecimalMinor(customerPrice,currencyExponent),scheduleVersion:'synthetic-v1',customerPrice,proceeds,mayAdjustAutomatically:true,source}});
}
const verifier={...common,verify:async()=>{throw new Error('No provider access.');}};

test('regional Apple quotes retain fixed credit and exact provider proceeds across shared and zero/three-decimal currencies',()=>{
  const rows=[product(),product('DEU','DE'),product('JPN','JP','jpy',0,'1100','849.9999'),
    product('KOR','KR','krw',0,'11000','8499.99'),product('KWT','KW','kwd',3,'2.199','1.86915')];
  for(const p of rows) {
    assert.equal(p.aiValueNanoUSD,'3690000000');assert.equal(p.quote.currency,'usd');assert.equal(p.quote.currencyExponent,2);
    assert.equal(p.quote.totalMinor,425);assert.equal(p.totalMinor,p.quote.apple!.unitTotalMinor);
    assert.equal(p.quote.processingEstimateMinor,0);assert.equal(p.quote.processingBufferMinor,0);
    assert.equal('proceedsUSDMinor' in p.quote.apple!,false);assert.equal('taxMinor' in p.quote.apple!,false);
  }
  assert.equal(new AIValuePurchases({} as any,{catalog:rows,verifiers:[verifier],salesEnabled:true}).products('apple').length,5);
  assert.throws(()=>new AIValuePurchases({} as any,{catalog:[rows[0]!,{...rows[0]!,sku:'duplicate'}],verifiers:[verifier]}),/invalid_ai_value_catalog/);
  for(const altered of [{...rows[0]!,aiValueNanoUSD:'3690000001'},
    {...rows[0]!,quote:{...rows[0]!.quote,exchangeRateNumerator:'2'}},
    {...rows[0]!,totalMinor:798}]) assert.throws(()=>new AIValuePurchases({} as any,{catalog:[altered],verifiers:[verifier]}),/invalid_ai_value_product/);
});
for(const [pack,price,proceeds] of [['small','149000','114100.99'],['medium','249000','190670.99'],['large','399000','305541']] as const)
  integration(`Apple IDR ${pack} quantity10 settles and refunds exact amounts beyond the old100M cap`,async()=>{
    const f=await fixture(product('IDN','ID','idr',2,price,proceeds,pack));try{
      const order=await f.order(10),request={kind:'client',environment:'live',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'};
      assert.equal(order.totalMinor,Number(price)*1000);assert.ok(order.totalMinor>100_000_000);
      await f.router.reconcile('apple',request);
      assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,order.aiValueNanoUSD);
      f.value.revocationDate=1700000000002;f.value.signedDate=1700000000003;f.value.revocationType='REFUND_PRORATED';f.value.revocationPercentage=50000;
      await f.router.reconcile('apple',request);
      assert.equal((await f.db.query('SELECT refunded_minor FROM ai_value_purchase_transactions WHERE order_id=$1',[order.orderID])).rows[0].refunded_minor,String(order.totalMinor/2));
      f.value.revocationType='REFUND_FULL';f.value.revocationPercentage=100000;f.value.signedDate=1700000000004;
      await f.router.reconcile('apple',request);
      assert.equal((await f.db.query('SELECT refunded_minor FROM ai_value_purchase_transactions WHERE order_id=$1',[order.orderID])).rows[0].refunded_minor,String(order.totalMinor));
      assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
      assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
    } finally {await f.cleanup();}
  });
integration('Apple exact1B order and refund ceilings preserve other providers100M database caps',async()=>{
  const f=await fixture(product('IDN','ID','idr',2,'1000000','500000'));try{
    const order=await f.order(10);assert.equal(order.totalMinor,1_000_000_000);
    assert.throws(()=>product('IDN','ID','idr',2,'1000000.01','500000'));
    await assert.rejects(f.ai.createOrder(f.account,'apple',f.row.sku,randomUUID(),11,{storefront:'IDN',scheduleVersion:'synthetic-v1'}));
    const request={kind:'client',environment:'live',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'};
    await f.router.reconcile('apple',request);
    f.value.revocationDate=1700000000002;f.value.signedDate=1700000000003;f.value.revocationType='REFUND_FULL';f.value.revocationPercentage=100000;
    await f.router.reconcile('apple',request);
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
    for(const [provider,total] of [['stripe',100_000_001],['play',100_000_001],['apple',1_000_000_001]] as const)
      await assert.rejects(f.db.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
        VALUES($1,$2,$3,$4,'test','synthetic','synthetic','synthetic','usd',$5,CASE WHEN $4='apple' THEN NULL ELSE 60000 END,CASE WHEN $4='apple' THEN 'ai_value' ELSE 'minutes' END)`,[randomUUID(),f.account,randomUUID(),provider,total]),
        (error:any)=>error.code==='23514' && error.constraint==='minute_purchase_orders_total_minor_check');
    for(const [provider,total] of [['stripe',100_000_001],['play',100_000_001],['apple',1_000_000_001]] as const)
      await assert.rejects(f.db.query(`INSERT INTO minute_purchase_events(id,order_id,provider,environment,merchant,event_hash,evidence_hash,state,refunded_minor)
        VALUES($1,$2,$3,'test','synthetic',$4,$5,'voided',$6)`,[randomUUID(),order.orderID,provider,'b'.repeat(64),'c'.repeat(64),total]),
        (error:any)=>error.code==='23514' && error.constraint==='minute_purchase_events_refunded_minor_check');
  } finally {await f.cleanup();}
});
test('regional Apple data rejects wrong precision, missing provenance, fabricated payouts and inexact prices',()=>{
  const a=product().quote.apple as RegionalApplePriceSnapshot;
  for(const change of [{currencyExponent:0},{currency:'xxx'},{storefront:'FR'},{regionCode:'FRA'},{customerPrice:'7.991'},
    {unitTotalMinor:798},{proceeds:'8.0'},{proceeds:'-1.0'},{proceeds:'0'},{taxMinor:0},{proceedsUSDMinor:700},
    {mayAdjustAutomatically:undefined},{source:{...source,sha256:'invalid'}},{source:{...source,sourceURL:'https://example.com/prices'}},
    {source:{...source,sourceURL:source.sourceURL+'?token=private'}},{source:{...source,capturedAt:'invalid'}},
    {source:{...source,kind:'app-store-connect-api'}}]) assert.throws(()=>makeRegionalAppleAIValueProduct({...common,sku:'bad',apple:{...a,...change} as any}));
  assert.equal(appleDecimalMinor('2990',2),299000);assert.equal(appleDecimalMinor('149000',2),14900000);
  assert.equal(appleDecimalMinor('1100',0),1100);assert.equal(appleDecimalMinor('2.199',3),2199);
  for(const [amount,exp] of [['1100.001',0],['2.1991',3],['1e3',2],['0',2]] as const) assert.throws(()=>appleDecimalMinor(amount,exp));
});
test('Apple regional generator requires complete pricing and explicit service decisions for every country',()=>{
  const p=product().quote.apple as RegionalApplePriceSnapshot;
  const prices=Object.fromEntries(['small','medium','large'].map(pack=>[pack,{currency:p.currency,customerPrice:p.customerPrice,
    proceeds:p.proceeds,mayAdjustAutomatically:p.mayAdjustAutomatically,source}])) as RegionalAppleCatalogInput['markets'][number]['prices'];
  const input:RegionalAppleCatalogInput={environment:'live',merchant:common.merchant,scheduleVersion:'synthetic-v1',policyVersion:1,
    serviceFeeBasisPoints:1500,estimate:common.estimate,enabledRegionCodes:['FR'],excludedRegionCodes:['CN'],
    markets:[{storefront:'FRA',regionCode:'FR',prices},{storefront:'CHN',regionCode:'CN',prices}]};
  const rows=makeRegionalAppleCatalog(input);
  assert.equal(rows.length,3);assert.deepEqual(rows.map(p=>p.aiValueNanoUSD),['3690000000','7660000000','11610000000']);
  assert.ok(rows.every(p=>p.quote.apple!.storefront==='FRA'));
  for(const change of [{excludedRegionCodes:[]},{enabledRegionCodes:['FR','CN'],excludedRegionCodes:['CN']},
    {markets:[input.markets[0]!,input.markets[0]!]},{markets:[{...input.markets[0]!,prices:{small:prices.small}}]},
    {markets:[{...input.markets[0]!,prices:{...prices,large:{...prices.large,customerPrice:'bad'}}},input.markets[1]!]}])
    assert.throws(()=>makeRegionalAppleCatalog({...input,...change} as any));
});
test('global Apple catalog selects exact storefront and verified scope without exposing source or account data',async()=>{
  const rows=[product(),product('DEU','DE'),product('JPN','JP','jpy',0,'1100','850')];
  const ai=new AIValuePurchases({} as any,{catalog:rows,verifiers:[verifier],salesEnabled:true});
  const events:DiagnosticRecord[]=[],app=createApp({db:{} as any,auth:{},diagnostics:new Diagnostics(r=>{events.push(r);}),
    minuteCommerce:{purchases:new MinutePurchases({} as any),aiPurchases:ai,apple:{environment:'live'} as any}});
  try {
    for(const code of ['FRA','DEU','JPN']) {
      const res=await app.inject(`/v1/minutes/products?provider=apple&storefront=${code}`);
      assert.equal(res.statusCode,200,res.body);assert.equal(res.json().products.length,1);assert.equal(res.json().products[0].storefront,code);
      assert.doesNotMatch(res.body,/proceeds|sourceURL|sha256|customerPrice|regionCode/);
    }
    for(const code of ['CHN','fra','private-country']) {
      const res=await app.inject(`/v1/minutes/products?provider=apple&storefront=${code}`);
      assert.equal(res.statusCode,200);assert.equal(res.json().available,false);assert.equal(events.filter(e=>e.event==='apple_catalog').at(-1)!.storefront,'unsupported');
    }
  } finally {await app.close();}
});


// Pin the production032 migration history used by the upgrade fixture.
const prior033Migrations=[
  ['001_billing.sql','3ed5ab59cbd61a2b996540043bf4b5743b6eb2bb787e40f2a72428e4c2b1a008'],
  ['002_checkout_attempts.sql','b046fe364b1426f14d7ce6af6a9ac06df4e4e107573c4adfa31d8de0a3c36cb1'],
  ['003_hosted_sessions.sql','9abe3bb73c69d9d3401c8520ed3ed53c6a8f727e7aae3cbe9e79f8b0ed00f442'],
  ['004_access_requests.sql','29f8815a4043150ca552d7956228fc4dabb0db46e7b17d4e5dcb977cfafcfb43'],
  ['005_account_readiness.sql','050aafb80d00594d47b064aa93b571c10569601cb1c79cd32e4e4d651d4ef0b9'],
  ['006_conversation_minutes.sql','f690c8610dc6637cc90b936d3a043e545eea90fb865db09cd6d3dec4e28ebf84'],
  ['007_welcome_funding.sql','980e0fdaabee99ffd0db4154935876b8a22a48c45a61f2370113df84e6b17dbd'],
  ['008_minute_purchases.sql','a2fd720bc9ad8b517b18abc2b544e50982914e028c926b0477bbc2d96b4f6091'],
  ['009_minute_provider_delivery.sql','688d72cbb250c252a3c9054e190cafe7f1107425cf681483971db96ecfefef6e'],
  ['010_ai_feedback.sql','46d76c214f63591df49bcd223e5da25491b67dfc7cc3a02f145e01af8e74bedc'],
  ['011_hosted_conversation_minutes.sql','67a9044379834d4b0d76d0e121250e66bf50bc5f3d31ae747c53460ebe0db209'],
  ['012_hosted_helpers.sql','6e81c46015e2c7541c14f996c1e546d176a50f71ca043008d99adf4410b03fdb'],
  ['013_minute_play_void_cursor.sql','ea7863cc8c811753cf32dcac5f28e1dec5140fc56a332b45a379e30344657e8f'],
  ['014_hosted_minimum_and_earned_helpers.sql','b991af298617c793e3e2ae98581f8a769217d6cb7095647829aba5963579bd7e'],
  ['015_public_guest_minutes.sql','9fd0e8e2c55ca08a92639c44c03dceeddbab121ad41a52fee3e3bd8269b2bcb9'],
  ['016_ai_pricing_policy.sql','2c737a85f84505708cf88c3d8a6cfd4db744f79472aadf7cd66a138a8aadd9f1'],
  ['017_ai_value_purchases.sql','ba1c7faf3ba0b6ce4d7344e5bf9bcb0095a3bbd3738d797e5c582849b42ce0cb'],
  ['018_hosted_actual_ai_value.sql','8c09186ad3178202095d40114af0245441daeaae18d13bcbcdba9c37f22a7365'],
  ['019_live_create_rejection.sql','25de70120fbd2e04e41962e2e81d664a9bb8305a8bc593dedf46604fba15b5c3'],
  ['020_hosted_startup_recoveries.sql','061a72850033374ecafbf4cfbd8e47bdfd819e6d0f1fe5257e5f70aac8a23a23'],
  ['021_stripe_managed_payments.sql','3b63383e8695d6f6291a1c26ba9305c4d947913f0f59ae92496358adfdabfa13'],
  ['022_stripe_provider_amount_bounds.sql','ae32fd53907278ba7cdc5f8300140898c9eae55f9d8f06d563a77d9cac4039fb'],
  ['023_deferred_guest_links.sql','00604ae0b9a0595e671abffdcbff5271fdd457600b966c3abda2c2f2cf922fb7'],
  ['024_purchase_quantity.sql','79e230892fc9a2ceb6b243468f1af59833af9fda8d8a2e2c8b71ea586dfa08b8'],
  ['025_apple_purchases.sql','3e6afd301f6524d5538de9a4fde494ed5b2fbb944af1fd67feb3eae6a4ea26f9'],
  ['026_minutes_presentation.sql','1f6a192adadc1ea8ecd6ba77b994774b09e02820717152500f2b2da7b7deccc6'],
  ['027_hosted_close_intents.sql','bba592a3490be4f7098965ed3d52f6004c70dcf9acebfa5a98f25a7a07f99676'],
  ['028_deployment_environment.sql','a997e470e64c0379bf74fa1bd192974dfcad9f05f7ab5bcf290015b53e1cd56d'],
  ['029_apple_notification_cursor.sql','fc54c449f8d8e931aacb28cae557ed6455ebe270bab6b2d45ad6601a3300ebd4'],
  ['030_apple_refund_declined.sql','87b406ae887f77081db24f960610987ffe13c422003bd4b43248b9568e59af5d'],
  ['031_play_regional_quotes.sql','a6a83dc58650b14c32968dfa7df23736825e04d40baea3dd63b74e21a5b65aa7'],
  ['032_apple_funding_scope.sql','835d12fd91756c018d70fb6403a14ab3c82cf5b1f7991289217530a3ca5f8a53'],
] as const;

async function fixture(p=product(),mode:'live'|'test'='live',before033=false) {
  const schema=`apple_regional_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);
  if(before033) await transaction(db,async sql=>{
    await sql.query('CREATE TABLE schema_migrations(name text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT now())');
    const directory=new URL('../migrations/',import.meta.url);
    for(const [file,expectedSHA256] of prior033Migrations) {
      const migration=await readFile(new URL(file,directory),'utf8');
      assert.equal(createHash('sha256').update(migration).digest('hex'),expectedSHA256,'Historical migration fixture must match its reviewed source');
      await sql.query(migration);
      await sql.query('INSERT INTO schema_migrations(name) VALUES($1)',[file]);
    }
  });else await migrate(db);
  const value:JWSTransactionDecodedPayload={};
  const transport:AppleMinuteTransport={transaction:async(jws)=>{assert.equal(jws,'valid.signed.transaction');return structuredClone(value);},
    latest:async()=> 'valid.signed.transaction',notification:async()=>{throw new Error('Unused');},history:async()=>({notifications:[]})};
  const vault=new MinuteReceiptVault(db,'test',new Map([['test',randomBytes(32)]]));
  const apple=new AppleMinuteProvider(db,vault,{bundleID:common.merchant,appAppleID:6816001011,environment:mode,allowLive:mode==='live',
    signingKey:'unused',keyID:'TESTKEY123',issuerID:randomUUID(),rootCertificates:[],purchasesEnabled:true},transport);
  const row={...p,environment:mode},ai=new AIValuePurchases(db,{catalog:[row],verifiers:[apple],salesEnabled:true,quantityEnabled:['apple']});
  const router=new PurchaseFulfillmentRouter(db,new MinutePurchases(db,{verifiers:[apple]}),ai,[apple]);
  const account=randomUUID();await db.query('INSERT INTO accounts(id) VALUES($1)',[account]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[account]);
  return {db,schema,vault,apple,ai,row,value,router,account,
    async order(quantity=1,key=randomUUID()) {
      const order=await ai.createOrder(account,'apple',row.sku,key,quantity,{storefront:row.quote.apple!.storefront,scheduleVersion:row.quote.apple!.scheduleVersion},undefined,mode);
      Object.assign(value,{bundleId:common.merchant,environment:mode==='live'?Environment.PRODUCTION:Environment.SANDBOX,type:'Consumable',
        inAppOwnershipType:'PURCHASED',transactionId:'1000000000001',appAccountToken:order.orderID,productId:row.providerProduct,quantity,
        currency:row.currency.toUpperCase(),price:row.totalMinor*quantity*10**(3-row.quote.apple!.currencyExponent),storefront:row.quote.apple!.storefront,
        purchaseDate:1700000000000,signedDate:1700000000001});return order;
    },async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}
for(const [code,region,currency,exponent,price,proceeds] of [['FRA','FR','eur',2,'7.99','5.65958'],['JPN','JP','jpy',0,'1100','850'],['KWT','KW','kwd',3,'2.199','1.8']] as const)
  integration(`Apple ${currency} receipts bind exact original terms, grant once and recover/refund after catalog replacement`,async()=>{
    const f=await fixture(product(code,region,currency,exponent,price,proceeds));try{
      const key=randomUUID(),order=await f.order(2,key),request={kind:'client',environment:'live',accountID:f.account,orderID:order.orderID,signedTransaction:'valid.signed.transaction'};
      for(const change of [{storefront:'USA'},{currency:'USD'},{price:f.value.price!+1},{quantity:1}]) {
        const original={...f.value};Object.assign(f.value,change);await assert.rejects(f.router.reconcile('apple',request),{code:'purchase_verification_failed'});Object.assign(f.value,original);
      }
      await Promise.all([f.router.reconcile('apple',request),f.router.reconcile('apple',request)]);
      assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'7380000000');
      assert.equal((await paidAIBalance(f.db,f.account,'test')).balanceNanoUSD,'0');
      const after=new AIValuePurchases(f.db,{catalog:[],verifiers:[f.apple],salesEnabled:true});
      assert.deepEqual(await after.createOrder(f.account,'apple',f.row.sku,key,2,{storefront:code,scheduleVersion:'synthetic-v1'}),order);
      await assert.rejects(after.createOrder(f.account,'apple',f.row.sku,key,2,{storefront:'USA',scheduleVersion:'synthetic-v1'}),{code:'idempotency_conflict'});
      const worker=new MinuteDeliveryWorker(f.db,f.router,[f.apple]);assert.equal((await worker.runBatch()).completed,1);
      await f.router.reconcile('apple',{kind:'recovery',environment:'live',accountID:f.account,signedTransaction:'valid.signed.transaction'});
      assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
      f.value.revocationDate=1700000000002;f.value.signedDate=1700000000003;f.value.revocationType='REFUND_PRORATED';f.value.revocationPercentage=50000;
      assert.equal((await f.router.reconcile('apple',request) as any).reversedNanoUSD,'3690000000');
      f.value.revocationType='REFUND_FULL';f.value.revocationPercentage=100000;f.value.signedDate=1700000000004;
      assert.equal((await f.router.reconcile('apple',request) as any).reversedNanoUSD,'7380000000');
      assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
    }finally{await f.cleanup();}
  });
integration('regional Apple order trigger rejects altered local price, USD allocation and source provenance',async()=>{
  const f=await fixture();try{
    const o=await f.order(),saved=(await f.db.query('SELECT * FROM ai_value_purchase_quotes WHERE order_id=$1',[o.orderID])).rows[0];
    for(const change of [{apple:{...saved.quote.apple,storefront:'FR'}},{apple:{...saved.quote.apple,unitTotalMinor:1}},
      {apple:{...saved.quote.apple,customerPrice:'8.99'}},{apple:{...saved.quote.apple,source:{...source,sha256:'bad'}}},
      {currency:'eur'},{totalMinor:1}]) {
      await assert.rejects(transaction(f.db,async sql=>{
        const id=randomUUID();await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,entitlement_kind,quantity)
          VALUES($1::uuid,$2,$1::uuid::text,'apple','live',$3,'changed',$4,'eur',799,'ai_value',1)`,[id,f.account,common.merchant,common.providerProduct]);
        await sql.query(`INSERT INTO ai_value_purchase_quotes(order_id,ai_value_nano,ai_value_minor,policy_version,service_fee_basis_points,service_fee_minor,processing_estimate_minor,processing_buffer_minor,quote)
          VALUES($1,3690000000,369,1,1500,56,0,0,$2)`,[id,JSON.stringify({...saved.quote,...change})]);
      }),/ai_value_quote_required/);
    }
  } finally {await f.cleanup();}
});
for(const storefront of ['USA','NOR'] as const) integration(`migration033 preserves prior032 ${storefront} orders, quotes and recovery`,async()=>{
  const legacy=makeAppleAIValueProduct({...common,sku:`legacy-${storefront.toLowerCase()}`,apple:{storefront,currency:storefront==='USA'?'usd':'nok',
    currencyExponent:2,unitTotalMinor:storefront==='USA'?700:8900,scheduleVersion:'synthetic-v1',commissionBasisPoints:3000,
    taxMinor:storefront==='USA'?0:1780,commissionMinor:storefront==='USA'?210:2136,proceedsMinor:storefront==='USA'?490:4984,
    proceedsUSDMinor:490,residualUSDMinor:65}});
  const f=await fixture(legacy,'live',true);try{
    assert.equal((await f.db.query('SELECT count(*) FROM schema_migrations')).rows[0].count,'32');
    const key=randomUUID(),order=await f.order(2,key);
    const quotes=(await f.db.query('SELECT * FROM ai_value_purchase_quotes WHERE order_id=$1',[order.orderID])).rows;
    const original=(await f.db.query('SELECT * FROM minute_purchase_orders WHERE id=$1',[order.orderID])).rows;
    await migrate(f.db);await migrate(f.db);
    assert.equal((await f.db.query('SELECT count(*) FROM schema_migrations')).rows[0].count,'33');
    assert.deepEqual((await f.db.query('SELECT * FROM ai_value_purchase_quotes WHERE order_id=$1',[order.orderID])).rows,quotes);
    assert.deepEqual((await f.db.query('SELECT * FROM minute_purchase_orders WHERE id=$1',[order.orderID])).rows,original);
    const replacement=new AIValuePurchases(f.db,{catalog:[product()],verifiers:[f.apple],salesEnabled:true,quantityEnabled:['apple']});
    assert.deepEqual(await replacement.createOrder(f.account,'apple',legacy.sku,key,2,{storefront,scheduleVersion:'synthetic-v1'}),order);
    await f.router.reconcile('apple',{kind:'recovery',environment:'live',accountID:f.account,signedTransaction:'valid.signed.transaction'});
    await f.router.reconcile('apple',{kind:'recovery',environment:'live',accountID:f.account,signedTransaction:'valid.signed.transaction'});
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'7380000000');
    assert.equal((await f.db.query("SELECT count(*) FROM ledger WHERE kind='purchase'")).rows[0].count,'1');
    f.value.revocationDate=1700000000002;f.value.signedDate=1700000000003;f.value.revocationType='REFUND_FULL';f.value.revocationPercentage=100000;
    await f.router.reconcile('apple',{kind:'recovery',environment:'live',accountID:f.account,signedTransaction:'valid.signed.transaction'});
    assert.equal((await paidAIBalance(f.db,f.account)).balanceNanoUSD,'0');
  } finally {await f.cleanup();}
});
