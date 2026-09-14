import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, chmod, symlink, link, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash, generateKeyPairSync } from 'node:crypto';
import { makeAIValueProduct } from '../src/ai-value-purchases.js';
import { configuredMinuteCommerce } from '../src/minute-commerce-config.js';

const privateKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
const stripeQuote = { provider: 'stripe', environment: 'test', merchant: 'acct_configfixture', sku: 'thirty',
  providerProduct: 'price_configfixture', minutes: 30, currency: 'usd', totalMinor: 997 };
const playQuote = { ...stripeQuote, provider: 'play', merchant: 'chat.mural.android', providerProduct: 'minute_thirty' };
const configError = { code: 'minute_commerce_configuration_invalid', status: 503 };
async function fixture(both = false) {
  const dir = await mkdtemp(join(tmpdir(), 'mural-commerce-config-'));
  const env: Record<string, string> = { MURAL_MINUTE_COMMERCE_ENABLED: 'true' };
  const file = async (name: string, value: unknown) => {
    const path = join(dir, name + '.json'); await writeFile(path, JSON.stringify(value), { mode: 0o600 });
    env['MURAL_MINUTE_' + name] = path; return path;
  };
  const manifest: any = { version: 1, environment: 'test', webOrigin: 'https://mural.example.test', stripe: { accountID: 'acct_configfixture' },
    ...(both ? { play: { packageName: 'chat.mural.android', currencyExponents: { usd: 2 } } } : {}) };
  const catalog = { version: 1, products: both ? [stripeQuote,playQuote] : [stripeQuote] };
  await file('COMMERCE_CONFIG_FILE', manifest); await file('CATALOG_FILE', catalog);
  await file('RECEIPT_KEYS_FILE', { activeKeyID: 'current', keys: { current: Buffer.alloc(32, 3).toString('base64') } });
  await file('STRIPE_CREDENTIALS_FILE', { secretKey: 'sk_test_' + 'syntheticfixture'.repeat(2), webhookSecret: 'whsec_' + 'syntheticfixture'.repeat(2) });
  if (both) {
    await file('PLAY_SERVICE_ACCOUNT_FILE', { type: 'service_account', client_email: 'mural@synthetic-project.iam.gserviceaccount.com', private_key: privateKey,
      token_uri: 'https://oauth2.googleapis.com/token', universe_domain: 'googleapis.com' });
    await file('PLAY_BINDING_KEY_FILE', { key: Buffer.alloc(32, 4).toString('base64') });
  }
  let rows: any[] = [], queries = 0, network = 0;
  const db = { query: async (sql: string) => { queries++; assert.match(sql, /^SELECT DISTINCT encryption_key_id/); return { rows }; } } as any;
  const dependencies = { request: (async () => { network++; throw new Error('No startup provider calls'); }) as typeof fetch };
  return { env, dir, file, db, manifest, catalog, dependencies, set rows(value: any[]) { rows = value; },
    get queries() { return queries; }, get network() { return network; },
    async approve() { env.MURAL_MINUTE_CATALOG_APPROVED_SHA256 = createHash('sha256').update(await readFile(env.MURAL_MINUTE_CATALOG_FILE!)).digest('hex'); },
    async clean() { await rm(dir, { recursive: true, force: true }); } };
}

test('commerce remains absent on defaults and never reads credentials, queries DB or starts network work', async () => {
  const db = { query: () => { throw new Error('No database work while disabled'); } } as any;
  assert.equal(await configuredMinuteCommerce(db, {}), undefined);
  assert.equal(await configuredMinuteCommerce(db, { MURAL_MINUTE_COMMERCE_ENABLED: 'false', MURAL_MINUTE_COMMERCE_CONFIG_FILE: '/not-a-real-file' }), undefined);
  await assert.rejects(configuredMinuteCommerce(db, { MURAL_MINUTE_SALES_ENABLED: 'true' }), configError);
  await assert.rejects(configuredMinuteCommerce(db, { MURAL_MINUTE_COMMERCE_ENABLED: 'yes' }), configError);
  await assert.rejects(configuredMinuteCommerce(db, { MURAL_MINUTE_COMMERCE_ENABLED: 'true' }), configError);
});

