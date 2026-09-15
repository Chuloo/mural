import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet, lockPaidWallet, reservePaidInTransaction, settlePaidInTransaction } from './ledger.js';
import { ServiceError } from './errors.js';
import { VoiceMeter } from './meter.js';
import { cost, RATE_VERSION, TRIAL_MS } from './pricing.js';
import { LiveCreateFailure, LiveCreateRejectedError, supportsLanguage, parseLiveContext, type LiveProvider, type Sideband, type VoiceUsage } from './live-provider.js';
import { appendMinuteEntry, lockMinuteWallet } from './minutes.js';
import { recoverMinutePurchaseShortfalls } from './minute-purchases.js';
import { hostedHelperExposure, type HostedHelpers } from './hosted-helpers.js';

const voiceCost = (milliseconds: number) => cost({ milliseconds, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, searchCalls: 0 }).voice;
const HOLD = voiceCost(TRIAL_MS);
const unresolved = "state<>'closed'";
interface Slot { providerID: string; connection?: Sideband; queue: Promise<void>; pending: number; lastHangup: number }
export interface HostedConfig {
  /** Restricted test mode keeps its explicit allowlist and aggregate dollar cap. */
  accountAllowlist: ReadonlySet<string>;
  lifetimeFundingCapNano: bigint;
  /** Public admission uses funded minute balances, never sandbox receipts or a lifetime test cap. */
  publicMinuteAccess?: boolean;
  /** Independently enabled only after end-to-end paid accounting verification. */
  publicPaidAccess?: boolean;
  billingUnit?: 'nanoUSD' | 'milliseconds';
  helpers?: Pick<HostedHelpers, 'reserveSessionBudget'> & Partial<Pick<HostedHelpers,'paidFundingPolicy'|'closeCashBudget'>>;
  onStartupFailure?: (diagnostic: { category: string; providerStatus?: number; requestID?: string }) => void;
  now?: () => number;
  closeGraceMilliseconds?: number;
}

