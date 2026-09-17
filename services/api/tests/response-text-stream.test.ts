import { test } from 'node:test';
import assert from 'node:assert/strict';
import { responseTextStream } from '../src/response-text-stream.js';

const event = (value: unknown) => `data: ${JSON.stringify(value)}\r\n\r\n`;
const final = { status: 'completed', output: [], usage: { input_tokens: 12, output_tokens: 7 } };
function response(text: string, width = 3) {
  const bytes = new TextEncoder().encode(text);
  let offset = 0, cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset === bytes.length) { controller.close(); return; }
      controller.enqueue(bytes.slice(offset, offset + width)); offset = Math.min(offset + width, bytes.length);
    }, cancel() { cancelled = true; },
  });
  return { response: new Response(stream, { headers: { 'content-type': 'text/event-stream; charset=utf-8' } }), cancelled: () => cancelled };
}

test('stream preserves Unicode split at every byte and exposes text before the completed usage', async () => {
  for (const width of [1, 2, 7, 4096]) {
    const fixture = response(': heartbeat\r\n\r\n' + event({ type: 'response.created' }) +
      ['Hygg', 'elig! café ', '你好 👋'].map(delta => event({ type: 'response.output_text.delta', delta })).join('') +
      event({ type: 'response.completed', response: final }) + 'unread', width);
    const seen: string[] = [];
    assert.deepEqual(await responseTextStream(fixture.response, text => seen.push(text)), final);
    assert.deepEqual(seen, ['Hygg', 'Hyggelig! café ', 'Hyggelig! café 你好 👋']);
    assert.equal(fixture.cancelled(), true);
  }
});
test('stream accepts multiple data lines and reads refusal through final usage', async () => {
  const fixture = response('data: {"type":"response.refusal.delta",\ndata: "delta":"no"}\n\n' +
    event({ type: 'response.completed', response: final }));
  assert.deepEqual(await responseTextStream(fixture.response, () => assert.fail('Refusal is not meaning text')), final);
});
test('stream rejects missing terminal, errors, malformed events and size violations', async () => {
  for (const source of [event({ type: 'response.output_text.delta', delta: 'partial' }), 'data: [DONE]\n\n',
    'data: not-json\n\n', event({ type: 'error' }), event({ type: 'response.failed' }),
    event({ type: 'response.incomplete' }), event({ type: 'response.completed', response: { status: 'incomplete' } }),
    event({ type: 'response.output_text.delta', delta: 42 }), 'data: ' + 'x'.repeat(65_537),
    ':'.repeat(65_537) + '\n\n']) {
    await assert.rejects(responseTextStream(response(source, 4096).response, () => {}));
  }
  await assert.rejects(responseTextStream(Response.json(final), () => {}));
});