test('protected configuration constructs optional providers without opening sales or starting timers', async () => {
  const f = await fixture();
  try {
    const service = (await configuredMinuteCommerce(f.db, f.env, f.dependencies))!;
    assert.equal(service.salesEnabled, false); assert.equal(service.environment, 'test'); assert.ok(service.stripe); assert.equal(service.play, undefined);
    assert.deepEqual(service.purchases.products('stripe'), []); assert.equal(f.queries, 1); assert.equal(f.network, 0);
    await service.runner.stop();
    const noStripe = { ...f.manifest }; delete noStripe.stripe;
    await f.file('COMMERCE_CONFIG_FILE', noStripe);
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
  } finally { await f.clean(); }
});

test('sales require explicit activation and approval of the exact immutable catalog bytes', async () => {
  const f = await fixture();
  try {
    f.env.MURAL_MINUTE_SALES_ENABLED = 'true';
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.approve();
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    const quote=makeAIValueProduct({provider:'stripe',environment:'test',merchant:'acct_configfixture',sku:'synthetic-ai',providerProduct:'price_configfixture',
      currency:'usd',currencyExponent:2,aiValueMinor:100,policyVersion:1,serviceFeeBasisPoints:1500,
      processing:{rateBasisPoints:0,fixedMinor:0,bufferBasisPoints:0},exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},
      estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'}});
    const catalog={version:2,products:[quote]};
    await f.file('CATALOG_FILE',catalog); await f.approve();
    const service = (await configuredMinuteCommerce(f.db, f.env))!;
    assert.equal(service.salesEnabled, true); assert.deepEqual(service.purchases.products('stripe'), []);
    assert.deepEqual(service.aiPurchases.products('stripe'),[quote]); await service.runner.stop();
    await writeFile(f.env.MURAL_MINUTE_CATALOG_FILE!, JSON.stringify(catalog) + '\n');
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.approve(); await f.file('CATALOG_FILE', { version: 1, products: [] }); await f.approve();
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
  } finally { await f.clean(); }
});

test('live environment needs its separate gate and environment-matched keys and quotes', async () => {
  const f = await fixture();
  try {
    f.manifest.environment = 'live'; await f.file('COMMERCE_CONFIG_FILE', f.manifest);
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    f.env.MURAL_MINUTE_ALLOW_LIVE = 'true';
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.file('STRIPE_CREDENTIALS_FILE', { secretKey: 'sk_live_' + 'syntheticfixture'.repeat(2), webhookSecret: 'whsec_' + 'syntheticfixture'.repeat(2) });
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.file('CATALOG_FILE', { version: 1, products: [{ ...stripeQuote, environment: 'live' }] });
    const service = (await configuredMinuteCommerce(f.db, f.env))!;
    assert.equal(service.environment, 'live'); assert.equal(service.salesEnabled, false); await service.runner.stop();
  } finally { await f.clean(); }
});

test('restricted Stripe keys retain environment validation and do not open sales', async () => {
  const f = await fixture();
  try {
    for (const environment of ['test', 'live']) {
      f.manifest.environment = environment;
      f.env.MURAL_MINUTE_ALLOW_LIVE = String(environment === 'live');
      await f.file('COMMERCE_CONFIG_FILE', f.manifest);
      await f.file('CATALOG_FILE', { version: 1, products: [{ ...stripeQuote, environment }] });
      await f.file('STRIPE_CREDENTIALS_FILE', { secretKey: `rk_${environment}_` + 'syntheticfixture'.repeat(2),
        webhookSecret: 'whsec_' + 'syntheticfixture'.repeat(2) });
      const service = (await configuredMinuteCommerce(f.db, f.env))!;
      assert.equal(service.environment, environment);
      assert.equal(service.salesEnabled, false);
      await service.runner.stop();
      const other = environment === 'test' ? 'live' : 'test';
      await f.file('STRIPE_CREDENTIALS_FILE', { secretKey: `rk_${other}_` + 'syntheticfixture'.repeat(2),
        webhookSecret: 'whsec_' + 'syntheticfixture'.repeat(2) });
      await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    }
  } finally { await f.clean(); }
});

test('configuration rejects public files, symlinks, hard links, oversized input and malformed JSON without exposing contents', async () => {
  const f = await fixture();
  try {
    const original = f.env.MURAL_MINUTE_STRIPE_CREDENTIALS_FILE!, copy = await readFile(original);
    await chmod(original, 0o644); await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError); await chmod(original, 0o600);
    const symbolic = join(f.dir, 'symbolic.json'); await symlink(original, symbolic); f.env.MURAL_MINUTE_STRIPE_CREDENTIALS_FILE = symbolic;
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError); f.env.MURAL_MINUTE_STRIPE_CREDENTIALS_FILE = original;
    const hard = join(f.dir, 'hard.json'); await link(original, hard); await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError); await rm(hard);
    for (const raw of ['{"secret":"must-not-appear"', 'x'.repeat(65_537)]) {
      await writeFile(original, raw);
      await assert.rejects(configuredMinuteCommerce(f.db, f.env), (error: any) => {
        assert.equal(error.code, configError.code); assert.equal(error.message, configError.code); assert.equal(JSON.stringify(error).includes('must-not-appear'), false); return true;
      });
    }
    await writeFile(original, copy); f.env.MURAL_MINUTE_STRIPE_CREDENTIALS_FILE = 'relative.json';
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
  } finally { await f.clean(); }
});

