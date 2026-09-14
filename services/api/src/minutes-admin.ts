import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { connectDatabase, transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendMinuteEntry, millisecondsForMinutes, MS_PER_MINUTE } from './minutes.js';
import { welcomeFunding, updateWelcomeFunding } from './welcome-funding.js';
import { aiPricingPolicy, updateAIPricingPolicy } from './ai-top-up-pricing.js';

export interface WelcomePolicy {
  version: number; welcomeEnabled: boolean; welcomeMinutes: number;
  /** Legacy input accepted for older operator clients; neither field controls grant admission. */
  dailyWelcomeBudgetMinutes?: number; lifetimeWelcomeBudgetMinutes?: number;
}
export interface CampaignRequest {
  id: string; actor: string; reason: string; minutesPerUser: number; maxTotalMinutes: number;
  audience: 'all-current-users' | string[];
}
const hash = (value: unknown) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const isUUID = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
function auditText(value: unknown): asserts value is string {
  if (typeof value !== 'string' || value.trim().length < 3 || value.length > 200 || /[\x00-\x1f]/.test(value))
    throw new ServiceError('operator_and_reason_required');
}
const budget = (value: number) => Number.isSafeInteger(value) && value >= 0 && value <= 144_000_000;
const policyFromRow = (row: any): WelcomePolicy => ({ version: row.version, welcomeEnabled: row.welcome_enabled,
  welcomeMinutes: Number(row.welcome_ms) / MS_PER_MINUTE });
export async function welcomePolicy(db: Database): Promise<WelcomePolicy> {
  return policyFromRow((await db.query('SELECT * FROM minute_policy WHERE singleton')).rows[0]);
}
export async function updateWelcomePolicy(db: Database, policy: WelcomePolicy, actor: string, reason: string) {
  auditText(actor); auditText(reason);
  if (typeof policy.welcomeEnabled !== 'boolean' || !Number.isSafeInteger(policy.version) || policy.version < 1 ||
    (policy.dailyWelcomeBudgetMinutes!==undefined && !budget(policy.dailyWelcomeBudgetMinutes)) ||
    (policy.lifetimeWelcomeBudgetMinutes!==undefined && !budget(policy.lifetimeWelcomeBudgetMinutes))) throw new ServiceError('invalid_minute_policy');
  const amount = millisecondsForMinutes(policy.welcomeMinutes);
  if (policy.welcomeEnabled && !amount) throw new ServiceError('welcome_minutes_required');
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const before = policyFromRow((await sql.query('SELECT * FROM minute_policy WHERE singleton FOR UPDATE')).rows[0]);
    if (before.version !== policy.version) throw new ServiceError('policy_changed_review_again', 409);
    const after = {version:policy.version+1,welcomeEnabled:policy.welcomeEnabled,welcomeMinutes:policy.welcomeMinutes};
    await sql.query('UPDATE minute_policy SET version=$1,welcome_enabled=$2,welcome_ms=$3 WHERE singleton',
      [after.version,after.welcomeEnabled,amount]);
    await sql.query('INSERT INTO minute_policy_audit(id,actor,reason,previous_policy,next_policy) VALUES($1,$2,$3,$4,$5)',
      [randomUUID(), actor, reason, JSON.stringify(before), JSON.stringify(after)]);
    return after;
  });
}

