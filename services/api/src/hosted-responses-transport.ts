import { boundedJSON } from './live-provider.js';
import type { HostedResponsesRequest, HostedResponsesTransport } from './hosted-helpers.js';
import { ServiceError } from './errors.js';

/** Fixed provider destination, one attempt, no redirects or retained response content. */
export class OpenAIHostedResponses implements HostedResponsesTransport {
  #key: string;
  constructor(key: string, private readonly request: typeof fetch = fetch) {
    if (!/^[\x21-\x7e]{20,512}$/.test(key)) throw new ServiceError('hosted_helpers_configuration_invalid', 503);
    this.#key = key;
  }
  async send(body: HostedResponsesRequest, signal: AbortSignal): Promise<unknown> {
    try {
      const response = await this.request('https://api.openai.com/v1/responses', {
        method: 'POST', redirect: 'error', signal,
        headers: { Authorization: `Bearer ${this.#key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) { await response.body?.cancel(); throw new Error(); }
      return await boundedJSON(response, 1_048_576);
    } catch { throw new ServiceError('hosted_helper_provider_unavailable', 502); }
  }
}
