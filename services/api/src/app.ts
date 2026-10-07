import Fastify, { type FastifyRequest } from 'fastify';
import { createHmac, randomUUID } from 'node:crypto';
import type { Database } from './db.js';
import { accountProfile, authenticate, createChallenge, connectGoogleIdentity, deleteAccount, exchangeIdentity, hasGoogleSignIn, signOut, type verifyIdentity, type AppleRevoker, type AuthConfig } from './auth.js';
import { HelperSessionLimitError, ServiceError } from './errors.js';
import { storeRegionCode } from './store-markets.js';
import { applyStripeEvent, type SandboxPayments } from './payments.js';
import { RATE_VERSION } from './pricing.js';
import { aiPricingPolicy } from './ai-top-up-pricing.js';
import { paidAIBalance } from './ledger.js';
import { conversationBalance } from './conversation-balance.js';
import { trialEligibility, UnconfiguredAttestor, type TrialAttestor } from './trial.js';
import type { HostedVoice } from './hosted-voice.js';
import { ACCESS_REQUEST_PATH, trustedClientNetwork, type AccessRequests } from './access-requests.js';
import type { AuthAdmission } from './auth-admission.js';
import { claimWelcomeMinutes, minuteBalance, UnconfiguredMinuteAttestor, type MinuteAttestor } from './minutes.js';
import { startGuestMinutes, linkGuestMinutes, UnconfiguredGuestMinuteAttestor, type GuestMinuteAttestor } from './guest-minutes.js';
import { AI_REPORT_BODY_LIMIT, AI_REPORT_PATH, reportNetwork, type AIReports } from './feedback.js';
import { stripeOrderByKey, type MinutePurchases, type PurchaseEnvironment } from './minute-purchases.js';
import { appleAppTransactionHeader, appleSignedEnvironment, type ApplePurchaseScopes } from './apple-purchase-scope.js';
import type { AIValuePurchases, PurchaseFulfillmentRouter } from './ai-value-purchases.js';
import type { StripeMinuteProvider } from './stripe-minute-provider.js';
import type { AppleMinuteProvider } from './apple-minute-provider.js';
import type { PlayMinuteProvider } from './play-minute-provider.js';
import type { PlayRtdnSubscriber } from './google-play-rtdn.js';
import { HOSTED_HELPER_BODY_LIMIT, type HostedHelpers } from './hosted-helpers.js';
import { Diagnostics, errorReference } from './diagnostics.js';
import { startupDiagnostic, type StartupDiagnostic } from './startup-diagnostics.js';
import { WEB_PURCHASE_PATH, type WebPurchases } from './web-purchases.js';

declare module 'fastify' {
  interface FastifyContextConfig { rateLimit?: { max: number; timeWindow: number } }
}

export interface Services { diagnostics?: Diagnostics; db: Database; auth: AuthConfig; payments?: SandboxPayments; attestor?: TrialAttestor; minuteAttestor?: MinuteAttestor; guestMinuteAttestor?: GuestMinuteAttestor; appleRevoker?: AppleRevoker; hosted?: HostedVoice; accessRequests?: AccessRequests; aiReports?: AIReports;
  webPurchases?: WebPurchases;
  onStartupDiagnostic?: (diagnostic: StartupDiagnostic) => void | Promise<void>;
  hostedHelpers?: HostedHelpers;
  minuteCommerce?: { purchases: MinutePurchases; aiPurchases?: AIValuePurchases; fulfillment?: PurchaseFulfillmentRouter;
    stripe?: StripeMinuteProvider; play?: PlayMinuteProvider; apple?:AppleMinuteProvider; appleSandbox?:AppleMinuteProvider;
    appleScopes?:ApplePurchaseScopes; playNotifications?: Pick<PlayRtdnSubscriber,'isOperational'> };
  accounts?: { admission: AuthAdmission; identityVerifier?: typeof verifyIdentity } }
const accountPaths = new Set(['/v1/auth/challenge', '/v1/auth/exchange', '/v1/auth/sign-out', '/v1/account', '/v1/wallet', '/v1/minutes/welcome', '/v1/minutes/link-guest',
  '/v1/account/connect-google',
  '/v1/minutes/orders', '/v1/minutes/orders/by-key/:key', '/v1/minutes/orders/:id', '/v1/minutes/orders/:id/play', '/v1/minutes/play/recover','/v1/minutes/orders/:id/apple','/v1/minutes/apple/recover']);
const paymentReadOptions = { config: { rateLimit: { max: 120, timeWindow: 60_000 } } };
const objectBody = (request: FastifyRequest): Record<string, unknown> => {
  if (!request.body || typeof request.body !== 'object' || Array.isArray(request.body) || Buffer.isBuffer(request.body)) throw new ServiceError('invalid_request');
  return request.body as Record<string, unknown>;
};
const stringField = (body: Record<string, unknown>, field: string, max = 1024) => {
  const value = body[field];
  if (typeof value !== 'string' || !value || value.length > max) throw new ServiceError('invalid_request');
  return value;
};
const uuid = (text: string) => {
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(text)) throw new ServiceError('invalid_request');
  return text;
};

