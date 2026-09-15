import { finalizeDeferredGuestLinks } from './guest-minutes.js';
import { createApp } from './app.js';
import { connectDatabase } from './db.js';
import { catalogFromEnvironment, SandboxPayments } from './payments.js';
import { hasGoogleSignIn, pruneAuthenticationRecords } from './auth.js';
import { readFile } from 'node:fs/promises';
import { AppleTokenRevoker } from './apple-revocation.js';
import { HostedVoice } from './hosted-voice.js';
import { OpenAILiveProvider } from './live-provider.js';
import { AccessRequests, accessRequestConfig, pruneAccessRequests } from './access-requests.js';
import { AuthAdmission, accountAdmissionConfig } from './auth-admission.js';
import { AIReports, aiReportConfig, pruneAIReports } from './feedback.js';
import { configuredMinuteCommerce } from './minute-commerce-config.js';
import { HostedHelpers } from './hosted-helpers.js';
import { OpenAIHostedResponses } from './hosted-responses-transport.js';
import { InstallationGuestMinuteAttestor } from './guest-minutes.js';

const databaseURL = process.env.DATABASE_URL;
if (!databaseURL) { console.error('DATABASE_URL is required.'); process.exit(1); }
const db = connectDatabase(databaseURL);
let hosted: HostedVoice | undefined;
let hostedHelpers: HostedHelpers | undefined;
let minuteCommerce: Awaited<ReturnType<typeof configuredMinuteCommerce>>;
try {
  const origin = new URL(process.env.PUBLIC_ORIGIN ?? 'http://localhost:8080');
  if (origin.username || origin.password || (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && origin.hostname === 'localhost')))
    throw new Error();
  const key = process.env.STRIPE_TEST_SECRET_KEY, secret = process.env.STRIPE_TEST_WEBHOOK_SECRET;
  const payments = key && secret ? new SandboxPayments(key, secret, catalogFromEnvironment(process.env), origin.origin) : undefined;
  const appleClient = process.env.APPLE_CLIENT_ID, appleTeam = process.env.APPLE_TEAM_ID,
    appleKey = process.env.APPLE_KEY_ID, appleFile = process.env.APPLE_PRIVATE_KEY_PATH;
  const appleRevoker = appleClient && appleTeam && appleKey && appleFile ? new AppleTokenRevoker(db,
    { clientID: appleClient, teamID: appleTeam, keyID: appleKey, privateKeyPEM: await readFile(appleFile, 'utf8') }) : undefined;
  await appleRevoker?.validateConfiguration();
  minuteCommerce = await configuredMinuteCommerce(db, process.env, { onFailure: code => console.error(code) });
  const accessMode = process.env.HOSTED_VOICE_ACCESS ?? 'restricted-test';
  if (!['restricted-test','public-minutes'].includes(accessMode)) throw new Error('Invalid hosted access mode.');
  const publicMinuteAccess = accessMode==='public-minutes';
  if (process.env.HOSTED_PAID_VALUE_ENABLED && !['true','false'].includes(process.env.HOSTED_PAID_VALUE_ENABLED))
    throw new Error('Invalid paid AI value gate.');
  const publicPaidAccess = process.env.HOSTED_PAID_VALUE_ENABLED === 'true';
  if (publicPaidAccess && (!publicMinuteAccess || process.env.HOSTED_HELPERS_EXPERIMENTAL !== 'true' ||
      process.env.HOSTED_VOICE_EXPERIMENTAL !== 'true')) throw new Error('Paid AI value requires public hosted voice and teaching.');
  if (minuteCommerce?.salesEnabled && !publicPaidAccess) throw new Error('AI value sales require a configured paid conversation service.');
  if (process.env.GUEST_MINUTES_ENABLED && !['true','false'].includes(process.env.GUEST_MINUTES_ENABLED))
    throw new Error('Invalid guest minute gate.');
  if (process.env.HOSTED_HELPERS_EXPERIMENTAL && !['true', 'false'].includes(process.env.HOSTED_HELPERS_EXPERIMENTAL))
    throw new Error('Invalid helper gate.');
  if (process.env.HOSTED_HELPERS_EXPERIMENTAL === 'true' &&
    (process.env.HOSTED_VOICE_EXPERIMENTAL !== 'true' || process.env.HOSTED_VOICE_BILLING_UNIT !== 'milliseconds'))
    throw new Error('Hosted helpers require minute-funded voice.');
  if (process.env.HOSTED_VOICE_EXPERIMENTAL === 'true') {
    const billingUnit = process.env.HOSTED_VOICE_BILLING_UNIT ?? 'nanoUSD';
    if (billingUnit !== 'nanoUSD' && billingUnit !== 'milliseconds') throw new Error('Invalid hosted billing unit.');
    const accounts = new Set((process.env.HOSTED_VOICE_ACCOUNT_ALLOWLIST ?? '').split(',').filter(Boolean));
    if ([...accounts].some(account => !/^[a-f0-9-]{36}$/.test(account))) throw new Error();
    const lifetimeFundingCapNano = BigInt(process.env.HOSTED_VOICE_LIFETIME_CAP_NANO ?? '0');
    if (process.env.HOSTED_HELPERS_EXPERIMENTAL === 'true') {
      hostedHelpers = new HostedHelpers(db, new OpenAIHostedResponses(process.env.OPENAI_API_KEY ?? ''), {
        accountAllowlist: accounts, aggregateFundingCapNano: lifetimeFundingCapNano,publicMinuteAccess,publicPaidAccess,
        helperBudgetNanoPerMinute: BigInt(process.env.HOSTED_HELPER_BUDGET_PER_MINUTE_NANO ?? '50000000'),
        maxRequestsPerMinute: Number(process.env.HOSTED_HELPER_REQUESTS_PER_MINUTE ?? '24'),
        maxSearchesPerSession: Number(process.env.HOSTED_HELPER_SEARCHES_PER_SESSION ?? '0'),
        maxConcurrentPerSession: 2, maxConcurrentGlobal: 10, postSessionMilliseconds: 120_000,
        inputFramingTokenAllowance: 4096, searchInputTokenAllowance: 1_050_000, timeoutMilliseconds: 30_000,
      });
      await hostedHelpers.expireBudgets();
    }
    hosted = new HostedVoice(db, new OpenAILiveProvider(process.env.OPENAI_API_KEY ?? ''),
      { accountAllowlist: accounts, billingUnit, lifetimeFundingCapNano,publicMinuteAccess,publicPaidAccess, helpers: hostedHelpers,
        onStartupFailure: diagnostic => console.warn(JSON.stringify({ event: 'live_startup_failed', ...diagnostic })) });
    await hosted.start();
  }
  const accessConfig = accessRequestConfig(process.env);
  const accessRequests = accessConfig ? new AccessRequests(db, accessConfig) : undefined;
  const accountsConfig = accountAdmissionConfig(process.env);
  const accounts = accountsConfig ? { admission: new AuthAdmission(db, accountsConfig) } : undefined;
  if (process.env.GUEST_MINUTES_ENABLED==='true' && (!accounts || !hosted?.publicMinuteAccess || !hostedHelpers))
    throw new Error('Guest minutes require public minute voice, teaching and trusted admission.');
  const guestMinuteAttestor = process.env.GUEST_MINUTES_ENABLED==='true' ? new InstallationGuestMinuteAttestor() : undefined;
  const reportConfig = aiReportConfig(process.env);
  const aiReports = reportConfig ? new AIReports(db, reportConfig) : undefined;
  await pruneAccessRequests(db);
  await pruneAuthenticationRecords(db);
  await pruneAIReports(db);
  const googleAndroidClientIDs = (process.env.GOOGLE_ANDROID_CLIENT_IDS ?? '').split(',').map(id => id.trim()).filter(Boolean);
  const googleAndroidServerClientID = process.env.GOOGLE_ANDROID_SERVER_CLIENT_ID;
  if (Boolean(googleAndroidServerClientID) !== Boolean(googleAndroidClientIDs.length) || googleAndroidClientIDs.length > 10 ||
    [googleAndroidServerClientID, ...googleAndroidClientIDs].filter(Boolean).some(id => !/^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$/.test(id!)))
    throw new Error('Android Google identity configuration is incomplete.');
  if (accounts && !hasGoogleSignIn({ googleClientID: process.env.GOOGLE_CLIENT_ID, googleAndroidServerClientID, googleAndroidClientIDs }) &&
    !(appleClient && appleRevoker)) throw new Error('No account identity provider configured.');
  const app = createApp({ db, auth: { googleClientID: process.env.GOOGLE_CLIENT_ID, appleClientID: appleClient,
    googleAndroidServerClientID, googleAndroidClientIDs }, payments, appleRevoker, hosted, hostedHelpers,
    minuteCommerce, accessRequests, accounts, aiReports,guestMinuteAttestor,
    onStartupDiagnostic: diagnostic => console.warn(JSON.stringify({ event: 'conversation_request_failed', ...diagnostic })) });
  const cleanup = setInterval(() => {
    void pruneAuthenticationRecords(db).catch(() => { console.error('Account retention cleanup failed.'); });
    void pruneAccessRequests(db).catch(() => { console.error('Access request retention cleanup failed.'); });
    void pruneAIReports(db).catch(() => { console.error('AI report retention cleanup failed.'); });
    void hostedHelpers?.expireBudgets().catch(() => { console.error('Hosted helper budget cleanup failed.'); });
  }, 15 * 60_000);
  cleanup.unref();
  let guestLinkFlight:Promise<unknown>|undefined;
  const retryGuestLinks=()=>{if(!guestLinkFlight)guestLinkFlight=finalizeDeferredGuestLinks(db)
    .catch(()=>{console.error('Guest allowance transfer retry failed.');}).finally(()=>{guestLinkFlight=undefined;});};
  const guestLinkCleanup=setInterval(retryGuestLinks,60_000);guestLinkCleanup.unref();retryGuestLinks();
  const close = async () => {
    clearInterval(cleanup);clearInterval(guestLinkCleanup);await guestLinkFlight; await app.close(); await hosted?.stop(); await minuteCommerce?.runner.stop();
    await db.end(); process.exit(0);
  };
  process.on('SIGTERM', close); process.on('SIGINT', close);
  await app.listen({ port: Number(process.env.PORT ?? 8080), host: '0.0.0.0' });
  minuteCommerce?.runner.start();
  console.info('Mural API is running.');
} catch {
  console.error('Mural could not start. Check configuration; no secret values are logged.');
  await hosted?.stop(); await minuteCommerce?.runner.stop(); await db.end(); process.exitCode = 1;
}
