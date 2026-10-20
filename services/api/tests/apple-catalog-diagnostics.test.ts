import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp, type Services } from '../src/app.js';
import type { Database } from '../src/db.js';
import { Diagnostics, type DiagnosticRecord, type DiagnosticSink } from '../src/diagnostics.js';
import { appleAppTransactionHeader } from '../src/apple-purchase-scope.js';
import type { PurchaseEnvironment } from '../src/minute-purchases.js';
import { ServiceError } from '../src/errors.js';

function fixture(environment: PurchaseEnvironment, admissionReady: boolean, sink?: DiagnosticSink) {
  const records: DiagnosticRecord[] = [];
  let productReads = 0, admissionChecks = 0;
  const products = ['USA', 'USA', 'USA', 'NOR'].map((storefront, index) => ({
    sku: `catalog-${index}`, providerProduct: `chat.mural.ios.minutes.catalog${index}.v1`,
    currency: storefront === 'USA' ? 'usd' : 'nok', totalMinor: storefront === 'USA' ? 700 : 8900,
    estimatedMilliseconds: '3600000', environment,
    quote: { estimateRateVersion: 'catalog-estimate', apple: { storefront, currencyExponent: 2, scheduleVersion: 'catalog-schedule' } },
  }));
  const commerce = {
    apple: { environment: 'live' }, appleSandbox: { environment: 'test' },
    appleScopes: {
      historyAdmissionRequired: true,
      verify: async (proof: unknown) => {
        if (proof !== 'private-verified-proof') throw new ServiceError('apple_purchase_verification_failed', 502);
        return environment;
      },
      admissionReady: async (scope: PurchaseEnvironment) => {
        assert.equal(scope, environment); admissionChecks++; return admissionReady;
      },
    },
    aiPurchases: {
      products: (provider: string, scope: PurchaseEnvironment) => {
        assert.equal(provider, 'apple'); assert.equal(scope, environment); productReads++; return products;
      },
      maximumQuantity: () => 10,
    },
  } as unknown as Services['minuteCommerce'];
  const db = { query: async () => { throw new Error('Catalog must not access accounts or the database.'); } } as unknown as Database;
  const app = createApp({ db, auth: {}, minuteCommerce: commerce,
    diagnostics: new Diagnostics(sink ?? (record => { records.push(record); })) });
  return { app, records, products, get productReads() { return productReads; }, get admissionChecks() { return admissionChecks; } };
}

test('Apple catalog logs one verified scope, readiness and filtered count while preserving each response', async () => {
  for (const environment of ['test', 'live'] as const) {
    for (const [storefront, admissionReady, count] of [['USA', true, 3], ['NOR', true, 1], ['USA', false, 0], ['private-country', true, 0]] as const) {
      const f = fixture(environment, admissionReady);
      try {
        const response = await f.app.inject({
          url: `/v1/minutes/products?provider=apple&storefront=${storefront}&environment=private-scope&offerCount=999&admissionReady=private-readiness&privateQuery=private-query`,
          headers: { [appleAppTransactionHeader]: 'private-verified-proof', authorization: 'Bearer private-token', 'x-request-id': 'private-identity' },
        });
        assert.equal(response.statusCode, 200, response.body);
        const expectedProducts = admissionReady ? f.products.filter(p => p.quote.apple.storefront === storefront).map(p => ({
          sku: p.sku, providerProduct: p.providerProduct, currency: p.currency, totalMinor: p.totalMinor,
          currencyExponent: 2, estimatedMilliseconds: p.estimatedMilliseconds, estimateRateVersion: 'catalog-estimate',
          scheduleVersion: 'catalog-schedule', storefront, environment,
        })) : [];
        assert.deepEqual(response.json(), { available: count > 0, maximumQuantity: storefront === 'private-country' ? 1 : 10, products: expectedProducts });
        const catalogs = f.records.filter(record => record.event === 'apple_catalog');
        assert.equal(catalogs.length, 1);
        assert.deepEqual(catalogs[0], { timestamp: catalogs[0]!.timestamp, level: 'info', event: 'apple_catalog',
          reference: catalogs[0]!.reference, environment, storefront: count ? storefront : 'unsupported',
          admissionReady, offerCount: count });
        assert.match(catalogs[0]!.reference!, /^[a-f0-9]{12}$/);
        assert.equal(catalogs[0]!.reference, f.records.find(record => record.event === 'request_completed')!.reference);
        assert.equal(f.admissionChecks, 1); assert.equal(f.productReads, admissionReady ? 1 : 0);
        assert.doesNotMatch(JSON.stringify(f.records), /private|Bearer|chat\.mural|totalMinor|currency|quote/);
      } finally { await f.app.close(); }
    }
  }
});

test('missing or forged Apple proof produces no catalog event or retained request input', async () => {
  for (const proof of [undefined, 'private-forged-proof']) {
    const f = fixture('test', true);
    try {
      const response = await f.app.inject({ url: '/v1/minutes/products?provider=apple&storefront=private-country',
        headers: proof === undefined ? {} : { [appleAppTransactionHeader]: proof } });
      assert.equal(response.statusCode, 502);
      assert.equal(f.records.filter(record => record.event === 'apple_catalog').length, 0);
      assert.equal(f.admissionChecks, 0); assert.equal(f.productReads, 0);
      assert.doesNotMatch(JSON.stringify(f.records), /private|forged|country/);
    } finally { await f.app.close(); }
  }
});

test('absent and repeated storefront inputs produce only the unsupported enum', async () => {
  for (const query of ['', '&storefront=USA&storefront=private-country']) {
    const f = fixture('test', true);
    try {
      const response = await f.app.inject({ url: `/v1/minutes/products?provider=apple${query}`,
        headers: { [appleAppTransactionHeader]: 'private-verified-proof' } });
      assert.equal(response.statusCode, 200);
      assert.deepEqual(response.json(), { available: false, maximumQuantity: 1, products: [] });
      const catalogs = f.records.filter(record => record.event === 'apple_catalog');
      assert.equal(catalogs.length, 1); assert.equal(catalogs[0]!.storefront, 'unsupported'); assert.equal(catalogs[0]!.offerCount, 0);
      assert.doesNotMatch(JSON.stringify(f.records), /private|country/);
    } finally { await f.app.close(); }
  }
});

test('Apple catalog still succeeds when its diagnostic sink fails', async () => {
  for (const sink of [() => { throw new Error('private-sink-error'); }, async () => { throw new Error('private-sink-error'); }]) {
    const f = fixture('test', true, sink);
    try {
      const response = await f.app.inject({ url: '/v1/minutes/products?provider=apple&storefront=USA',
        headers: { [appleAppTransactionHeader]: 'private-verified-proof' } });
      assert.equal(response.statusCode, 200); assert.equal(response.json().products.length, 3);
      await new Promise(resolve => setImmediate(resolve));
    } finally { await f.app.close(); }
  }
});
