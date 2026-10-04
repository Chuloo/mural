import { ServiceError } from './errors.js';
import type { WebPurchaseConfig } from './web-purchase-config.js';

export interface PurchaseEmail { challengeID: string; email: string; code: string }
export interface PurchaseEmailSender { send(message: PurchaseEmail): Promise<string> }

export function purchaseEmailContent(code: string): { subject: string; text: string } {
  if (!/^[0-9]{6}$/.test(code)) throw new Error('Invalid purchase verification code.');
  const subject = 'Verify your Mural minutes purchase';
  const text = `Use this code to buy minutes for your existing Mural account:\n\n${code}\n\nThis code expires in 10 minutes and works once. It only gives access to checkout.\n\nIf you didn’t request it, ignore this email. For help, reply to hi@hackmamba.io.\n\nMural · Hackmamba Inc.`;
  return { subject, text };
}

export class ResendPurchaseEmails implements PurchaseEmailSender {
  constructor(readonly config: WebPurchaseConfig, readonly fetcher: typeof fetch = fetch) {}
  async send(message: PurchaseEmail): Promise<string> {
    const content = purchaseEmailContent(message.code);
    let response: Response;
    try {
      response = await this.fetcher('https://api.resend.com/emails', { method: 'POST',
        headers: { Authorization: `Bearer ${this.config.apiKey}`, 'Content-Type': 'application/json',
          'Idempotency-Key': `mural-web-purchase-${message.challengeID}` },
        body: JSON.stringify({ from: this.config.from, to: [message.email], reply_to: this.config.replyTo, ...content }),
        signal: AbortSignal.timeout(10_000), redirect: 'error' });
    } catch { throw new ServiceError('purchase_email_retry', 502); }
    if (!response.ok) {
      void response.body?.cancel().catch(() => {});
      throw new ServiceError(response.status === 429 || response.status >= 500 ? 'purchase_email_retry' : 'purchase_email_rejected', 502);
    }
    const reader = response.body?.getReader();
    if (!reader) throw new ServiceError('purchase_email_rejected', 502);
    let data = '', size = 0;
    try {
      const decoder = new TextDecoder();
      for (;;) {
        const { done, value } = await reader.read(); if (done) break;
        size += value.length; if (size > 8192) throw new Error();
        data += decoder.decode(value, { stream: true });
      }
      const id = (JSON.parse(data) as { id?: unknown }).id;
      if (typeof id !== 'string' || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(id)) throw new Error();
      return id;
    } catch { await reader.cancel().catch(() => {}); throw new ServiceError('purchase_email_rejected', 502); }
    finally { reader.releaseLock(); }
  }
}
