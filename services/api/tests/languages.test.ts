import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
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
      ['nb-NO', 'Norwegian Bokmål'], ['es-ES', 'Spanish from Spain'], ['en', 'English'], ['en-US', 'English'], ['fr-FR', 'French from France'],
      ['de-DE', 'Standard German as spoken in Germany'], ['it-IT', 'Italian as spoken in Italy'],
      ['pt-BR', 'Brazilian Portuguese'], ['zh-CN', 'Standard Mandarin with Simplified Chinese writing'],
      ['sr-Latn-RS', 'Standard Serbian as spoken in Serbia, with ekavian forms and the Latin script']
    ]) {
      assert.equal(supportsLanguage(locale!), true, locale);
      await provider.create('v=0', locale!);
      assert.ok(requests.at(-1).session.instructions.includes(`Speak only ${target}`));
      assert.equal(requests.at(-1).session.audio.output.voice, 'marin');
      assert.equal(requests.at(-1).session.store, false);
    }
    for (const unsupported of ['pt-PT', 'de', 'zh', 'zh-TW', 'sr', 'sr-RS', 'sr-Cyrl-RS', '__proto__', 'constructor', '']) {
      assert.equal(supportsLanguage(unsupported), false);
      await assert.rejects(provider.create('v=0', unsupported), { code: 'invalid_language' });
    }
    assert.equal(requests.length, 10);
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});

// Read the shipped registries so a duplicated server-side locale list cannot hide drift.
test('hosted admission accepts every locale shipped by Android and iOS', async () => {
  const android = await readFile(new URL('../../../apps/android/app/src/main/java/chat/mural/core/Languages.kt', import.meta.url), 'utf8');
  const androidLocales = [...android.matchAll(/locale = "([^"\n]+)"/g)].map(match => match[1]!).sort();
  const directory = new URL('../../../apps/ios/Core/Languages/', import.meta.url);
  const swift = await Promise.all((await readdir(directory)).filter(name => name.endsWith('.swift'))
    .map(name => readFile(new URL(name, directory), 'utf8')));
  const iosLocales = swift.flatMap(source => [...source.matchAll(/locale: "([^"\n]+)"/g)].map(match => match[1]!)).sort();
  assert.ok(androidLocales.length >= 8);
  assert.deepEqual(androidLocales, iosLocales);
  for (const locale of androidLocales) assert.equal(supportsLanguage(locale), true, `Native locale rejected: ${locale}`);
  assert.equal(supportsLanguage('en-US'), true, 'Preserve existing client compatibility');
});

test('hosted provider preserves selected theme instructions and continuation history', async () => {
  let payload: any;
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    payload = JSON.parse(Buffer.concat(chunks).toString());
    response.writeHead(201, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ session: { id: 'live_theme_fixture' }, transport: { type: 'webrtc', sdp: 'v=0' } }));
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address() as { port: number };
    const provider = new OpenAILiveProvider('test-no-real-provider-key', { testOrigin: `http://127.0.0.1:${address.port}` });
    const instructions = 'Conversation direction: Plan dinner together. Ask about ingredients and preferences.';
    const history = [{ type: 'message' as const, role: 'user' as const,
      content: [{ type: 'input_text' as const, text: 'Jeg har tomater.' }] }];
    await provider.create('v=0', 'nb-NO', { instructions, history });
    assert.ok(payload.session.instructions.startsWith(instructions + '\n'));
    assert.ok(payload.session.instructions.includes('Speak only Norwegian Bokmål'));
    assert.deepEqual(payload.session.input, history);
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
