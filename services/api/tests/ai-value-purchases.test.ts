import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { AIValuePurchases, makeAIValueProduct, PurchaseFulfillmentRouter, refundedAIValue, type AIValueProduct,
  type AIValueProductInput } from '../src/ai-value-purchases.js';
import { MinutePurchases, type MinutePurchaseVerifier, type VerifiedMinutePurchase, type PurchaseEnvironment } from '../src/minute-purchases.js';
import { connectDatabase, transaction } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { appendEntry, paidAIBalance, reserve, settle } from '../src/ledger.js';
import { updateAIPricingPolicy } from '../src/ai-top-up-pricing.js';
import { applyStripeEvent } from '../src/payments.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
// Synthetic amounts and rates only. No merchant offers or live provider calls are created by these tests.
function input(environment:PurchaseEnvironment='live',overrides:Partial<AIValueProductInput>={}):AIValueProductInput {
  return {provider:'stripe',environment,merchant:'acct_syntheticvalue',sku:'synthetic-ai',providerProduct:'price_syntheticvalue',
    currency:'usd',currencyExponent:2,aiValueMinor:1000,policyVersion:1,serviceFeeBasisPoints:1500,
    processing:{rateBasisPoints:321,fixedMinor:23,bufferBasisPoints:71},
    exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'},...overrides};
}
async function fixture() {
  const schema=`aivalue_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  const evidence=new Map<string,VerifiedMinutePurchase>();let verifications=0;
  const product=makeAIValueProduct(input());
  const verifier:MinutePurchaseVerifier={provider:product.provider,environment:product.environment,merchant:product.merchant,verify:async value=>{
    verifications++;if (typeof value!=='string'||!evidence.has(value)) throw new Error('Provider verification failed');return evidence.get(value)!;
  }};
  const ai=new AIValuePurchases(db,{catalog:[product],verifiers:[verifier],salesEnabled:true});
  const legacyProduct={provider:product.provider,environment:product.environment,merchant:product.merchant,sku:'historical-synthetic',
    providerProduct:'price_historical',currency:'usd',totalMinor:997,minutes:30};
  const legacy=new MinutePurchases(db,{catalog:[legacyProduct],verifiers:[verifier],salesEnabled:true});
  const router=new PurchaseFulfillmentRouter(db,legacy,ai,[verifier]);
  return {db,ai,legacy,router,product,verifier,evidence,schema,url:url.toString(),get verifications(){return verifications;},
    async account(guest=false){const id=randomUUID();await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)',[id,guest]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[id]);return id;},
    async order(account:string,key=randomUUID()){return ai.createOrder(account,'stripe',product.sku,key);},
    proof(order:{orderID:string;providerProduct:string;currency:string;totalMinor:number},overrides:Partial<VerifiedMinutePurchase>={}){
      const value:VerifiedMinutePurchase={provider:product.provider,environment:product.environment,merchant:product.merchant,orderID:order.orderID,
        transactionID:`synthetic:${randomUUID()}`,eventID:`snapshot:${randomUUID()}`,providerProduct:order.providerProduct,quantity:1,
        currency:order.currency,totalMinor:order.totalMinor,state:'purchased',refundedMinor:0,...overrides};
      const key=randomUUID();evidence.set(key,value);return {key,value};
    },async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}

test('AI quote computes only the reviewed allocation, separate fees and a conservative non-entitlement estimate',()=>{
  const p=makeAIValueProduct(input());assert.equal(p.aiValueNanoUSD,'10000000000');assert.equal(p.estimatedMilliseconds,6_000_000);
  assert.equal(p.estimate,true);assert.equal(p.entitlementKind,'ai_value');assert.equal(p.billingBasis,'actual-ai-usage');
  assert.equal(p.quote.serviceFeeMinor,150);assert.equal(p.totalMinor,p.quote.aiValueMinor+p.quote.serviceFeeMinor+p.quote.processingEstimateMinor+p.quote.processingBufferMinor);
  assert.ok(p.quote.processingEstimateMinor>0);assert.ok(p.quote.processingBufferMinor>0);assert.ok(Object.isFrozen(p.quote));
  const euro=makeAIValueProduct(input('test',{currency:'eur',exchangeRate:{numerator:'11',denominator:'10',version:'synthetic-fx'}}));
  assert.equal(euro.aiValueNanoUSD,'11000000000');
});
test('catalog rejects claimed nano, minute estimate or fee totals that differ from reviewed quote arithmetic',()=>{
  const p=makeAIValueProduct(input());const verifier={...p,verify:async()=>{throw new Error();}};
  for (const bad of [{...p,aiValueNanoUSD:'10000000001'},{...p,estimatedMilliseconds:1},{...p,totalMinor:p.totalMinor+1},
    {...p,quote:{...p.quote,serviceFeeMinor:0}},{...p,quote:{...p.quote,processingBufferMinor:999}},
    {...p,quote:{...p.quote,exchangeRateNumerator:'2'}}])
    assert.throws(()=>new AIValuePurchases({} as any,{catalog:[bad],verifiers:[verifier]}),/invalid_ai_value_product/);
  for (const change of [{currencyExponent:3},{aiValueMinor:0},{exchangeRate:{numerator:'0',denominator:'1',version:'bad'}},
    {estimate:{nanoUSDPerMinute:'0',rateVersion:'bad'}}]) assert.throws(()=>makeAIValueProduct(input('test',change)),/invalid_ai_value_product/);
});
test('partial refund reverses only the original AI allocation proportion, rounded once, never checkout fees',()=>{
  assert.equal(refundedAIValue(10_000_000_000n,1,1200),8_333_334n);
  assert.equal(refundedAIValue(10_000_000_000n,600,1200),5_000_000_000n);
  assert.equal(refundedAIValue(10_000_000_000n,1200,1200),10_000_000_000n);
  for (const [refund,total] of [[-1,100],[101,100],[0.5,100],[1,0]])assert.throws(()=>refundedAIValue(1n,refund!,total!),/invalid_ai_value_refund/);
});
integration('default-off AI sales and guest exclusion never create payable orders',async()=>{
  const f=await fixture();try{
    const member=await f.account(),disabled=new AIValuePurchases(f.db,{catalog:[f.product],verifiers:[f.verifier]});
    assert.deepEqual(disabled.products('stripe'),[]);await assert.rejects(disabled.createOrder(member,'stripe',f.product.sku,randomUUID()),/ai_value_purchases_unavailable/);
    await assert.rejects(f.order(await f.account(true)),/purchase_requires_account/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('immutable order snapshots survive fee changes and reject tampering or missing quote',async()=>{
  const f=await fixture();try{
    const member=await f.account(),key=randomUUID(),order=await f.order(member,key);
    assert.equal((await f.db.query('SELECT allowance_ms FROM minute_purchase_orders WHERE id=$1',[order.orderID])).rows[0].allowance_ms,null);
    await assert.rejects(f.db.query('UPDATE ai_value_purchase_quotes SET ai_value_nano=1 WHERE order_id=$1',[order.orderID]),/immutable/);
    await assert.rejects(f.db.query("UPDATE minute_purchase_orders SET entitlement_kind='minutes',allowance_ms=60000 WHERE id=$1",[order.orderID]),/immutable/);
    await updateAIPricingPolicy(f.db,{version:1,serviceFeeBasisPoints:900},'synthetic operator','testing quoted policy snapshot');
    assert.deepEqual(await f.order(member,key),order);await assert.rejects(f.order(member),/ai_pricing_changed_review_quote/);
    await assert.rejects(f.db.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
      VALUES($1,$2,$3,'stripe','test','acct_synthetic','missing','price_missing','usd',100,NULL,'ai_value')`,[randomUUID(),member,randomUUID()]),/ai_value_quote_required/);
    const proof=f.proof(order);await f.ai.reconcile('stripe',proof.key);assert.equal((await paidAIBalance(f.db,member)).balanceNanoUSD,order.aiValueNanoUSD);
  }finally{await f.cleanup();}
});
integration('unverified cash blocks both new checkout and pending checkout retries while existing receipts can resolve',async()=>{
  const f=await fixture();try{
    const member=await f.account(),key=randomUUID(),order=await f.order(member,key);
    await f.db.query('UPDATE wallets SET cash_provenance_verified=false WHERE account_id=$1',[member]);
    await assert.rejects(f.order(member),/cash_balance_reconciliation_required/);
    await assert.rejects(f.order(member,key),/cash_balance_reconciliation_required/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders WHERE account_id=$1',[member])).rows[0].count,'1');
    assert.equal((await f.ai.status(member,order.orderID)).state,'created');
    const paid=f.proof(order);await f.router.reconcile('stripe',paid.key);
    assert.equal((await f.ai.status(member,order.orderID)).grantedNanoUSD,order.aiValueNanoUSD);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'0');
    await assert.rejects(f.order(member,key),/cash_balance_reconciliation_required/);
    await f.router.reconcile('stripe',f.proof(order,{transactionID:paid.value.transactionID,state:'voided'}).key);
    assert.equal((await f.ai.status(member,order.orderID)).reversedNanoUSD,order.aiValueNanoUSD);
    await f.db.query('UPDATE wallets SET cash_provenance_verified=true WHERE account_id=$1',[member]);
    assert.deepEqual(await f.order(member,key),order);
  }finally{await f.cleanup();}
});
integration('the actual runtime role creates, fulfills and refunds AI orders without permission to edit pricing policy',async()=>{
  const f=await fixture(),role=`aivalue_runtime_${randomUUID().replaceAll('-','')}`;
  let runtime:ReturnType<typeof connectDatabase>|undefined;
  try{
    const member=await f.account();
    await f.db.query(`CREATE ROLE ${role};GRANT USAGE ON SCHEMA ${f.schema} TO ${role};GRANT SELECT,UPDATE ON accounts TO ${role}`);
    for(const file of ['minute-runtime-grants.sql','minute-purchase-runtime-grants.sql','actual-value-runtime-grants.sql']){
      const grants=await readFile(new URL(`../operations/${file}`,import.meta.url),'utf8');
      await f.db.query(grants.replaceAll('mural_runtime',role));
    }
    const url=new URL(f.url);url.searchParams.set('options',`-c search_path=${f.schema} -c role=${role}`);
    runtime=connectDatabase(url.toString());
    // A caller-controlled temporary table must not replace the privileged function's table.
    const probe=await runtime.connect(),writer=await f.db.connect();
    try{
      await probe.query('CREATE TEMP TABLE ai_pricing_policy(singleton boolean,version integer,service_fee_basis_points integer)');
      await probe.query('INSERT INTO pg_temp.ai_pricing_policy VALUES(true,999,0)');
      await probe.query('BEGIN');
      assert.deepEqual((await probe.query('SELECT * FROM lock_ai_pricing_policy()')).rows,[{version:1,service_fee_basis_points:1500}]);
      await writer.query("SET lock_timeout='200ms'");
      await assert.rejects(writer.query(`UPDATE ${f.schema}.ai_pricing_policy SET service_fee_basis_points=900 WHERE singleton`),
        (error:any)=>error.code==='55P03');
      await probe.query('ROLLBACK');
      await assert.rejects(probe.query(`UPDATE ${f.schema}.ai_pricing_policy SET service_fee_basis_points=0`),/permission denied/);
      await assert.rejects(probe.query(`DELETE FROM ${f.schema}.ai_pricing_policy`),/permission denied/);
      await assert.rejects(probe.query(`INSERT INTO ${f.schema}.ai_pricing_audit(id,actor,reason,previous_policy,next_policy) VALUES($1,'test','test','{}','{}')`,[randomUUID()]),/permission denied/);
    }finally{await probe.query('ROLLBACK').catch(()=>{});await writer.query('RESET lock_timeout');probe.release();writer.release();}
    const purchases=new AIValuePurchases(runtime,{catalog:[f.product],verifiers:[f.verifier],salesEnabled:true});
    const order=await purchases.createOrder(member,'stripe',f.product.sku,randomUUID()),proof=f.proof(order);
    const purchase=await purchases.reconcile('stripe',proof.key);assert.equal(purchase.grantedNanoUSD,order.aiValueNanoUSD);
    assert.equal((await paidAIBalance(runtime,member)).availableNanoUSD,order.aiValueNanoUSD);
    const refunded=await purchases.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,state:'voided'}).key);
    assert.equal(refunded.reversedNanoUSD,order.aiValueNanoUSD);
    assert.equal((await paidAIBalance(runtime,member)).availableNanoUSD,'0');
    assert.equal((await f.db.query(`SELECT service_fee_basis_points FROM ${f.schema}.ai_pricing_policy`)).rows[0].service_fee_basis_points,1500);
  }finally{
    await runtime?.end();
    if((await f.db.query('SELECT 1 FROM pg_roles WHERE rolname=$1',[role])).rowCount){await f.db.query(`DROP OWNED BY ${role};DROP ROLE ${role}`);}
    await f.cleanup();
  }
});
integration('verified purchase and concurrent replay add exact AI allocation once and no fixed minutes',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),proof=f.proof(order);
    const values=await Promise.all(Array.from({length:5},()=>f.router.reconcile('stripe',proof.key)));
    assert.equal(f.verifications,5);assert.ok(values.every(value=>value.fulfillmentRecorded));
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,order.aiValueNanoUSD);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger WHERE account_id=$1',[member])).rows[0].count,'1');
    assert.equal((await f.db.query('SELECT count(*) FROM minute_entries WHERE account_id=$1',[member])).rows[0].count,'0');
    await assert.rejects(f.legacy.reconcile('stripe',proof.key),/purchase_entitlement_mismatch/);
    await assert.rejects(f.ai.status(await f.account(),order.orderID),/purchase_not_found/);
  }finally{await f.cleanup();}
});
integration('pending, cancellation before fulfillment and late purchased event never create usable cash',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),pending=f.proof(order,{state:'pending'});
    assert.equal((await f.ai.reconcile('stripe',pending.key)).fulfillmentRecorded,false);
    const voided=f.proof(order,{transactionID:pending.value.transactionID,state:'voided'});await f.ai.reconcile('stripe',voided.key);
    const late=f.proof(order,{transactionID:pending.value.transactionID});assert.equal((await f.ai.reconcile('stripe',late.key)).state,'voided');
    assert.equal((await paidAIBalance(f.db,member)).balanceNanoUSD,'0');
  }finally{await f.cleanup();}
});
integration('partial and full refunds are cumulative and stale provider snapshots cannot restore AI value',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),purchase=f.proof(order);await f.ai.reconcile('stripe',purchase.key);
    const partial=f.proof(order,{transactionID:purchase.value.transactionID,refundedMinor:17});
    const partialResult=await f.ai.reconcile('stripe',partial.key);assert.equal(partialResult.reversedNanoUSD,refundedAIValue(BigInt(order.aiValueNanoUSD),17,order.totalMinor).toString());
    await f.ai.reconcile('stripe',partial.key);await f.ai.reconcile('stripe',purchase.key);
    const full=f.proof(order,{transactionID:purchase.value.transactionID,refundedMinor:order.totalMinor});
    assert.equal((await f.ai.reconcile('stripe',full.key)).reversedNanoUSD,order.aiValueNanoUSD);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'0');
  }finally{await f.cleanup();}
});
integration('refund while reserved preserves the hold and later top-ups first repay actual AI debt',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),proof=f.proof(order);await f.ai.reconcile('stripe',proof.key);
    const hold=await reserve(f.db,member,randomUUID(),8_000_000_000n);
    const refund=f.proof(order,{transactionID:proof.value.transactionID,state:'voided'});await f.ai.reconcile('stripe',refund.key);
    const before=await paidAIBalance(f.db,member);assert.equal(before.balanceNanoUSD,'0');assert.equal(before.reservedNanoUSD,'8000000000');assert.equal(before.availableNanoUSD,'0');
    await settle(f.db,hold,7_000_000_000n);assert.equal((await paidAIBalance(f.db,member)).balanceNanoUSD,'-7000000000');
    const second=await f.order(member);await f.ai.reconcile('stripe',f.proof(second).key);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'3000000000');
  }finally{await f.cleanup();}
});
integration('sandbox value and sandbox refunds cannot fund public conversations or consume real value',async()=>{
  const f=await fixture();try{
    const member=await f.account(),live=await f.order(member);await f.ai.reconcile('stripe',f.proof(live).key);
    const product=makeAIValueProduct(input('test')),proofs=new Map<string,VerifiedMinutePurchase>();
    const verifier={provider:product.provider,environment:product.environment,merchant:product.merchant,verify:async(key:unknown)=>proofs.get(key as string)!};
    const sandbox=new AIValuePurchases(f.db,{catalog:[product],verifiers:[verifier],salesEnabled:true});
    const order=await sandbox.createOrder(member,'stripe',product.sku,randomUUID());
    const proof={...f.proof(order).value,environment:'test' as const};proofs.set('purchase',proof);await sandbox.reconcile('stripe','purchase');
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,live.aiValueNanoUSD);
    proofs.set('refund',{...proof,eventID:randomUUID(),state:'voided'});await sandbox.reconcile('stripe','refund');
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,live.aiValueNanoUSD);
    const row=(await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[member])).rows[0];
    assert.equal(row.balance_nano,live.aiValueNanoUSD);assert.equal(row.sandbox_balance_nano,'0');
  }finally{await f.cleanup();}
});
integration('mismatched evidence and one receipt crossing historical and AI-value orders are rejected',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member);
    for (const overrides of [{totalMinor:order.totalMinor+1},{currency:'eur'},{quantity:2},{merchant:'acct_wrong'}])
      await assert.rejects(f.ai.reconcile('stripe',f.proof(order,overrides).key));
    const legacy=await f.legacy.createOrder(member,'stripe','historical-synthetic',randomUUID()),proof=f.proof(legacy);
    await f.router.reconcile('stripe',proof.key);
    await assert.rejects(f.ai.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID}).key),/purchase_transaction_conflict/);
    const claimed=f.proof(order);await f.ai.reconcile('stripe',claimed.key);
    const second=await f.legacy.createOrder(member,'stripe','historical-synthetic',randomUUID());
    await assert.rejects(f.legacy.reconcile('stripe',f.proof(second,{transactionID:claimed.value.transactionID}).key),/purchase_transaction_conflict/);
  }finally{await f.cleanup();}
});
integration('same event identity with changed facts fails without modifying paid value',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),proof=f.proof(order);await f.ai.reconcile('stripe',proof.key);
    await assert.rejects(f.ai.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,eventID:proof.value.eventID,refundedMinor:1}).key),/purchase_event_conflict/);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,order.aiValueNanoUSD);
  }finally{await f.cleanup();}
});
integration('encrypted receipt and delivery retry use the shared router without granting the AI allocation twice',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order(member),proof=f.proof(order);let complete=0,verified=0;
    const vault=new MinuteReceiptVault(f.db,'test',new Map([['test',Buffer.alloc(32,7)]]));
    const adapter={...f.verifier,verify:async()=>{verified++;return proof.value;},complete:async()=>{complete++;if (complete===1) throw new Error('Synthetic acknowledgment failure');}};
    const ai=new AIValuePurchases(f.db,{verifiers:[adapter]}),legacy=new MinutePurchases(f.db,{verifiers:[adapter]});
    const worker=new MinuteDeliveryWorker(f.db,new PurchaseFulfillmentRouter(f.db,legacy,ai,[adapter]),[adapter]);
    await vault.save(order.orderID,adapter,proof.value.transactionID);
    assert.deepEqual(await worker.runBatch(1),{processed:1,completed:0,retried:1});
    await vault.schedule(order.orderID);assert.deepEqual(await worker.runBatch(1),{processed:1,completed:1,retried:0});
    assert.equal(verified,2);assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,order.aiValueNanoUSD);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger WHERE account_id=$1',[member])).rows[0].count,'1');
  }finally{await f.cleanup();}
});

