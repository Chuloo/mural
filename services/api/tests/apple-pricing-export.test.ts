import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {parseApplePricingCSV,reviewedAppleMarkets,type AppleStorefrontReview,type ApplePricingExport} from '../src/apple-pricing-export.js';
import {type RegionalApplePriceSnapshot} from '../src/apple-market-prices.js';
import {makeRegionalAppleCatalog,type AppleMinutePack} from '../src/apple-regional-catalog.js';

const review:AppleStorefrontReview={version:1,reviewedOn:'2026-10-04',identitySources:['https://unstats.un.org/unsd/methodology/m49/overview/'],
  serviceAvailability:{provider:'openai',sourceURL:'https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories',
    reviewedOn:'2026-10-04',supportedRegionCodes:['FR','ID']},storefronts:[
    {name:'France',regionCode:'FR',storefront:'FRA',eligible:true},
    {name:'Indonesia',regionCode:'ID',storefront:'IDN',eligible:true},
    {name:'China mainland',regionCode:'CN',storefront:'CHN',eligible:false}]};
const header='Countries or Regions,Currency Code,Price,Proceeds,May Adjust Automatically\r\n';
const packs=['small','medium','large'] as const;
const ids={small:'6816506070',medium:'6816521026',large:'6816522011'};
const hash=(v:string)=>createHash('sha256').update(v).digest('hex');
function exports():Record<AppleMinutePack,ApplePricingExport> {
  return Object.fromEntries(packs.map(pack=>{
    const csv=header+`France,EUR,8.0,5.67,Y\r\nIndonesia,IDR,${{small:149000,medium:249000,large:399000}[pack]},100000.123,Y\r\nChina mainland,CNY,48.0,36.23,Y\r\n`;
    return [pack,{csv,csvSHA256:hash(csv),source:{kind:'app-store-connect-export',capturedAt:'2026-10-04T00:00:00.000Z',
      sha256:'a'.repeat(64),sourceURL:`https://appstoreconnect.apple.com/apps/6816001011/distribution/iaps/${ids[pack]}`}}];
  })) as Record<AppleMinutePack,ApplePricingExport>;
}
test('Apple export parser preserves decimals and quoted country labels and rejects malformed CSV',()=>{
  assert.deepEqual(parseApplePricingCSV('\uFEFF'+header+'"Congo, Republic of the",USD,7.0,5.9500,Y\r\n'),
    [['Congo, Republic of the','USD','7.0','5.9500','Y']]);
  assert.deepEqual(parseApplePricingCSV(header+'"Quoted ""name""",USD,7,5,N'),[['Quoted "name"','USD','7','5','N']]);
  for(const text of [header+'"unclosed,USD,7,5,Y',header+'"name"x,USD,7,5,Y',header+'name,USD,7,5',header+'name,,7,5,Y',
    header+'name,USD,7,5,Y\n\n',header.replace('Price','Amount')+'name,USD,7,5,Y']) assert.throws(()=>parseApplePricingCSV(text));
});
test('Apple exports preserve original listed proceeds and complete reviewed country coverage across all packs',()=>{
  const input=reviewedAppleMarkets(review,exports());assert.equal(input.markets.length,3);
  assert.deepEqual(input.enabledRegionCodes,['FR','ID']);assert.deepEqual(input.excludedRegionCodes,['CN']);
  const products=makeRegionalAppleCatalog({...input,environment:'live',merchant:'chat.mural.ios',scheduleVersion:'synthetic-import',
    policyVersion:1,serviceFeeBasisPoints:1500,estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic'}});
  assert.equal(products.length,6);
  assert.deepEqual(products.filter(p=>p.quote.apple!.storefront==='IDN').map(p=>p.totalMinor),[14900000,24900000,39900000]);
  assert.ok(products.every(p=>(p.quote.apple as RegionalApplePriceSnapshot).proceeds!=='0' && !('proceedsUSDMinor' in p.quote.apple!)));
});
test('Apple importer fails closed for corrupt sources, incomplete country rows or unreviewed availability',()=>{
  for(const change of [
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.small.csv+='Unknown,USD,7,5,Y\r\n';},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.small.csv=x.small.csv.replace('France,EUR,8.0,5.67,Y\r\n','');x.small.csvSHA256=hash(x.small.csv);},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.small.csv=x.small.csv.replace('France,EUR,8.0,5.67,Y','France,XXX,8.0,5.67,Y');x.small.csvSHA256=hash(x.small.csv);},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.small.csv=x.small.csv.replace('France,EUR,8.0,5.67,Y','France,EUR,8.0,5.67,?');x.small.csvSHA256=hash(x.small.csv);},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.medium.csv=x.medium.csv.replace('France,EUR','France,USD');x.medium.csvSHA256=hash(x.medium.csv);},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.large.source.sourceURL=x.small.source.sourceURL;},
    (x:Record<AppleMinutePack,ApplePricingExport>)=>{x.large.csv+='France,EUR,8.0,5.67,Y\r\n';x.large.csvSHA256=hash(x.large.csv);},
  ]) {const x=exports();change(x);assert.throws(()=>reviewedAppleMarkets(review,x));}
  for(const altered of [{...review,storefronts:[...review.storefronts,{...review.storefronts[0]!,name:'duplicate'}]},
    {...review,storefronts:review.storefronts.map(r=>({...r,eligible:true}))},
    {...review,serviceAvailability:{...review.serviceAvailability,sourceURL:'https://example.com/regions'}}])
    assert.throws(()=>reviewedAppleMarkets(altered,exports()));
});
test('the frozen Apple country review enables162 of175 countries using the fresh service policy',async()=>{
  const actual=JSON.parse(await readFile(new URL('../../../release/apple/apple-storefronts.json',import.meta.url),'utf8')) as AppleStorefrontReview;
  assert.equal(actual.storefronts.length,175);assert.equal(actual.serviceAvailability.supportedRegionCodes.length,188);
  assert.equal(actual.storefronts.filter(r=>r.eligible).length,162);
  assert.deepEqual(actual.storefronts.filter(r=>!r.eligible).map(r=>r.regionCode).sort(),['AI','BM','BY','CN','HK','KY','MO','MS','RU','TC','VE','VG','XK']);
  const quote=(v:string)=>'"'+v.replaceAll('"','""')+'"';
  const mocked=exports();
  for(const pack of packs) {mocked[pack].csv=header+actual.storefronts.map(r=>`${quote(r.name)},USD,7.0,5.95,Y\r\n`).join('');mocked[pack].csvSHA256=hash(mocked[pack].csv);}
  assert.equal(reviewedAppleMarkets(actual,mocked).enabledRegionCodes.length,162);
});
test('the offline Apple generator rejects path arguments before reading or writing export files',()=>{
  const tool=fileURLToPath(new URL('../../../release/apple/generate-regional-apple-catalog.mts',import.meta.url));
  const cwd=fileURLToPath(new URL('../',import.meta.url));
  for(const args of [['../outside'],['/private/tmp/foreign'],['safe/../../foreign'],[''],['a'.repeat(91)],['valid-version','extra-path']]) {
    const result=spawnSync(process.execPath,['--import','tsx',tool,...args],{cwd,encoding:'utf8',timeout:10_000});
    assert.equal(result.error,undefined);assert.equal(result.status,1);
    assert.match(result.stderr,/Invalid schedule version|Only a schedule version argument is accepted/);
    assert.equal(result.stdout,'');
  }
});