/** Existing sandbox grants require an explicit reviewed opening balance; no inferred consumption. */
export async function reconcileSandboxMinutes(db: Database, input: {
  accountID: string; expectedBalanceMilliseconds: number; sandboxMilliseconds: number; actor: string; reason: string;
}) {
  auditText(input.actor); auditText(input.reason);
  if (!isUUID(input.accountID) || !Number.isSafeInteger(input.expectedBalanceMilliseconds) || input.expectedBalanceMilliseconds<0 ||
    !Number.isSafeInteger(input.sandboxMilliseconds) || input.sandboxMilliseconds<0 || input.sandboxMilliseconds>input.expectedBalanceMilliseconds)
    throw new ServiceError('invalid_sandbox_reconciliation');
  return transaction(db,async sql=>{
    await sql.query('SELECT id FROM accounts WHERE id=$1 FOR UPDATE',[input.accountID]);
    const wallet=(await sql.query('SELECT * FROM minute_wallets WHERE account_id=$1 FOR UPDATE',[input.accountID])).rows[0];
    if (!wallet || wallet.sandbox_reconciled || Number(wallet.balance_ms)!==input.expectedBalanceMilliseconds || Number(wallet.reserved_ms)!==0)
      throw new ServiceError('sandbox_reconciliation_review_again',409);
    await sql.query(`INSERT INTO minute_sandbox_reconciliations(id,account_id,actor,reason,balance_ms,reserved_ms,previous_sandbox_ms,next_sandbox_ms)
      VALUES($1,$2,$3,$4,$5,0,$6,$7)`,[randomUUID(),input.accountID,input.actor,input.reason,wallet.balance_ms,wallet.sandbox_balance_ms,input.sandboxMilliseconds]);
    await sql.query('UPDATE minute_wallets SET sandbox_balance_ms=$2,sandbox_reconciled=true WHERE account_id=$1',[input.accountID,input.sandboxMilliseconds]);
    return {accountID:input.accountID,reconciled:true,sandboxMilliseconds:input.sandboxMilliseconds};
  });
}
function campaignSummary(row: any) {
  return { campaignID: row.id as string, state: row.state as string, recipients: Number(row.recipient_count),
    minutesPerUser: Number(row.minutes_per_user), totalMinutes: Number(row.recipient_count) * Number(row.minutes_per_user),
    confirmation: row.target_digest as string };
}
export async function prepareMinuteCampaign(db: Database, request: CampaignRequest) {
  if (!isUUID(request.id)) throw new ServiceError('invalid_campaign_id');
  auditText(request.actor); auditText(request.reason);
  if (!request.minutesPerUser) throw new ServiceError('invalid_minutes');
  millisecondsForMinutes(request.minutesPerUser);
  if (!budget(request.maxTotalMinutes) || !request.maxTotalMinutes) throw new ServiceError('campaign_budget_required');
  let audience: 'all-current-users' | string[] = request.audience;
  if (audience !== 'all-current-users') {
    if (!Array.isArray(audience) || !audience.length || audience.length > 100_000 || !audience.every(isUUID))
      throw new ServiceError('invalid_recipients');
    audience = [...new Set(audience.map(id => id.toLowerCase()))].sort();
  }
  const digest = hash({ ...request, id: request.id.toLowerCase(), audience });
  return transaction(db, async sql => {
    await sql.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`minute-campaign:${request.id.toLowerCase()}`]);
    const existing = (await sql.query('SELECT * FROM minute_campaigns WHERE id=$1', [request.id])).rows[0];
    if (existing) {
      if (existing.request_digest !== digest) throw new ServiceError('idempotency_conflict', 409);
      return campaignSummary(existing);
    }
    const targets = (await sql.query(`SELECT id FROM accounts WHERE deleted_at IS NULL AND NOT is_guest
      ${audience === 'all-current-users' ? '' : 'AND id=ANY($1::uuid[])'} ORDER BY id LIMIT 100001`,
    audience === 'all-current-users' ? [] : [audience])).rows.map(row => row.id as string);
    if (!targets.length || targets.length > 100_000 || (audience !== 'all-current-users' && targets.length !== audience.length))
      throw new ServiceError('recipient_not_found');
    if (targets.length * request.minutesPerUser > request.maxTotalMinutes) throw new ServiceError('campaign_budget_exceeded', 409);
    const confirmation = hash({ id: request.id.toLowerCase(), targets, minutes: request.minutesPerUser,
      actor: request.actor, reason: request.reason, maxTotalMinutes: request.maxTotalMinutes });
    const row = (await sql.query(`INSERT INTO minute_campaigns(id,actor,reason,minutes_per_user,max_total_minutes,recipient_count,request_digest,target_digest)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`, [request.id, request.actor, request.reason, request.minutesPerUser,
      request.maxTotalMinutes, targets.length, digest, confirmation])).rows[0];
    await sql.query('INSERT INTO minute_campaign_recipients(campaign_id,account_id) SELECT $1,unnest($2::uuid[])', [request.id, targets]);
    return campaignSummary(row);
  });
}
export async function applyMinuteCampaign(db: Database, id: string, confirmation: string) {
  if (!isUUID(id) || !/^[a-f0-9]{64}$/.test(confirmation)) throw new ServiceError('campaign_confirmation_required');
  return transaction(db, async sql => {
    // Applied in bounded batches. The frozen recipient list survives disconnects and retries.
    const campaign = (await sql.query('SELECT * FROM minute_campaigns WHERE id=$1 FOR UPDATE', [id])).rows[0];
    if (!campaign || campaign.target_digest !== confirmation) throw new ServiceError('campaign_confirmation_required', 409);
    const targets = (await sql.query(`SELECT account_id FROM minute_campaign_recipients
      WHERE campaign_id=$1 AND outcome='pending' ORDER BY account_id LIMIT 200`, [id])).rows;
    for (const { account_id: account } of targets) {
      const owner = (await sql.query('SELECT deleted_at FROM accounts WHERE id=$1 FOR UPDATE', [account])).rows[0];
      let outcome = 'account_deleted';
      if (owner && !owner.deleted_at) {
        await appendMinuteEntry(sql, account, `gift:${id}:${account}`, 'gift', Number(campaign.minutes_per_user) * MS_PER_MINUTE, 0);
        outcome = 'granted';
      }
      await sql.query('UPDATE minute_campaign_recipients SET outcome=$3 WHERE campaign_id=$1 AND account_id=$2', [id, account, outcome]);
    }
    const totals = (await sql.query(`SELECT count(*) FILTER (WHERE outcome='pending') AS pending,
      count(*) FILTER (WHERE outcome='granted') AS granted,count(*) FILTER (WHERE outcome='account_deleted') AS skipped
      FROM minute_campaign_recipients WHERE campaign_id=$1`, [id])).rows[0];
    if (Number(totals.pending) === 0) await sql.query("UPDATE minute_campaigns SET state='applied',applied_at=COALESCE(applied_at,now()) WHERE id=$1", [id]);
    return { campaignID: id, pending: Number(totals.pending), granted: Number(totals.granted), skipped: Number(totals.skipped) };
  });
}