test('Play fixes the permanent Android package and requires complete existing service credentials and currency exponents', async () => {
  const f = await fixture(true);
  try {
    const service = (await configuredMinuteCommerce(f.db, f.env, f.dependencies))!;
    assert.equal(service.play!.merchant, 'chat.mural.android'); assert.equal(f.network, 0); await service.runner.stop();
    f.manifest.play.packageName = 'chat.mural.old'; await f.file('COMMERCE_CONFIG_FILE', f.manifest);
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    f.manifest.play.packageName = 'chat.mural.android'; f.manifest.play.currencyExponents = { eur: 2 }; await f.file('COMMERCE_CONFIG_FILE', f.manifest);
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    f.manifest.play.currencyExponents = { usd: 2 }; await f.file('COMMERCE_CONFIG_FILE', f.manifest);
    delete f.env.MURAL_MINUTE_PLAY_BINDING_KEY_FILE; await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
  } finally { await f.clean(); }
});

test('receipt rotation retains historical keys and scopes; missing configuration fails before work starts', async () => {
  const f = await fixture();
  try {
    f.rows = [{ encryption_key_id: 'old', provider: 'stripe', environment: 'test', merchant: 'acct_configfixture' }];
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.file('RECEIPT_KEYS_FILE', { activeKeyID: 'current', keys: { current: Buffer.alloc(32, 3).toString('base64'), old: Buffer.alloc(32, 5).toString('base64') } });
    const service = (await configuredMinuteCommerce(f.db, f.env))!; await service.runner.stop();
    f.rows = [{ encryption_key_id: 'old', provider: 'play', environment: 'test', merchant: 'chat.mural.android' }];
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    f.rows = [{ encryption_key_id: null, provider: 'play', environment: 'test', merchant: 'chat.mural.android' }];
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    f.rows = [{ encryption_key_id: null, provider: 'stripe', environment: 'test', merchant: 'acct_configfixture' }];
    await (await configuredMinuteCommerce(f.db, f.env))!.runner.stop();
  } finally { await f.clean(); }
});

test('unexpected fields, mismatched product bindings, partial providers and invalid runner limits fail closed', async () => {
  const f = await fixture();
  try {
    for (const product of [{ ...stripeQuote, clientAccountID: 'forbidden' }, { ...stripeQuote, merchant: 'acct_other' },
      { ...stripeQuote, minutes: 0.5 }, { ...stripeQuote, totalMinor: 0 }]) {
      await f.file('CATALOG_FILE', { version: 1, products: [product] });
      await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    }
    await f.file('CATALOG_FILE', f.catalog);
    await f.file('COMMERCE_CONFIG_FILE', { ...f.manifest, salesEnabled: true }); await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.file('COMMERCE_CONFIG_FILE', { ...f.manifest, runner: { deliveryLimit: 0 } }); await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
    await f.file('COMMERCE_CONFIG_FILE', f.manifest); f.env.MURAL_MINUTE_PLAY_SERVICE_ACCOUNT_FILE = '/not-used';
    await assert.rejects(configuredMinuteCommerce(f.db, f.env), configError);
  } finally { await f.clean(); }
});
