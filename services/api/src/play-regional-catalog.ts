import { makeRegionalPlayAIValueProduct, type AIValueProduct } from './ai-value-purchases.js';
import type { PurchaseEnvironment } from './minute-purchases.js';
import { ServiceError } from './errors.js';
import { storeRegionCode } from './store-markets.js';

export interface GoogleMoney { currencyCode: string; units?: string; nanos?: number }
export type GoogleRegionalPrice = { regionCode: string; price: GoogleMoney } & (
  {taxAmount: GoogleMoney;consoleTax?:never} |
  {taxAmount?:never;consoleTax:{rateBasisPoints:number;hasLocationOverrides:boolean}}
);
export interface RegionalPlayCatalogInput {
  environment: PurchaseEnvironment;
  merchant: string;
  scheduleVersion: string;
  policyVersion: number;
  serviceFeeBasisPoints: number;
  commissionBasisPoints: number;
  estimate: { nanoUSDPerMinute: string; rateVersion: string };
  currencyExponents: Readonly<Record<string,number>>;
  /** Explicit reviewed country set; missing quotes fail rather than silently omitting a market. */
  regionCodes: readonly string[];
  packs: readonly { pack: 'small'|'medium'|'large'; providerProduct: string;
    /** Google-listed prices and either exact conversion taxes or labeled Console tax estimates. */
    convertedRegionPrices: Readonly<Record<string,GoogleRegionalPrice>> }[];
}
const allocationUSDMinor={small:369,medium:766,large:1161} as const;
/** Exact Money conversion, with explicit ISO minor-unit precision and no floating-point FX. */
export function googleMoneyMinor(value: GoogleMoney, exponent: number): number {
  const units=value?.units??'0',nanos=value?.nanos??0;
  if(!value || !/^[A-Z]{3}$/.test(value.currencyCode) || !Number.isInteger(exponent) || exponent<0 || exponent>3 ||
    typeof units!=='string' || !/^\d{1,12}$/.test(units) || !Number.isSafeInteger(nanos) || nanos<0 || nanos>999_999_999)
    throw new ServiceError('invalid_play_regional_price');
  const amount=BigInt(units)*1_000_000_000n+BigInt(nanos),divisor=10n**BigInt(9-exponent);
  if(amount%divisor!==0n || amount/divisor>100_000_000n) throw new ServiceError('invalid_play_regional_price');
  return Number(amount/divisor);
}
/** Pure generation: never discovers credentials, changes store offers, or enables server sales. */
export function makeRegionalPlayCatalog(input: RegionalPlayCatalogInput):readonly Readonly<AIValueProduct>[] {
  if(!input.regionCodes.length || input.regionCodes.length>300 || new Set(input.regionCodes).size!==input.regionCodes.length ||
    input.packs.length!==3 || new Set(input.packs.map(p=>p.pack)).size!==3 ||
    input.packs.some(p=>!Object.hasOwn(allocationUSDMinor,p.pack))) throw new ServiceError('invalid_play_regional_catalog');
  const products:Readonly<AIValueProduct>[]=[];
  for(const region of [...input.regionCodes].sort()) {
    const regionCode=storeRegionCode(region);
    for(const pack of input.packs) {
      const converted=pack.convertedRegionPrices[regionCode];
      if(!converted || converted.regionCode!==regionCode || !converted.price ||
        (converted.taxAmount && converted.price.currencyCode!==converted.taxAmount.currencyCode) ||
        (!!converted.taxAmount===!!converted.consoleTax)) throw new ServiceError('invalid_play_regional_price');
      const currency=converted.price.currencyCode.toLowerCase(),currencyExponent=input.currencyExponents[currency];
      const unitTotalMinor=googleMoneyMinor(converted.price,currencyExponent!);
      const rate=converted.consoleTax?.rateBasisPoints;
      if(converted.consoleTax && (!Number.isInteger(rate) || rate!<0 || rate!>10000 || typeof converted.consoleTax.hasLocationOverrides!=='boolean'))
        throw new ServiceError('invalid_play_regional_price');
      // Round estimated inclusive tax upward. Location overrides stay in the reviewed source snapshot;
      // settlement uses Google's actual order tax, never this estimate. No VAT is not a tax-exemption claim.
      const taxMinor=converted.taxAmount?googleMoneyMinor(converted.taxAmount,currencyExponent!):
        Number((BigInt(unitTotalMinor)*BigInt(rate!)+BigInt(10000+rate!)-1n)/BigInt(10000+rate!));
      if(!Number.isInteger(input.commissionBasisPoints) || input.commissionBasisPoints<0 || input.commissionBasisPoints>10000)
        throw new ServiceError('invalid_play_regional_catalog');
      const commissionMinor=Number((BigInt(unitTotalMinor-taxMinor)*BigInt(input.commissionBasisPoints)+9999n)/10000n);
      products.push(makeRegionalPlayAIValueProduct({provider:'play',environment:input.environment,merchant:input.merchant,
        sku:`play-${regionCode.toLowerCase()}-${pack.pack}-${input.scheduleVersion}`,providerProduct:pack.providerProduct,
        aiValueMinor:allocationUSDMinor[pack.pack],policyVersion:input.policyVersion,serviceFeeBasisPoints:input.serviceFeeBasisPoints,
        estimate:input.estimate,play:{pricingBasis:'fixed-usd-allocation',regionCode,currency,currencyExponent:currencyExponent!,
          unitTotalMinor,scheduleVersion:input.scheduleVersion,taxBasis:converted.taxAmount?'google-conversion':'console-rate-estimate',taxMinor,commissionBasisPoints:input.commissionBasisPoints,
          commissionMinor,proceedsMinor:unitTotalMinor-taxMinor-commissionMinor,
          ...(converted.consoleTax?{taxRateBasisPoints:converted.consoleTax.rateBasisPoints,hasLocationOverrides:converted.consoleTax.hasLocationOverrides}:{})}}));
    }
  }
  return Object.freeze(products);
}
