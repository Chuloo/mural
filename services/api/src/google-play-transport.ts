import { importPKCS8, SignJWT } from 'jose';
import { ServiceError } from './errors.js';

export interface GoogleAccessTokenSource { accessToken(): Promise<string> }
export interface PlayTransport {
  purchase(packageName: string, token: string): Promise<any>;
  order(packageName: string, orderID: string): Promise<any>;
  consume(packageName: string, product: string, token: string): Promise<void>;
  voided(packageName: string, startMilliseconds: number, endMilliseconds: number, pageToken?: string): Promise<any>;
}
async function boundedJSON(response: Response, maxBytes: number): Promise<any> {
  if (!response.ok) { await response.body?.cancel(); throw new ServiceError('google_provider_unavailable', 502); }
  const reader = response.body?.getReader();
  if (!reader) throw new ServiceError('google_provider_response_invalid', 502);
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.length;
      if (size > maxBytes) { await reader.cancel(); throw new Error(); }
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch { throw new ServiceError('google_provider_response_invalid', 502); }
  finally { reader.releaseLock(); }
}

/** Uses caller-supplied service-account credentials; this module never creates or discovers keys. */
export class GoogleServiceAccountTokens implements GoogleAccessTokenSource {
  #key?: ReturnType<typeof importPKCS8>;
  readonly #privateKeyPEM: string;
  #cached?: { token: string; until: number };
  #pending?: Promise<string>;
  constructor(readonly email: string, privateKeyPEM: string, readonly request: typeof fetch = fetch) {
    if (!/^[a-zA-Z0-9._-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$/.test(email) ||
      !privateKeyPEM.startsWith('-----BEGIN PRIVATE KEY-----')) throw new ServiceError('google_service_configuration_invalid', 503);
    this.#privateKeyPEM = privateKeyPEM;
  }
  async accessToken(): Promise<string> {
    if (this.#cached && this.#cached.until > Date.now()) return this.#cached.token;
    if (this.#pending) return this.#pending;
    this.#pending = this.#exchange();
    try { return await this.#pending; } finally { this.#pending = undefined; }
  }
  async #exchange(): Promise<string> {
    try {
      const assertion = await new SignJWT({ scope: 'https://www.googleapis.com/auth/androidpublisher' })
        .setProtectedHeader({ alg: 'RS256', typ: 'JWT' }).setIssuer(this.email).setAudience('https://oauth2.googleapis.com/token')
        .setIssuedAt().setExpirationTime('1h').sign(await (this.#key ??= importPKCS8(this.#privateKeyPEM, 'RS256')));
      const response = await this.request('https://oauth2.googleapis.com/token', { method: 'POST', redirect: 'error',
        headers: { 'content-type': 'application/x-www-form-urlencoded' }, signal: AbortSignal.timeout(15_000),
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }) });
      const body = await boundedJSON(response, 16_384);
      if (typeof body.access_token !== 'string' || !/^[\x21-\x7e]{20,8192}$/.test(body.access_token) || body.token_type !== 'Bearer' ||
        !Number.isSafeInteger(body.expires_in) || body.expires_in < 120 || body.expires_in > 3600) throw new Error();
      this.#cached = { token: body.access_token, until: Date.now() + (body.expires_in - 60) * 1000 };
      return body.access_token;
    } catch { throw new ServiceError('google_service_authorization_unavailable', 503); }
  }
}

/** Fixed Google origin, encoded path segments, bounded bodies, timeouts and no redirects. */
export class GooglePlayHTTPTransport implements PlayTransport {
  constructor(readonly tokens: GoogleAccessTokenSource, readonly request: typeof fetch = fetch) {}
  async #request(path: string, method: 'GET' | 'POST' = 'GET'): Promise<any> {
    try {
      const token = await this.tokens.accessToken();
      if (typeof token !== 'string' || !/^[\x21-\x7e]{20,8192}$/.test(token)) throw new Error();
      const response = await this.request(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${path}`, {
        method, redirect: 'error', headers: { authorization: `Bearer ${token}`, accept: 'application/json' }, signal: AbortSignal.timeout(15_000) });
      if (method === 'POST') {
        if (!response.ok) { await response.body?.cancel(); throw new Error(); }
        await response.body?.cancel(); return;
      }
      return await boundedJSON(response, 262_144);
    } catch { throw new ServiceError('google_provider_unavailable', 502); }
  }
  purchase(packageName: string, token: string) {
    return this.#request(`${encodeURIComponent(packageName)}/purchases/productsv2/tokens/${encodeURIComponent(token)}`);
  }
  order(packageName: string, orderID: string) {
    return this.#request(`${encodeURIComponent(packageName)}/orders/${encodeURIComponent(orderID)}`);
  }
  consume(packageName: string, product: string, token: string) {
    return this.#request(`${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}:consume`, 'POST');
  }
  voided(packageName: string, startMilliseconds: number, endMilliseconds: number, pageToken?: string) {
    const query = new URLSearchParams({ startTime: String(startMilliseconds), endTime: String(endMilliseconds), type: '0',
      includeQuantityBasedPartialRefund: 'true', maxResults: '1000' });
    if (pageToken) query.set('token', pageToken);
    return this.#request(`${encodeURIComponent(packageName)}/purchases/voidedpurchases?${query}`);
  }
}
