import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export interface WelcomeFundingPolicy {
  version: number; currency: 'USD'; reserveCostPerMinuteMinor: number;
  dailyBudgetMinor: number; lifetimeBudgetMinor: number;
}
const policyFromRow = (row: any): WelcomeFundingPolicy => ({ version: row.version, currency: 'USD',
  reserveCostPerMinuteMinor: row.reserve_cost_per_minute_minor, dailyBudgetMinor: Number(row.daily_budget_minor),
  lifetimeBudgetMinor: Number(row.lifetime_budget_minor) });
export async function welcomeFunding(db: Database) {
  return policyFromRow((await db.query('SELECT * FROM welcome_funding_policy WHERE singleton')).rows[0]);
}
export async function updateWelcomeFunding(db: Database, policy: WelcomeFundingPolicy, actor: string, reason: string) {
  for (const text of [actor, reason]) if (typeof text !== 'string' || text.trim().length < 3 || text.length > 200 || /[\x00-\x1f]/.test(text))
    throw new ServiceError('operator_and_reason_required');
  if (!Number.isSafeInteger(policy.version) || policy.version < 1 || policy.currency !== 'USD' ||
    !Number.isSafeInteger(policy.reserveCostPerMinuteMinor) || policy.reserveCostPerMinuteMinor < 5 || policy.reserveCostPerMinuteMinor > 1000 ||
    ![policy.dailyBudgetMinor, policy.lifetimeBudgetMinor].every(value => Number.isSafeInteger(value) && value >= 0 && value <= 100_000_000) ||
    policy.dailyBudgetMinor > policy.lifetimeBudgetMinor) throw new ServiceError('invalid_funding_policy');
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const before = policyFromRow((await sql.query('SELECT * FROM welcome_funding_policy WHERE singleton FOR UPDATE')).rows[0]);
    if (before.version !== policy.version) throw new ServiceError('policy_changed_review_again', 409);
    const after = { ...policy, version: policy.version + 1 };
    await sql.query(`UPDATE welcome_funding_policy SET version=$1,reserve_cost_per_minute_minor=$2,daily_budget_minor=$3,lifetime_budget_minor=$4
      WHERE singleton`, [after.version, after.reserveCostPerMinuteMinor, after.dailyBudgetMinor, after.lifetimeBudgetMinor]);
    await sql.query('INSERT INTO welcome_funding_audit(id,actor,reason,previous_policy,next_policy) VALUES($1,$2,$3,$4,$5)',
      [randomUUID(), actor, reason, JSON.stringify(before), JSON.stringify(after)]);
    return after;
  });
}

/** Called inside the minute grant transaction; the reservation survives use, deletion and sign-in. */
export async function reserveWelcomeFunding(sql: PoolClient, account: string, allowance: number) {
  await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
  const policy = (await sql.query('SELECT * FROM welcome_funding_policy WHERE singleton')).rows[0];
  const reserve = (BigInt(allowance) * BigInt(policy.reserve_cost_per_minute_minor) + 59_999n) / 60_000n;
  const totals = (await sql.query(`SELECT COALESCE(sum(reserve_cost_minor),0) AS lifetime,
    COALESCE(sum(reserve_cost_minor) FILTER (WHERE created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),0) AS daily
    FROM welcome_funding_allocations`)).rows[0];
  if (BigInt(totals.daily) + reserve > BigInt(policy.daily_budget_minor) ||
    BigInt(totals.lifetime) + reserve > BigInt(policy.lifetime_budget_minor)) throw new ServiceError('welcome_funding_budget_reached', 503);
  await sql.query(`INSERT INTO welcome_funding_allocations(reference,account_id,allowance_ms,reserve_cost_minor,policy_version)
    VALUES($1,$2,$3,$4,$5)`, [`welcome:${account}`, account, allowance, reserve.toString(), policy.version]);
}
