import { randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export interface AIPricingPolicy { version: number; serviceFeeBasisPoints: number }
export interface ProcessingCost {
  /** Verified channel/market rate on the full checkout amount, excluding separately handled tax. */
  rateBasisPoints: number;
  fixedMinor: number;
  bufferBasisPoints: number;
}
const whole = (value: number) => Number.isSafeInteger(value) && value >= 0;
const ceil = (n: bigint, d: bigint) => (n + d - 1n) / d;
function validatePolicy(policy: AIPricingPolicy) {
  if (!whole(policy.version) || policy.version < 1 || !whole(policy.serviceFeeBasisPoints) || policy.serviceFeeBasisPoints > 10_000)
    throw new ServiceError('invalid_ai_pricing_policy');
}

/** Pure quote only: this does not create a purchasable offer or fund a wallet. */
export function quoteAITopUp(aiValueMinor: number, policy: AIPricingPolicy, processing: ProcessingCost) {
  validatePolicy(policy);
  if (!whole(aiValueMinor) || aiValueMinor === 0 ||
      ![processing.rateBasisPoints, processing.fixedMinor, processing.bufferBasisPoints].every(whole) ||
      processing.rateBasisPoints + processing.bufferBasisPoints >= 10_000)
    throw new ServiceError('invalid_ai_top_up_quote');
  const ai = BigInt(aiValueMinor), service = ceil(ai * BigInt(policy.serviceFeeBasisPoints), 10_000n);
  const net = ai + service, fixed = BigInt(processing.fixedMinor);
  // Payment providers charge a percentage of the full amount collected, including the fee itself.
  const total = ceil((net + fixed) * 10_000n, 10_000n - BigInt(processing.rateBasisPoints + processing.bufferBasisPoints));
  const processingEstimate = ceil(total * BigInt(processing.rateBasisPoints), 10_000n) + fixed;
  const buffer = total - net - processingEstimate;
  if (total > BigInt(Number.MAX_SAFE_INTEGER)) throw new ServiceError('invalid_ai_top_up_quote');
  return { policyVersion: policy.version, aiValueMinor, serviceFeeBasisPoints: policy.serviceFeeBasisPoints,
    serviceFeeMinor: Number(service), processingEstimateMinor: Number(processingEstimate),
    processingBufferMinor: Number(buffer), paymentFeeMinor: Number(processingEstimate + buffer), totalMinor: Number(total) };
}

/** An estimate is not an entitlement. Actual provider usage debits the prepaid currency wallet. */
export function estimatedConversationMilliseconds(aiValueNano: bigint, estimatedNanoPerMinute: bigint): number {
  if (aiValueNano < 0n || estimatedNanoPerMinute <= 0n) throw new ServiceError('invalid_minute_estimate');
  const milliseconds = aiValueNano * 60_000n / estimatedNanoPerMinute;
  if (milliseconds > BigInt(Number.MAX_SAFE_INTEGER)) throw new ServiceError('invalid_minute_estimate');
  return Number(milliseconds);
}

export async function aiPricingPolicy(db: Database): Promise<AIPricingPolicy> {
  const row = (await db.query('SELECT version,service_fee_basis_points FROM ai_pricing_policy WHERE singleton')).rows[0];
  if (!row) throw new ServiceError('ai_pricing_unavailable', 503);
  return { version: row.version, serviceFeeBasisPoints: row.service_fee_basis_points };
}

/** Privileged database operation; deliberately has no administrative HTTP endpoint. */
export async function updateAIPricingPolicy(db: Database, policy: AIPricingPolicy, actor: string, reason: string) {
  validatePolicy(policy);
  for (const text of [actor, reason]) if (typeof text !== 'string' || text.trim().length < 3 || text.length > 200 || /[\x00-\x1f]/.test(text))
    throw new ServiceError('operator_and_reason_required');
  return transaction(db, async sql => {
    const row = (await sql.query('SELECT version,service_fee_basis_points FROM ai_pricing_policy WHERE singleton FOR UPDATE')).rows[0];
    if (!row || row.version !== policy.version) throw new ServiceError('policy_changed_review_again', 409);
    const before = { version: row.version, serviceFeeBasisPoints: row.service_fee_basis_points };
    const after = { ...policy, version: policy.version + 1 };
    await sql.query('UPDATE ai_pricing_policy SET version=$1,service_fee_basis_points=$2 WHERE singleton', [after.version, after.serviceFeeBasisPoints]);
    await sql.query('INSERT INTO ai_pricing_audit(id,actor,reason,previous_policy,next_policy) VALUES($1,$2,$3,$4,$5)',
      [randomUUID(), actor, reason, JSON.stringify(before), JSON.stringify(after)]);
    return after;
  });
}
