import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { HelperSessionLimitError, ServiceError } from './errors.js';
import { RATE_VERSION } from './pricing.js';
import { randomUUID } from 'node:crypto';
import { appendEntry, lockPaidWallet, lockWallet, reservePaidInTransaction, settlePaidInTransaction } from './ledger.js';

export const HOSTED_HELPER_MODEL = 'gpt-5.6-luna';
export const HOSTED_HELPER_RATE_VERSION = `${RATE_VERSION}-helper-cache-write-long-context-v1`;
export const HOSTED_HELPER_BODY_LIMIT = 65_536;
export const HOSTED_HELPER_PURPOSES = ['meaning', 'assessment', 'lookup', 'delegation', 'typed_reply', 'topic', 'help'] as const;
export type HostedHelperPurpose = typeof HOSTED_HELPER_PURPOSES[number];
type JSONValue = null | boolean | number | string | JSONValue[] | { [key: string]: JSONValue };
type JSONObject = { [key: string]: JSONValue };
export interface HostedHelperInput {
  requestID: string; purpose: HostedHelperPurpose; instructions: string; input: string; schema?: JSONObject; search?: boolean;
}
export interface HostedHelperConfig {
  accountAllowlist: ReadonlySet<string>;
  aggregateFundingCapNano: bigint;
  publicMinuteAccess?: boolean;
  publicPaidAccess?: boolean;
  helperBudgetNanoPerMinute: bigint;
  maxRequestsPerMinute: number;
  maxSearchesPerSession: number;
  maxConcurrentPerSession: number;
  maxConcurrentGlobal: number;
  postSessionMilliseconds: number;
  /** Conservative framing allowance on top of every byte of the serialized request. */
  inputFramingTokenAllowance: number;
  /** Conservative extra input allowance for one built-in search, reviewed before enabling it. */
  searchInputTokenAllowance: number;
  timeoutMilliseconds: number;
}
export interface HostedResponsesRequest {
  model: typeof HOSTED_HELPER_MODEL; store: false; background: false; stream: boolean;
  service_tier: 'default'; prompt_cache_options: { mode: 'explicit' };
  instructions: string; input: [{ role: 'user'; content: string }];
  max_output_tokens: 1400 | 2200; reasoning: { effort: 'low' };
  text?: { format: { type: 'json_schema'; name: 'mural_result'; strict: true; schema: JSONObject } };
  tools?: [{ type: 'web_search'; search_context_size: 'low' }]; tool_choice: 'auto' | 'none'; max_tool_calls: 1;
}
/** One network attempt only. The implementation must honor the signal, bound the body and never retry. */
export interface HostedResponsesTransport { send(body: HostedResponsesRequest, signal: AbortSignal, onText?: (text: string) => void): Promise<unknown> }
export interface HostedHelperUsage { inputTokens: number; cachedInputTokens: number; cacheWriteTokens: number; outputTokens: number; searchCalls: number }
export interface HostedHelperResult {
  requestID: string; text: string; sources: Array<{ title: string; url: string }>;
  usage: HostedHelperUsage; costNanoUSD: string; rateVersion: string;
}
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const object = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value) && !Buffer.isBuffer(value);
const integer = (value: unknown, min: number, max: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max;
const validText = (value: unknown, bytes: number): value is string => typeof value === 'string' && Boolean(value.trim()) && Buffer.byteLength(value) <= bytes &&
  !/[\uD800-\uDFFF]/u.test(value) && !/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value);
const invalid = () => new ServiceError('invalid_hosted_helper_request');

