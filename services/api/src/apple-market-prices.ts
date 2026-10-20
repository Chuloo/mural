import { ServiceError } from './errors.js';
import { storeRegionCode } from './store-markets.js';
import { storeCurrencyExponents } from './store-currency-exponents.js';
export const appleMaximumUnitMinor=100_000_000;
export const appleMaximumOrderMinor=1_000_000_000;

export interface ApplePriceSource {
  kind:'app-store-connect-export'|'app-store-connect-api';
  sourceURL:string;
  capturedAt:string;
  sha256:string;
  pricePointID?:string;
  scheduleID?:string;
}
export interface RegionalApplePriceSnapshot {
  pricingBasis:'fixed-usd-allocation';
  storefront:string;
  regionCode:string;
  currency:string;
  currencyExponent:number;
  unitTotalMinor:number;
  scheduleVersion:string;
  /** Exact Apple-listed decimals. Proceeds are provider price data, not a settled payout or USD conversion. */
  customerPrice:string;
  proceeds:string;
  mayAdjustAutomatically:boolean;
  source:ApplePriceSource;
}
const invalid=()=>new ServiceError('invalid_ai_value_product');
const identifier=/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
export function appleStorefrontCode(value:unknown):string {
  if(typeof value!=='string' || !/^[A-Z]{3}$/.test(value)) throw invalid();
  return value;
}
function decimal(value:unknown):{amount:bigint;scale:bigint} {
  if(typeof value!=='string' || !/^(0|[1-9][0-9]{0,11})(\.[0-9]{1,9})?$/.test(value)) throw invalid();
  const [whole,fraction='']=value.split('.');
  const amount=BigInt(whole!+fraction),scale=10n**BigInt(fraction.length);
  if(amount<=0n) throw invalid();
  return {amount,scale};
}
export function appleDecimalMinor(value:string,exponent:number):number {
  if(!Number.isInteger(exponent) || exponent<0 || exponent>3) throw invalid();
  const d=decimal(value),scaled=d.amount*10n**BigInt(exponent);
  if(scaled%d.scale!==0n || scaled/d.scale>BigInt(appleMaximumUnitMinor)) throw invalid();
  return Number(scaled/d.scale);
}
export function validateRegionalApplePrice(price:RegionalApplePriceSnapshot):void {
  if(!price || Object.keys(price).some(k=>!['pricingBasis','storefront','regionCode','currency','currencyExponent','unitTotalMinor',
    'scheduleVersion','customerPrice','proceeds','mayAdjustAutomatically','source'].includes(k)) || price.pricingBasis!=='fixed-usd-allocation' ||
    typeof price.mayAdjustAutomatically!=='boolean') throw invalid();
  appleStorefrontCode(price.storefront);storeRegionCode(price.regionCode);
  if(!/^[a-z]{3}$/.test(price.currency) || storeCurrencyExponents[price.currency]===undefined ||
    price.currencyExponent!==storeCurrencyExponents[price.currency] || !identifier.test(price.scheduleVersion) ||
    price.unitTotalMinor!==appleDecimalMinor(price.customerPrice,price.currencyExponent)) throw invalid();
  const gross=decimal(price.customerPrice),proceeds=decimal(price.proceeds);
  if(proceeds.amount*gross.scale>gross.amount*proceeds.scale) throw invalid();
  const s=price.source;
  if(!s || Object.keys(s).some(k=>!['kind','sourceURL','capturedAt','sha256','pricePointID','scheduleID'].includes(k)) ||
    !['app-store-connect-export','app-store-connect-api'].includes(s.kind) || !/^[a-f0-9]{64}$/.test(s.sha256) ||
    typeof s.capturedAt!=='string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/.test(s.capturedAt) ||
    !Number.isFinite(Date.parse(s.capturedAt)) ||
    [s.pricePointID,s.scheduleID].some(v=>v!==undefined && (typeof v!=='string' || !/^[A-Za-z0-9_=-]{1,4096}$/.test(v)))) throw invalid();
  let url:URL;try{url=new URL(s.sourceURL);}catch{throw invalid();}
  if(url.protocol!=='https:' || url.username || url.password || url.port || url.search || url.hash ||
    !['appstoreconnect.apple.com','api.appstoreconnect.apple.com'].includes(url.hostname) ||
    (s.kind==='app-store-connect-api' && (url.hostname!=='api.appstoreconnect.apple.com' || !s.pricePointID))) throw invalid();
}
