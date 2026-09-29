import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { makeRegionalPlayCatalog } from '../../services/api/src/play-regional-catalog.js';
import { reviewedStoreRegionCodes } from '../../services/api/src/store-markets.js';

// Run with services/api/node_modules/.bin/tsx. Output is a deployment candidate, never store activation.
const [environment = 'live'] = process.argv.slice(2);
assert(environment === 'live' || environment === 'test', 'Environment must be live or test');
const outputPath = environment === 'test'
  ? new URL('../private/play-global-catalog-test.json', import.meta.url)
  : new URL('../private/play-global-catalog-live.json', import.meta.url);
const snapshot = JSON.parse(readFileSync(new URL('./play-regional-prices.json', import.meta.url), 'utf8'));
const legacy = JSON.parse(readFileSync(new URL('./play-catalog-draft.json', import.meta.url), 'utf8'));
assert.equal(snapshot.version, 1);
assert.equal(snapshot.taxBasis, 'console-rate-estimate');
assert.equal(snapshot.serviceAvailability.provider, 'openai');
assert.equal(snapshot.serviceAvailability.sourceURL, 'https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories');
assert.match(snapshot.serviceAvailability.reviewedOn, /^\d{4}-\d{2}-\d{2}$/);
const enabled = new Set(reviewedStoreRegionCodes(
  snapshot.regions.map((r: {regionCode: string}) => r.regionCode),
  snapshot.serviceAvailability.regionCodes,
  snapshot.excludedRegions.map((r: {regionCode: string}) => r.regionCode),
));
const regions = snapshot.regions.filter((r: {regionCode: string}) => enabled.has(r.regionCode));
const regional = makeRegionalPlayCatalog({
  environment, merchant: 'chat.mural.android', scheduleVersion: snapshot.scheduleVersion,
  policyVersion: 1, serviceFeeBasisPoints: 1500, commissionBasisPoints: 3000,
  estimate: {nanoUSDPerMinute: '100000000', rateVersion: 'play-20260929-estimate-v1'},
  currencyExponents: snapshot.currencyExponents,
  regionCodes: regions.map((r: {regionCode: string}) => r.regionCode),
  packs: (['small', 'medium', 'large'] as const).map(pack => ({
    pack, providerProduct: `chat.mural.android.minutes.${pack}.v1`,
    convertedRegionPrices: Object.fromEntries(regions.map((r: any) => [r.regionCode, {
      regionCode: r.regionCode, price: r.prices[pack],
      consoleTax: {rateBasisPoints: r.taxRateBasisPoints, hasLocationOverrides: r.hasLocationOverrides},
    }])),
  })),
});
assert.equal(legacy.products.length, 6);
assert(legacy.products.every((p: any) => p.provider === 'play' && !p.quote.play.regionCode));
const products = [...legacy.products.map((p: any) => ({...p, environment})), ...regional];
const output = JSON.stringify({version: 2, products}, null, 2) + '\n';
mkdirSync(new URL('../private/', import.meta.url), {recursive: true, mode: 0o700});
writeFileSync(outputPath, output, {mode: 0o600});
console.log(JSON.stringify({environment, regions: regions.length, products: products.length,
  currencyExponents: snapshot.currencyExponents,
  bytes: Buffer.byteLength(output), sha256: createHash('sha256').update(output).digest('hex')}));
