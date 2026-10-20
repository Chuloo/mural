import {createHash} from 'node:crypto';
import type { AppleMinuteProvider } from './apple-minute-provider.js';
import type { PurchaseEnvironment } from './minute-purchases.js';
import { ServiceError } from './errors.js';

export const appleAppTransactionHeader='x-mural-apple-app-transaction';

/** An unsigned environment is only a verifier-selection hint, never funding evidence. */
export function appleSignedEnvironment(signed:string,field:'receiptType'|'environment'):PurchaseEnvironment {
  try {
    if(signed.length>32_768 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(signed)) throw new Error();
    const payload=JSON.parse(Buffer.from(signed.split('.')[1]!, 'base64url').toString('utf8'));
    const value=field==='environment'?(payload.environment??payload.data?.environment??payload.summary?.environment):payload[field];
    if(value==='Sandbox')return 'test';
    if(value==='Production')return 'live';
  } catch { /* Fail closed before provider work. */ }
  throw new ServiceError('apple_purchase_verification_failed',502);
}

/** Bounds repeat certificate work; cached entries contain only verified scope, never JWS. */
export class ApplePurchaseScopes {
  readonly #cache=new Map<string,{scope:PurchaseEnvironment;until:number}>();
  constructor(readonly live:AppleMinuteProvider,readonly sandbox?:AppleMinuteProvider,readonly historyAdmissionRequired=false) {}
  provider(scope:PurchaseEnvironment) {
    const provider=scope===this.live.environment?this.live:this.sandbox;
    if(!provider || provider.environment!==scope) throw new ServiceError('minute_purchases_unavailable',503);
    return provider;
  }
  /** Opening checkout requires a verified, completed history window for that exact scope. */
  async historyReadiness():Promise<{liveReady:boolean;testReady:boolean}> {
    const providers=[this.live,...(this.sandbox?[this.sandbox]:[])];
    if(!this.historyAdmissionRequired)return {liveReady:providers.some(p=>p.environment==='live'),testReady:providers.some(p=>p.environment==='test')};
    const rows=(await this.live.db.query(`SELECT environment FROM apple_notification_cursors WHERE merchant=$1
      AND environment=ANY($2::text[]) AND completed_through_ms>
      floor(extract(epoch FROM now()-interval '1 hour')*1000) AND completed_through_ms<=floor(extract(epoch FROM now())*1000)`,
      [this.live.merchant,providers.map(p=>p.environment)])).rows;
    return {liveReady:rows.some(row=>row.environment==='live'),testReady:rows.some(row=>row.environment==='test')};
  }
  async admissionReady(scope:PurchaseEnvironment):Promise<boolean> {
    this.provider(scope);
    const ready=await this.historyReadiness();
    return scope==='live'?ready.liveReady:ready.testReady;
  }
  async requireAdmission(scope:PurchaseEnvironment):Promise<void> {
    if(!await this.admissionReady(scope))throw new ServiceError('minute_purchases_unavailable',503);
  }
  async verify(signed:unknown):Promise<PurchaseEnvironment> {
    if(typeof signed!=='string')throw new ServiceError('apple_purchase_verification_failed',502);
    const scope=appleSignedEnvironment(signed,'receiptType');
    const key=createHash('sha256').update(signed).digest('hex'),now=Date.now(),cached=this.#cache.get(key);
    if(cached && cached.until>now)return cached.scope;
    const verified=await this.provider(scope).appTransactionScope(signed);
    if(this.#cache.size>=1000)this.#cache.delete(this.#cache.keys().next().value!);
    this.#cache.set(key,{scope:verified,until:now+300_000});
    return verified;
  }
}
