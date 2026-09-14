import { createHash, randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';
import { quoteAITopUp, estimatedConversationMilliseconds, type ProcessingCost } from './ai-top-up-pricing.js';
import { type MinutePurchases, type MinutePurchaseStatus, type MinutePurchaseVerifier, type PurchaseProvider,
  type PurchaseScope, type VerifiedMinutePurchase } from './minute-purchases.js';

const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const money = (value: unknown, positive=false): value is number => typeof value==='number' && Number.isSafeInteger(value) && value>=(positive?1:0) && value<=100_000_000;
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const scopeKey = (scope: PurchaseScope) => JSON.stringify([scope.provider,scope.environment,scope.merchant]);
const productKey = (scope: PurchaseScope, sku: string) => JSON.stringify([scopeKey(scope),sku]);
function scopeValid(scope: PurchaseScope) {
  return scope && ['stripe','play'].includes(scope.provider) && ['test','live'].includes(scope.environment) &&
    typeof scope.merchant==='string' && identifier.test(scope.merchant);
}
function integerString(value: unknown, max: bigint): bigint {
  if (typeof value!=='string' || !/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value)>max) throw new ServiceError('invalid_ai_value_product');
  return BigInt(value);
}
export interface AIValueProductInput extends PurchaseScope {
  sku: string; providerProduct: string; currency: string; currencyExponent: number;
  aiValueMinor: number; policyVersion: number; serviceFeeBasisPoints: number;
  processing: ProcessingCost;
  /** USD major units per one checkout-currency major unit, a reviewed exact rational snapshot. */
  exchangeRate: { numerator: string; denominator: string; version: string };
  estimate: { nanoUSDPerMinute: string; rateVersion: string };
}
export interface AIValueProduct extends PurchaseScope {
  readonly sku: string; readonly providerProduct: string; readonly currency: string; readonly totalMinor: number;
  readonly entitlementKind: 'ai_value'; readonly billingBasis: 'actual-ai-usage'; readonly estimate: true;
  readonly aiValueNanoUSD: string; readonly estimatedMilliseconds: number;
  readonly quote: ReturnType<typeof quoteAITopUp> & {
    currency: string; currencyExponent: number; processingRateBasisPoints: number; processingFixedMinor: number;
    processingBufferBasisPoints: number; exchangeRateNumerator: string; exchangeRateDenominator: string;
    exchangeRateVersion: string; estimatedNanoUSDPerMinute: string; estimateRateVersion: string;
  };
}
export interface AIValueOrder extends AIValueProduct { readonly orderID: string }
export interface AIValuePurchaseStatus {
  readonly orderID: string; readonly entitlementKind: 'ai_value';
  readonly state: 'created'|'pending'|'purchased'|'voided';
  readonly grantedNanoUSD: string; readonly reversedNanoUSD: string; readonly reversalOutstandingNanoUSD: string;
  readonly fulfillmentRecorded: boolean;
}

