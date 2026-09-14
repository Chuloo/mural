import { test } from 'node:test';
import assert from 'node:assert/strict';
import { OpenAIHostedResponses } from '../src/hosted-responses-transport.js';
import { hostedHelperBody, parseHostedHelperInput } from '../src/hosted-helpers.js';
import { randomUUID } from 'node:crypto';

const key = 'synthetic-provider-key-for-local-transport-test';
const body = () => hostedHelperBody(parseHostedHelperInput({ requestID: randomUUID(), purpose: 'meaning',
  instructions: 'Explain the meaning in the selected subtitle language.', input: 'Buenos días.' }));

test('helper transport pins the provider destination and forwards cancellation without retries', async () => {
  const controller = new AbortController(); let attempts = 0;
  const transport = new OpenAIHostedResponses(key, (async (url, init) => {
    attempts++;
    assert.equal(url, 'https://api.openai.com/v1/responses');
    assert.equal(init?.method, 'POST'); assert.equal(init?.redirect, 'error');
    assert.equal(init?.signal, controller.signal);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get('authorization'), `Bearer ${key}`);
    const sent = JSON.parse(init?.body as string);
    assert.equal(sent.store, false); assert.equal(sent.stream, false); assert.equal(sent.background, false);
    assert.equal(sent.model, 'gpt-5.6-luna');
    return Response.json({ id: 'resp_fixture', status: 'completed' });
  }) as typeof fetch);
  assert.deepEqual(await transport.send(body(), controller.signal), { id: 'resp_fixture', status: 'completed' });
  assert.equal(attempts, 1);
});

test('helper transport rejects oversized and failed responses without leaking bodies or retrying', async () => {
  for (const failure of ['status', 'oversized', 'network'] as const) {
    let attempts = 0, cancelled = false;
    const transport = new OpenAIHostedResponses(key, (async () => {
      attempts++;
      if (failure === 'network') throw new Error(`Sensitive error ${key}`);
      const stream = new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode(failure === 'oversized' ? 'x'.repeat(1_048_577) : key));
      }, cancel() { cancelled = true; } });
      return new Response(stream, { status: failure === 'status' ? 307 : 200 });
    }) as typeof fetch);
    await assert.rejects(transport.send(body(), new AbortController().signal), error => {
      assert.equal((error as {code:string}).code, 'hosted_helper_provider_unavailable');
      assert.equal(String(error).includes(key), false); return true;
    });
    assert.equal(attempts, 1);
    if (failure !== 'network') assert.equal(cancelled, true);
  }
});
