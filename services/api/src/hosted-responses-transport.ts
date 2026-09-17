import { responseTextStream } from './response-text-stream.js';
import { Diagnostics } from './diagnostics.js';
import { boundedJSON } from './live-provider.js';
import type { HostedResponsesRequest, HostedResponsesTransport } from './hosted-helpers.js';
import { ServiceError } from './errors.js';

/** Fixed provider destination, one attempt, no redirects or retained response content. */
export class OpenAIHostedResponses implements HostedResponsesTransport {
  #key: string;
  constructor(key: string, private readonly request: typeof fetch = fetch, private readonly diagnostics = new Diagnostics()) {
    if (!/^[\x21-\x7e]{20,512}$/.test(key)) throw new ServiceError('hosted_helpers_configuration_invalid', 503);
    this.#key = key;
  }
  async send(body: HostedResponsesRequest, signal: AbortSignal, onText?: (text: string) => void): Promise<unknown> {
    const started = performance.now();
    let status: number | undefined, requestID: string | undefined;
    try {
      const response = await this.request('https://api.openai.com/v1/responses', {
        method: 'POST', redirect: 'error', signal,
        headers: { Authorization: `Bearer ${this.#key}`, 'Content-Type': 'application/json', Accept: body.stream ? 'text/event-stream' : 'application/json' },
        body: JSON.stringify(body),
      });
      status = response.status; requestID = response.headers.get('x-request-id') ?? undefined;
      if (!response.ok) { await response.body?.cancel(); throw new Error(); }
      const result = body.stream ? await responseTextStream(response, onText ?? (() => {})) : await boundedJSON(response, 1_048_576);
      this.diagnostics.record('provider_completed', { operation: 'helper.respond', providerStatus: status,
        providerRequestID: requestID, durationMilliseconds: performance.now() - started });
      return result;
    } catch {
      const error = new ServiceError('hosted_helper_provider_unavailable', 502);
      this.diagnostics.record('provider_failed', { operation: 'helper.respond', providerStatus: status,
        providerRequestID: requestID, durationMilliseconds: performance.now() - started }, error);
      throw error;
    }
  }
}