export function createApp(services: Services) {
  const { db } = services;
  const diagnostics = services.diagnostics ?? new Diagnostics();
  const failed = new WeakSet<FastifyRequest>();
  const fundingScopes=new WeakMap<FastifyRequest,Promise<PurchaseEnvironment | undefined>>();
  const fundingScope=(request:FastifyRequest,requireApple=false):Promise<PurchaseEnvironment | undefined>=>{
    const commerce=services.minuteCommerce,proof=request.headers[appleAppTransactionHeader];
    if(proof===undefined) {
      // Historical single-environment servers and other platforms retain their defaults.
      if(requireApple && commerce?.appleSandbox)throw new ServiceError('apple_purchase_verification_failed',502);
      return Promise.resolve(requireApple?commerce?.apple?.environment:undefined);
    }
    if(!commerce?.appleScopes)throw new ServiceError('minute_purchases_unavailable',503);
    if(!fundingScopes.has(request))fundingScopes.set(request,commerce.appleScopes.verify(proof));
    return fundingScopes.get(request)!;
  };
  const operation = (request: FastifyRequest) => `${request.method} ${request.routeOptions.url ?? "unmatched"}`;
  const orderStatus = async (account: string, id: string) => {
    const commerce = services.minuteCommerce;
    if (!commerce) throw new ServiceError('minute_purchases_unavailable', 503);
    const kind = (await db.query('SELECT entitlement_kind FROM minute_purchase_orders WHERE id=$1 AND account_id=$2', [id, account])).rows[0]?.entitlement_kind;
    if (kind === 'ai_value') {
      if (!commerce.aiPurchases) throw new ServiceError('ai_value_purchases_unavailable', 503);
      return commerce.aiPurchases.status(account, id);
    }
    return commerce.purchases.status(account, id);
  };
  const app = Fastify({ logger: false, bodyLimit: 262_144, routerOptions: { maxParamLength: 128 },
    requestTimeout: 15_000, trustProxy: false, genReqId: () => randomUUID() });
  app.addHook('onRequest', (request, _reply, done) => {
    diagnostics.run(errorReference(request.id), done);
  });
  app.addHook('onResponse', async (request, reply) => {
    if (!failed.has(request)) diagnostics.record('request_completed', {
      operation: operation(request), reference: errorReference(request.id), status: reply.statusCode,
      durationMilliseconds: reply.elapsedTime,
    });
  });
  // No request bodies, Authorization headers, tokens, transcripts, or Stripe payloads are logged.
  app.removeContentTypeParser('application/json');
  app.addContentTypeParser('application/json', { parseAs: 'buffer' }, (request, body, done) => {
    if (['/v1/webhooks/stripe', '/v1/webhooks/stripe/minutes'].includes(request.routeOptions.url ?? '')) return done(null, body);
    try { done(null, JSON.parse(body.toString())); } catch { done(new ServiceError('invalid_json')); }
  });
  const windows = new Map<string, { until: number; count: number }>();
  const limitNetworkRequest = (request: FastifyRequest, reply: { header(name: string, value: string): unknown },
    limit: { max: number; timeWindow: number }) => {
    let networkKey = request.ip;
    const proxy = services.accounts?.admission.config ?? services.accessRequests?.config;
    if (proxy) {
      let network: string;
      try { network = trustedClientNetwork(request.headers, request.raw.socket.remoteAddress ?? request.ip, proxy.proxyToken); }
      catch { throw new ServiceError('trusted_proxy_required', 503); }
      networkKey = createHmac('sha256', Buffer.from(proxy.hmacKey, 'hex')).update(network).digest('hex');
    }
    const now = Date.now();
    if (windows.size > 10_000) for (const [key, value] of windows) if (value.until <= now) windows.delete(key);
    let slot = windows.get(networkKey);
    if (!slot || slot.until <= now) {
      if (windows.size >= 20_000) throw new ServiceError('rate_limit', 429);
      slot = { until: now + limit.timeWindow, count: 0 }; windows.set(networkKey, slot);
    }
    if (++slot.count > limit.max) {
      reply.header('Retry-After', String(Math.max(1, Math.ceil((slot.until - now) / 1000))));
      throw new ServiceError('rate_limit', 429);
    }
  };
  app.addHook('onRequest', async (request, reply) => {
    reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff');
    // Fastify decodes static route names. Security checks must use the matched route too.
    const path = request.routeOptions.url ?? request.url.split('?')[0]!;
    const routeLimit = request.routeOptions.config.rateLimit;
    // Explicit payment-read limits run before database admission, authentication or Apple certificate work.
    if (routeLimit) limitNetworkRequest(request, reply, routeLimit);
    if (path === '/v1/guest/minutes' && services.guestMinuteAttestor?.requiresTrustedAdmission) {
      if (!services.accounts) throw new ServiceError('guest_minutes_unavailable', 503);
      try { await services.accounts.admission.enter('guest', request.headers, request.raw.socket.remoteAddress ?? request.ip); }
      catch (error) {
        if (error instanceof ServiceError) { if (error.status === 429) reply.header('Retry-After', '3600'); throw error; }
        throw new ServiceError('guest_minutes_unavailable', 503);
      }
      return;
    }
    if (accountPaths.has(path)) {
      if (!services.accounts || (!hasGoogleSignIn(services.auth) && !(services.auth.appleClientID && services.appleRevoker))) throw new ServiceError('accounts_unavailable', 503);
      try {
        await services.accounts.admission.enter(path === '/v1/auth/challenge' ? 'challenge' : path === '/v1/auth/exchange' ? 'exchange' : 'account',
          request.headers, request.raw.socket.remoteAddress ?? request.ip);
      } catch (error) {
        if (error instanceof ServiceError) { if (error.status === 429) reply.header('Retry-After', '3600'); throw error; }
        throw new ServiceError('accounts_unavailable', 503);
      }
      return;
    }
    // This endpoint has separate durable admission limits; Caddy's shared address is not its visitor identity.
    if (path === ACCESS_REQUEST_PATH || path === AI_REPORT_PATH || path === '/healthz') return;
    if (!routeLimit) limitNetworkRequest(request, reply, paymentReadOptions.config.rateLimit);
  });
  app.setErrorHandler((error, request, reply) => {
    const purchaseReconciliation = error && typeof error === 'object' && 'code' in error && 'message' in error && error.code === 'P0001' &&
        error.message === 'minute_purchase_reconciliation_required';
    const candidate = error && typeof error === 'object' && 'statusCode' in error ? error.statusCode : null;
    const status = purchaseReconciliation ? 409 : error instanceof ServiceError ? error.status : typeof candidate === 'number' && candidate >= 400 && candidate < 500 ? candidate : 500;
    const code = purchaseReconciliation ? 'minute_purchase_reconciliation_required' : error instanceof ServiceError ? error.code : status < 500 ? 'invalid_request' : 'service_unavailable';
    const reference = errorReference(request.id);
    reply.header('X-Mural-Error-Reference', reference);
    failed.add(request);
    diagnostics.record('request_failed', { operation: operation(request), reference, status,
      durationMilliseconds: reply.elapsedTime }, error);
    const diagnostic = startupDiagnostic(request.method, request.routeOptions.url, request.id, status, code, error);
    if (diagnostic) {
      reply.header('X-Mural-Error-Reference', diagnostic.reference);
      try { void Promise.resolve(services.onStartupDiagnostic?.(diagnostic)).catch(() => {}); } catch { /* Diagnostics cannot change a request's outcome. */ }
    }
    if (error instanceof HelperSessionLimitError) {
      if (error.retryable) reply.header('Retry-After', String(Math.ceil(error.retryAfterMilliseconds! / 1000)));
      return reply.code(status).send({ error: { code, retryable: error.retryable,
        ...(error.retryable ? { retryAfterMilliseconds: error.retryAfterMilliseconds } : {}) } });
    }
    reply.code(status).send({ error: { code } });
  });
  app.setNotFoundHandler(() => { throw new ServiceError('not_found', 404); });
  const featureState=()=>{
    const hostedVoice=Boolean(services.hosted?.available && (!services.hosted.minuteFunded || services.hostedHelpers));
    const livePayments=['stripe','play','apple'].some(provider=>services.minuteCommerce?.aiPurchases?.products(provider as 'stripe'|'play'|'apple').some(product=>product.environment==='live'));
    return {hostedVoice,guestMinutes:Boolean(hostedVoice && services.hosted?.publicMinuteAccess && services.guestMinuteAttestor),livePayments};
  };
  app.get('/healthz', async () => ({ ok: true, stage: 'commercial-foundation', ...featureState() }));
  app.get('/readyz', async () => {
    await db.query('SELECT 1'); return { database: true, ...featureState() };
  });
  app.get('/v1/pricing', async () => ({ currency: 'USD', rateVersion: RATE_VERSION, moneyUnit: 'nanoUSD',
    nanoUSDPerDollar: '1000000000', creditNanoUSD: '10000000', serviceFeePercent: (await aiPricingPolicy(db)).serviceFeeBasisPoints / 100,
    voice: { model: 'gpt-live-1', perMinuteNanoUSD: '50000000', billingUnit: 'active-session-seconds' },
    text: { model: 'gpt-5.6-luna', inputPerTokenNanoUSD: '200', cachedInputPerTokenNanoUSD: '20', outputPerTokenNanoUSD: '1200' },
    searchPerCallNanoUSD: '10000000', paymentFees: 'quoted separately at checkout', hostedVoiceAvailable: featureState().hostedVoice,
    consumerUnit: 'prepaid-ai-value', consumerBillingBasis: 'actual-ai-usage', freeAllowanceUnit:'conversation-minutes',
    paidMinuteEstimatesOnly:true,minutePacks: [], minutePurchasesAvailable: false }));
  const webCommerce = () => {
    const commerce = services.minuteCommerce;
    if (!services.webPurchases || !commerce?.aiPurchases || commerce.stripe?.environment !== 'live')
      throw new ServiceError('web_purchases_unavailable',503);
    return { access:services.webPurchases,purchases:commerce.aiPurchases,stripe:commerce.stripe };
  };
  const webGuard = async (request: FastifyRequest, reply: import('fastify').FastifyReply) => {
    const { access } = webCommerce(), origin = access.allowedOrigin(request.headers.origin);
    reply.header('Access-Control-Allow-Origin',origin).header('Vary','Origin');
    if (request.method === 'OPTIONS') {
      const method = request.headers['access-control-request-method'], requested = request.headers['access-control-request-headers'];
      const path = request.routeOptions.url ?? '';
      const methods = path.endsWith('/session') ? ['GET','DELETE'] :
        path.endsWith('/challenges') || path.endsWith('/verify') || path.endsWith('/orders') ? ['POST'] : ['GET'];
      if (typeof method !== 'string' || !methods.includes(method) || (requested !== undefined &&
        (typeof requested !== 'string' || requested.toLowerCase().split(',').some(header =>
          !['content-type','authorization','idempotency-key'].includes(header.trim()))))) throw new ServiceError('invalid_preflight');
      return reply.header('Access-Control-Allow-Methods',methods.join(', '))
        .header('Access-Control-Allow-Headers','Content-Type, Authorization, Idempotency-Key')
        .header('Access-Control-Max-Age','600').code(204).send();
    }
    const path = request.routeOptions.url ?? '', address = access.clientAddress(request.headers,request.raw.socket.remoteAddress ?? request.ip);
    if (path.endsWith('/challenges') || path.endsWith('/verify'))
      await access.enter(path.endsWith('/challenges')?'challenge':'verify',address);
    else {
      if (!services.accounts) throw new ServiceError('web_purchases_unavailable',503);
      await services.accounts.admission.enter('account',request.headers,request.raw.socket.remoteAddress ?? request.ip);
    }
    if (request.method === 'POST' && request.headers['content-type']?.split(';')[0]?.trim().toLowerCase() !== 'application/json')
      throw new ServiceError('invalid_content_type',415);
  };
  const webOptions = { config:{rateLimit:{max:120,timeWindow:60_000}},bodyLimit:1024,onRequest:webGuard };
  for (const suffix of ['/challenges','/verify','/products','/orders','/orders/by-key/:key','/orders/:id','/session'])
    app.options(`${WEB_PURCHASE_PATH}${suffix}`,webOptions,async () => ({}));
  app.post(`${WEB_PURCHASE_PATH}/challenges`,webOptions,async (request,reply) =>
    reply.code(202).send(await webCommerce().access.challenge(request.body)));
  app.post(`${WEB_PURCHASE_PATH}/verify`,webOptions,async request => webCommerce().access.verify(request.body));
  app.get(`${WEB_PURCHASE_PATH}/session`,webOptions,async request => {
    const identity = await webCommerce().access.authenticate(request.headers.authorization); return { email:identity.email };
  });
  app.delete(`${WEB_PURCHASE_PATH}/session`,webOptions,async request => {
    await webCommerce().access.revoke(request.headers.authorization); return { signedOut:true };
  });
  app.get(`${WEB_PURCHASE_PATH}/products`,webOptions,async request => {
    const { access,purchases } = webCommerce(); await access.authenticate(request.headers.authorization);
    const products = purchases.products('stripe','live');
    return { available:products.length>0,maximumQuantity:purchases.maximumQuantity('stripe'),billingBasis:'actual-ai-usage',
      products:products.map(({merchant:_merchant,provider:_provider,...product}) => product) };
  });
  const ownedWebOrder = async (account: string,id: string) => {
    const row = (await db.query(`SELECT o.id,o.sku,o.quantity,o.currency,o.total_minor,q.ai_value_nano
      FROM minute_purchase_orders o JOIN ai_value_purchase_quotes q ON q.order_id=o.id WHERE o.id=$1 AND o.account_id=$2
      AND o.provider='stripe' AND o.environment='live' AND o.entitlement_kind='ai_value'`,[uuid(id),account])).rows[0];
    if (!row) throw new ServiceError('purchase_not_found',404);
    return { ...await webCommerce().purchases.status(account,id),order:{sku:row.sku,quantity:Number(row.quantity),
      currency:row.currency,totalMinor:Number(row.total_minor),aiValueNanoUSD:String(row.ai_value_nano)} };
  };
  app.post(`${WEB_PURCHASE_PATH}/orders`,webOptions,async request => {
    const { access,purchases,stripe } = webCommerce(), { accountID } = await access.authenticate(request.headers.authorization);
    const body = objectBody(request);
    if (Object.keys(body).some(key => !['sku','quantity'].includes(key))) throw new ServiceError('invalid_request');
    const key = request.headers['idempotency-key']; if (typeof key !== 'string') throw new ServiceError('invalid_ai_value_order');
    const order = await purchases.createOrder(accountID,'stripe',stringField(body,'sku',200),key,
      body.quantity === undefined ? 1 : body.quantity as number,undefined,undefined,'live');
    const payment = await stripe.checkout(accountID,order.orderID);
    const {merchant:_merchant,provider:_provider,...quoted} = order; return { ...quoted,payment };
  });
  app.get(`${WEB_PURCHASE_PATH}/orders/by-key/:key`,webOptions,async request => {
    const { accountID } = await webCommerce().access.authenticate(request.headers.authorization);
    const { orderID } = await stripeOrderByKey(db,accountID,(request.params as {key:string}).key);
    await ownedWebOrder(accountID,orderID); return { orderID };
  });
  app.get(`${WEB_PURCHASE_PATH}/orders/:id`,webOptions,async request => {
    const { accountID } = await webCommerce().access.authenticate(request.headers.authorization);
    return ownedWebOrder(accountID,(request.params as {id:string}).id);
  });
  app.route({ method: ['POST', 'OPTIONS'], url: ACCESS_REQUEST_PATH, bodyLimit: 1024,
    onRequest: async (request, reply) => {
      const access = services.accessRequests;
      if (!access) throw new ServiceError('access_requests_unavailable', 503);
      const origin = access.allowedOrigin(request.headers.origin);
      reply.header('Access-Control-Allow-Origin', origin).header('Vary', 'Origin');
      if (request.method === 'OPTIONS') {
        const requested = request.headers['access-control-request-headers'];
        if (request.headers['access-control-request-method'] !== 'POST' ||
            (requested !== undefined && (typeof requested !== 'string' || requested.toLowerCase().split(',').some(header => header.trim() !== 'content-type'))))
          throw new ServiceError('invalid_preflight');
        return reply.header('Access-Control-Allow-Methods', 'POST').header('Access-Control-Allow-Headers', 'Content-Type')
          .header('Access-Control-Max-Age', '600').code(204).send();
      }
      // Caddy overwrites these headers. A direct request cannot invent its network address.
      access.clientAddress(request.headers, request.raw.socket.remoteAddress ?? request.ip, origin);
      if (request.headers['content-type']?.split(';')[0]?.trim().toLowerCase() !== 'application/json') throw new ServiceError('invalid_content_type', 415);
    },
    handler: async (request, reply) => {
      const access = services.accessRequests!;
      try {
        const origin = access.allowedOrigin(request.headers.origin);
        await access.submit(request.body, access.clientAddress(request.headers, request.raw.socket.remoteAddress ?? request.ip, origin));
      } catch (error) {
        if (error instanceof ServiceError) {
          if (error.status === 429) reply.header('Retry-After', '3600');
          throw error;
        }
        throw new ServiceError('access_requests_unavailable', 503);
      }
      return reply.code(202).send({ accepted: true });
    }
  });
  app.get('/v1/auth/providers', async () => ({ google: Boolean(services.accounts && (services.auth.googleClientID || services.auth.googleIOSClientIDs?.length)),
    googleAndroid: Boolean(services.accounts && services.auth.googleAndroidServerClientID && services.auth.googleAndroidClientIDs?.length),
    apple: Boolean(services.accounts && services.auth.appleClientID && services.appleRevoker) }));
  app.get('/v1/feedback/capabilities', async () => ({ aiReports: Boolean(services.aiReports?.config) }));
  app.post(AI_REPORT_PATH, { bodyLimit: AI_REPORT_BODY_LIMIT }, async (request, reply) => {
    const reports = services.aiReports;
    if (!reports?.config) throw new ServiceError('ai_reports_unavailable', 503);
    const network = reportNetwork(request.headers, request.raw.socket.remoteAddress ?? request.ip, reports.config);
    try { return reply.code(202).send(await reports.submit(objectBody(request), network)); }
    catch (error) {
      if (error instanceof ServiceError && error.status === 429) reply.header('Retry-After', '3600');
      throw error;
    }
  });
  app.post('/v1/auth/challenge', { bodyLimit: 1024 }, async request => {
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    return createChallenge(db);
  });
  app.post('/v1/auth/exchange', { bodyLimit: 20_000 }, async request => {
    const body = objectBody(request), provider = stringField(body, 'provider', 10);
    if (Object.keys(body).some(key => !['provider', 'idToken', 'challengeID', 'expectedAccountID'].includes(key))) throw new ServiceError('invalid_request');
    if (provider !== 'google' && provider !== 'apple') throw new ServiceError('invalid_identity_provider');
    // Apple account creation cannot be enabled before account deletion can revoke Apple authorization.
    if (provider === 'apple' && !services.appleRevoker) throw new ServiceError('apple_sign_in_not_ready', 503);
    const expectedAccountID = body.expectedAccountID === undefined ? undefined : uuid(stringField(body, 'expectedAccountID', 36));
    return exchangeIdentity(db, provider, stringField(body, 'idToken', 16_384), uuid(stringField(body, 'challengeID', 36)), services.auth, services.accounts?.identityVerifier, expectedAccountID);
  });
  app.get('/v1/account', async request => accountProfile(db, request.headers.authorization));
  app.post('/v1/account/connect-google',{bodyLimit:34_000},async request=>{
    const body=objectBody(request);
    if(Object.keys(body).some(key=>!['confirmation','appleChallengeID','appleToken','googleChallengeID','googleToken'].includes(key)) ||
      body.confirmation!=='connect_google') throw new ServiceError('invalid_request');
    return connectGoogleIdentity(db,request.headers.authorization,{
      appleChallengeID:uuid(stringField(body,'appleChallengeID',36)).toLowerCase(),appleToken:stringField(body,'appleToken',16384),
      googleChallengeID:uuid(stringField(body,'googleChallengeID',36)).toLowerCase(),googleToken:stringField(body,'googleToken',16384)
    },services.auth,services.accounts?.identityVerifier);
  });
  app.get('/v1/minutes', paymentReadOptions, async request => conversationBalance(db, await authenticate(db, request.headers.authorization, true),
    services.hosted?.publicMinuteAccess===true, services.hosted?.publicPaidAccess ? { enabled: true,
      estimatedNanoUSDPerMinute: services.hosted.estimatedNanoUSDPerMinute, minimumSessionNanoUSD: services.hosted.minimumPaidSessionNanoUSD } : undefined,
    services.hosted?.realFundedAccess ? 'live' : await fundingScope(request),services.hosted?.realFundedAccess===true));
  app.post('/v1/guest/minutes', { bodyLimit: 1024 }, async request => {
    const proof = objectBody(request);
    try { return { available: true, ...await startGuestMinutes(db, proof, services.guestMinuteAttestor ?? new UnconfiguredGuestMinuteAttestor()) }; }
    catch (error) {
      if (error instanceof ServiceError) {
        if (['welcome_minutes_unavailable', 'welcome_funding_budget_reached', 'trial_attestation_unavailable'].includes(error.code))
          return { available: false, reason: 'temporarily_unavailable', remainingMilliseconds: 0 };
        if (['sign_in_to_continue', 'trial_already_claimed'].includes(error.code))
          return { available: false, reason: 'sign_in_required', remainingMilliseconds: 0 };
      }
      throw error;
    }
  });
  app.post('/v1/minutes/link-guest', { bodyLimit: 1024 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => !['guestAccessToken','deferPending','guestAccountID'].includes(key)) ||
      (body.deferPending!==undefined&&typeof body.deferPending!=='boolean')) throw new ServiceError('invalid_request');
    return linkGuestMinutes(db,account,body.guestAccessToken===undefined&&body.deferPending===true?undefined:stringField(body,'guestAccessToken',43),body.deferPending===true,body.guestAccountID===undefined?undefined:uuid(stringField(body,'guestAccountID',36)));
  });
  app.post('/v1/minutes/welcome', { bodyLimit: 20_000 }, async request => {
    const account = await authenticate(db, request.headers.authorization);
    try { return { available: true, ...await claimWelcomeMinutes(db, account, objectBody(request), services.minuteAttestor ?? new UnconfiguredMinuteAttestor()) }; }
    catch (error) {
      if (error instanceof ServiceError && ['welcome_minutes_unavailable', 'welcome_funding_budget_reached', 'trial_attestation_unavailable'].includes(error.code))
        return { available: false, reason: 'temporarily_unavailable', grantedMilliseconds: 0 };
      throw error;
    }
  });
  app.get('/v1/minutes/products', paymentReadOptions, async request => {
    const provider = (request.query as Record<string, unknown>).provider;
    if (provider !== 'stripe' && provider !== 'play' && provider !== 'apple') throw new ServiceError('invalid_purchase_provider');
    if (services.minuteCommerce?.aiPurchases) {
      const appleEnvironment=provider==='apple'?await fundingScope(request,true):undefined;
      let admissionReady=true;
      if(provider==='apple' && appleEnvironment && services.minuteCommerce.appleScopes?.historyAdmissionRequired)
        admissionReady=await services.minuteCommerce.appleScopes.admissionReady(appleEnvironment);
      let products = admissionReady?services.minuteCommerce.aiPurchases.products(provider,appleEnvironment):[];
      if(provider==='play') {
        const selected=(request.query as Record<string,unknown>).regionCode;
        if(selected!==undefined) {
          const regionCode=storeRegionCode(selected),enabled=products.length>0;
          products=products.filter(p=>p.quote.play?.regionCode===regionCode);
          return {available:products.length>0,maximumQuantity:1,billingBasis:'actual-ai-usage',regionCode,
            ...(enabled&&!products.length?{availabilityReason:'unsupported_country'}:{}),
            products:products.map(({merchant:_merchant,provider:_provider,...product})=>product)};
        }
        // Shipped clients request no country and have a 100-row/128KiB parser bound.
        products=products.filter(p=>p.quote.play?.regionCode===undefined);
      }
      if(provider==='apple') {
        const storefront=(request.query as Record<string,unknown>).storefront;
        if(typeof storefront!=='string' || !/^[A-Z]{3}$/.test(storefront)) {
          diagnostics.record('apple_catalog',{environment:appleEnvironment,storefront:'unsupported',admissionReady,offerCount:0});
          return {available:false,maximumQuantity:1,products:[]};
        }
        products=products.filter(p=>p.quote.apple?.storefront===storefront);
        diagnostics.record('apple_catalog',{environment:appleEnvironment,storefront:products.length?storefront:'unsupported',admissionReady,offerCount:products.length});
        return {available:products.length>0,maximumQuantity:services.minuteCommerce.aiPurchases.maximumQuantity(provider),
          products:products.map(p=>({sku:p.sku,providerProduct:p.providerProduct,currency:p.currency,totalMinor:p.totalMinor,
            currencyExponent:p.quote.apple!.currencyExponent,estimatedMilliseconds:p.estimatedMilliseconds,
            estimateRateVersion:p.quote.estimateRateVersion,scheduleVersion:p.quote.apple!.scheduleVersion,storefront,environment:p.environment}))};
      }
      return { available: products.length > 0, maximumQuantity: services.minuteCommerce.aiPurchases.maximumQuantity(provider), billingBasis: 'actual-ai-usage', products: products.map(({ merchant: _merchant, provider: _provider, ...product }) => product) };
    }
    const products = services.minuteCommerce?.purchases.products(provider) ?? [];
    return { available: products.length > 0, billingBasis: 'connected-conversation-time', products: products.map(product => ({
      sku: product.sku, providerProduct: product.providerProduct, minutes: product.minutes,
      currency: product.currency, totalMinor: product.totalMinor, environment: product.environment
    })) };
  });
  app.post('/v1/minutes/orders', { bodyLimit: 1024 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => !['provider','sku','quantity','storefront','scheduleVersion','regionCode'].includes(key))) throw new ServiceError('invalid_request');
    const provider = stringField(body, 'provider', 10), key = request.headers['idempotency-key'];
    if (provider !== 'stripe' && provider !== 'play' && provider !== 'apple') throw new ServiceError('invalid_purchase_provider');
    if (typeof key !== 'string') throw new ServiceError('idempotency_key_required');
    const commerce = services.minuteCommerce;
    if (!commerce || !commerce[provider]) throw new ServiceError('minute_purchases_unavailable', 503);
    const quantity=body.quantity===undefined?1:body.quantity;
    const appleEnvironment=provider==='apple'?await fundingScope(request,true):undefined;
    if(appleEnvironment && commerce.appleScopes?.historyAdmissionRequired)await commerce.appleScopes.requireAdmission(appleEnvironment);
    if (typeof quantity!=='number' || !Number.isInteger(quantity) || quantity<1 || quantity>10 || (provider==='play' && quantity!==1)) throw new ServiceError('invalid_purchase_quantity');
    if (!commerce.aiPurchases && quantity!==1) throw new ServiceError('invalid_purchase_quantity');
    const order = commerce.aiPurchases ? await commerce.aiPurchases.createOrder(account, provider, stringField(body, 'sku', 128), key, quantity,provider==='apple'?{storefront:stringField(body,'storefront',3),scheduleVersion:stringField(body,'scheduleVersion',200)}:undefined,
      provider==='play' && (body.regionCode!==undefined || body.scheduleVersion!==undefined)?
        {regionCode:storeRegionCode(body.regionCode),scheduleVersion:stringField(body,'scheduleVersion',200)}:undefined,appleEnvironment)
      : await commerce.purchases.createOrder(account, provider, stringField(body, 'sku', 128), key);
    const payment = provider === 'stripe' ? await commerce.stripe!.checkout(account, order.orderID)
      : provider==='apple'?await (commerce.appleScopes?.provider(appleEnvironment??commerce.apple!.environment)??commerce.apple!).prepare(account,order.orderID):await commerce.play!.prepare(account, order.orderID);
    if(provider==='apple' && 'entitlementKind' in order) return {orderID:order.orderID,quantity:order.quantity,totalMinor:order.totalMinor,
      currency:order.currency,estimatedMilliseconds:order.estimatedMilliseconds,payment};
    if ('entitlementKind' in order) {
      const { merchant: _merchant, provider: _provider, ...quoted } = order;
      return { ...quoted, payment };
    }
    return { orderID: order.orderID, minutes: order.minutes, currency: order.currency, totalMinor: order.totalMinor, payment };
  });
  app.get('/v1/minutes/orders/by-key/:key', async request => {
    const account = await authenticate(db, request.headers.authorization);
    const query = request.query as Record<string, unknown>;
    if (Object.keys(query).some(key => key !== 'provider')) throw new ServiceError('invalid_request');
    if (query.provider !== 'stripe') throw new ServiceError('invalid_purchase_provider');
    if (!services.minuteCommerce) throw new ServiceError('minute_purchases_unavailable', 503);
    return stripeOrderByKey(db, account, (request.params as { key: string }).key);
  });
  app.get('/v1/minutes/orders/:id', async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.minuteCommerce) throw new ServiceError('minute_purchases_unavailable', 503);
    return orderStatus(account, uuid((request.params as { id: string }).id));
  });
  app.post('/v1/minutes/orders/:id/play', { bodyLimit: 8192 }, async request => {
    const account = await authenticate(db, request.headers.authorization), body = objectBody(request);
    if (Object.keys(body).some(key => key !== 'purchaseToken')) throw new ServiceError('invalid_request');
    if (!services.minuteCommerce?.play) throw new ServiceError('minute_purchases_unavailable', 503);
    const orderID = uuid((request.params as { id: string }).id), purchaseToken = stringField(body, 'purchaseToken', 4096);
    if (!/^[\x21-\x7e]+$/.test(purchaseToken)) throw new ServiceError('invalid_request');
    await orderStatus(account, orderID);
    return (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('play', { kind: 'client', accountID: account,
      orderID, purchaseToken });
  });
  for(const route of ['/v1/minutes/orders/:id/apple','/v1/minutes/apple/recover']) app.post(route,{bodyLimit:34_000},async request=>{
    const account=await authenticate(db,request.headers.authorization),body=objectBody(request);
    if(Object.keys(body).length!==1 || Object.keys(body).some(key=>!['signedTransaction','transactionID'].includes(key))) throw new ServiceError('invalid_request');
    const commerce=services.minuteCommerce;
    if(!commerce?.apple || !commerce.fulfillment) throw new ServiceError('minute_purchases_unavailable',503);
    const orderID=route.includes(':id')?uuid((request.params as {id:string}).id):undefined;
    const evidence=body.transactionID!==undefined?{transactionID:stringField(body,'transactionID',64)}:
      {signedTransaction:stringField(body,'signedTransaction',32768)};
    if('transactionID' in evidence && !/^[0-9]{1,64}$/.test(evidence.transactionID!)) throw new ServiceError('invalid_request');
    return commerce.fulfillment.reconcile('apple',{kind:orderID?'client':'recovery',accountID:account,orderID,
      environment:await fundingScope(request,true),...evidence});
  });
  app.post('/v1/webhooks/apple',{bodyLimit:65_536},async request=>{
    const body=objectBody(request),commerce=services.minuteCommerce;
    const signed=stringField(body,'signedPayload',32768);
    const apple=commerce?.appleSandbox?commerce.appleScopes!.provider(appleSignedEnvironment(signed,'environment')):commerce?.apple;
    if(!apple) throw new ServiceError('minute_purchases_unavailable',503);
    if(Object.keys(body).some(key=>key!=='signedPayload')) throw new ServiceError('invalid_request');
    await apple.notify(signed);
    return {received:true};
  });
  app.post('/v1/webhooks/stripe/minutes', async request => {
    if (!services.minuteCommerce?.stripe) throw new ServiceError('minute_purchases_unavailable', 503);
    const signature = request.headers['stripe-signature'];
    if (!Buffer.isBuffer(request.body) || typeof signature !== 'string') throw new ServiceError('invalid_webhook_signature');
    await (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('stripe', { kind: 'webhook', raw: request.body, signature });
    return { received: true };
  });
  app.post('/v1/minutes/play/recover', { bodyLimit: 8192 }, async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.minuteCommerce?.play) throw new ServiceError('minute_purchases_unavailable', 503);
    const body = objectBody(request);
    if (Object.keys(body).length !== 1 || typeof body.purchaseToken !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(body.purchaseToken))
      throw new ServiceError('invalid_request');
    return (services.minuteCommerce.fulfillment ?? services.minuteCommerce.purchases).reconcile('play', { kind: 'recovery', accountID: account, purchaseToken: body.purchaseToken });
  });
  app.get('/v1/wallet', paymentReadOptions, async request => {
    const account=await authenticate(db,request.headers.authorization,true);
    const wallet=await paidAIBalance(db,account,services.hosted?.realFundedAccess ? 'live' : await fundingScope(request),false);
    return {currency:'USD',balanceNanoUSD:wallet.balanceNanoUSD,reservedNanoUSD:wallet.reservedNanoUSD,
      availableNanoUSD:wallet.availableNanoUSD};
  });
  app.post('/v1/auth/sign-out', { bodyLimit: 1024 }, async request => {
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    await signOut(db, request.headers.authorization);
    return { signedOut: true };
  });
  app.delete('/v1/guest/account', {
    bodyLimit: 1024, config: { rateLimit: { max: 120, timeWindow: 60_000 } },
  }, async request => {
    const account=await authenticate(db,request.headers.authorization,true);
    const owner=(await db.query('SELECT is_guest FROM accounts WHERE id=$1',[account])).rows[0];
    if(!owner?.is_guest) throw new ServiceError('invalid_request');
    const body=objectBody(request);
    if(Object.keys(body).some(key=>key!=='confirmCreditAccessLoss') || body.confirmCreditAccessLoss!==true)
      throw new ServiceError('invalid_request');
    const result=await deleteAccount(db,account,undefined,undefined,request.headers.authorization,new Date(),false,true);
    return {deleted:true,retained:result.retainedFinancialRecords?'Required records, linked to an opaque account ID.':null};
  });
  app.delete('/v1/account', { bodyLimit: 5120 }, async request => {
    const account = await authenticate(db, request.headers.authorization, true);
    const body = objectBody(request);
    if (Object.keys(body).some(key => !['appleAuthorizationCode','confirmCreditAccessLoss'].includes(key)) ||
        (body.confirmCreditAccessLoss !== undefined && typeof body.confirmCreditAccessLoss !== 'boolean')) throw new ServiceError('invalid_request');
    const code = body.appleAuthorizationCode === undefined ? undefined : stringField(body, 'appleAuthorizationCode', 4096);
    const result = await deleteAccount(db, account, services.appleRevoker, code, request.headers.authorization,
      new Date(), services.minuteCommerce?.playNotifications?.isOperational() === true, body.confirmCreditAccessLoss === true);
    return { deleted: true, retained: result.retainedFinancialRecords ? 'Required financial records, linked to an opaque account ID.' : null };
  });
  app.post('/v1/checkout', async request => {
    const account = await authenticate(db, request.headers.authorization);
    if (!services.payments) throw new ServiceError('checkout_not_configured', 503);
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string' || key.length < 8 || key.length > 128) throw new ServiceError('idempotency_key_required');
    return services.payments.checkout(db, account, stringField(objectBody(request), 'product', 64), key);
  });
  app.post('/v1/webhooks/stripe', async request => {
    if (!services.payments) throw new ServiceError('checkout_not_configured', 503);
    const signature = request.headers['stripe-signature'];
    if (!Buffer.isBuffer(request.body) || typeof signature !== 'string') throw new ServiceError('invalid_webhook_signature');
    const event = services.payments.verify(request.body, signature);
    await applyStripeEvent(db, event);
    return { received: true };
  });
  app.post('/v1/trial/eligibility', async request => trialEligibility(db, request.body, services.attestor ?? new UnconfiguredAttestor()));
  app.get('/v1/live/capabilities', async request => {
    if (!services.hosted?.minuteFunded || !services.hosted.available || !services.hostedHelpers || !request.headers.authorization) return { hostedMinutes: false };
    const account = await authenticate(db, request.headers.authorization, true);
    return { hostedMinutes: services.hosted.allows(account) && services.hostedHelpers.allows(account), experimental: true };
  });
  app.post('/v1/live/sessions', async request => {
    if (!services.hosted?.available) throw new ServiceError('hosted_voice_not_ready', 503);
    if (services.hosted.minuteFunded && !services.hostedHelpers) throw new ServiceError('hosted_helpers_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded), body = objectBody(request);
    if (Object.keys(body).some(key => !['sdp','language','instructions','history','requestedMilliseconds'].includes(key))) throw new ServiceError('invalid_request');
    if (body.requestedMilliseconds !== undefined && (typeof body.requestedMilliseconds !== 'number' ||
        !Number.isSafeInteger(body.requestedMilliseconds) || body.requestedMilliseconds < 60_000 || body.requestedMilliseconds > 3_600_000))
      throw new ServiceError('invalid_request');
    const key = request.headers['idempotency-key'];
    if (typeof key !== 'string') throw new ServiceError('idempotency_key_required');
    return services.hosted.create(account, key, stringField(body, 'sdp', 65_536), stringField(body, 'language', 10),
      { instructions: body.instructions, history: body.history }, body.requestedMilliseconds as number | undefined,
      services.hosted.realFundedAccess ? 'live' : await fundingScope(request));
  });
  app.get('/v1/live/sessions/:id', async request => {
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded);
    return services.hosted.status(account, uuid((request.params as { id: string }).id));
  });
  app.get('/v1/live/sessions/current', async request => {
    if (!services.hosted?.minuteFunded) throw new ServiceError('hosted_voice_not_ready', 503);
    return services.hosted.current(await authenticate(db, request.headers.authorization, true));
  });
  app.post('/v1/live/sessions/:id/close', async request => {
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, services.hosted.minuteFunded);
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    return services.hosted.close(account, uuid((request.params as { id: string }).id));
  });
  app.post('/v1/live/requests/:key/close', async request => {
    if (!services.hosted) throw new ServiceError('hosted_voice_not_ready',503);
    const account=await authenticate(db,request.headers.authorization,services.hosted.minuteFunded);
    if (Object.keys(objectBody(request)).length) throw new ServiceError('invalid_request');
    return services.hosted.closeByKey(account,uuid((request.params as {key:string}).key));
  });
  app.post('/v1/live/sessions/:id/helpers', { bodyLimit: HOSTED_HELPER_BODY_LIMIT }, async (request, reply) => {
    if (!services.hosted?.minuteFunded || !services.hostedHelpers) throw new ServiceError('hosted_helpers_not_ready', 503);
    const account = await authenticate(db, request.headers.authorization, true);
    const sessionID = uuid((request.params as { id: string }).id);
    // Streaming is explicit opt-in; wildcard clients keep the existing JSON contract.
    const wantsStream = request.headers.accept?.split(',').some(value => {
      const [type, ...parameters] = value.split(';').map(part => part.trim());
      if (type?.toLowerCase() !== 'text/event-stream') return false;
      const weights = parameters.filter(part => /^q\s*=/i.test(part));
      if (!weights.length) return true;
      const quality = weights[0]!.slice(weights[0]!.indexOf('=') + 1).trim();
      return weights.length === 1 && /^(?:0(?:\.\d{0,3})?|1(?:\.0{0,3})?)$/.test(quality) && Number(quality) > 0;
    });
    if (!wantsStream) return services.hostedHelpers.request(account, sessionID, request.body);
    let started = false, previous = '';
    const emit = (event: object) => {
      if (reply.raw.destroyed || reply.raw.writableEnded) return;
      if (!started) {
        started = true; reply.hijack();
        reply.raw.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff', 'X-Accel-Buffering': 'no' });
      }
      reply.raw.write(`data: ${JSON.stringify(event)}\n\n`);
    };
    try {
      // Admission errors remain normal HTTP errors. Once admitted, client disconnects must
      // not restart the funded request or prevent final usage settlement.
      const result = await services.hostedHelpers.request(account, sessionID, request.body, text => {
        const delta = text.slice(previous.length); previous = text;
        emit({ type: 'mural.meaning.delta', delta });
      });
      emit({ type: 'mural.meaning.completed', result });
      if (!reply.raw.destroyed) reply.raw.end();
      return reply;
    } catch (error) {
      if (!started) throw error;
      const reference = errorReference(request.id);
      failed.add(request);
      diagnostics.record('request_failed', { operation: operation(request), reference,
        status: error instanceof ServiceError ? error.status : 502, durationMilliseconds: reply.elapsedTime }, error);
      emit({ type: 'mural.meaning.error', code: error instanceof ServiceError ? error.code : 'helper_response_uncertain', reference });
      if (!reply.raw.destroyed) reply.raw.end();
      return reply;
    }
  });
  app.get('/payment-return', async (_request, reply) => reply.type('text/html').send('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Mural sandbox</title><body><h1>Return to Mural</h1><p>This is a sandbox payment test. The app checks payment confirmation independently.</p></body></html>'));
  return app;
}
