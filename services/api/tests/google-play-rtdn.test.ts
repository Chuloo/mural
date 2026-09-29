import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePlayNotification, PlayNotificationTransportError, PlayRtdnSubscriber } from '../src/google-play-rtdn.js';
import { PlayMinuteProvider } from '../src/play-minute-provider.js';

const packageName = 'chat.mural.android';
const topic = 'projects/mural-prod/topics/mural-play-purchases';
const subscription = 'projects/mural-prod/subscriptions/mural-play-api';
const message = (body: unknown) => Buffer.from(JSON.stringify(body)).toString('base64');
const purchase = { version: '1.0', packageName,
  oneTimeProductNotification: { version: '1.0', notificationType: 1, purchaseToken: 'token-1', sku: 'mural_small' } };
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200,
  headers: { 'content-type': 'application/json' } });

test('Play notification parser accepts only package-scoped, bounded one-time events', () => {
  assert.deepEqual(parsePlayNotification(message(purchase), packageName),
    { kind: 'purchase', purchaseToken: 'token-1', sku: 'mural_small' });
  assert.deepEqual(parsePlayNotification(message({ ...purchase, oneTimeProductNotification: undefined,
    voidedPurchaseNotification: { productType: 2, purchaseToken: 'token-1' } }), packageName),
    { kind: 'purchase', purchaseToken: 'token-1' });
  assert.deepEqual(parsePlayNotification(message({ version: '1.0', packageName, testNotification: { version: '1.0' } }), packageName),
    { kind: 'ignore' });
  assert.throws(() => parsePlayNotification(message({ ...purchase, packageName: 'attacker.app' }), packageName),
    { code: 'play_notification_invalid' });
  assert.throws(() => parsePlayNotification(message({ ...purchase, oneTimeProductNotification: {
    ...purchase.oneTimeProductNotification, notificationType: 7 } }), packageName), { code: 'play_notification_invalid' });
  assert.throws(() => parsePlayNotification('!!!!', packageName), { code: 'play_notification_invalid' });
  assert.throws(() => parsePlayNotification(message({ ...purchase, oneTimeProductNotification: {
    version: '1.0', notificationType: 1, sku: 'mural_small' } }), packageName), { code: 'play_notification_invalid' });
  assert.throws(() => parsePlayNotification(message({ version: '1.0', packageName,
    voidedPurchaseNotification: null }), packageName), { code: 'play_notification_invalid' });
  assert.throws(() => parsePlayNotification(message({ version: '1.0', packageName,
    voidedPurchaseNotification: { productType: 2 } }), packageName), { code: 'play_notification_invalid' });
});

test('subscriber verifies topic and only acknowledges a purchase after reconciliation', async () => {
  const operations: string[] = [];
  const request = (async (url: string, options: RequestInit) => {
    const operation = url.split(':').at(-1)!;
    operations.push(operation);
    assert.equal(options.headers && (options.headers as Record<string, string>).authorization, 'Bearer test-access-token-1234567890');
    if (url.endsWith(subscription)) return json({ name: subscription, topic, pushConfig: {} });
    if (operation === 'pull') return json({ receivedMessages: [{ ackId: 'ack-1', message: { data: message(purchase) } }] });
    if (operation === 'modifyAckDeadline') {
      assert.deepEqual(JSON.parse(String(options.body)), { ackIds: ['ack-1'], ackDeadlineSeconds: 120 }); return json({});
    }
    if (operation === 'acknowledge') return json({});
    throw new Error('Unexpected request');
  }) as typeof fetch;
  const subscriber = new PlayRtdnSubscriber({ topic, subscription, packageName, projectID: 'mural-prod' },
    { accessToken: async () => 'test-access-token-1234567890' },
    { reconcile: async (provider, input) => {
      operations.push('reconcile'); assert.equal(provider, 'play');
      assert.deepEqual(input, { kind: 'notification', purchaseToken: 'token-1', sku: 'mural_small' });
    } }, request);
  assert.equal(subscriber.isOperational(), false);
  assert.deepEqual(await subscriber.poll(), { received: 1, handled: 1 });
  assert.equal(subscriber.isOperational(), true);
  assert.deepEqual(operations, [`//pubsub.googleapis.com/v1/${subscription}`, 'pull', 'modifyAckDeadline', 'reconcile', 'acknowledge']);
});

test('subscriber leaves failed purchases unacknowledged and closes deletion gate', async () => {
  let reject = false, acknowledgements = 0;
  const request = (async (url: string) => {
    if (url.endsWith(subscription)) return json({ name: subscription, topic, pushConfig: {} });
    if (url.endsWith(':pull')) return json({ receivedMessages: [{ ackId: 'ack-1', message: { data: message(purchase) } }] });
    if (url.endsWith(':acknowledge')) acknowledgements++;
    return json({});
  }) as typeof fetch;
  const subscriber = new PlayRtdnSubscriber({ topic, subscription, packageName, projectID: 'mural-prod' },
    { accessToken: async () => 'test-access-token-1234567890' },
    { reconcile: async () => { if (reject) throw new Error('provider unavailable'); } }, request);
  await subscriber.poll(); assert.equal(subscriber.isOperational(), true);
  reject = true;
  await assert.rejects(subscriber.poll(), /provider unavailable/);
  assert.equal(subscriber.isOperational(), false);
  assert.equal(acknowledgements, 1);
});