/** Accept the Android assessment schema subset, without references, remote schemas or executable tools. */
function validateSchema(value: unknown, depth = 0, counter = { nodes: 0 }): void {
  if (!object(value) || ++counter.nodes > 400 || depth > 8) throw invalid();
  const allowed = new Set(['type', 'properties', 'required', 'additionalProperties', 'items', 'enum', 'minimum', 'maximum', 'minItems', 'maxItems', 'minLength', 'maxLength', 'description']);
  if (Object.keys(value).some(key => !allowed.has(key)) || !['object', 'array', 'string', 'integer', 'number', 'boolean', 'null'].includes(String(value.type))) throw invalid();
  if (value.description !== undefined && !validText(value.description, 1000)) throw invalid();
  if (value.type === 'object') {
    if (!object(value.properties) || Object.keys(value.properties).length > 60 || value.additionalProperties !== false || !Array.isArray(value.required) ||
      new Set(value.required).size !== value.required.length || value.required.length !== Object.keys(value.properties).length ||
      value.required.some(key => typeof key !== 'string' || !Object.hasOwn(value.properties as object, key))) throw invalid();
    for (const [key, child] of Object.entries(value.properties)) {
      if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key)) throw invalid();
      validateSchema(child, depth + 1, counter);
    }
  } else if (value.properties !== undefined || value.required !== undefined || value.additionalProperties !== undefined) throw invalid();
  if (value.type === 'array') validateSchema(value.items, depth + 1, counter);
  else if (value.items !== undefined) throw invalid();
  if (value.enum !== undefined && (!Array.isArray(value.enum) || value.enum.length < 1 || value.enum.length > 80 ||
    value.enum.some(entry => !['string', 'number', 'boolean'].includes(typeof entry) || (typeof entry === 'string' && !validText(entry, 300)) || (typeof entry === 'number' && !Number.isFinite(entry))))) throw invalid();
  for (const key of ['minimum', 'maximum']) if (value[key] !== undefined && (typeof value[key] !== 'number' || !Number.isFinite(value[key]) || Math.abs(value[key] as number) > 1_000_000)) throw invalid();
  for (const key of ['minItems', 'maxItems', 'minLength', 'maxLength']) if (value[key] !== undefined && !integer(value[key], 0, 10_000)) throw invalid();
}
export function parseHostedHelperInput(body: unknown): HostedHelperInput {
  if (!object(body) || Object.keys(body).some(key => !['requestID', 'purpose', 'instructions', 'input', 'schema', 'search'].includes(key)) ||
    typeof body.requestID !== 'string' || !UUID.test(body.requestID) || !(HOSTED_HELPER_PURPOSES as readonly unknown[]).includes(body.purpose) ||
    !validText(body.instructions, 16_384) || !validText(body.input, 24_576) || (body.search !== undefined && typeof body.search !== 'boolean')) throw invalid();
  if (body.search === true && body.purpose !== 'delegation' && body.purpose !== 'topic') throw invalid();
  if (body.schema !== undefined) {
    if (body.purpose !== 'assessment') throw invalid();
    validateSchema(body.schema);
    if ((body.schema as JSONObject).type !== 'object' || Buffer.byteLength(JSON.stringify(body.schema)) > 12_288) throw invalid();
  }
  if (body.purpose === 'assessment' && body.schema === undefined) throw invalid();
  let serialized: string;
  try { serialized = JSON.stringify(body); } catch { throw invalid(); }
  if (Buffer.byteLength(serialized) > HOSTED_HELPER_BODY_LIMIT) throw invalid();
  // Snapshot transient values before any database await; caller mutation cannot change a reserved call.
  const result = JSON.parse(serialized) as HostedHelperInput; result.requestID = result.requestID.toLowerCase();
  return result;
}
export function hostedHelperBody(input: HostedHelperInput, stream = false): HostedResponsesRequest {
  return { model: HOSTED_HELPER_MODEL, store: false, background: false, stream, service_tier: 'default',
    prompt_cache_options: { mode: 'explicit' }, instructions: input.instructions, input: [{ role: 'user', content: input.input }],
    max_output_tokens: input.schema ? 2200 : 1400, reasoning: { effort: 'low' }, tool_choice: input.search ? 'auto' : 'none', max_tool_calls: 1,
    ...(input.schema ? { text: { format: { type: 'json_schema' as const, name: 'mural_result' as const, strict: true as const, schema: input.schema } } } : {}),
    ...(input.search ? { tools: [{ type: 'web_search' as const, search_context_size: 'low' as const }] as [{ type: 'web_search'; search_context_size: 'low' }] } : {}) };
}
/** Published standard-tier rates, including cache writes and the long-context threshold. */
export function hostedHelperCost(usage: HostedHelperUsage): bigint {
  const values = [usage.inputTokens, usage.cachedInputTokens, usage.cacheWriteTokens, usage.outputTokens, usage.searchCalls];
  if (values.some(value => !integer(value, 0, 100_000_000)) || usage.cachedInputTokens + usage.cacheWriteTokens > usage.inputTokens) throw new ServiceError('helper_usage_invalid', 502);
  const long = usage.inputTokens > 272_000;
  const input = BigInt(usage.inputTokens - usage.cachedInputTokens - usage.cacheWriteTokens) * 200n +
    BigInt(usage.cachedInputTokens) * 20n + BigInt(usage.cacheWriteTokens) * 250n;
  return input * (long ? 2n : 1n) + BigInt(usage.outputTokens) * (long ? 1800n : 1200n) + BigInt(usage.searchCalls) * 10_000_000n;
}
export async function hostedHelperExposure(sql: Pick<PoolClient, 'query'>): Promise<bigint> {
  return BigInt((await sql.query('SELECT COALESCE(sum(liability_nano),0) AS total FROM hosted_helper_sessions')).rows[0].total);
}
function validateConfig(config: HostedHelperConfig): void {
  if ((config.publicPaidAccess && !config.publicMinuteAccess) || (!config.publicMinuteAccess && (!config.accountAllowlist.size || typeof config.aggregateFundingCapNano !== 'bigint' ||
    config.aggregateFundingCapNano <= 0n || config.aggregateFundingCapNano > 100_000_000_000n)) ||
    [...config.accountAllowlist].some(id => !UUID.test(id)) ||
    typeof config.helperBudgetNanoPerMinute !== 'bigint' || config.helperBudgetNanoPerMinute <= 0n || config.helperBudgetNanoPerMinute > 1_000_000_000n ||
    !integer(config.maxRequestsPerMinute, 1, 60) || !integer(config.maxSearchesPerSession, 0, 3) ||
    !integer(config.maxConcurrentPerSession, 1, 3) || !integer(config.maxConcurrentGlobal, 1, 20) ||
    !integer(config.postSessionMilliseconds, 0, 120_000) || !integer(config.inputFramingTokenAllowance, 4096, 65_536) ||
    !integer(config.searchInputTokenAllowance, 1_050_000, 4_200_000) || !integer(config.timeoutMilliseconds, 50, 60_000))
    throw new ServiceError('invalid_hosted_helper_configuration', 503);
}

