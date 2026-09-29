import { ServiceError } from './errors.js';
import type { GoogleAccessTokenSource } from './google-play-transport.js';

const namePattern = /^projects\/([a-z][a-z0-9-]{4,61}[a-z0-9])\/(topics|subscriptions)\/([A-Za-z][A-Za-z0-9._~-]{2,254})$/;
const tokenPattern = /^[\x21-\x7e]{1,4096}$/;
const ackPattern = /^[\x21-\x7e]{1,4096}$/;
const base64Pattern = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const invalid = () => new ServiceError('play_notification_invalid', 502);

async function boundedJSON(response: Response, limit: number): Promise<any> {
  if (!response.ok || !response.body) { await response.body?.cancel(); throw new ServiceError('play_notification_unavailable', 503); }
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const part = await reader.read(); if (part.done) break;
      size += part.value.length; if (size > limit) throw invalid();
      chunks.push(part.value);
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } catch { throw invalid(); }
  finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export type PlayNotification = { kind: 'purchase'; purchaseToken: string; sku?: string } | { kind: 'ignore' };
/** Pub/Sub is authenticated by the subscriber credential; every purchase is rechecked with Play. */
export function parsePlayNotification(data: unknown, packageName: string): PlayNotification {
  if (typeof data !== 'string' || data.length < 4 || data.length > 16_384 || !base64Pattern.test(data)) throw invalid();
  const bytes = Buffer.from(data, 'base64');
  if (bytes.toString('base64') !== data || bytes.length > 12_288) throw invalid();
  let notification: any;
  try { notification = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw invalid(); }
  if (!notification || typeof notification !== 'object' || Array.isArray(notification) ||
    notification.packageName !== packageName || notification.version !== '1.0') throw invalid();
  const one = notification.oneTimeProductNotification;
  if (one !== undefined) {
    if (!one || typeof one !== 'object' || Array.isArray(one) || one.version !== '1.0' ||
      ![1, 2].includes(one.notificationType) || typeof one.purchaseToken !== 'string' || !tokenPattern.test(one.purchaseToken) ||
      typeof one.sku !== 'string' || !/^[A-Za-z0-9_.-]{1,200}$/.test(one.sku)) throw invalid();
    return { kind: 'purchase', purchaseToken: one.purchaseToken, sku: one.sku };
  }
  const voided = notification.voidedPurchaseNotification;
  if (voided !== undefined && (!voided || typeof voided !== 'object' || Array.isArray(voided))) throw invalid();
  if (voided !== undefined && voided.productType === 2) {
    if (typeof voided.purchaseToken !== 'string' || !tokenPattern.test(voided.purchaseToken)) throw invalid();
    return { kind: 'purchase', purchaseToken: voided.purchaseToken };
  }
  if (notification.testNotification || notification.subscriptionNotification ||
    notification.pendingRefundReviewNotification || voided) return { kind: 'ignore' };
  throw invalid();
}

export interface PlayRtdnConfig { topic: string; subscription: string; packageName: string; projectID: string }
export interface PlayNotificationFulfillment {
  reconcile(provider: 'play', input: { kind: 'notification'; purchaseToken: string; sku?: string }): Promise<unknown>;
}
export class PlayRtdnSubscriber {
  readonly #base: string;
  #operationalUntil = 0;
  constructor(readonly config: PlayRtdnConfig, readonly tokens: GoogleAccessTokenSource,
    readonly fulfillment: PlayNotificationFulfillment, readonly request: typeof fetch = fetch,
    readonly isForeignEnvironmentPurchase?: (purchaseToken: string) => Promise<boolean>) {
    const topic = namePattern.exec(config.topic), sub = namePattern.exec(config.subscription);
    if (!topic || !sub || topic[2] !== 'topics' || sub[2] !== 'subscriptions' ||
      topic[1] !== config.projectID || sub[1] !== config.projectID || config.packageName !== 'chat.mural.android')
      throw new ServiceError('play_notification_configuration_invalid', 503);
    this.#base = `https://pubsub.googleapis.com/v1/${config.subscription}`;
  }
  isOperational(): boolean { return Date.now() < this.#operationalUntil; }
  async #call(url: string, body?: object): Promise<any> {
    const token = await this.tokens.accessToken();
    if (typeof token !== 'string' || !tokenPattern.test(token)) throw invalid();
    const response = await this.request(url, { method: body ? 'POST' : 'GET', redirect: 'error',
      headers: { authorization: `Bearer ${token}`, accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(20_000) });
    return boundedJSON(response, 131_072);
  }
  async poll(): Promise<{ received: number; handled: number }> {
    try {
      const subscription = await this.#call(this.#base);
      if (subscription?.name !== this.config.subscription || subscription.topic !== this.config.topic ||
        subscription.pushConfig?.pushEndpoint || subscription.detached === true) throw invalid();
      const result = await this.#call(`${this.#base}:pull`, { maxMessages: 5 });
      const messages = result?.receivedMessages ?? [];
      if (!Array.isArray(messages) || messages.length > 5) throw invalid();
      const ackIds = messages.map(message => message?.ackId);
      if (ackIds.some(ack => typeof ack !== 'string' || !ackPattern.test(ack))) throw invalid();
      if (ackIds.length) await this.#call(`${this.#base}:modifyAckDeadline`, { ackIds, ackDeadlineSeconds: 120 });
      let handled = 0, failed = false;
      let firstFailure: unknown;
      for (const message of messages) {
        try {
          if (!message || typeof message !== 'object' || typeof message.ackId !== 'string' || !ackPattern.test(message.ackId) ||
            !message.message || typeof message.message !== 'object') throw invalid();
          const notification = parsePlayNotification(message.message.data, this.config.packageName);
          if (notification.kind === 'purchase' && !(await this.isForeignEnvironmentPurchase?.(notification.purchaseToken)))
            await this.fulfillment.reconcile('play', { kind: 'notification', purchaseToken: notification.purchaseToken,
              ...(notification.sku ? { sku: notification.sku } : {}) });
          // The verifier saves the encrypted receipt and commits fulfillment before this message is acknowledged.
          await this.#call(`${this.#base}:acknowledge`, { ackIds: [message.ackId] });
          handled++;
        } catch (error) {
          // One unverified purchase must not starve later messages or open the account-deletion gate.
          if (!failed) firstFailure = error;
          failed = true;
        }
      }
      if (failed) throw firstFailure;
      this.#operationalUntil = Date.now() + 180_000;
      return { received: messages.length, handled };
    } catch (error) { this.#operationalUntil = 0; throw error; }
  }
}
