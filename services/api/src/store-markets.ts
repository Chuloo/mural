import { ServiceError } from './errors.js';

/** Provider-neutral country identity. Apple storefront IDs must be mapped explicitly when enabled. */
export function storeRegionCode(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Z]{2}$/.test(value)) throw new ServiceError('invalid_store_region');
  return value;
}

/** Keep store checkout money separate from the wallet's fixed USD allocation. */
export interface StoreMarketPrice {
  regionCode: string;
  currency: string;
  currencyExponent: number;
  unitTotalMinor: number;
  scheduleVersion: string;
  /** Reviewed tax/commission/proceeds estimates; settlement is verified independently from the provider. */
  taxBasis: 'google-conversion'|'console-rate-estimate';
  taxRateBasisPoints?: number;
  hasLocationOverrides?: boolean;
  taxMinor: number;
  commissionBasisPoints: number;
  commissionMinor: number;
  proceedsMinor: number;
}
export function validateStoreMarketPrice(price: StoreMarketPrice): void {
  storeRegionCode(price.regionCode);
  const money = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 100_000_000;
  if (!['google-conversion','console-rate-estimate'].includes(price.taxBasis) || !/^[a-z]{3}$/.test(price.currency) || !Number.isInteger(price.currencyExponent) || price.currencyExponent < 0 || price.currencyExponent > 3 ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(price.scheduleVersion) ||
    ![price.unitTotalMinor,price.taxMinor,price.commissionMinor,price.proceedsMinor].every(money) ||
    price.unitTotalMinor <= 0 || price.proceedsMinor <= 0 || price.taxMinor >= price.unitTotalMinor ||
    !Number.isInteger(price.commissionBasisPoints) || price.commissionBasisPoints < 0 || price.commissionBasisPoints > 10_000 ||
    price.commissionMinor !== Number((BigInt(price.unitTotalMinor-price.taxMinor)*BigInt(price.commissionBasisPoints)+9999n)/10000n) ||
    price.unitTotalMinor !== price.taxMinor+price.commissionMinor+price.proceedsMinor) throw new ServiceError('invalid_store_market_price');
  if(price.taxBasis==='console-rate-estimate') {
    const rate=price.taxRateBasisPoints;
    if(!Number.isInteger(rate) || rate!<0 || rate!>10000 || typeof price.hasLocationOverrides!=='boolean' ||
      price.taxMinor!==Number((BigInt(price.unitTotalMinor)*BigInt(rate!)+BigInt(10000+rate!)-1n)/BigInt(10000+rate!)))
      throw new ServiceError('invalid_store_market_price');
  } else if(price.taxRateBasisPoints!==undefined || price.hasLocationOverrides!==undefined) throw new ServiceError('invalid_store_market_price');
}

/** Every priced country needs an explicit availability decision before a catalog can be generated. */
export function reviewedStoreRegionCodes(priced:readonly string[],allowed:readonly string[],excluded:readonly string[]):readonly string[] {
  const set=(values:readonly string[])=>{
    if(!Array.isArray(values) || values.length>300) throw new ServiceError('invalid_store_market_availability');
    const result=new Set(values.map(storeRegionCode));
    if(result.size!==values.length) throw new ServiceError('invalid_store_market_availability');
    return result;
  };
  const prices=set(priced),service=set(allowed),blocked=set(excluded);
  if(!prices.size || !service.size || [...service].some(region=>!prices.has(region)||blocked.has(region)) ||
    [...blocked].some(region=>!prices.has(region)) || [...prices].some(region=>!service.has(region)&&!blocked.has(region)))
    throw new ServiceError('invalid_store_market_availability');
  return Object.freeze([...prices].filter(region=>service.has(region)).sort());
}
