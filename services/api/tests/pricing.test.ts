import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cost, webTopUp, NANO_USD } from '../src/pricing.js';
import { VoiceMeter } from '../src/meter.js';

test('ten minutes plus regular teaching/search costs $0.5480', () => {
  const quote = cost({ milliseconds: 600_000, inputTokens: 100_000, cachedInputTokens: 0, outputTokens: 15_000, searchCalls: 1 });
  assert.equal(quote.voice, 500_000_000n); assert.equal(quote.total, 548_000_000n);
});
test('cached input is separated from normal input and monetary values remain integers', () => {
  const quote = cost({ milliseconds: 1, inputTokens: 100, cachedInputTokens: 50, outputTokens: 10, searchCalls: 0 });
  assert.equal(quote.text, 23_000n); assert.equal(quote.voice, 834n);
  assert.deepEqual(webTopUp(10n * NANO_USD), { aiValueNano: 10n * NANO_USD, serviceFeeNano: 1_500_000_000n, totalNano: 11_500_000_000n });
});
test('invalid provider quantities cannot become wallet debits', () => {
  const empty = { milliseconds: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, searchCalls: 0 };
  for (const amount of [-1, NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => cost({ ...empty, inputTokens: amount }));
  assert.throws(() => cost({ ...empty, cachedInputTokens: 1 }));
});
test('voice snapshots coalesce and 600 seconds requests closure exactly once', () => {
  const meter = new VoiceMeter('live-test');
  meter.receive('live-test', { type: 'session.usage.updated', usage: { seconds: 200 } });
  meter.receive('live-test', { type: 'session.usage.updated', usage: { seconds: 400 } });
  meter.receive('live-test', { type: 'session.usage.updated', usage: { seconds: 399 } });
  assert.equal(meter.milliseconds, 400_000); assert.equal(meter.closeRequested, false);
  meter.receive('live-test', { type: 'session.usage.updated', usage: { seconds: 600 } });
  assert.equal(meter.milliseconds, 600_000); assert.equal(meter.closeRequested, true);
  meter.receive('live-test', { type: 'session.closed', usage: { seconds: 600.001 } });
  assert.equal(meter.finalized, true); assert.equal(meter.milliseconds, 600_001);
});
test('meter rejects a different attached session or regressing final usage', () => {
  const meter = new VoiceMeter('live-test');
  assert.throws(() => meter.receive('other', { type: 'session.usage.updated', usage: { seconds: 1 } }));
  meter.receive('live-test', { type: 'session.usage.updated', usage: { seconds: 2 } });
  assert.throws(() => meter.receive('live-test', { type: 'session.closed', usage: { seconds: 1 } }));
  meter.deadlineExpired(); assert.equal(meter.closeRequested, true); assert.equal(meter.finalized, false);
});