/** No FX, processing rates or payable product prices are invented by this function. */
export function makeAIValueProduct(input: AIValueProductInput): Readonly<AIValueProduct> {
  if (!scopeValid(input) || typeof input.sku!=='string' || typeof input.providerProduct!=='string' || !identifier.test(input.sku) || input.sku.length>128 || !identifier.test(input.providerProduct) ||
    !/^[a-z]{3}$/.test(input.currency) || !Number.isInteger(input.currencyExponent) || input.currencyExponent<0 || input.currencyExponent>3 ||
    !money(input.aiValueMinor,true) || !Number.isSafeInteger(input.policyVersion) || input.policyVersion<1 || input.policyVersion>2_147_483_647 ||
    typeof input.exchangeRate?.version!=='string' || typeof input.estimate?.rateVersion!=='string' || !identifier.test(input.exchangeRate?.version) || !identifier.test(input.estimate?.rateVersion))
    throw new ServiceError('invalid_ai_value_product');
  const numerator=integerString(input.exchangeRate.numerator,1_000_000_000_000n);
  const denominator=integerString(input.exchangeRate.denominator,1_000_000_000_000n);
  if (input.currency==='usd' && (input.currencyExponent!==2 || numerator!==1n || denominator!==1n))
    throw new ServiceError('invalid_ai_value_product');
  const allocation=BigInt(input.aiValueMinor)*1_000_000_000n*numerator/(10n**BigInt(input.currencyExponent)*denominator);
  if (allocation<=0n || allocation>1_000_000_000_000_000n) throw new ServiceError('invalid_ai_value_product');
  const estimateRate=integerString(input.estimate.nanoUSDPerMinute,1_000_000_000_000n);
  const priced=quoteAITopUp(input.aiValueMinor,{version:input.policyVersion,serviceFeeBasisPoints:input.serviceFeeBasisPoints},input.processing);
  if (!money(priced.totalMinor,true) || !money(priced.processingBufferMinor)) throw new ServiceError('invalid_ai_value_product');
  const quote=Object.freeze({...priced,currency:input.currency,currencyExponent:input.currencyExponent,
    processingRateBasisPoints:input.processing.rateBasisPoints,processingFixedMinor:input.processing.fixedMinor,
    processingBufferBasisPoints:input.processing.bufferBasisPoints,exchangeRateNumerator:input.exchangeRate.numerator,
    exchangeRateDenominator:input.exchangeRate.denominator,exchangeRateVersion:input.exchangeRate.version,
    estimatedNanoUSDPerMinute:input.estimate.nanoUSDPerMinute,estimateRateVersion:input.estimate.rateVersion});
  return Object.freeze({provider:input.provider,environment:input.environment,merchant:input.merchant,sku:input.sku,
    providerProduct:input.providerProduct,currency:input.currency,totalMinor:priced.totalMinor,entitlementKind:'ai_value',
    billingBasis:'actual-ai-usage',estimate:true,aiValueNanoUSD:allocation.toString(),
    estimatedMilliseconds:estimatedConversationMilliseconds(allocation,estimateRate),quote});
}
function validateProduct(product: AIValueProduct): Readonly<AIValueProduct> {
  try {
    const q=product.quote;
    const canonical=makeAIValueProduct({...product,currencyExponent:q.currencyExponent,aiValueMinor:q.aiValueMinor,
      policyVersion:q.policyVersion,serviceFeeBasisPoints:q.serviceFeeBasisPoints,
      processing:{rateBasisPoints:q.processingRateBasisPoints,fixedMinor:q.processingFixedMinor,bufferBasisPoints:q.processingBufferBasisPoints},
      exchangeRate:{numerator:q.exchangeRateNumerator,denominator:q.exchangeRateDenominator,version:q.exchangeRateVersion},
      estimate:{nanoUSDPerMinute:q.estimatedNanoUSDPerMinute,rateVersion:q.estimateRateVersion}});
    for (const key of Object.keys(canonical) as (keyof AIValueProduct)[]) {
      if (key==='quote') {
        if (Object.keys(q).length!==Object.keys(canonical.quote).length || Object.entries(canonical.quote).some(([k,v])=>q[k as keyof typeof q]!==v)) throw new Error();
      } else if (canonical[key]!==product[key]) throw new Error();
    }
    if (Object.keys(canonical).length!==Object.keys(product).length) throw new Error();
    return canonical;
  } catch { throw new ServiceError('invalid_ai_value_product'); }
}
function mappedOrder(row: any): AIValueOrder {
  return {orderID:row.id,provider:row.provider,environment:row.environment,merchant:row.merchant,sku:row.sku,
    providerProduct:row.provider_product,currency:row.currency,totalMinor:Number(row.total_minor),
    entitlementKind:'ai_value',billingBasis:'actual-ai-usage',estimate:true,
    aiValueNanoUSD:row.ai_value_nano.toString(),estimatedMilliseconds:estimatedConversationMilliseconds(BigInt(row.ai_value_nano),
      BigInt(row.quote.estimatedNanoUSDPerMinute)),quote:row.quote};
}
const purchaseStatus=(id:string,row?:any):AIValuePurchaseStatus=>({orderID:id,entitlementKind:'ai_value',state:row?.state??'created',
  grantedNanoUSD:String(row?.granted_nano??0),reversedNanoUSD:String(row?.reversed_nano??0),reversalOutstandingNanoUSD:'0',
  fulfillmentRecorded:BigInt(row?.granted_nano??0)>0n});

/** Fees are not AI entitlement. Reverse the original AI allocation's proportion of a total refund. */
export function refundedAIValue(allocation: bigint, refundedMinor:number,totalMinor:number):bigint {
  if (allocation<=0n || allocation>1_000_000_000_000_000n || !money(refundedMinor) || !money(totalMinor,true) || refundedMinor>totalMinor)
    throw new ServiceError('invalid_ai_value_refund');
  return (allocation*BigInt(refundedMinor)+BigInt(totalMinor)-1n)/BigInt(totalMinor);
}
function evidenceValid(e:VerifiedMinutePurchase,scope:PurchaseScope) {
  if (!scopeValid(e) || scopeKey(e)!==scopeKey(scope) || !uuid.test(e.orderID) ||
    typeof e.transactionID!=='string' || !/^[\x21-\x7e]{1,4096}$/.test(e.transactionID) ||
    typeof e.eventID!=='string' || !/^[\x21-\x7e]{1,4096}$/.test(e.eventID) || !identifier.test(e.providerProduct) || e.quantity!==1 ||
    !/^[a-z]{3}$/.test(e.currency) || !money(e.totalMinor,true) || !money(e.refundedMinor) || e.refundedMinor>e.totalMinor ||
    !['pending','purchased','voided'].includes(e.state) || (e.state==='pending' && e.refundedMinor!==0)) throw new ServiceError('invalid_purchase_evidence',502);
}

