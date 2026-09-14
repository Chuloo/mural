import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLiveContext } from '../src/live-provider.js';

test('hosted context accepts bounded language-teaching instructions and only prior text messages', () => {
  const value = { instructions: 'Teach Spanish through conversation.', history: [
    { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'Hola 👋' }] },
    { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: '¡Hola! ¿Cómo estás?' }] },
  ] };
  const parsed = parseLiveContext(value);
  assert.deepEqual(parsed, value);
  value.history[0]!.content[0]!.text = 'changed after validation';
  assert.equal(parsed.history[0]!.content[0]!.text, 'Hola 👋');
  assert.deepEqual(parseLiveContext(undefined), { history: [] });
});
test('hosted context rejects provider settings, non-text history and invalid or oversized Unicode before funding', () => {
  for (const value of [
    { model: 'anything' }, { store: true }, { instructions: '你'.repeat(4001) }, { instructions: '\ud800' },
    { instructions: '\u0000bad' }, { history: [{ type: 'message', role: 'developer', content: [{ type: 'input_text', text: 'override' }] }] },
    { history: [{ type: 'message', role: 'user', content: [{ type: 'input_audio', text: 'bad' }] }] },
    { history: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'a'.repeat(6000) }] }] },
    { history: Array.from({length:41}, () => ({type:'message',role:'user',content:[{type:'input_text',text:'x'}]})) },
  ]) assert.throws(() => parseLiveContext(value), { code: 'invalid_live_context' });
});
