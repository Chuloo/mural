import {createHash} from 'node:crypto';
import {type ApplePriceSource} from './apple-market-prices.js';
import {type AppleListedPrice,type AppleMinutePack,type RegionalAppleCatalogInput,makeRegionalAppleCatalog} from './apple-regional-catalog.js';
import {reviewedStoreRegionCodes,storeRegionCode} from './store-markets.js';

export interface AppleStorefrontReview {
  version:1;
  reviewedOn:string;
  identitySources:readonly string[];
  serviceAvailability:{provider:'openai';sourceURL:string;reviewedOn:string;supportedRegionCodes:readonly string[]};
  storefronts:readonly {name:string;regionCode:string;storefront:string;eligible:boolean}[];
}
export interface ApplePricingExport {
  csv:string;
  csvSHA256:string;
  source:ApplePriceSource;
}
const packs=['small','medium','large'] as const;
const appleIDs={small:'6816506070',medium:'6816521026',large:'6816522011'} as const;
const headers=['Countries or Regions','Currency Code','Price','Proceeds','May Adjust Automatically'];
function invalid():never {throw new Error('Invalid reviewed Apple pricing export');}

/** Parses quoted Apple CSV fields without numeric coercion or loss of the original decimals. */
export function parseApplePricingCSV(input:string):readonly (readonly string[])[] {
  const text=input.replace(/^\uFEFF/,'');
  const rows:string[][]=[],row:string[]=[];
  let value='',quoted=false,closed=false;
  for(let i=0;i<text.length;i++) {
    const c=text[i]!;
    if(quoted) {
      if(c==='"') {if(text[i+1]==='"'){value+='"';i++;}else{quoted=false;closed=true;}}
      else value+=c;
    } else if(c==='"') {if(value!=='' || closed) invalid();quoted=true;}
    else if(c===',' || c==='\n' || c==='\r') {
      row.push(value);value='';closed=false;
      if(c!==',') {if(c==='\r'&&text[i+1]==='\n')i++;rows.push([...row]);row.length=0;}
    } else {if(closed) invalid();value+=c;}
  }
  if(quoted) invalid();
  if(value!=='' || row.length || closed) {row.push(value);rows.push([...row]);}
  if(rows.length<2 || JSON.stringify(rows.shift())!==JSON.stringify(headers)) invalid();
  if(rows.some(r=>r.length!==5 || r.some(v=>v===''))) invalid();
  return rows;
}

/** Matches all three provider exports to the same reviewed country identities and service decisions. */
export function reviewedAppleMarkets(review:AppleStorefrontReview,exports:Readonly<Record<AppleMinutePack,ApplePricingExport>>):{
  markets:RegionalAppleCatalogInput['markets'];enabledRegionCodes:readonly string[];excludedRegionCodes:readonly string[];
} {
  if(review.version!==1 || !/^\d{4}-\d{2}-\d{2}$/.test(review.reviewedOn) || review.serviceAvailability.provider!=='openai' ||
    review.serviceAvailability.sourceURL!=='https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories' ||
    !/^\d{4}-\d{2}-\d{2}$/.test(review.serviceAvailability.reviewedOn) || !review.identitySources.length ||
    !review.storefronts.length || review.storefronts.length>300) invalid();
  const names=new Set<string>(),countries=new Set<string>(),storefronts=new Set<string>();
  const supported=new Set(review.serviceAvailability.supportedRegionCodes.map(storeRegionCode));
  if(supported.size!==review.serviceAvailability.supportedRegionCodes.length) invalid();
  for(const r of review.storefronts) {
    if(!r.name || names.has(r.name) || countries.has(r.regionCode) || storefronts.has(r.storefront) ||
      !/^[A-Z]{3}$/.test(r.storefront) || r.eligible!==supported.has(storeRegionCode(r.regionCode))) invalid();
    names.add(r.name);countries.add(r.regionCode);storefronts.add(r.storefront);
  }
  const enabledRegionCodes=review.storefronts.filter(r=>r.eligible).map(r=>r.regionCode);
  const excludedRegionCodes=review.storefronts.filter(r=>!r.eligible).map(r=>r.regionCode);
  reviewedStoreRegionCodes([...countries],enabledRegionCodes,excludedRegionCodes);
  if(Object.keys(exports).length!==3 || Object.keys(exports).some(k=>!packs.includes(k as AppleMinutePack))) invalid();
  const packPrices=new Map<AppleMinutePack,Map<string,AppleListedPrice>>();
  for(const pack of packs) {
    const exported=exports[pack];
    if(!exported || exported.source.kind!=='app-store-connect-export' ||
      exported.source.sourceURL!==`https://appstoreconnect.apple.com/apps/6816001011/distribution/iaps/${appleIDs[pack]}` ||
      createHash('sha256').update(exported.csv).digest('hex')!==exported.csvSHA256) invalid();
    const prices=new Map<string,AppleListedPrice>();
    for(const [name,currency,customerPrice,proceeds,automatic] of parseApplePricingCSV(exported.csv)) {
      if(!names.has(name!) || prices.has(name!) || !/^[A-Z]{3}$/.test(currency!) || !['Y','N'].includes(automatic!)) invalid();
      prices.set(name!,{currency:currency!.toLowerCase(),customerPrice:customerPrice!,proceeds:proceeds!,
        mayAdjustAutomatically:automatic==='Y',source:exported.source});
    }
    if(prices.size!==names.size) invalid();
    packPrices.set(pack,prices);
  }
  const markets=review.storefronts.map(r=>({storefront:r.storefront,regionCode:r.regionCode,
    prices:Object.fromEntries(packs.map(pack=>[pack,packPrices.get(pack)!.get(r.name)!])) as Record<AppleMinutePack,AppleListedPrice>}));
  // Validate every price, including excluded regions, before returning deployment input.
  makeRegionalAppleCatalog({environment:'live',merchant:'chat.mural.ios',scheduleVersion:'validation',policyVersion:1,
    serviceFeeBasisPoints:1500,estimate:{nanoUSDPerMinute:'100000000',rateVersion:'validation'},enabledRegionCodes,excludedRegionCodes,markets});
  return {markets,enabledRegionCodes,excludedRegionCodes};
}