/** Experimental controller with durable voice and optional teaching funding. Usage is provider-authoritative. */
export class HostedVoice {
  private leader?: PoolClient;
  private leaderError?: () => void;
  private accepting = false;
  private timer?: NodeJS.Timeout;
  private ticking = false;
  private readonly slots = new Map<string, Slot>();
  private readonly now: () => number;
  private readonly grace: number;
  constructor(private readonly db: Database, private readonly provider: LiveProvider, private readonly config: HostedConfig) {
    if (config.publicMinuteAccess ? config.billingUnit!=='milliseconds' :
      config.lifetimeFundingCapNano < HOLD || config.lifetimeFundingCapNano > 25_000_000_000n || !config.accountAllowlist.size)
      throw new ServiceError('invalid_hosted_funding_configuration', 503);
    this.now = config.now ?? Date.now; this.grace = config.closeGraceMilliseconds ?? 5_000;
    if (!Number.isSafeInteger(this.grace) || this.grace<0 || this.grace>60_000)
      throw new ServiceError('invalid_hosted_close_configuration',503);
    if (config.publicPaidAccess && (!config.publicMinuteAccess || !config.helpers?.paidFundingPolicy?.enabled || !config.helpers.closeCashBudget))
      throw new ServiceError('invalid_hosted_paid_configuration',503);
  }
  get available() { return this.accepting; }
  get minuteFunded() { return this.config.billingUnit === 'milliseconds'; }
  get publicMinuteAccess() { return this.config.publicMinuteAccess === true; }
  get publicPaidAccess() { return this.config.publicPaidAccess === true; }
  get estimatedNanoUSDPerMinute() { return 50_000_000n+(this.config.helpers?.paidFundingPolicy?.helperBudgetNanoPerMinute ?? 0n); }
  get minimumPaidSessionNanoUSD() { return this.paidHold(15_000).total; }
  private paidHold(duration: number) {
    const voice=voiceCost(Math.max(15_000,duration)+this.grace+1000);
    const helper=(BigInt(duration)*(this.config.helpers?.paidFundingPolicy?.helperBudgetNanoPerMinute ?? 0n)+59_999n)/60_000n;
    return { voice,helper,total:voice+helper };
  }
  allows(account: string) { return this.accepting && (this.publicMinuteAccess || this.config.accountAllowlist.has(account)); }
  async start(): Promise<void> {
    if (this.leader) throw new ServiceError('voice_worker_already_started', 503);
    const leader = await this.db.connect();
    try {
      const lock = await leader.query("SELECT pg_try_advisory_lock(hashtext('mural-hosted-voice-worker')) AS acquired");
      if (!lock.rows[0].acquired) throw new ServiceError('voice_worker_already_running', 503);
      this.leader = leader;
      this.leaderError=() => { this.accepting = false; void this.emergencyClose(); };
      leader.on('error',this.leaderError);
      // Recovery prioritizes stopping billed sessions; it never opens a replacement session automatically.
      const rows = (await this.db.query(`SELECT * FROM hosted_sessions WHERE ${unresolved}`)).rows;
      for (const row of rows) {
        if (!row.provider_session_id) {
          if (Number(row.minimum_charge_ms) === 15_000 && !row.provider_attempted_at) {
            await this.prepareMinuteProviderAttempt(row.id, row.account_id, false);
            continue;
          }
          await this.db.query("UPDATE hosted_sessions SET state='incomplete',close_reason='create_uncertain' WHERE id=$1 AND state<>'closed'", [row.id]);
          continue;
        }
        try { await this.attach(row.id, row.provider_session_id); } catch { /* The watchdog retries. */ }
        await this.requestClose(row.id, 'worker_recovery');
      }
      this.accepting = true;
      this.timer = setInterval(() => { void this.tick().catch(() => { this.accepting = false; void this.emergencyClose(); }); }, 1_000);
      this.timer.unref();
    } catch (error) {
      if (this.leader) { await leader.query("SELECT pg_advisory_unlock(hashtext('mural-hosted-voice-worker'))").catch(() => {}); this.leader = undefined; }
      if (this.leaderError) leader.removeListener('error',this.leaderError);
      this.leaderError=undefined;leader.release(); throw error;
    }
  }
  async create(account: string, key: string, sdp: string, language: string, context?: unknown, requestedMilliseconds?: number) {
    if (!this.allows(account)) throw new ServiceError('hosted_voice_not_ready', 503);
    if (!key || key.length < 8 || key.length > 128 || !supportsLanguage(language) || !sdp.startsWith('v=0') || Buffer.byteLength(sdp) > 65_536)
      throw new ServiceError('invalid_live_offer');
    const teachingContext = parseLiveContext(context);
    if (requestedMilliseconds!==undefined && (!Number.isSafeInteger(requestedMilliseconds) || requestedMilliseconds<60_000 || requestedMilliseconds>3_600_000))
      throw new ServiceError('invalid_session_duration');
    const id = randomUUID(), reservation = randomUUID();
    let minutes = this.config.billingUnit === 'milliseconds', paid=false;
    let reservedMilliseconds = TRIAL_MS;
    let paidReserve=this.paidHold(15_000);
    let deadline = new Date(this.now() + reservedMilliseconds);
    await transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const minuteWallet = minutes ? await lockMinuteWallet(sql, account) : undefined;
      let wallet = minuteWallet ?? await lockWallet(sql, account, true);
      if((await sql.query('SELECT 1 FROM minute_guest_link_intents WHERE guest_account_id=$1',[account])).rowCount)
        throw new ServiceError('sign_in_to_continue',403);
      const previous = (await sql.query('SELECT id FROM hosted_sessions WHERE account_id=$1 AND idempotency_key=$2', [account, key])).rows[0];
      // SDP is not persisted. Retrying an offer never starts a second billed call.
      if (previous) throw new ServiceError('live_request_already_created', 409);
      if ((await sql.query(`SELECT id FROM hosted_sessions WHERE account_id=$1 AND ${unresolved}`, [account])).rowCount)
        throw new ServiceError('live_session_unresolved', 409);
      if (!this.publicMinuteAccess && (await sql.query("SELECT id FROM hosted_sessions WHERE state='incomplete' LIMIT 1")).rowCount)
        throw new ServiceError('provider_reconciliation_required', 503);
      const exposure = this.publicMinuteAccess ? 0n : BigInt((await sql.query('SELECT COALESCE(sum(funding_exposure_nano),0) AS total FROM hosted_sessions')).rows[0].total) +
        await hostedHelperExposure(sql);
      if (minutes) {
        if (this.publicMinuteAccess && !minuteWallet!.sandboxReconciled) throw new ServiceError('minute_balance_reconciliation_required',409);
        reservedMilliseconds = Math.min(TRIAL_MS,requestedMilliseconds ?? TRIAL_MS, Number(wallet.balance) - Number(wallet.reserved) -
          (this.publicMinuteAccess ? minuteWallet!.sandbox : 0));
        if (reservedMilliseconds<=0 && this.publicPaidAccess) {
          const cash=await lockPaidWallet(sql,account);
          if (cash.fundedAvailable<this.minimumPaidSessionNanoUSD) throw new ServiceError('insufficient_credit',402);
          let low=15_000,high=requestedMilliseconds ?? 900_000;
          while (low<high) { const middle=Math.ceil((low+high)/2); if (this.paidHold(middle).total<=cash.fundedAvailable) low=middle; else high=middle-1; }
          reservedMilliseconds=low; paidReserve=this.paidHold(low); paid=true; minutes=false; wallet=cash;
        } else if (reservedMilliseconds <= 0) throw new ServiceError('insufficient_minutes', 402);
      }
      if (minutes) {
        const funding = voiceCost(Math.max(15_000, reservedMilliseconds));
        if (!this.publicMinuteAccess && exposure + funding > this.config.lifetimeFundingCapNano) throw new ServiceError('hosted_funding_cap_reached', 503);
        // A bounded provider setup window is separate from the user's remaining conversation time.
        deadline = new Date(this.now() + Math.max(30_000, reservedMilliseconds));
        await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms,public_minutes) VALUES($1,$2,$3,$4,$5)',
          [reservation, account, `hosted:${key}`, reservedMilliseconds,this.publicMinuteAccess]);
        await appendMinuteEntry(sql, account, `minute-reserve:${reservation}`, 'reserve', 0, reservedMilliseconds);
        await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,minute_reservation_id,reserved_ms,rate_version,state,deadline,funding_exposure_nano,minimum_charge_ms,public_minutes,funding_mode)
          VALUES($1,$2,$3,$4,$5,$6,'creating',$7,$8,15000,$9,'minutes')`, [id, account, key, reservation, reservedMilliseconds, RATE_VERSION, deadline, funding.toString(),this.publicMinuteAccess]);
        await this.config.helpers?.reserveSessionBudget(sql, account, id);
      } else if (paid) {
        deadline=new Date(this.now()+Math.max(30_000,reservedMilliseconds));
        await reservePaidInTransaction(sql,account,reservation,`hosted:${key}`,paidReserve.voice,RATE_VERSION);
        await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,reservation_id,rate_version,state,deadline,
          funding_exposure_nano,minimum_charge_ms,funding_mode,limit_ms)
          VALUES($1,$2,$3,$4,$5,'creating',$6,$7,15000,'ai-value',$8)`,
          [id,account,key,reservation,RATE_VERSION,deadline,paidReserve.voice.toString(),reservedMilliseconds]);
        await this.config.helpers!.reserveSessionBudget(sql,account,id);
      } else {
        if (exposure + HOLD > this.config.lifetimeFundingCapNano) throw new ServiceError('hosted_funding_cap_reached', 503);
        if (BigInt(wallet.balance) - BigInt(wallet.reserved) < HOLD) throw new ServiceError('insufficient_credit', 402);
        await sql.query('INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version) VALUES($1,$2,$3,$4,$5)',
          [reservation, account, `hosted:${key}`, HOLD.toString(), RATE_VERSION]);
        await appendEntry(sql, account, `reservation:${reservation}`, 'reserve', 0n, HOLD, RATE_VERSION);
        await sql.query(`INSERT INTO hosted_sessions(id,account_id,idempotency_key,reservation_id,rate_version,state,deadline,funding_exposure_nano)
          VALUES($1,$2,$3,$4,$5,'creating',$6,$7)`, [id, account, key, reservation, RATE_VERSION, deadline, HOLD.toString()]);
      }
    });
    if ((minutes || paid) && !await this.prepareMinuteProviderAttempt(id, account))
      throw new ServiceError('live_session_cancelled', 409);
    let created: { sessionID: string; sdp: string } | undefined;
    let startupStage = 'provider_create';
    try {
      created = await this.provider.create(sdp, language, teachingContext);
      if (minutes || paid) deadline = new Date(this.now() + reservedMilliseconds);
      startupStage = 'persist_provider_session';
      const persisted = await this.db.query("UPDATE hosted_sessions SET provider_session_id=$2,state='active',deadline=$3 WHERE id=$1 AND state<>'closed'", [id, created.sessionID, deadline]);
      if (persisted.rowCount !== 1) throw new ServiceError('provider_session_no_longer_active', 502);
      startupStage = 'provider_attach';
      await this.attach(id, created.sessionID);
      startupStage = 'confirm_active';
      const row = (await this.db.query('SELECT state,close_requested_at FROM hosted_sessions WHERE id=$1', [id])).rows[0];
      if (row.state !== 'active' || row.close_requested_at || !this.accepting) throw new ServiceError('provider_connection_lost', 502);
      return { sessionID: id, providerSessionID: created.sessionID, sdp: created.sdp,
        fundingMode: paid ? 'ai-value' as const : minutes ? 'minutes' as const : undefined,
        deadline: deadline.toISOString(), reservedMilliseconds: minutes ? reservedMilliseconds : undefined,
        limitMilliseconds: paid ? reservedMilliseconds : undefined,
        billingBasis: paid ? 'actual-ai-usage' as const : minutes ? 'connected-conversation-time' as const : undefined,
        minimumChargeMilliseconds: minutes || paid ? 15_000 : undefined,
        billingPolicy: paid ? 'actual-ai-usage-15s-minimum-v1' as const : minutes ? 'connected-time-15s-minimum-v1' as const : undefined,
        reservedNanoUSD: paid ? paidReserve.total.toString() : minutes ? undefined : HOLD.toString(),
        voiceReservedNanoUSD: paid ? paidReserve.voice.toString() : undefined,
        helperReservedNanoUSD: paid ? paidReserve.helper.toString() : undefined,
        helperRateVersion: paid ? this.config.helpers!.paidFundingPolicy!.rateVersion : undefined,
        rateVersion: RATE_VERSION, experimental: true };
    } catch (error) {
      this.reportStartupFailure(error instanceof LiveCreateFailure ? {
        category: error.category, providerStatus: error.providerStatus, requestID: error.requestID
      } : { category: `${startupStage}_failed` });
      if (!created && error instanceof LiveCreateRejectedError) {
        try {
          await this.settleRejectedCreate(id, account, error);
        } catch {
          this.reportStartupFailure({ category: 'rejection_settlement_failed' });
          // Database failure leaves the original reservation intact for reconciliation.
          throw new ServiceError('provider_session_unconfirmed', 502);
        }
        throw error;
      }
      if (created) await this.provider.hangup(created.sessionID).catch(() => {});
      await this.db.query(`UPDATE hosted_sessions SET state='incomplete',close_reason='create_or_attach_uncertain'
        WHERE id=$1 AND state<>'closed'`, [id]).catch(() => {});
      // Never guess a final bill or release this hold before a trusted final event/reconciliation.
      throw new ServiceError('provider_session_unconfirmed', 502);
    }
  }
  private reportStartupFailure(diagnostic: { category: string; providerStatus?: number; requestID?: string }) {
    try { this.config.onStartupFailure?.(diagnostic); } catch { /* Diagnostics cannot change accounting. */ }
  }
  private async settleRejectedCreate(id: string, account: string, rejection: LiveCreateRejectedError): Promise<void> {
    await transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const funding = (await sql.query('SELECT minute_reservation_id FROM hosted_sessions WHERE id=$1 AND account_id=$2', [id, account])).rows[0];
      if (!funding) throw new ServiceError('provider_reconciliation_required', 503);
      if (funding.minute_reservation_id) await lockMinuteWallet(sql, account, false);
      else await lockWallet(sql, account);
      const row = (await sql.query('SELECT * FROM hosted_sessions WHERE id=$1 AND account_id=$2 FOR UPDATE', [id, account])).rows[0];
      if (row.state === 'closed' && row.provider_rejection_status !== null) return;
      if (row.provider_session_id || Number(row.observed_ms) !== 0 || !['creating', 'closing', 'incomplete'].includes(row.state))
        throw new ServiceError('provider_reconciliation_required', 503);
      if (row.minute_reservation_id) {
        const hold = (await sql.query('SELECT * FROM minute_reservations WHERE id=$1 FOR UPDATE', [row.minute_reservation_id])).rows[0];
        if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
        await appendMinuteEntry(sql, account, `minute-finish:${hold.id}`, 'settle', 0, -Number(hold.amount_ms), row.public_minutes ? 'funded' : 'mixed');
        await sql.query("UPDATE minute_reservations SET state='settled',used_ms=0 WHERE id=$1", [hold.id]);
        await recoverMinutePurchaseShortfalls(sql, account);
      } else if (row.funding_mode === 'ai-value') {
        await settlePaidInTransaction(sql, account, row.reservation_id, 0n);
      } else {
        const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [row.reservation_id])).rows[0];
        if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
        await appendEntry(sql, account, `settlement:${hold.id}`, 'settle', 0n, -BigInt(hold.reserved_nano), row.rate_version);
        await sql.query("UPDATE reservations SET state='settled',actual_nano=0 WHERE id=$1", [hold.id]);
      }
      await sql.query(`UPDATE hosted_sessions SET state='closed',provider_cost_nano=0,funding_exposure_nano=0,
        charged_ms=CASE WHEN minute_reservation_id IS NOT NULL THEN 0 ELSE NULL END,
        charged_nano=CASE WHEN reservation_id IS NOT NULL THEN 0 ELSE NULL END,
        close_reason='provider_create_rejected',provider_rejection_status=$2,provider_rejection_request_id=$3 WHERE id=$1`,
        [id, rejection.providerStatus, rejection.requestID ?? null]);
      if (row.funding_mode === 'ai-value') await this.config.helpers!.closeCashBudget!(sql, account, id);
    });
  }
  /** A committed attempt marker precedes the network call. A crash after it stays uncertain;
   * only a durable cancellation before it can release time without a provider final event. */
  private async prepareMinuteProviderAttempt(id: string, account: string, attempt = true): Promise<boolean> {
    return transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const funding=(await sql.query('SELECT funding_mode FROM hosted_sessions WHERE id=$1 AND account_id=$2',[id,account])).rows[0];
      const paid=funding?.funding_mode==='ai-value';
      if (paid) await lockWallet(sql,account); else await lockMinuteWallet(sql, account, false);
      const row = (await sql.query('SELECT * FROM hosted_sessions WHERE id=$1 AND account_id=$2 FOR UPDATE', [id, account])).rows[0];
      if (!row || (!row.minute_reservation_id && !paid))
        throw new ServiceError('provider_reconciliation_required', 503);
      if (row.provider_attempted_at || row.provider_session_id) {
        if (!attempt) return true; // A concurrent create crossed the durable boundary.
        throw new ServiceError('provider_reconciliation_required', 503);
      }
      if (row.state === 'closed') return false;
      if (attempt && this.accepting && row.state === 'creating' && !row.close_requested_at) {
        await sql.query('UPDATE hosted_sessions SET provider_attempted_at=now() WHERE id=$1', [id]);
        return true;
      }
      if (paid) {
        await settlePaidInTransaction(sql,account,row.reservation_id,0n);
        await sql.query(`UPDATE hosted_sessions SET state='closed',charged_nano=0,provider_cost_nano=0,funding_exposure_nano=0,
          close_reason='cancelled_before_provider' WHERE id=$1`,[id]);
        await this.config.helpers!.closeCashBudget!(sql,account,id);
        return false;
      }
      const hold = (await sql.query('SELECT * FROM minute_reservations WHERE id=$1 FOR UPDATE', [row.minute_reservation_id])).rows[0];
      if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
      await appendMinuteEntry(sql, account, `minute-finish:${hold.id}`, 'settle', 0, -Number(hold.amount_ms));
      await sql.query("UPDATE minute_reservations SET state='settled',used_ms=0 WHERE id=$1", [hold.id]);
      await recoverMinutePurchaseShortfalls(sql, account);
      await sql.query(`UPDATE hosted_sessions SET state='closed',charged_ms=0,provider_cost_nano=0,funding_exposure_nano=0,
        close_reason='cancelled_before_provider' WHERE id=$1`, [id]);
      return false;
    });
  }
  private async attach(id: string, providerID: string) {
    if (this.slots.has(id)) return;
    const slot: Slot = { providerID, queue: Promise.resolve(), pending: 0, lastHangup: 0 };
    this.slots.set(id, slot);
    try {
      slot.connection = await this.provider.attach(providerID, event => {
        if (++slot.pending > 100) { void this.connectionLost(id); return; }
        slot.queue = slot.queue.then(() => this.recordUsage(id, providerID, event))
          .catch(() => this.connectionLost(id)).finally(() => { slot.pending--; });
      }, () => { void this.connectionLost(id); });
      await slot.queue;
      if (!this.slots.has(id)) slot.connection.disconnect();
    } catch { this.slots.delete(id); throw new ServiceError('provider_attach_failed', 502); }
  }
  private async recordUsage(id: string, providerID: string, event: VoiceUsage) {
    const state = await transaction(this.db, async sql => {
      // Match admission/helper lock order so final voice cost and reduced helper liability
      // become visible atomically to a new funded conversation.
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const owner = (await sql.query('SELECT account_id,minute_reservation_id FROM hosted_sessions WHERE id=$1', [id])).rows[0];
      if (!owner) throw new ServiceError('unknown_hosted_session');
      if (owner.minute_reservation_id) await lockMinuteWallet(sql, owner.account_id, false);
      else await lockWallet(sql, owner.account_id);
      const row = (await sql.query('SELECT * FROM hosted_sessions WHERE id=$1 FOR UPDATE', [id])).rows[0];
      if (row.provider_session_id !== providerID || row.rate_version !== RATE_VERSION) throw new ServiceError('provider_session_mismatch');
      const meter = new VoiceMeter(providerID, row.limit_ms ? Number(row.limit_ms) : row.reserved_ms ? Number(row.reserved_ms) : TRIAL_MS);
      meter.milliseconds = Number(row.observed_ms); meter.finalized = row.state === 'closed';
      meter.receive(providerID, event);
      if (row.state === 'closed') return { finalized: true, close: false };
      await sql.query('UPDATE hosted_sessions SET observed_ms=$2,funding_exposure_nano=GREATEST(funding_exposure_nano,$3) WHERE id=$1',
        [id, meter.milliseconds, voiceCost(meter.milliseconds).toString()]);
      if (meter.finalized) {
        // WebRTC creation has a 15-second minimum; it is credited against active duration.
        const providerCost = voiceCost(Math.max(15_000, meter.milliseconds));
        if (row.minute_reservation_id) {
          const hold = (await sql.query('SELECT * FROM minute_reservations WHERE id=$1 FOR UPDATE', [row.minute_reservation_id])).rows[0];
          if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
          const maximum = Number(hold.amount_ms), charged = Math.min(maximum, Math.max(Number(row.minimum_charge_ms), meter.milliseconds));
          // New sessions have a disclosed 15-second minimum. Existing session policy is
          // immutable; a final smaller balance is consumed once, and cutoff overrun is ours.
          await appendMinuteEntry(sql, row.account_id, `minute-finish:${hold.id}`, 'settle', -charged, -maximum,row.public_minutes ? 'funded' : 'mixed');
          await sql.query("UPDATE minute_reservations SET state='settled',used_ms=$2 WHERE id=$1", [hold.id, charged]);
          await recoverMinutePurchaseShortfalls(sql, row.account_id);
          await sql.query(`UPDATE hosted_sessions SET state='closed',provider_cost_nano=$2,charged_ms=$3,funding_exposure_nano=$2 WHERE id=$1`,
            [id, providerCost.toString(), charged]);
        } else if (row.funding_mode==='ai-value') {
          const hold=(await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE',[row.reservation_id])).rows[0];
          if (providerCost>BigInt(hold.reserved_nano)) {
            await sql.query(`INSERT INTO hosted_cash_reconciliation(reference,session_id,usage,provider_cost_nano,reason)
              VALUES($1,$2,$3,$4,'voice_hold_exceeded') ON CONFLICT(reference) DO NOTHING`,
              [`voice:${id}`,id,JSON.stringify({milliseconds:meter.milliseconds}),providerCost.toString()]);
            await sql.query("UPDATE hosted_sessions SET state='incomplete',provider_cost_nano=$2,close_reason='voice_hold_exceeded' WHERE id=$1",[id,providerCost.toString()]);
            return {finalized:false,close:true};
          }
          await settlePaidInTransaction(sql,row.account_id,row.reservation_id,providerCost);
          await sql.query("UPDATE hosted_sessions SET state='closed',provider_cost_nano=$2,charged_nano=$2,funding_exposure_nano=$2 WHERE id=$1",[id,providerCost.toString()]);
          await this.config.helpers!.closeCashBudget!(sql,row.account_id,id);
        } else {
          const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [row.reservation_id])).rows[0];
          if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
          const maximum = BigInt(hold.reserved_nano), charged = providerCost > maximum ? maximum : providerCost;
          await appendEntry(sql, row.account_id, `settlement:${hold.id}`, 'settle', -charged, -maximum, row.rate_version);
          await sql.query("UPDATE reservations SET state='settled',actual_nano=$2 WHERE id=$1", [hold.id, charged.toString()]);
          await sql.query(`UPDATE hosted_sessions SET state='closed',provider_cost_nano=$2,charged_nano=$3,funding_exposure_nano=$2 WHERE id=$1`,
            [id, providerCost.toString(), charged.toString()]);
        }
      }
      return { finalized: meter.finalized, close: meter.closeRequested };
    });
    if (state.finalized) { this.slots.get(id)?.connection?.disconnect(); this.slots.delete(id); }
    else if (state.close) await this.requestClose(id, 'usage_limit');
  }
  private async connectionLost(id: string) {
    const slot = this.slots.get(id); slot?.connection?.disconnect(); this.slots.delete(id);
    await this.db.query(`UPDATE hosted_sessions SET state='incomplete',close_requested_at=COALESCE(close_requested_at,$2),close_reason='sideband_lost'
      WHERE id=$1 AND state<>'closed'`, [id, new Date(this.now())]).catch(() => { this.accepting = false; });
    const row = (await this.db.query('SELECT provider_session_id,state FROM hosted_sessions WHERE id=$1', [id]).catch(() => ({ rows: [] }))).rows[0];
    if (row?.provider_session_id && row.state !== 'closed') await this.provider.hangup(row.provider_session_id).catch(() => {});
  }
  async requestClose(id: string, reason: 'user_requested' | 'worker_recovery' | 'usage_limit' | 'deadline' | 'funding_reversed' | 'worker_shutdown' | 'sign_out') {
    await this.db.query(`UPDATE hosted_sessions SET state=CASE WHEN state='incomplete' THEN state ELSE 'closing' END,
      close_requested_at=COALESCE(close_requested_at,$2),close_reason=COALESCE(close_reason,$3) WHERE id=$1 AND state<>'closed'`, [id, new Date(this.now()), reason]);
    try { this.slots.get(id)?.connection?.closeSession(); } catch { await this.connectionLost(id); }
  }
  async status(account: string, id: string) {
    const row = (await this.db.query(`SELECT h.*,r.reserved_nano AS voice_reserved_nano,b.budget_nano AS helper_budget_nano,
      b.cash_pool_nano,b.rate_version AS helper_rate_version,
      (SELECT COALESCE(sum(CASE WHEN q.state='settled' THEN q.cost_nano ELSE 0 END),0) FROM hosted_helper_requests q WHERE q.session_id=h.id) AS helper_charged_nano,
      (SELECT COALESCE(sum(CASE WHEN q.state<>'settled' THEN q.hold_nano ELSE 0 END),0) FROM hosted_helper_requests q WHERE q.session_id=h.id) AS helper_pending_nano
      FROM hosted_sessions h LEFT JOIN reservations r ON r.id=h.reservation_id LEFT JOIN hosted_helper_sessions b ON b.session_id=h.id
      WHERE h.id=$1 AND h.account_id=$2`, [id, account])).rows[0];
    if (!row) throw new ServiceError('live_session_not_found', 404);
    if (row.funding_mode==='ai-value') return {sessionID:row.id,state:row.state,deadline:row.deadline,
      fundingMode:'ai-value' as const,billingBasis:'actual-ai-usage' as const,billingPolicy:'actual-ai-usage-15s-minimum-v1' as const,
      minimumChargeMilliseconds:15000,limitMilliseconds:Number(row.limit_ms),observedMilliseconds:Number(row.observed_ms),
      reservedNanoUSD:(BigInt(row.voice_reserved_nano)+BigInt(row.helper_budget_nano)).toString(),
      voiceReservedNanoUSD:row.voice_reserved_nano,helperReservedNanoUSD:row.helper_budget_nano,
      chargedVoiceNanoUSD:row.charged_nano,chargedHelperNanoUSD:row.helper_charged_nano,
      chargedNanoUSD:row.charged_nano===null ? null : (BigInt(row.charged_nano)+BigInt(row.helper_charged_nano)).toString(),
      helperPendingNanoUSD:row.helper_pending_nano,helperReservedRemainingNanoUSD:(BigInt(row.cash_pool_nano)+BigInt(row.helper_pending_nano)).toString(),
      providerCostNanoUSD:row.provider_cost_nano,rateVersion:row.rate_version,helperRateVersion:row.helper_rate_version};
    return { sessionID: row.id, state: row.state, deadline: row.deadline, observedMilliseconds: Number(row.observed_ms),
      fundingMode:row.minute_reservation_id ? 'minutes' as const : undefined,
      reservedMilliseconds: row.reserved_ms ? Number(row.reserved_ms) : undefined,
      chargedMilliseconds: row.reserved_ms ? row.charged_ms === null ? null : Number(row.charged_ms) : undefined,
      billingBasis: row.reserved_ms ? 'connected-conversation-time' as const : undefined,
      minimumChargeMilliseconds: row.reserved_ms ? Number(row.minimum_charge_ms) : undefined,
      billingPolicy: row.reserved_ms ? Number(row.minimum_charge_ms) === 15_000 ? 'connected-time-15s-minimum-v1' : 'connected-time-only-v1' : undefined,
      chargedNanoUSD: row.reserved_ms ? undefined : row.charged_nano, providerCostNanoUSD: row.provider_cost_nano };
  }
  async current(account: string) {
    const row = (await this.db.query("SELECT id FROM hosted_sessions WHERE account_id=$1 AND state<>'closed'", [account])).rows[0];
    return { session: row ? await this.status(account, row.id) : null };
  }
  async close(account: string, id: string) { await this.status(account, id); await this.requestClose(id, 'user_requested'); return this.status(account, id); }
  async tick(): Promise<void> {
    if (this.ticking || !this.leader) return; this.ticking = true;
    try {
      await this.leader.query('SELECT 1');
      const rows = (await this.db.query(`SELECT h.*,w.balance_nano,w.reserved_nano,w.sandbox_balance_nano,
        EXISTS(SELECT 1 FROM minute_purchase_transactions p WHERE p.account_id=h.account_id
          AND (NOT h.public_minutes OR p.environment='live') AND p.recovered_ms<LEAST(p.reversal_target_ms,p.granted_ms)) AS minute_refund_due
        FROM hosted_sessions h LEFT JOIN wallets w ON w.account_id=h.account_id WHERE h.state<>'closed'`)).rows;
      for (const row of rows) {
        if (!row.provider_session_id) {
          if (Number(row.minimum_charge_ms) === 15_000 && !row.provider_attempted_at &&
            (row.close_requested_at || this.now() >= new Date(row.deadline).getTime()))
            await this.prepareMinuteProviderAttempt(row.id, row.account_id, false);
          continue;
        }
        if (row.state === 'incomplete' && !this.slots.has(row.id)) {
          try { await this.attach(row.id, row.provider_session_id); } catch { /* Keep the hold and retry closure. */ }
        }
        if (row.close_requested_at) await this.requestClose(row.id, row.close_reason === 'sign_out' ? 'sign_out' : 'user_requested');
        else if (row.minute_reservation_id ? row.minute_refund_due : BigInt(row.balance_nano)-(row.funding_mode==='ai-value' ? BigInt(row.sandbox_balance_nano) : 0n) < BigInt(row.reserved_nano)) await this.requestClose(row.id, 'funding_reversed');
        else if (this.now() >= new Date(row.deadline).getTime()) await this.requestClose(row.id, 'deadline');
        if (row.close_requested_at && this.now() - new Date(row.close_requested_at).getTime() >= this.grace) {
          const slot = this.slots.get(row.id);
          if (!slot || this.now() - slot.lastHangup >= this.grace) {
            if (slot) slot.lastHangup = this.now();
            await this.provider.hangup(row.provider_session_id).catch(() => {});
          }
          // An HTTP 2xx hangup is not a final usage event. Keep the reservation unresolved.
          await this.db.query("UPDATE hosted_sessions SET state='incomplete' WHERE id=$1 AND state<>'closed'", [row.id]);
        }
      }
    } finally { this.ticking = false; }
  }
  private async emergencyClose() {
    for (const slot of this.slots.values()) { try { slot.connection?.closeSession(); } catch { /* Try the HTTP control below. */ } }
    // These IDs remain available even when PostgreSQL is unreachable.
    await Promise.allSettled([...this.slots.values()].map(slot => this.provider.hangup(slot.providerID)));
    const rows = (await this.db.query(`SELECT provider_session_id FROM hosted_sessions WHERE ${unresolved}`).catch(() => ({ rows: [] }))).rows;
    await Promise.allSettled(rows.filter(row => row.provider_session_id).map(row => this.provider.hangup(row.provider_session_id)));
  }
  /** Stops accepting first, requests closure, and leaves unfinished records recoverable. */
  async stop(): Promise<void> {
    this.accepting = false; if (this.timer) clearInterval(this.timer);
    await this.emergencyClose();
    await Promise.all([...this.slots.values()].map(slot => slot.queue));
    for (const slot of this.slots.values()) slot.connection?.disconnect();
    this.slots.clear();
    if (this.leader) {
      await this.leader.query("SELECT pg_advisory_unlock(hashtext('mural-hosted-voice-worker'))").catch(() => {});
      if (this.leaderError) this.leader.removeListener('error',this.leaderError);
      this.leaderError=undefined;
      this.leader.release(); this.leader = undefined;
    }
  }
}