/** Optional catalog. All real entitlements come from server-verified provider evidence. */
export class AIValuePurchases {
  readonly #catalog=new Map<string,Readonly<AIValueProduct>>();
  readonly #verifiers=new Map<PurchaseProvider,MinutePurchaseVerifier>();
  readonly #salesEnabled:boolean;
  constructor(readonly db:Database, options:{catalog?:readonly AIValueProduct[];verifiers?:readonly MinutePurchaseVerifier[];salesEnabled?:boolean}={}) {
    this.#salesEnabled=options.salesEnabled===true;
    for (const verifier of options.verifiers??[]) {
      if (!scopeValid(verifier) || typeof verifier.verify!=='function' || this.#verifiers.has(verifier.provider)) throw new ServiceError('invalid_purchase_verifier');
      this.#verifiers.set(verifier.provider,Object.freeze({provider:verifier.provider,environment:verifier.environment,merchant:verifier.merchant,verify:verifier.verify.bind(verifier)}));
    }
    const bindings=new Set<string>();
    for (const candidate of options.catalog??[]) {
      const product=validateProduct(candidate),key=productKey(product,product.sku),binding=JSON.stringify([scopeKey(product),product.providerProduct,product.currency]);
      const verifier=this.#verifiers.get(product.provider);
      if (this.#catalog.has(key) || bindings.has(binding) || !verifier || scopeKey(product)!==scopeKey(verifier)) throw new ServiceError('invalid_ai_value_catalog');
      this.#catalog.set(key,product);bindings.add(binding);
    }
  }
  products(provider:PurchaseProvider):readonly Readonly<AIValueProduct>[] {
    return this.#salesEnabled?[...this.#catalog.values()].filter(product=>product.provider===provider):[];
  }
  async createOrder(accountID:string,provider:PurchaseProvider,sku:string,idempotencyKey:string):Promise<AIValueOrder> {
    if (!this.#salesEnabled) throw new ServiceError('ai_value_purchases_unavailable',503);
    if (!uuid.test(accountID) || typeof sku!=='string' || typeof idempotencyKey!=='string' || !/^[A-Za-z0-9._:-]{8,128}$/.test(idempotencyKey)) throw new ServiceError('invalid_ai_value_order');
    const verifier=this.#verifiers.get(provider); if (!verifier) throw new ServiceError('ai_value_purchases_unavailable',503);
    return transaction(this.db,async sql=>{
      const wallet=await lockWallet(sql,accountID,true);
      if ((await sql.query('SELECT is_guest FROM accounts WHERE id=$1',[accountID])).rows[0].is_guest) throw new ServiceError('purchase_requires_account',403);
      // Returning an unpaid prior order also opens checkout. Leave status/recovery available,
      // but never offer a charge until existing cash has known production/sandbox provenance.
      if (!wallet.cashProvenanceVerified) throw new ServiceError('cash_balance_reconciliation_required',409);
      const prior=(await sql.query(`SELECT o.*,q.ai_value_nano,q.quote FROM minute_purchase_orders o
        LEFT JOIN ai_value_purchase_quotes q ON q.order_id=o.id WHERE o.account_id=$1 AND o.idempotency_key=$2`,[accountID,idempotencyKey])).rows[0];
      if (prior) {
        if (prior.entitlement_kind!=='ai_value' || prior.provider!==provider || prior.sku!==sku || prior.environment!==verifier.environment || prior.merchant!==verifier.merchant)
          throw new ServiceError('idempotency_conflict',409);
        return mappedOrder(prior);
      }
      const product=this.#catalog.get(productKey(verifier,sku));if (!product) throw new ServiceError('ai_value_product_unavailable',503);
      const policy=(await sql.query('SELECT version,service_fee_basis_points FROM lock_ai_pricing_policy()')).rows[0];
      if (!policy || policy.version!==product.quote.policyVersion || policy.service_fee_basis_points!==product.quote.serviceFeeBasisPoints)
        throw new ServiceError('ai_pricing_changed_review_quote',409);
      const id=randomUUID();
      await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,NULL,'ai_value')`,[id,accountID,idempotencyKey,provider,product.environment,product.merchant,sku,product.providerProduct,product.currency,product.totalMinor]);
      const q=product.quote;
      await sql.query(`INSERT INTO ai_value_purchase_quotes(order_id,ai_value_nano,ai_value_minor,policy_version,service_fee_basis_points,service_fee_minor,
        processing_estimate_minor,processing_buffer_minor,quote) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
        [id,product.aiValueNanoUSD,q.aiValueMinor,q.policyVersion,q.serviceFeeBasisPoints,q.serviceFeeMinor,q.processingEstimateMinor,q.processingBufferMinor,JSON.stringify(q)]);
      return {...product,orderID:id};
    });
  }
  async status(accountID:string,orderID:string):Promise<AIValuePurchaseStatus> {
    if (!uuid.test(accountID) || !uuid.test(orderID)) throw new ServiceError('purchase_not_found',404);
    const found=(await this.db.query(`SELECT p.* FROM minute_purchase_orders o JOIN accounts a ON a.id=o.account_id
      LEFT JOIN ai_value_purchase_transactions p ON p.order_id=o.id WHERE o.id=$1 AND o.account_id=$2
      AND o.entitlement_kind='ai_value' AND a.deleted_at IS NULL`,[orderID,accountID])).rows[0];
    if (!found) throw new ServiceError('purchase_not_found',404);
    return purchaseStatus(orderID,found.order_id?found:undefined);
  }
  async reconcile(provider:PurchaseProvider,input:unknown):Promise<AIValuePurchaseStatus> {
    const verifier=this.#verifiers.get(provider);if (!verifier) throw new ServiceError('purchase_verification_unavailable',503);
    let verified:VerifiedMinutePurchase;
    try {verified=await verifier.verify(input);}catch {throw new ServiceError('purchase_verification_failed',502);}
    return this.applyVerifiedEvidence(provider,verified);
  }
  /** Server-internal: callers must use the configured provider verifier or shared fulfillment router. */
  async applyVerifiedEvidence(provider:PurchaseProvider,verified:VerifiedMinutePurchase):Promise<AIValuePurchaseStatus> {
    const verifier=this.#verifiers.get(provider);if (!verifier) throw new ServiceError('purchase_verification_unavailable',503);
    evidenceValid(verified,verifier);
    const evidence={provider:verified.provider,environment:verified.environment,merchant:verified.merchant,orderID:verified.orderID.toLowerCase(),
      transactionHash:hash(verified.transactionID),eventHash:hash(verified.eventID),providerProduct:verified.providerProduct,currency:verified.currency,
      totalMinor:verified.totalMinor,state:verified.state,refundedMinor:verified.refundedMinor};
    const evidenceHash=hash(JSON.stringify(evidence));
    return transaction(this.db,async sql=>{
      const locks=[`minute-purchase-transaction:${scopeKey(evidence)}:${evidence.transactionHash}`,`minute-purchase-event:${scopeKey(evidence)}:${evidence.eventHash}`].sort();
      for (const key of locks) await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[key]);
      const order=(await sql.query(`SELECT o.*,q.ai_value_nano,q.quote FROM minute_purchase_orders o
        JOIN ai_value_purchase_quotes q ON q.order_id=o.id WHERE o.id=$1`,[evidence.orderID])).rows[0];
      if (!order || order.entitlement_kind!=='ai_value') throw new ServiceError('purchase_entitlement_mismatch',409);
      if (order.provider!==provider || order.environment!==evidence.environment || order.merchant!==evidence.merchant || order.provider_product!==evidence.providerProduct ||
        order.currency!==evidence.currency || Number(order.total_minor)!==evidence.totalMinor) throw new ServiceError('ai_value_purchase_mismatch',409);
      if ((await sql.query(`SELECT 1 FROM minute_purchase_transactions WHERE provider=$1 AND environment=$2 AND merchant=$3 AND transaction_hash=$4`,
        [provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rowCount) throw new ServiceError('purchase_transaction_conflict',409);
      await lockWallet(sql,order.account_id);
      const duplicate=(await sql.query(`SELECT evidence_hash FROM minute_purchase_events WHERE provider=$1 AND environment=$2 AND merchant=$3 AND event_hash=$4`,
        [provider,evidence.environment,evidence.merchant,evidence.eventHash])).rows[0];
      if (duplicate && duplicate.evidence_hash!==evidenceHash) throw new ServiceError('purchase_event_conflict',409);
      const rows=(await sql.query(`SELECT * FROM ai_value_purchase_transactions WHERE order_id=$1 OR
        (provider=$2 AND environment=$3 AND merchant=$4 AND transaction_hash=$5) FOR UPDATE`,[evidence.orderID,provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rows;
      if (rows.some(row=>row.order_id!==evidence.orderID || row.transaction_hash!==evidence.transactionHash)) throw new ServiceError('purchase_transaction_conflict',409);
      let purchase=rows[0];
      if (!purchase) purchase=(await sql.query(`INSERT INTO ai_value_purchase_transactions(order_id,account_id,provider,environment,merchant,transaction_hash,state,ai_value_nano)
        VALUES($1,$2,$3,$4,$5,$6,'pending',$7) RETURNING *`,[evidence.orderID,order.account_id,provider,evidence.environment,evidence.merchant,evidence.transactionHash,order.ai_value_nano])).rows[0];
      if (!duplicate) {
        const state=evidence.state==='voided'||purchase.state==='voided'?'voided':evidence.state==='purchased'||purchase.state==='purchased'?'purchased':'pending';
        const allocation=BigInt(order.ai_value_nano),granted=BigInt(purchase.granted_nano);
        const version=`ai-value:${order.quote.policyVersion}:${order.quote.exchangeRateVersion}`;
        if (state==='purchased' && granted===0n) await appendEntry(sql,order.account_id,`ai-purchase:${order.id}`,'purchase',allocation,0n,version,
          order.environment==='test'?allocation:0n);
        const refund=Math.max(Number(purchase.refunded_minor),evidence.refundedMinor);
        const target=state==='voided'?allocation:refundedAIValue(allocation,refund,Number(order.total_minor));
        const effectiveGrant=state==='purchased'?allocation:granted;
        const reversal=target<effectiveGrant?target:effectiveGrant;
        const delta=reversal-BigInt(purchase.reversed_nano);
        if (delta>0n) await appendEntry(sql,order.account_id,`ai-refund:${order.id}:${reversal}`,'reversal',-delta,0n,version,
          order.environment==='test'?-delta:0n);
        await sql.query(`UPDATE ai_value_purchase_transactions SET state=$2,granted_nano=$3,refunded_minor=$4,reversed_nano=$5,updated_at=now() WHERE order_id=$1`,
          [order.id,state,effectiveGrant.toString(),refund,reversal.toString()]);
        await sql.query(`INSERT INTO minute_purchase_events(id,order_id,provider,environment,merchant,event_hash,evidence_hash,state,refunded_minor)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,[randomUUID(),order.id,provider,evidence.environment,evidence.merchant,evidence.eventHash,evidenceHash,evidence.state,evidence.refundedMinor]);
      }
      return purchaseStatus(order.id,(await sql.query('SELECT * FROM ai_value_purchase_transactions WHERE order_id=$1',[order.id])).rows[0]);
    });
  }
}

/** Webhook, Play recovery and durable worker share one authoritative verification before dispatch. */
export class PurchaseFulfillmentRouter {
  readonly #verifiers=new Map<PurchaseProvider,MinutePurchaseVerifier>();
  constructor(readonly db:Database,readonly minutes:MinutePurchases,readonly ai:AIValuePurchases,verifiers:readonly MinutePurchaseVerifier[]) {
    for (const verifier of verifiers) {
      if (!scopeValid(verifier) || typeof verifier.verify!=='function' || this.#verifiers.has(verifier.provider)) throw new ServiceError('invalid_purchase_verifier');
      this.#verifiers.set(verifier.provider,Object.freeze({provider:verifier.provider,environment:verifier.environment,merchant:verifier.merchant,verify:verifier.verify.bind(verifier)}));
    }
  }
  async reconcile(provider:PurchaseProvider,input:unknown):Promise<MinutePurchaseStatus|AIValuePurchaseStatus> {
    const verifier=this.#verifiers.get(provider);if (!verifier) throw new ServiceError('purchase_verification_unavailable',503);
    let evidence:VerifiedMinutePurchase;
    try {evidence=await verifier.verify(input);}catch {throw new ServiceError('purchase_verification_failed',502);}
    evidenceValid(evidence,verifier);
    const row=(await this.db.query('SELECT entitlement_kind FROM minute_purchase_orders WHERE id=$1',[evidence.orderID])).rows[0];
    if (row?.entitlement_kind==='ai_value') return this.ai.applyVerifiedEvidence(provider,evidence);
    if (row?.entitlement_kind==='minutes') return this.minutes.applyVerifiedEvidence(provider,evidence);
    throw new ServiceError('unmapped_purchase',409);
  }
}
