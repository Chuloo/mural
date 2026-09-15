import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { createHash, createPrivateKey } from 'node:crypto';
import type { Database } from './db.js';
import { ServiceError } from './errors.js';
import { AIValuePurchases, PurchaseFulfillmentRouter, type AIValueProduct } from './ai-value-purchases.js';
import { MinutePurchases, type MinuteProduct, type PurchaseEnvironment } from './minute-purchases.js';
import { MinuteReceiptVault, MinuteDeliveryWorker, type MinuteDeliveryAdapter } from './minute-provider-delivery.js';
import { StripeMinuteProvider, type StripeMinuteTransport } from './stripe-minute-provider.js';
import { PlayMinuteProvider } from './play-minute-provider.js';
import { GooglePlayHTTPTransport, GoogleServiceAccountTokens, type PlayTransport } from './google-play-transport.js';
import { MinuteCommerceRunner, PlayVoidReconciler, type CommerceRunnerOptions } from './minute-commerce-runner.js';

export const permanentAndroidPackage = 'chat.mural.android';
type Environment = Readonly<Record<string, string | undefined>>;
const prefix = 'MURAL_MINUTE_';
const invalid = () => new ServiceError('minute_commerce_configuration_invalid', 503);
const object = (value: unknown): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  return value as Record<string, any>;
};
const keys = (value: unknown, allowed: readonly string[]) => {
  const result = object(value);
  if (Object.keys(result).some(key => !allowed.includes(key))) throw invalid();
  return result;
};
const flag = (env: Environment, name: string): boolean => {
  const value = env[prefix + name];
  if (value !== undefined && value !== 'true' && value !== 'false') throw invalid();
  return value === 'true';
};
const base64Key = (value: unknown): Buffer => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(value)) throw invalid();
  const decoded = Buffer.from(value, 'base64');
  if (decoded.length !== 32 || decoded.toString('base64') !== value) throw invalid();
  return decoded;
};

/** Reads only the named protected regular file, with no discovery, symlinks or unbounded reads. */
async function protectedJSON(path: string | undefined, maximumBytes = 65_536): Promise<{ value: unknown; hash: string }> {
  if (!path || !isAbsolute(path) || path.length > 4096 || /[\x00-\x1f]/.test(path)) throw invalid();
  let handle: Awaited<ReturnType<typeof open>> | undefined;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const before = await handle.stat(), uid = process.getuid?.();
    if (!before.isFile() || before.nlink !== 1 || (before.mode & 0o077) !== 0 ||
      (before.uid !== 0 && before.uid !== uid) || before.size < 2 || before.size > maximumBytes) throw invalid();
    const buffer = Buffer.alloc(maximumBytes + 1), { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    const after = await handle.stat();
    if (bytesRead !== before.size || bytesRead > maximumBytes || after.size !== before.size || after.mtimeMs !== before.mtimeMs) throw invalid();
    const raw = buffer.subarray(0, bytesRead);
    return { value: JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(raw)), hash: createHash('sha256').update(raw).digest('hex') };
  } catch { throw invalid(); }
  finally { await handle?.close(); }
}

export interface MinuteCommerceServices {
  /** Historical fixed-minute reconciliation only. New sales are off in this runtime. */
  purchases: MinutePurchases;
  aiPurchases: AIValuePurchases;
  fulfillment: PurchaseFulfillmentRouter;
  stripe?: StripeMinuteProvider;
  play?: PlayMinuteProvider;
  vault: MinuteReceiptVault;
  worker: MinuteDeliveryWorker;
  runner: MinuteCommerceRunner;
  environment: PurchaseEnvironment;
  salesEnabled: boolean;
  catalogSHA256: string;
}
export interface MinuteCommerceDependencies {
  stripeTransport?: StripeMinuteTransport;
  playTransport?: PlayTransport;
  request?: typeof fetch;
  onFailure?: CommerceRunnerOptions['onFailure'];
}

