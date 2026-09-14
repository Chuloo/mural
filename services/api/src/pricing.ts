export const RATE_VERSION = 'openai-2026-09-12';
export const NANO_USD = 1_000_000_000n;
export const TRIAL_MS = 600_000;
export interface Usage { milliseconds: number; inputTokens: number; cachedInputTokens: number; outputTokens: number; searchCalls: number }
const integer = (number: number): bigint => {
  if (!Number.isSafeInteger(number) || number < 0) throw new RangeError('Usage must be a nonnegative safe integer.');
  return BigInt(number);
};
const ceilDivide = (numerator: bigint, denominator: bigint) => (numerator + denominator - 1n) / denominator;
export function cost(usage: Usage) {
  const input = integer(usage.inputTokens), cached = integer(usage.cachedInputTokens);
  if (cached > input) throw new RangeError('Cached tokens cannot exceed input.');
  const voice = ceilDivide(integer(usage.milliseconds) * 50_000_000n, 60_000n);
  const text = (input - cached) * 200n + cached * 20n + integer(usage.outputTokens) * 1_200n;
  const search = integer(usage.searchCalls) * 10_000_000n;
  return { voice, text, search, total: voice + text + search, rateVersion: RATE_VERSION };
}
export function webTopUp(aiValueNano: bigint) {
  if (aiValueNano <= 0n) throw new RangeError('Top-up must be positive.');
  const fee = ceilDivide(aiValueNano * 15n, 100n);
  return { aiValueNano, serviceFeeNano: fee, totalNano: aiValueNano + fee };
}
