import {makeRegionalAppleAIValueProduct,type AIValueProduct} from './ai-value-purchases.js';
import {appleDecimalMinor,appleStorefrontCode,type ApplePriceSource} from './apple-market-prices.js';
import {storeCurrencyExponents} from './store-currency-exponents.js';
import {reviewedStoreRegionCodes,storeRegionCode} from './store-markets.js';
import type {PurchaseEnvironment} from './minute-purchases.js';
import {ServiceError} from './errors.js';

export type AppleMinutePack='small'|'medium'|'large';
export interface AppleListedPrice {
  currency:string;
  customerPrice:string;
  proceeds:string;
  mayAdjustAutomatically:boolean;
  source:ApplePriceSource;
}
export interface RegionalAppleCatalogInput {
  environment:PurchaseEnvironment;
  merchant:string;
  scheduleVersion:string;
  policyVersion:number;
  serviceFeeBasisPoints:number;
  estimate:{nanoUSDPerMinute:string;rateVersion:string};
  /** Explicit service decisions for every Apple-priced country, including unavailable countries. */
  enabledRegionCodes:readonly string[];
  excludedRegionCodes:readonly string[];
  markets:readonly {storefront:string;regionCode:string;prices:Readonly<Record<AppleMinutePack,AppleListedPrice>>}[];
}
const allocations={small:369,medium:766,large:1161} as const;
/** Builds only a candidate catalog from reviewed Apple data; it never activates store or server sales. */
export function makeRegionalAppleCatalog(input:RegionalAppleCatalogInput):readonly Readonly<AIValueProduct>[] {
  if(!input || input.merchant!=='chat.mural.ios' || !Array.isArray(input.markets) || !input.markets.length || input.markets.length>300 ||
    new Set(input.markets.map(m=>m.storefront)).size!==input.markets.length) throw new ServiceError('invalid_ai_value_catalog');
  const enabled=new Set(reviewedStoreRegionCodes(input.markets.map(m=>m.regionCode),input.enabledRegionCodes,input.excludedRegionCodes));
  const rows:Readonly<AIValueProduct>[]=[];
  for(const market of [...input.markets].sort((a,b)=>a.storefront.localeCompare(b.storefront))) {
    const storefront=appleStorefrontCode(market.storefront),regionCode=storeRegionCode(market.regionCode);
    if(!market.prices || Object.keys(market.prices).length!==3 || Object.keys(market.prices).some(pack=>!Object.hasOwn(allocations,pack)))
      throw new ServiceError('invalid_ai_value_catalog');
    for(const pack of Object.keys(allocations) as AppleMinutePack[]) {
      const price=market.prices[pack];
      if(!price || Object.keys(price).some(key=>!['currency','customerPrice','proceeds','mayAdjustAutomatically','source'].includes(key)))
        throw new ServiceError('invalid_ai_value_product');
      const currencyExponent=storeCurrencyExponents[price.currency];
      const row=makeRegionalAppleAIValueProduct({provider:'apple',environment:input.environment,merchant:input.merchant,
        sku:`apple-${storefront.toLowerCase()}-${pack}-${input.scheduleVersion}`,providerProduct:`chat.mural.ios.minutes.${pack}.v1`,
        aiValueMinor:allocations[pack],policyVersion:input.policyVersion,serviceFeeBasisPoints:input.serviceFeeBasisPoints,estimate:input.estimate,
        apple:{pricingBasis:'fixed-usd-allocation',storefront,regionCode,...price,currencyExponent:currencyExponent!,
          unitTotalMinor:appleDecimalMinor(price.customerPrice,currencyExponent!),scheduleVersion:input.scheduleVersion}});
      if(enabled.has(regionCode)) rows.push(row);
    }
    if(new Set(Object.values(market.prices as Record<AppleMinutePack,AppleListedPrice>).map(p=>p.currency)).size!==1) throw new ServiceError('invalid_ai_value_catalog');
  }
  return Object.freeze(rows);
}