// Local operator process only. No administrative HTTP routes or client admin key.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const command = process.argv[2], url = process.env.DATABASE_URL;
  if (!url || process.argv.length !== 3 || !['policy', 'set-policy', 'funding', 'set-funding', 'pricing', 'set-pricing', 'prepare-grant', 'apply-grant','reconcile-sandbox'].includes(command ?? '')) {
    console.error('Use minutes-admin policy | set-policy | funding | set-funding | pricing | set-pricing | prepare-grant | apply-grant | reconcile-sandbox. Mutations read JSON from stdin.'); process.exitCode = 1;
  } else {
    const db = connectDatabase(url);
    try {
      let input: any;
      if (command !== 'policy' && command !== 'funding' && command !== 'pricing') {
        let data = '';
        for await (const chunk of process.stdin) { data += chunk.toString(); if (data.length > 4_000_000) throw new ServiceError('invalid_request'); }
        input = JSON.parse(data);
      }
      let result: unknown;
      if (command === 'policy') result = await welcomePolicy(db);
      else if (command === 'set-policy') result = await updateWelcomePolicy(db, input.policy, input.actor, input.reason);
      else if (command === 'funding') result = await welcomeFunding(db);
      else if (command === 'set-funding') result = await updateWelcomeFunding(db, input.policy, input.actor, input.reason);
      else if (command === 'pricing') result = await aiPricingPolicy(db);
      else if (command === 'set-pricing') result = await updateAIPricingPolicy(db, input.policy, input.actor, input.reason);
      else if (command === 'prepare-grant') result = await prepareMinuteCampaign(db, input);
      else if (command === 'reconcile-sandbox') result=await reconcileSandboxMinutes(db,input);
      else {
        do { result = await applyMinuteCampaign(db, input.campaignID, input.confirmation); }
        while ((result as { pending: number }).pending > 0);
      }
      console.info(JSON.stringify(result));
    } catch (error) {
      console.error(error instanceof ServiceError ? error.code : 'minute_operation_failed'); process.exitCode = 1;
    } finally { await db.end(); }
  }
}
