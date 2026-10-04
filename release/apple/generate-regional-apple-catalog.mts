import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {makeRegionalAppleCatalog,type AppleMinutePack} from '../../services/api/src/apple-regional-catalog.js';
import {reviewedAppleMarkets,type AppleStorefrontReview,type ApplePricingExport} from '../../services/api/src/apple-pricing-export.js';

// Offline candidate generator. Store availability and production activation are separate release actions.
const args=process.argv.slice(2);
assert(args.length<=1,'Only a schedule version argument is accepted');
const [scheduleVersion='asc-20261004-v1']=args;
assert.match(scheduleVersion,/^[A-Za-z0-9][A-Za-z0-9._:-]{0,89}$/,'Invalid schedule version');
const directory=new URL('../private/apple-pricing-20261004/',import.meta.url).pathname;
const sha=(data:string|Buffer)=>createHash('sha256').update(data).digest('hex');
const review=JSON.parse(readFileSync(new URL('./apple-storefronts.json',import.meta.url),'utf8')) as AppleStorefrontReview;
const manifest=JSON.parse(readFileSync(resolve(directory,'export-manifest.json'),'utf8'));
assert.equal(manifest.length,3,'All three reviewed exports are required');
const exported={} as Record<AppleMinutePack,ApplePricingExport>;
for(const pack of ['small','medium','large'] as const) {
  const entries=manifest.filter((row:any)=>row.pack===pack);assert.equal(entries.length,1,'Pack source must be unique');
  const entry=entries[0],csv=readFileSync(resolve(directory,`${pack}-prices.csv`),'utf8');
  assert.equal(sha(readFileSync(resolve(directory,`${pack}-prices.zip`))),entry.sha256,'Original provider download hash must match');
  assert.equal(sha(csv),entry.csvSHA256,'Extracted provider CSV hash must match');
  exported[pack]={csv,csvSHA256:entry.csvSHA256,source:{kind:'app-store-connect-export',sourceURL:entry.sourceURL,
    capturedAt:new Date(entry.capturedAt).toISOString(),sha256:entry.sha256}};
}
const input=reviewedAppleMarkets(review,exported),usa=input.markets.find(m=>m.storefront==='USA')!;
for(const [pack,price] of Object.entries({small:7,medium:13,large:20})) {
  assert.equal(usa.prices[pack as AppleMinutePack].currency,'usd');
  assert.equal(Number(usa.prices[pack as AppleMinutePack].customerPrice),price,'Approved US price anchors must match');
}
mkdirSync(directory,{recursive:true,mode:0o700});
const outputs=[];
for(const environment of ['live','test'] as const) {
  const products=makeRegionalAppleCatalog({...input,environment,merchant:'chat.mural.ios',scheduleVersion,
    policyVersion:1,serviceFeeBasisPoints:1500,estimate:{nanoUSDPerMinute:'100000000',rateVersion:'apple-20261004-estimate-v1'}});
  const body=JSON.stringify({version:2,products},null,2)+'\n';
  const filename=`apple-global-catalog-${environment}.json`;writeFileSync(resolve(directory,filename),body,{mode:0o600});
  outputs.push({environment,filename,products:products.length,bytes:Buffer.byteLength(body),sha256:sha(body)});
}
writeFileSync(resolve(directory,'normalized-price-snapshot.json'),JSON.stringify({...input,scheduleVersion},null,2)+'\n',{mode:0o600});
const summary={scheduleVersion,reviewedOn:review.reviewedOn,countries:input.markets.length,eligible:input.enabledRegionCodes.length,
  currencies:[...new Set(input.markets.filter(m=>input.enabledRegionCodes.includes(m.regionCode)).map(m=>m.prices.small.currency))].sort(),
  excluded:review.storefronts.filter(r=>!r.eligible),outputs};
writeFileSync(resolve(directory,'catalog-summary.json'),JSON.stringify(summary,null,2)+'\n',{mode:0o600});
console.log(JSON.stringify(summary));
