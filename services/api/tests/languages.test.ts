import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { OpenAILiveProvider, supportsLanguage } from '../src/live-provider.js';

test('all native locales reach the provider with the intended regional speech target', async () => {
  const requests: any[] = [];
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    requests.push(JSON.parse(Buffer.concat(chunks).toString()));
    response.writeHead(201, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ session: { id: 'live_language_fixture' }, transport: { type: 'webrtc', sdp: 'v=0' } }));
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address() as { port: number };
    const provider = new OpenAILiveProvider('test-no-real-provider-key', { testOrigin: `http://127.0.0.1:${address.port}` });
    for (const [locale, target] of [
      ['nb-NO', 'Norwegian Bokmål'], ['es-ES', 'Spanish from Spain'], ['en-US', 'English'], ['fr-FR', 'French from France'],
      ['de-DE', 'Standard German as spoken in Germany'], ['it-IT', 'Italian as spoken in Italy'],
      ['pt-BR', 'Brazilian Portuguese'], ['zh-CN', 'Standard Mandarin with Simplified Chinese writing'],
      ['af-ZA', 'Afrikaans as spoken in South Africa']
    ]) {
      assert.equal(supportsLanguage(locale!), true, locale);
      await provider.create('v=0', locale!);
      assert.ok(requests.at(-1).session.instructions.includes(`Speak only ${target}`));
      assert.equal(requests.at(-1).session.store, false);
    }
    for (const unsupported of ['pt-PT', 'de', 'zh', 'zh-TW', 'af', 'nl-NL', '__proto__', 'constructor', '']) {
      assert.equal(supportsLanguage(unsupported), false);
      await assert.rejects(provider.create('v=0', unsupported), { code: 'invalid_language' });
    }
    assert.equal(requests.length, 9);
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