/** Optional startup boundary. Credentials never come from HTTP input or database configuration. */
export async function configuredMinuteCommerce(db: Database, env: Environment = process.env,
  dependencies: MinuteCommerceDependencies = {}): Promise<MinuteCommerceServices | undefined> {
  try { return await configure(db, env, dependencies); }
  catch { throw invalid(); }
}
async function configure(db: Database, env: Environment, dependencies: MinuteCommerceDependencies): Promise<MinuteCommerceServices | undefined> {
  const enabled = flag(env, 'COMMERCE_ENABLED'), salesEnabled = flag(env, 'SALES_ENABLED'), allowLive = flag(env, 'ALLOW_LIVE');
  if (!enabled) {
    if (salesEnabled) throw invalid();
    return undefined;
  }
  const pathNames = ['COMMERCE_CONFIG_FILE', 'CATALOG_FILE', 'RECEIPT_KEYS_FILE', 'STRIPE_CREDENTIALS_FILE', 'PLAY_SERVICE_ACCOUNT_FILE', 'PLAY_BINDING_KEY_FILE'];
  const paths = pathNames.map(name => env[prefix + name]).filter((path): path is string => path !== undefined);
  if (new Set(paths).size !== paths.length) throw invalid();
  const manifest = keys((await protectedJSON(env[prefix + 'COMMERCE_CONFIG_FILE'])).value,
    ['version','environment','webOrigin','stripe','play','runner']);
  if (manifest.version !== 1 || !['test','live'].includes(manifest.environment) ||
    (manifest.environment === 'live' && !allowLive) || (manifest.environment === 'test' && allowLive) ||
    (!manifest.stripe && !manifest.play)) throw invalid();
  const environment: PurchaseEnvironment = manifest.environment;
  if (manifest.webOrigin !== undefined) {
    const origin = new URL(manifest.webOrigin);
    if (typeof manifest.webOrigin !== 'string' || origin.protocol !== 'https:' || origin.username || origin.password ||
      origin.port || origin.pathname !== '/' || origin.search || origin.hash) throw invalid();
  }
  const catalogFile = await protectedJSON(env[prefix + 'CATALOG_FILE'], 262_144);
  const catalog = keys(catalogFile.value, ['version','products']);
  if (![1,2].includes(catalog.version) || (catalog.version===1 && salesEnabled) || !Array.isArray(catalog.products) || catalog.products.length > 100 || (salesEnabled && !catalog.products.length)) throw invalid();
  const approved = env[prefix + 'CATALOG_APPROVED_SHA256'];
  if (approved !== undefined && (!/^[a-f0-9]{64}$/.test(approved) || approved !== catalogFile.hash)) throw invalid();
  if (salesEnabled && approved !== catalogFile.hash) throw invalid();
  const products: MinuteProduct[] = catalog.version===1 ? catalog.products.map((value: unknown) => keys(value,
    ['provider','environment','merchant','sku','providerProduct','minutes','currency','totalMinor']) as MinuteProduct) : [];
  const aiProducts: AIValueProduct[] = catalog.version===2 ? catalog.products.map((value: unknown) => keys(value,
    ['provider','environment','merchant','sku','providerProduct','currency','totalMinor','entitlementKind','billingBasis',
      'estimate','aiValueNanoUSD','estimatedMilliseconds','quote']) as unknown as AIValueProduct) : [];
  const receipt = keys((await protectedJSON(env[prefix + 'RECEIPT_KEYS_FILE'])).value, ['activeKeyID','keys']);
  const ring = new Map(Object.entries(object(receipt.keys)).map(([id, key]) => [id, base64Key(key)]));
  const vault = new MinuteReceiptVault(db, receipt.activeKeyID, ring);
  let stripe: StripeMinuteProvider | undefined, play: PlayMinuteProvider | undefined;
  if (manifest.stripe) {
    const settings = keys(manifest.stripe, ['accountID','managedPayments']);
    const credentials = keys((await protectedJSON(env[prefix + 'STRIPE_CREDENTIALS_FILE'])).value, ['secretKey','webhookSecret']);
    stripe = new StripeMinuteProvider(db, vault, { accountID: settings.accountID, secretKey: credentials.secretKey,
      webhookSecret: credentials.webhookSecret, managedPayments: settings.managedPayments, environment, allowLive, checkoutEnabled: salesEnabled, webOrigin: manifest.webOrigin }, dependencies.stripeTransport);
  } else if (env[prefix + 'STRIPE_CREDENTIALS_FILE'] !== undefined || dependencies.stripeTransport) throw invalid();
  if (manifest.play) {
    const settings = keys(manifest.play, ['packageName','currencyExponents']);
    if (settings.packageName !== permanentAndroidPackage) throw invalid();
    const credentials = keys((await protectedJSON(env[prefix + 'PLAY_SERVICE_ACCOUNT_FILE'])).value,
      ['type','project_id','private_key_id','private_key','client_email','client_id','auth_uri','token_uri','auth_provider_x509_cert_url','client_x509_cert_url','universe_domain']);
    if (credentials.type !== 'service_account' ||
      (credentials.token_uri !== undefined && credentials.token_uri !== 'https://oauth2.googleapis.com/token') ||
      (credentials.universe_domain !== undefined && credentials.universe_domain !== 'googleapis.com')) throw invalid();
    const signingKey = createPrivateKey(credentials.private_key);
    if (signingKey.asymmetricKeyType !== 'rsa' || (signingKey.asymmetricKeyDetails?.modulusLength ?? 0) < 2048) throw invalid();
    const tokens = new GoogleServiceAccountTokens(credentials.client_email, credentials.private_key, dependencies.request);
    const binding = keys((await protectedJSON(env[prefix + 'PLAY_BINDING_KEY_FILE'])).value, ['key']);
    play = new PlayMinuteProvider(db, vault, { packageName: permanentAndroidPackage, environment, allowLive,
      bindingKey: base64Key(binding.key), currencyExponents: object(settings.currencyExponents), purchasesEnabled: salesEnabled },
    dependencies.playTransport ?? new GooglePlayHTTPTransport(tokens, dependencies.request));
    for (const product of [...products,...aiProducts]) if (product.provider === 'play' && (settings.currencyExponents[product.currency] === undefined ||
      ('quote' in product && product.quote.currencyExponent!==settings.currencyExponents[product.currency]))) throw invalid();
  } else if (env[prefix + 'PLAY_SERVICE_ACCOUNT_FILE'] !== undefined || env[prefix + 'PLAY_BINDING_KEY_FILE'] !== undefined || dependencies.playTransport) throw invalid();
  const adapters: MinuteDeliveryAdapter[] = [stripe, play].filter((item): item is StripeMinuteProvider | PlayMinuteProvider => !!item);
  const purchases = new MinutePurchases(db, { catalog: products, verifiers: adapters, salesEnabled: false });
  const aiPurchases = new AIValuePurchases(db, { catalog: aiProducts, verifiers: adapters, salesEnabled });
  const fulfillment = new PurchaseFulfillmentRouter(db, purchases, aiPurchases, adapters);
  // Removing a historical decryption key or provider would strand settled purchases and refunds.
  const receipts = (await db.query(`SELECT DISTINCT encryption_key_id,provider,environment,merchant FROM minute_provider_receipts
    UNION SELECT NULL AS encryption_key_id,provider,environment,merchant FROM minute_purchase_orders`)).rows;
  for (const row of receipts) if ((row.encryption_key_id !== null && !ring.has(row.encryption_key_id)) || !adapters.some(adapter =>
    adapter.provider === row.provider && adapter.environment === row.environment && adapter.merchant === row.merchant)) throw invalid();
  const runnerSettings = manifest.runner === undefined ? {} : keys(manifest.runner,
    ['intervalMilliseconds','deliveryLimit','reconciliationLimit','voidPagesPerRun']);
  const worker = new MinuteDeliveryWorker(db, fulfillment, adapters);
  const runner = new MinuteCommerceRunner(vault, worker, play ? new PlayVoidReconciler(db, play) : undefined,
    { ...runnerSettings, onFailure: dependencies.onFailure });
  return { purchases, aiPurchases, fulfillment, ...(stripe ? { stripe } : {}), ...(play ? { play } : {}), vault, worker, runner,
    environment, salesEnabled, catalogSHA256: catalogFile.hash };
}
