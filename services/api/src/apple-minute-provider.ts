import { AppStoreServerAPIClient, SignedDataVerifier, Environment, type AppTransaction, type JWSTransactionDecodedPayload,
  type ResponseBodyV2DecodedPayload } from '@apple/app-store-server-library';
import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import type { PurchaseEnvironment, VerifiedMinutePurchase } from './minute-purchases.js';
import { loadProviderOrder, MinuteReceiptVault, orderIDPattern, providerHash, type MinuteDeliveryAdapter } from './minute-provider-delivery.js';
import appleFetch from 'node-fetch';

export interface AppleMinuteConfig {
  bundleID: string; appAppleID: number; environment: PurchaseEnvironment; allowLive?: boolean;
  signingKey: string; keyID: string; issuerID: string; rootCertificates: Buffer[];
  purchasesEnabled?: boolean;
}
export interface AppleMinuteTransport {
  appTransaction?(signed: string): Promise<AppTransaction>;
  transaction(signed: string): Promise<JWSTransactionDecodedPayload>;
  notification(signed: string): Promise<ResponseBodyV2DecodedPayload>;
  latest(transactionID: string): Promise<string>;
  history(start:number,end:number,pageToken?:string):Promise<{notifications:string[];nextPageToken?:string}>;
}
const invalid = () => new ServiceError('apple_purchase_verification_failed',502);
const numericID = (value: unknown): value is string => typeof value==='string' && /^[0-9]{1,64}$/.test(value);
const signedData = (value: unknown): value is string => typeof value==='string' && value.length<=32_768 && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value);
const validDate = (value: unknown): value is number => typeof value==='number' && Number.isSafeInteger(value) && value>0;
function environment(config: AppleMinuteConfig) {
  if (config.bundleID!=='chat.mural.ios' || !Number.isSafeInteger(config.appAppleID) || config.appAppleID<=0 ||
    !['test','live'].includes(config.environment) || (config.environment==='live' && config.allowLive!==true))
    throw new ServiceError('apple_commerce_configuration_invalid',503);
  return config.environment==='live'?Environment.PRODUCTION:Environment.SANDBOX;
}
/** Certificate roots and environment are server configuration. Local Xcode JWS is never accepted here. */
class BoundedAppleAPIClient extends AppStoreServerAPIClient {
  constructor(config: AppleMinuteConfig, private readonly mode: Environment) {
    super(config.signingKey,config.keyID,config.issuerID,config.bundleID,mode);
  }
  protected override makeFetchRequest(path:string, query:URLSearchParams, method:string, body:string|Buffer|undefined, headers:Record<string,string>) {
    const origin=this.mode===Environment.PRODUCTION?'https://api.storekit.apple.com':'https://api.storekit-sandbox.apple.com';
    return appleFetch(`${origin}${path}?${query}`,{method,body,headers,timeout:10_000,size:1_048_576,redirect:'error'});
  }
}
export class AppleSDKMinuteTransport implements AppleMinuteTransport {
  readonly #verifier: SignedDataVerifier;
  readonly #api: AppStoreServerAPIClient;
  constructor(config: AppleMinuteConfig) {
    const mode=environment(config);
    if (!config.rootCertificates.length || !/^[A-Z0-9]{10}$/.test(config.keyID) || !orderIDPattern.test(config.issuerID))
      throw new ServiceError('apple_commerce_configuration_invalid',503);
    this.#verifier=new SignedDataVerifier(config.rootCertificates,true,mode,config.bundleID,config.appAppleID);
    this.#api=new BoundedAppleAPIClient(config,mode);
  }
  transaction(signed:string) {return this.#verifier.verifyAndDecodeTransaction(signed);}
  appTransaction(signed:string) {return this.#verifier.verifyAndDecodeAppTransaction(signed);}
  notification(signed:string) {return this.#verifier.verifyAndDecodeNotification(signed);}
  async latest(id:string) {
    const response=await this.#api.getTransactionInfo(id);
    if (!signedData(response.signedTransactionInfo)) throw invalid();
    return response.signedTransactionInfo;
  }
  async history(start:number,end:number,pageToken?:string) {
    const result=await this.#api.getNotificationHistory(pageToken??null,{startDate:start,endDate:end});
    if(typeof result.hasMore!=='boolean' || !Array.isArray(result.notificationHistory) || result.notificationHistory.length>20 ||
      result.notificationHistory.some(item=>!signedData(item.signedPayload)) ||
      (result.hasMore && (typeof result.paginationToken!=='string' || result.paginationToken.length<1 || result.paginationToken.length>4096))) throw invalid();
    return {notifications:result.notificationHistory.map(item=>item.signedPayload!),...(result.hasMore?{nextPageToken:result.paginationToken}:{})};
  }
}
/** Apple price already includes quantity. Convert milliunits exactly; never multiply it again. */
export function appleMinorUnits(price:number,exponent:number):number {
  if (!Number.isSafeInteger(price) || price<=0 || !Number.isInteger(exponent) || exponent<0 || exponent>3) throw invalid();
  const numerator=BigInt(price)*10n**BigInt(exponent);
  if (numerator%1000n!==0n || numerator/1000n>100_000_000n) throw invalid();
  return Number(numerator/1000n);
}
export function applePriceMatches(price:number,exponent:number,quantity:number,totalMinor:number,mode:PurchaseEnvironment):boolean {
  const amount=appleMinorUnits(price,exponent);
  if(!Number.isSafeInteger(quantity) || quantity<1 || quantity>10 || !Number.isSafeInteger(totalMinor) || totalMinor<=0) return false;
  // Apple's documented JWS price is the total. Real sandbox multi-quantity
  // transactions also return the unit price (verified US quantity 2, Sep 2026).
  // Accept that exact quantity-adjusted value only in the isolated test scope.
  return amount===totalMinor || (mode==='test' && amount*quantity===totalMinor);
}
export function appleRefundParts(value:JWSTransactionDecodedPayload):number {
  if (value.revocationDate===undefined) {
    if (value.revocationPercentage!==undefined || value.revocationType!==undefined) throw invalid();
    return 0;
  }
  if (!validDate(value.revocationDate)) throw invalid();
  if (value.revocationType!==undefined && !['REFUND_FULL','REFUND_PRORATED'].includes(value.revocationType)) throw invalid();
  if (value.revocationPercentage===undefined) {
    if(value.revocationType==='REFUND_PRORATED') throw invalid();
    return 100_000; // Older signed full refunds predate revocationPercentage.
  }
  if (!Number.isInteger(value.revocationPercentage) || value.revocationPercentage<1 || value.revocationPercentage>100_000 ||
    (value.revocationType==='REFUND_FULL' && value.revocationPercentage!==100_000)) throw invalid();
  return value.revocationPercentage;
}
export class AppleMinuteProvider implements MinuteDeliveryAdapter {
  readonly provider='apple' as const;
  readonly environment:PurchaseEnvironment;
  readonly merchant:string;
  constructor(readonly db:Database,readonly vault:MinuteReceiptVault,readonly config:AppleMinuteConfig,
    readonly transport:AppleMinuteTransport=new AppleSDKMinuteTransport(config)) {
    environment(config);this.environment=config.environment;this.merchant=config.bundleID;
  }
  /** Select a funding scope only after Apple's signature and app identity verify. */
  async appTransactionScope(signed:string):Promise<PurchaseEnvironment> {
    if (!signedData(signed) || !this.transport.appTransaction) throw invalid();
    let value:AppTransaction;
    try {value=await this.transport.appTransaction(signed);}catch {throw invalid();}
    if (value.bundleId!==this.merchant || value.receiptType!==environment(this.config) ||
      (value.appAppleId!==undefined && value.appAppleId!==this.config.appAppleID) ||
      !validDate(value.receiptCreationDate) || value.receiptCreationDate>Date.now()+300_000) throw invalid();
    return this.environment;
  }
  async prepare(accountID:string,orderID:string) {
    if(!this.config.purchasesEnabled) throw new ServiceError('minute_purchases_unavailable',503);
    const order=await loadProviderOrder(this.db,orderID,this,accountID);
    if(order.entitlement_kind!=='ai_value' || !order.ai_value_quote?.apple) throw invalid();
    // One opaque token per immutable order. It identifies the server-owned account indirectly.
    return {orderID:order.id,appAccountToken:order.id,productID:order.provider_product,quantity:order.quantity};
  }
  async #bound(signed:string,accountID?:string,expectedOrderID?:string,validateTerms=true) {
    if(!signedData(signed)) throw invalid();
    const value=await this.transport.transaction(signed);
    if(value.bundleId!==this.merchant || value.environment!==environment(this.config) || value.type!=='Consumable' ||
      value.inAppOwnershipType!=='PURCHASED' || !numericID(value.transactionId) || !value.appAccountToken ||
      !orderIDPattern.test(value.appAccountToken) || !validDate(value.signedDate) || !validDate(value.purchaseDate) ||
      value.signedDate> Date.now()+300_000 || value.purchaseDate>value.signedDate || value.offerIdentifier!==undefined || value.offerType!==undefined)
      throw invalid();
    const order=await loadProviderOrder(this.db,value.appAccountToken,this,accountID);
    if(expectedOrderID!==undefined && order.id!==expectedOrderID.toLowerCase()) throw invalid();
    const price=order.ai_value_quote?.apple;
    if(order.entitlement_kind!=='ai_value' || !price) throw invalid();
    if(validateTerms && (value.productId!==order.provider_product || value.quantity!==order.quantity ||
      value.storefront!==price.storefront || value.currency?.toLowerCase()!==order.currency ||
      !applePriceMatches(value.price!,price.currencyExponent,order.quantity,Number(order.total_minor),this.environment))) throw invalid();
    return {value,order};
  }
  async verify(input:unknown):Promise<VerifiedMinutePurchase> {
    if(!input || typeof input!=='object') throw invalid();
    const request=input as Record<string,unknown>;
    let bound:{value:JWSTransactionDecodedPayload;order:any};
    if(request.kind==='stored' && typeof request.orderID==='string') {
      const id=await this.vault.read(request.orderID,this);
      bound=await this.#bound(await this.transport.latest(id),undefined,request.orderID);
      if(bound.value.transactionId!==id) throw invalid();
    } else if ((request.kind==='client' || request.kind==='recovery') && typeof request.accountID==='string' && numericID(request.transactionID)) {
      // The client provides only a lookup key. Apple's authenticated API and signed
      // response establish the purchase and its immutable account/order binding.
      const signed=await this.transport.latest(request.transactionID);
      const initial=await this.#bound(signed,request.accountID,typeof request.orderID==='string'?request.orderID:undefined,false);
      if(initial.value.transactionId!==request.transactionID) throw invalid();
      await this.vault.save(initial.order.id,this,initial.value.transactionId);
      bound=await this.#bound(signed,request.accountID,initial.order.id);
    } else if ((request.kind==='client' || request.kind==='recovery') && typeof request.accountID==='string' && typeof request.signedTransaction==='string') {
      const initial=await this.#bound(request.signedTransaction,request.accountID,typeof request.orderID==='string'?request.orderID:undefined,false);
      // A verified transaction bound to this owner may represent a real charge even
      // when its price or storefront differs. Retain it for reconciliation; never grant mismatched terms.
      await this.vault.save(initial.order.id,this,initial.value.transactionId!);
      bound=await this.#bound(await this.transport.latest(initial.value.transactionId!),request.accountID,initial.order.id);
      if(bound.value.transactionId!==initial.value.transactionId) throw invalid();
    } else throw invalid();
    const {value,order}=bound,parts=appleRefundParts(value);
    await this.vault.save(order.id,this,value.transactionId!,false);
    return {provider:this.provider,environment:this.environment,merchant:this.merchant,orderID:order.id,
      transactionID:value.transactionId!,eventID:`apple:${value.transactionId}:${value.signedDate}:${providerHash(JSON.stringify(value))}`,
      providerProduct:value.productId!,quantity:value.quantity!,currency:order.currency,totalMinor:Number(order.total_minor),
      state:'purchased',refundedMinor:Number((BigInt(order.total_minor)*BigInt(parts)+99_999n)/100_000n),
      providerRevision:value.signedDate!,refundedPartsPer100000:parts};
  }
  /** Acknowledge only after the verified notification and receipt have durable retry state. */
  async notify(signed:string):Promise<void> {
    if(!signedData(signed)) throw invalid();
    let notification:ResponseBodyV2DecodedPayload;
    try {notification=await this.transport.notification(signed);}catch {throw invalid();}
    if(!notification.notificationUUID || !orderIDPattern.test(notification.notificationUUID) || notification.version!=='2.0' ||
      !validDate(notification.signedDate) || notification.signedDate>Date.now()+300_000) throw invalid();
    const type=notification.notificationType;
    if(type==='TEST') return;
    if(!['ONE_TIME_CHARGE','REFUND','REFUND_REVERSED','REFUND_DECLINED','CONSUMPTION_REQUEST'].includes(type??'') || !notification.data?.signedTransactionInfo) throw invalid();
    if(notification.data.bundleId!==this.merchant || notification.data.environment!==environment(this.config) ||
      notification.data.appAppleId!==this.config.appAppleID) throw invalid();
    let transaction:Awaited<ReturnType<AppleMinuteProvider['transport']['transaction']>>;
    let order:any;
    try {
      const result=await this.#bound(notification.data.signedTransactionInfo,undefined,undefined,false);
      transaction=result.value;order=result.order;
    } catch(error) {
      // Sandbox history can include orders belonging to the older isolated test
      // server. Apple's verified app/environment never grants an unmapped order.
      if(this.environment==='test' && error instanceof ServiceError && error.code==='purchase_not_found')return;
      throw error;
    }
    const value=transaction;
    await this.vault.save(order.id,this,value.transactionId!);
    const evidenceHash=providerHash(signed);
    await this.db.query(`INSERT INTO apple_purchase_notifications(notification_id,order_id,notification_type,signed_date,evidence_hash)
      VALUES($1,$2,$3,$4,$5) ON CONFLICT(notification_id) DO NOTHING`,
      [notification.notificationUUID,order.id,type,notification.signedDate,evidenceHash]);
    const saved=(await this.db.query('SELECT evidence_hash FROM apple_purchase_notifications WHERE notification_id=$1',[notification.notificationUUID])).rows[0];
    if(saved.evidence_hash!==evidenceHash) throw invalid();
    // Consumption requests are retained for operator review. Never disclose usage without consent.
  }
  async complete(_orderID:string):Promise<void> { /* StoreKit finishes only after the client sees durable delivery. */ }
  async pollHistory(start:number,end:number,pageToken?:string) {
    const page=await this.transport.history(start,end,pageToken);
    if(page.notifications.length>20) throw invalid();
    for(const signed of page.notifications) await this.notify(signed);
    return {scheduled:page.notifications.length,nextPageToken:page.nextPageToken};
  }
}
