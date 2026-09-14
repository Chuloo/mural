import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { aiPricingPolicy, updateAIPricingPolicy, quoteAITopUp, estimatedConversationMilliseconds } from '../src/ai-top-up-pricing.js';

const policy = { version: 1, serviceFeeBasisPoints: 1500 };
const noProcessing = { rateBasisPoints: 0, fixedMinor: 0, bufferBasisPoints: 0 };
test('a quote preserves the AI value and itemizes the configurable 15% fee', () => {
  assert.deepEqual(quoteAITopUp(200, policy, noProcessing), {
    policyVersion: 1, aiValueMinor: 200, serviceFeeBasisPoints: 1500, serviceFeeMinor: 30,
    processingEstimateMinor: 0, processingBufferMinor: 0, paymentFeeMinor: 0, totalMinor: 230,
  });
  assert.equal(quoteAITopUp(200, { ...policy, version: 2, serviceFeeBasisPoints: 1000 }, noProcessing).totalMinor, 220);
});
test('synthetic processing rates apply to the full charge and do not erode AI funds', () => {
  // Synthetic rates exercise arithmetic; they are not a Stripe or Play pricing recommendation.
  const result = quoteAITopUp(200, policy, { rateBasisPoints: 300, fixedMinor: 30, bufferBasisPoints: 100 });
  assert.equal(result.totalMinor, 271);
  assert.equal(result.processingEstimateMinor, 39);
  assert.equal(result.processingBufferMinor, 2);
  assert.equal(result.totalMinor - result.paymentFeeMinor, result.aiValueMinor + result.serviceFeeMinor);
});
test('rounding preserves every minor unit for small top-ups and percentage-based store fees', () => {
  for (const ai of [1, 2, 99, 100, 201, 10_000]) for (const rate of [0, 1, 300, 1500, 2500, 3000]) {
    const result = quoteAITopUp(ai, policy, { rateBasisPoints: rate, fixedMinor: 0, bufferBasisPoints: 0 });
    assert.equal(result.aiValueMinor, ai);
    assert.equal(result.totalMinor, ai + result.serviceFeeMinor + result.processingEstimateMinor + result.processingBufferMinor);
    assert.ok(result.processingBufferMinor >= 0);
    assert.ok(result.totalMinor - Math.ceil(result.totalMinor * rate / 10_000) >= ai + result.serviceFeeMinor);
  }
});
test('invalid rates and overflowing quotes cannot create offers', () => {
  for (const value of [-1, 0, NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER]) assert.throws(() => quoteAITopUp(value, policy, noProcessing));
  for (const value of [-1, 1.5, Infinity, 10_000]) assert.throws(() => quoteAITopUp(100, policy, { ...noProcessing, rateBasisPoints: value }));
  assert.throws(() => quoteAITopUp(100, policy, { ...noProcessing, rateBasisPoints: 9000, bufferBasisPoints: 1000 }));
  assert.throws(() => quoteAITopUp(100, { ...policy, serviceFeeBasisPoints: -1 }, noProcessing));
});
test('display estimates depend on the stated usage rate and never change prepaid value', () => {
  assert.equal(estimatedConversationMilliseconds(2_000_000_000n, 50_000_000n), 2_400_000);
  assert.equal(estimatedConversationMilliseconds(2_000_000_000n, 100_000_000n), 1_200_000);
  assert.equal(estimatedConversationMilliseconds(0n, 100_000_000n), 0);
  assert.throws(() => estimatedConversationMilliseconds(1n, 0n));
  assert.throws(() => estimatedConversationMilliseconds(-1n, 1n));
});

const databaseURL = process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
test('pricing changes are audited, versioned and leave previously calculated quotes intact', { skip: !databaseURL }, async () => {
  const schema = `pricing_${randomUUID().replaceAll('-', '')}`, url = new URL(databaseURL!);
  url.searchParams.set('options', `-c search_path=${schema}`);
  const db = connectDatabase(url.toString());
  await db.query(`CREATE SCHEMA ${schema}`);
  try {
    await migrate(db);
    const before = await aiPricingPolicy(db);
    assert.deepEqual(before, policy);
    const original = quoteAITopUp(200, before, noProcessing);
    const after = await updateAIPricingPolicy(db, { ...before, serviceFeeBasisPoints: 1000 }, 'synthetic-operator', 'Synthetic fee change');
    assert.deepEqual(await aiPricingPolicy(db), { version: 2, serviceFeeBasisPoints: 1000 });
    assert.equal(quoteAITopUp(200, after, noProcessing).serviceFeeMinor, 20);
    assert.equal(original.serviceFeeMinor, 30);
    await assert.rejects(updateAIPricingPolicy(db, before, 'synthetic-operator', 'Stale revision'), /policy_changed_review_again/);
    assert.equal((await db.query('SELECT count(*) FROM ai_pricing_audit')).rows[0].count, '1');
    await assert.rejects(db.query('DELETE FROM ai_pricing_audit'), /immutable/);
  } finally {
    await db.query(`DROP SCHEMA ${schema} CASCADE`); await db.end();
  }
});