integration('legacy Stripe sandbox grants and refunds cannot become public paid funds after the schema upgrade',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=randomUUID();
    await f.db.query(`INSERT INTO checkout_orders(id,account_id,idempotency_key,product,currency,total_minor,credit_nano,stripe_price_id)
      VALUES($1,$2,$3,'legacy-test','usd',1150,10000000000,'price_legacy')`,[order,member,randomUUID()]);
    const paid={id:'cs_legacy',client_reference_id:order,mode:'payment',currency:'usd',amount_total:1150,payment_status:'paid',payment_intent:'pi_legacy'};
    const event=(type:string,object:any)=>({id:`evt_${randomUUID()}`,livemode:false,type,data:{object}} as any);
    await applyStripeEvent(f.db,event('checkout.session.completed',paid));
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'0');
    assert.equal((await f.db.query('SELECT sandbox_balance_nano FROM wallets WHERE account_id=$1',[member])).rows[0].sandbox_balance_nano,'10000000000');
    await applyStripeEvent(f.db,event('charge.refunded',{id:'ch_legacy',payment_intent:'pi_legacy',amount_refunded:1150}));
    await applyStripeEvent(f.db,event('checkout.session.completed',paid));
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'0');
    assert.equal((await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[member])).rows[0].balance_nano,'0');
  }finally{await f.cleanup();}
});