test('subscriber handles later purchases after an unverified message without opening deletion gate', async () => {
  const acknowledgements: string[] = [], reconciled: string[] = [];
  const second = { ...purchase, oneTimeProductNotification: {
    ...purchase.oneTimeProductNotification, purchaseToken: 'token-2' } };
  const request = (async (url: string, options: RequestInit) => {
    if (url.endsWith(subscription)) return json({ name: subscription, topic, pushConfig: {} });
    if (url.endsWith(':pull')) return json({ receivedMessages: [
      { ackId: 'ack-failed', message: { data: message(purchase) } },
      { ackId: 'ack-success', message: { data: message(second) } },
    ] });
    if (url.endsWith(':acknowledge')) acknowledgements.push(...JSON.parse(String(options.body)).ackIds);
    return json({});
  }) as typeof fetch;
  const subscriber = new PlayRtdnSubscriber({ topic, subscription, packageName, projectID: 'mural-prod' },
    { accessToken: async () => 'test-access-token-1234567890' },
    { reconcile: async (_, input) => {
      reconciled.push(input.purchaseToken);
      if (input.purchaseToken === 'token-1') throw new Error('purchase cannot be verified');
    } }, request);
  await assert.rejects(subscriber.poll(), /purchase cannot be verified/);
  assert.deepEqual(reconciled, ['token-1', 'token-2']);
  assert.deepEqual(acknowledgements, ['ack-success']);
  assert.equal(subscriber.isOperational(), false);
});

test('subscriber refuses a mismatched subscription topic before pulling', async () => {
  let calls = 0;
  const subscriber = new PlayRtdnSubscriber({ topic, subscription, packageName, projectID: 'mural-prod' },
    { accessToken: async () => 'test-access-token-1234567890' }, { reconcile: async () => {} },
    (async () => { calls++; return json({ name: subscription, topic: 'projects/mural-prod/topics/other' }); }) as typeof fetch);
  await assert.rejects(subscriber.poll(), { code: 'play_notification_invalid' });
  assert.equal(calls, 1); assert.equal(subscriber.isOperational(), false);
});

test('subscriber classifies provider HTTP status and timeouts without response details', async () => {
  const configuration = { topic, subscription, packageName, projectID: 'mural-prod' };
  const tokens = { accessToken: async () => 'test-access-token-1234567890' };
  const fulfillment = { reconcile: async () => {} };
  const forbidden = new PlayRtdnSubscriber(configuration, tokens, fulfillment,
    (async () => new Response('{"error":"private"}', { status: 403 })) as typeof fetch);
  await assert.rejects(forbidden.poll(), error => error instanceof PlayNotificationTransportError &&
    error.code === 'play_notification_unavailable' && error.providerStatus === 403 &&
    !JSON.stringify(error).includes('private'));
  assert.equal(forbidden.isOperational(), false);
  const timedOut = new PlayRtdnSubscriber(configuration, tokens, fulfillment,
    (async (url: string) => {
      if (url.endsWith(subscription)) return json({ name: subscription, topic, pushConfig: {} });
      throw new DOMException('private network detail', 'TimeoutError');
    }) as typeof fetch);
  await assert.rejects(timedOut.poll(), { code: 'play_notification_timeout' });
  assert.equal(timedOut.isOperational(), false);
});

test('verified foreign-environment tokens are acknowledged without a ledger grant', async () => {
  let grants = 0, acknowledged = 0, checked = 0;
  const subscriber = new PlayRtdnSubscriber({ topic, subscription, packageName, projectID: 'mural-prod' },
    { accessToken: async () => 'test-access-token-1234567890' },
    { reconcile: async () => { grants++; } },
    (async (url: string) => {
      if (url.endsWith(subscription)) return json({ name: subscription, topic, pushConfig: {} });
      if (url.endsWith(':pull')) return json({ receivedMessages: [{ ackId: 'ack-foreign', message: { data: message(purchase) } }] });
      if (url.endsWith(':acknowledge')) acknowledged++;
      return json({});
    }) as typeof fetch,
    async token => { checked++; assert.equal(token, 'token-1'); return true; });
  await subscriber.poll();
  assert.equal(checked, 1); assert.equal(grants, 0); assert.equal(acknowledged, 1);
});

test('Play environment preflight distinguishes license tests from paid purchases', async () => {
  const purchased = { purchaseStateContext: { purchaseState: 'PURCHASED' }, productLineItem: [{ productId: 'mural_small' }] };
  let current: any = { ...purchased, testPurchaseContext: { fopType: 'TEST' } };
  const transport = { purchase: async () => current } as any;
  const configuration = { packageName, bindingKey: Buffer.alloc(32, 8), currencyExponents: { usd: 2 }, allowLive: true };
  const live = new PlayMinuteProvider({} as any, {} as any, { ...configuration, environment: 'live' }, transport);
  const sandbox = new PlayMinuteProvider({} as any, {} as any, { ...configuration, environment: 'test' }, transport);
  assert.equal(await live.isForeignEnvironmentPurchase('token-1'), true);
  assert.equal(await sandbox.isForeignEnvironmentPurchase('token-1'), false);
  current = purchased;
  assert.equal(await live.isForeignEnvironmentPurchase('token-1'), false);
  assert.equal(await sandbox.isForeignEnvironmentPurchase('token-1'), true);
  current = {};
  await assert.rejects(live.isForeignEnvironmentPurchase('token-1'), { code: 'play_purchase_state_invalid' });
});