/** Funded, one-shot gateway. Content lives only in this request's memory and the provider call. */
export class HostedHelpers {
  private readonly config: HostedHelperConfig;
  constructor(private readonly db: Database, private readonly transport: HostedResponsesTransport, config: HostedHelperConfig) {
    validateConfig(config); this.config = Object.freeze({ ...config, accountAllowlist: new Set(config.accountAllowlist) });
  }
  get available(): boolean { return true; }
  get paidFundingPolicy() { return { enabled: this.config.publicPaidAccess===true,
    helperBudgetNanoPerMinute: this.config.helperBudgetNanoPerMinute, rateVersion: HOSTED_HELPER_RATE_VERSION }; }
  allows(account: string): boolean { return this.config.publicMinuteAccess===true || this.config.accountAllowlist.has(account); }
  /** Call inside voice admission's transaction after inserting its minute-funded session, before provider creation. */
  async reserveSessionBudget(sql: PoolClient, account: string, sessionID: string): Promise<void> {
    if (!this.allows(account)) throw new ServiceError('hosted_helpers_not_ready', 503);
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
    const owner=(await sql.query('SELECT funding_mode FROM hosted_sessions WHERE id=$1 AND account_id=$2',[sessionID,account])).rows[0];
    if (owner?.funding_mode==='ai-value') await lockPaidWallet(sql,account);
    const session = (await sql.query(`SELECT h.*,a.deleted_at,r.account_id AS minute_owner,r.amount_ms AS minute_amount,r.state AS minute_state
      FROM hosted_sessions h JOIN accounts a ON a.id=h.account_id LEFT JOIN minute_reservations r ON r.id=h.minute_reservation_id
      WHERE h.id=$1 AND h.account_id=$2 FOR UPDATE OF h`, [sessionID, account])).rows[0];
    if (!session || session.deleted_at) throw new ServiceError('live_session_not_found', 404);
    const paid = session.funding_mode==='ai-value';
    if (paid) {
      if (!this.config.publicPaidAccess) throw new ServiceError('hosted_paid_not_ready',503);
    }
    if (this.config.publicMinuteAccess && !session.public_minutes && !paid) throw new ServiceError('helper_session_funding_unavailable',409);
    if (!['creating','active'].includes(session.state) || (paid ? !session.limit_ms : !session.reserved_ms || session.minute_owner !== account ||
      Number(session.minute_amount) !== Number(session.reserved_ms) || session.minute_state !== 'open'))
      throw new ServiceError('helper_session_funding_unavailable', 409);
    await this.ensureBudget(sql, session);
  }
  async request(account: string, sessionID: string, body: unknown, onText?: (text: string) => void): Promise<HostedHelperResult> {
    if (!this.allows(account)) throw new ServiceError('hosted_helpers_not_ready', 503);
    if (!UUID.test(sessionID)) throw invalid();
    const input = parseHostedHelperInput(body);
    if (onText && input.purpose !== 'meaning') throw invalid();
    const providerBody = hostedHelperBody(input, Boolean(onText));
    const reservation = await this.reserve(account, sessionID, input, providerBody);
    let raw: unknown;
    const controller = new AbortController();
    let timeout: NodeJS.Timeout | undefined;
    try {
      if (Date.now() >= reservation.active_until.getTime()) throw new Error('Reserved request deadline passed.');
      raw = await Promise.race([this.transport.send(providerBody, controller.signal, onText), new Promise<never>((_, reject) => {
        timeout = setTimeout(() => { controller.abort(); reject(new Error('Provider deadline exceeded.')); },
          Math.min(reservation.timeout_ms, reservation.active_until.getTime() - Date.now()));
      })]);
    } catch {
      await this.uncertain(input.requestID);
      throw new ServiceError('helper_response_uncertain', 502);
    } finally { if (timeout) clearTimeout(timeout); }
    let observed: ObservedResponse;
    try { observed = observedResponse(raw); }
    catch { await this.uncertain(input.requestID); throw new ServiceError('helper_response_uncertain', 502); }
    const breached = observed.usage.inputTokens > reservation.input_token_ceiling || observed.usage.outputTokens > providerBody.max_output_tokens ||
      observed.usage.searchCalls > (input.search ? 1 : 0);
    const charge = hostedHelperCost(observed.usage);
    try { await this.settle(input.requestID, sessionID, observed, charge, breached); }
    catch { await this.uncertain(input.requestID); throw new ServiceError('helper_response_uncertain', 502); }
    if (breached) throw new ServiceError('helper_provider_limit_exceeded', 503);
    const output = outputText(raw);
    return { requestID: input.requestID, ...output, usage: observed.usage, costNanoUSD: charge.toString(), rateVersion: HOSTED_HELPER_RATE_VERSION };
  }
  private async reserve(account: string, sessionID: string, input: HostedHelperInput, providerBody: HostedResponsesRequest) {
    return transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const owner = (await sql.query('SELECT funding_mode FROM hosted_sessions WHERE id=$1 AND account_id=$2', [sessionID,account])).rows[0];
      const paidWallet = owner?.funding_mode==='ai-value' ? await lockPaidWallet(sql,account) : undefined;
      const session = (await sql.query(`SELECT h.*,a.deleted_at,r.account_id AS minute_owner,r.amount_ms AS minute_amount,r.state AS minute_state,
        EXISTS(SELECT 1 FROM minute_purchase_transactions p WHERE p.account_id=h.account_id
          AND (NOT h.public_minutes OR p.environment='live') AND p.recovered_ms<LEAST(p.reversal_target_ms,p.granted_ms)) AS refund_due,now() AS database_now
        FROM hosted_sessions h JOIN accounts a ON a.id=h.account_id LEFT JOIN minute_reservations r ON r.id=h.minute_reservation_id
        WHERE h.id=$1 AND h.account_id=$2 FOR UPDATE OF h`, [sessionID, account])).rows[0];
      if (!session || session.deleted_at) throw new ServiceError('live_session_not_found', 404);
      const paid = session.funding_mode==='ai-value';
      if (paid && !this.config.publicPaidAccess) throw new ServiceError('hosted_paid_not_ready',503);
      if (this.config.publicMinuteAccess && !session.public_minutes && !paid) throw new ServiceError('helper_session_funding_unavailable',409);
      if (!paid && (!session.minute_reservation_id || !session.reserved_ms)) throw new ServiceError('helper_minute_session_required', 409);
      if (paid ? !paidWallet || paidWallet.fundedBalance < paidWallet.reserved :
        session.minute_owner !== account || Number(session.minute_amount) !== Number(session.reserved_ms) || session.refund_due ||
        (session.state === 'active' ? session.minute_state !== 'open' : session.minute_state !== 'settled'))
        throw new ServiceError('helper_session_funding_unavailable', 409);
      if ((await sql.query('SELECT request_id FROM hosted_helper_requests WHERE request_id=$1', [input.requestID])).rowCount)
        throw new ServiceError('helper_request_already_attempted', 409);
      if ((await sql.query('SELECT request_id FROM hosted_helper_requests WHERE limit_breached LIMIT 1')).rowCount)
        throw new ServiceError('helper_provider_reconciliation_required', 503);
      const now = session.database_now.getTime();
      let budget = (await sql.query('SELECT * FROM hosted_helper_sessions WHERE session_id=$1 FOR UPDATE', [sessionID])).rows[0];
      const post = budget ? budget.post_session_ms : this.config.postSessionMilliseconds;
      const active = session.state === 'active' && !session.close_requested_at && now < session.deadline.getTime();
      const afterConversation = ['assessment', 'meaning', 'lookup'].includes(input.purpose) && session.state === 'closed' && session.helper_closed_at &&
        now < session.helper_closed_at.getTime() + post && now < session.deadline.getTime() + post;
      if ((!active && !afterConversation) || (budget && (budget.activation_pending || budget.state !== 'open' || now >= budget.expires_at.getTime())))
        throw new ServiceError('helper_session_window_closed', 409);
      if (!budget) budget = await this.ensureBudget(sql, session);
      if (budget.rate_version !== HOSTED_HELPER_RATE_VERSION) throw new ServiceError('helper_rate_review_required', 503);
      const earned = budget.earned_time ? earnedMilliseconds(session) : Number(session.reserved_ms);
      const available = budget.earned_time ? BigInt(earned) * BigInt(budget.per_minute_nano) / 60_000n : BigInt(budget.budget_nano);
      const requestLimit = budget.earned_time ? Math.min(budget.request_limit,
        Math.max(1, Math.ceil(earned * budget.requests_per_minute / 60_000))) : budget.request_limit;
      const counts = (await sql.query(`SELECT count(*)::integer AS attempts,
        count(*) FILTER(WHERE search_requested)::integer AS searches,
        count(*) FILTER(WHERE state<>'settled' AND active_until>now())::integer AS pending,
        COALESCE(sum(CASE WHEN state='settled' THEN cost_nano ELSE hold_nano END),0) AS exposure
        FROM hosted_helper_requests WHERE session_id=$1`, [sessionID])).rows[0];
      if (input.search && counts.searches >= budget.search_limit) throw new HelperSessionLimitError();
      if (counts.attempts >= requestLimit)
        throw new HelperSessionLimitError(earnedRequestRetryDelay(session, budget, counts.attempts, active));
      const pendingGlobal = Number((await sql.query("SELECT count(*) AS total FROM hosted_helper_requests WHERE state<>'settled' AND active_until>now()")).rows[0].total);
      if (counts.pending >= budget.concurrency_limit || pendingGlobal >= this.config.maxConcurrentGlobal) throw new ServiceError('helper_concurrency_limit', 429);
      const inputCeiling = Buffer.byteLength(JSON.stringify(providerBody)) + budget.framing_tokens + (input.search ? budget.search_input_tokens : 0);
      const hold = hostedHelperCost({ inputTokens: inputCeiling, cachedInputTokens: 0, cacheWriteTokens: inputCeiling,
        outputTokens: providerBody.max_output_tokens, searchCalls: input.search ? 1 : 0 });
      if (BigInt(counts.exposure) + hold > available) throw new ServiceError('helper_budget_exhausted', 429);
      const activeUntil = new Date(now + Number(budget.timeout_ms) + 5000);
      let cashReservation: string | null = null;
      if (paid) {
        if (BigInt(budget.cash_pool_nano)<hold) throw new ServiceError('helper_budget_exhausted',429);
        cashReservation=randomUUID();
        await appendEntry(sql,account,`helper-pool-allocate:${input.requestID}`,'release',0n,-hold,budget.rate_version);
        await sql.query('UPDATE hosted_helper_sessions SET cash_pool_nano=cash_pool_nano-$2 WHERE session_id=$1',[sessionID,hold.toString()]);
        await reservePaidInTransaction(sql,account,cashReservation,`helper:${input.requestID}`,hold,budget.rate_version);
      }
      await sql.query(`INSERT INTO hosted_helper_requests(request_id,session_id,purpose,search_requested,input_token_ceiling,output_token_ceiling,hold_nano,active_until,cash_reservation_id)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, [input.requestID, sessionID, input.purpose, Boolean(input.search), inputCeiling, providerBody.max_output_tokens, hold.toString(), activeUntil,cashReservation]);
      return { timeout_ms: Number(budget.timeout_ms), input_token_ceiling: inputCeiling, active_until: activeUntil };
    });
  }
  private async ensureBudget(sql: PoolClient, session: any) {
    const previous = (await sql.query('SELECT * FROM hosted_helper_sessions WHERE session_id=$1 FOR UPDATE', [session.id])).rows[0];
    if (previous) return previous;
    const paid=session.funding_mode==='ai-value', duration=Number(paid ? session.limit_ms : session.reserved_ms);
    const amount = (BigInt(duration) * this.config.helperBudgetNanoPerMinute + 59_999n) / 60_000n;
    const earnedTime = Number(session.minimum_charge_ms) > 0;
    const postClose = earnedTime && session.state === 'closed'
      ? BigInt(earnedMilliseconds(session)) * this.config.helperBudgetNanoPerMinute / 60_000n : null;
    const liability = postClose ?? amount;
    if (!this.config.publicMinuteAccess && !paid) {
      const voice = BigInt((await sql.query('SELECT COALESCE(sum(funding_exposure_nano),0) AS total FROM hosted_sessions')).rows[0].total);
      if (voice + await hostedHelperExposure(sql) + liability > this.config.aggregateFundingCapNano)
        throw new ServiceError('hosted_funding_cap_reached', 503);
    }
    if (paid) {
      const wallet=await lockPaidWallet(sql,session.account_id);
      if (wallet.fundedAvailable<amount) throw new ServiceError('insufficient_credit',402);
      await appendEntry(sql,session.account_id,`helper-pool-open:${session.id}`,'reserve',0n,amount,HOSTED_HELPER_RATE_VERSION);
    }
    return (await sql.query(`INSERT INTO hosted_helper_sessions(session_id,reserved_ms,per_minute_nano,budget_nano,liability_nano,
      request_limit,search_limit,concurrency_limit,post_session_ms,framing_tokens,search_input_tokens,timeout_ms,rate_version,activation_pending,expires_at,
      earned_time,requests_per_minute,post_close_budget_nano,cash_funded,cash_pool_nano)
      VALUES($1,$2,$3,$4,$15,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$16,$17,$18,$19,$20) RETURNING *`,
    [session.id, duration, this.config.helperBudgetNanoPerMinute.toString(), amount.toString(),
      Math.max(1, Math.ceil(duration * this.config.maxRequestsPerMinute / 60_000)), this.config.maxSearchesPerSession,
      this.config.maxConcurrentPerSession, this.config.postSessionMilliseconds, this.config.inputFramingTokenAllowance, this.config.searchInputTokenAllowance,
      this.config.timeoutMilliseconds, HOSTED_HELPER_RATE_VERSION, session.state === 'creating',
      new Date(session.deadline.getTime() + this.config.postSessionMilliseconds), liability.toString(), earnedTime,
      this.config.maxRequestsPerMinute, postClose?.toString() ?? null,paid,paid ? amount.toString() : '0'])).rows[0];
  }
  private async uncertain(requestID: string) {
    // A failed write leaves 'pending'. Both states retain funding; concurrency expires at active_until.
    await this.db.query("UPDATE hosted_helper_requests SET state='uncertain',finished_at=now() WHERE request_id=$1 AND state='pending'", [requestID]).catch(() => {});
  }
  private async settle(requestID: string, sessionID: string, observed: ObservedResponse, charge: bigint, breached: boolean) {
    const overrun = await transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const session=(await sql.query('SELECT account_id,funding_mode FROM hosted_sessions WHERE id=$1',[sessionID])).rows[0];
      if (!session) throw new ServiceError('live_session_not_found',404);
      if (session.funding_mode==='ai-value') await lockWallet(sql,session.account_id);
      const budget = (await sql.query('SELECT * FROM hosted_helper_sessions WHERE session_id=$1 FOR UPDATE', [sessionID])).rows[0];
      const attempt=(await sql.query('SELECT * FROM hosted_helper_requests WHERE request_id=$1 AND session_id=$2 FOR UPDATE',[requestID,sessionID])).rows[0];
      if (!attempt || attempt.state!=='pending') throw new ServiceError('helper_settlement_conflict',503);
      if (attempt.cash_reservation_id && charge>BigInt(attempt.hold_nano)) {
        await sql.query(`INSERT INTO hosted_cash_reconciliation(reference,session_id,request_id,provider_response_id,usage,provider_cost_nano,reason)
          VALUES($1,$2,$3,$4,$5,$6,'helper_hold_exceeded') ON CONFLICT(reference) DO NOTHING`,
          [`helper:${requestID}`,sessionID,requestID,observed.id,JSON.stringify(observed.usage),charge.toString()]);
        await sql.query('UPDATE hosted_helper_requests SET limit_breached=true WHERE request_id=$1',[requestID]);
        return true;
      }
      if (attempt.cash_reservation_id) await settlePaidInTransaction(sql,session.account_id,attempt.cash_reservation_id,charge);
      const updated = await sql.query(`UPDATE hosted_helper_requests SET state='settled',provider_response_id=$2,input_tokens=$3,cached_input_tokens=$4,
        cache_write_tokens=$5,output_tokens=$6,search_calls=$7,cost_nano=$8,limit_breached=$9,finished_at=now()
        WHERE request_id=$1 AND state='pending'`, [requestID, observed.id, observed.usage.inputTokens, observed.usage.cachedInputTokens,
        observed.usage.cacheWriteTokens, observed.usage.outputTokens, observed.usage.searchCalls, charge.toString(), breached]);
      if (updated.rowCount !== 1) throw new ServiceError('helper_settlement_conflict', 503);
      await refreshLiability(sql, budget);
      if (budget.cash_funded) await syncCashPool(sql,session.account_id,budget,`settle:${requestID}`);
      return false;
    });
    if (overrun) throw new ServiceError('helper_provider_limit_exceeded',503);
  }
  /** Voice finalization shrinks future allowance; requests already sent retain separate cash holds. */
  async closeCashBudget(sql: PoolClient, account: string, sessionID: string) {
    const budget=(await sql.query('SELECT * FROM hosted_helper_sessions WHERE session_id=$1 FOR UPDATE',[sessionID])).rows[0];
    if (budget?.cash_funded) await syncCashPool(sql,account,budget,'voice-close');
  }
  /** Release only unused session allowance; unresolved provider attempts keep their holds indefinitely. */
  async expireBudgets(): Promise<number> {
    return transaction(this.db, async sql => {
      await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
      const rows = (await sql.query(`SELECT b.*,h.account_id FROM hosted_helper_sessions b JOIN hosted_sessions h ON h.id=b.session_id
        WHERE b.state='open' AND (NOT b.activation_pending OR h.state<>'creating')
          AND (b.expires_at<=now() OR (h.helper_closed_at IS NOT NULL AND
          h.helper_closed_at + b.post_session_ms * interval '1 millisecond' <= now()))`)).rows;
      for (const budget of rows) {
        if (budget.cash_funded) await lockWallet(sql,budget.account_id);
        await sql.query('SELECT session_id FROM hosted_helper_sessions WHERE session_id=$1 FOR UPDATE',[budget.session_id]);
        await sql.query("UPDATE hosted_helper_sessions SET state='expired' WHERE session_id=$1", [budget.session_id]);
        await refreshLiability(sql, { ...budget, state: 'expired' });
        if (budget.cash_funded) await syncCashPool(sql,budget.account_id,{...budget,state:'expired'},'expire');
      }
      return rows.length;
    });
  }
}
async function syncCashPool(sql: PoolClient,account: string,budget: any,event: string) {
  const wallet=await lockWallet(sql,account);
  const exposure=BigInt((await sql.query(`SELECT COALESCE(sum(CASE WHEN state='settled' THEN cost_nano ELSE hold_nano END),0) AS total
    FROM hosted_helper_requests WHERE session_id=$1`,[budget.session_id])).rows[0].total);
  const allowance=BigInt(budget.post_close_budget_nano ?? budget.budget_nano);
  const current=BigInt(budget.cash_pool_nano);
  let desired=budget.state==='open' && allowance>exposure ? allowance-exposure : 0n;
  // A refund can consume backing while a request is in flight. Never re-reserve unbacked funds.
  if (desired>current && desired-current>wallet.fundedAvailable) desired=current+wallet.fundedAvailable;
  const delta=desired-current;
  if (delta) {
    await appendEntry(sql,account,`helper-pool:${budget.session_id}:${event}`,delta>0n?'reserve':'release',0n,delta,budget.rate_version);
    await sql.query('UPDATE hosted_helper_sessions SET cash_pool_nano=$2 WHERE session_id=$1',[budget.session_id,desired.toString()]);
  }
}
async function refreshLiability(sql: PoolClient, budget: any): Promise<void> {
  const value = BigInt((await sql.query(`SELECT COALESCE(sum(CASE WHEN state='settled' THEN cost_nano ELSE hold_nano END),0) AS total
    FROM hosted_helper_requests WHERE session_id=$1`, [budget.session_id])).rows[0].total);
  const allowance = BigInt(budget.post_close_budget_nano ?? budget.budget_nano);
  const amount = budget.state === 'open' && allowance > value ? allowance : value;
  await sql.query('UPDATE hosted_helper_sessions SET liability_nano=$2 WHERE session_id=$1', [budget.session_id, amount.toString()]);
}
function earnedMilliseconds(session: any): number {
  const paid=session.funding_mode==='ai-value';
  const value = session.state === 'closed' ? paid ? session.provider_attempted_at && !session.provider_rejection_status ? Math.max(15_000,Number(session.observed_ms)) : 0 : session.charged_ms
    : Math.max(Number(session.minimum_charge_ms), Number(session.observed_ms));
  if (value === null || !Number.isSafeInteger(Number(value)) || Number(value) < 0)
    throw new ServiceError('helper_session_funding_unavailable', 409);
  return Math.min(Number(paid ? session.limit_ms : session.reserved_ms), Number(value));
}

function earnedRequestRetryDelay(session: any, budget: any, attempts: number, active: boolean): number | undefined {
  if (!active || !budget.earned_time || attempts >= budget.request_limit) return;
  // ceil(earned * rate / 60000) must exceed the attempts already made. The minimum
  // charge can grant early capacity, but cannot substitute for elapsed voice time here.
  const nextObserved = Math.floor(attempts * 60_000 / budget.requests_per_minute) + 1;
  const maximum = Number(session.funding_mode === 'ai-value' ? session.limit_ms : session.reserved_ms);
  const delay = Math.max(1000, nextObserved - Number(session.observed_ms) + 1000);
  const remaining = Math.min(session.deadline.getTime(), budget.expires_at.getTime()) - session.database_now.getTime();
  // The delay is advisory: a later admission still rechecks authoritative observed
  // time, budget and concurrency. It never grants time or reserves provider spend.
  if (nextObserved > maximum || delay > 60_000 || delay >= remaining) return;
  return delay;
}
interface ObservedResponse { id: string; usage: HostedHelperUsage }
function observedResponse(raw: unknown): ObservedResponse {
  if (!object(raw) || typeof raw.id !== 'string' || !/^resp_[A-Za-z0-9_-]{1,200}$/.test(raw.id) || raw.model !== HOSTED_HELPER_MODEL ||
    (raw.service_tier !== undefined && raw.service_tier !== 'default') || !['completed', 'incomplete', 'failed'].includes(String(raw.status)) ||
    !object(raw.usage) || !object(raw.usage.input_tokens_details) || !Array.isArray(raw.output) || raw.output.length > 100 ||
    raw.output.some(item => !object(item) || !['message', 'reasoning', 'web_search_call'].includes(String(item.type))))
    throw new Error('Invalid provider usage.');
  const details = raw.usage.input_tokens_details;
  const usage = { inputTokens: raw.usage.input_tokens, cachedInputTokens: details.cached_tokens,
    cacheWriteTokens: details.cache_write_tokens ?? 0, outputTokens: raw.usage.output_tokens,
    searchCalls: raw.output.filter(item => object(item) && item.type === 'web_search_call').length } as HostedHelperUsage;
  hostedHelperCost(usage);
  return { id: raw.id, usage };
}
function outputText(raw: unknown): Pick<HostedHelperResult, 'text' | 'sources'> {
  if (!object(raw) || raw.status !== 'completed' || !Array.isArray(raw.output)) throw new ServiceError('helper_output_incomplete', 502);
  let text = '';
  const sources = new Map<string, { title: string; url: string }>();
  for (const item of raw.output) {
    if (!object(item)) throw new ServiceError('helper_output_incomplete', 502);
    if (item.type !== 'message') continue;
    if (!Array.isArray(item.content) || item.content.length > 40) throw new ServiceError('helper_output_incomplete', 502);
    for (const content of item.content) {
      if (!object(content)) throw new ServiceError('helper_output_incomplete', 502);
      if (content.type === 'refusal') throw new ServiceError('helper_output_refused', 422);
      if (content.type !== 'output_text' || typeof content.text !== 'string') continue;
      text += content.text;
      if (Buffer.byteLength(text) > 65_536) throw new ServiceError('helper_output_incomplete', 502);
      if (!Array.isArray(content.annotations)) continue;
      for (const annotation of content.annotations.slice(0, 40)) {
        if (!object(annotation) || annotation.type !== 'url_citation' || typeof annotation.url !== 'string' || annotation.url.length > 2048 || sources.size >= 12) continue;
        try {
          const url = new URL(annotation.url);
          if (url.protocol !== 'https:' || url.username || url.password) continue;
          sources.set(url.href, { url: url.href, title: typeof annotation.title === 'string' ? annotation.title.slice(0, 200) : 'Source' });
        } catch { /* Ignore invalid citations. They are never fetched by this gateway. */ }
      }
    }
  }
  if (!validText(text, 65_536)) throw new ServiceError('helper_output_incomplete', 502);
  return { text, sources: [...sources.values()] };
}
