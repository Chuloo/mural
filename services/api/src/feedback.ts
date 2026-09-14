import { createHmac } from 'node:crypto';
import type { IncomingHttpHeaders } from 'node:http';
import type { PoolClient } from 'pg';
import { trustedClientNetwork } from './access-requests.js';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';

export const AI_REPORT_PATH = '/v1/feedback/ai';
export const AI_REPORT_CONSENT_VERSION = 'ai-report-v1';
export const AI_REPORT_BODY_LIMIT = 16_384;
export const AI_REPORT_MAX_EXCERPT = 2_000;
export const AI_REPORT_REASONS = ['offensive', 'incorrect', 'wrong_language', 'other'] as const;
export const AI_REPORT_LIMITS = Object.freeze({ networkHourly: 5, networkDaily: 20, globalHourly: 200, globalDaily: 1_000 });
export type AIReportReason = typeof AI_REPORT_REASONS[number];
export interface AIReportInput { reportID: string; languageID: string; reason: AIReportReason; excerpt: string; consentVersion: typeof AI_REPORT_CONSENT_VERSION }
export interface AIReportConfig { hmacKey: string; proxyToken: string; allowLocalLoopback: boolean }
declare const networkReference: unique symbol;
export type TrustedFeedbackNetwork = string & { readonly [networkReference]: true };

export function aiReportConfig(env: NodeJS.ProcessEnv): AIReportConfig | undefined {
  if (env.AI_REPORTS_ENABLED !== 'true') return undefined;
  const hmacKey = env.AI_REPORTS_HMAC_KEY ?? '', proxyToken = env.AI_REPORTS_PROXY_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(hmacKey) || !/^[a-f0-9]{64}$/.test(proxyToken) || hmacKey === proxyToken)
    throw new Error('AI reports require separate random 32-byte HMAC and trusted-proxy secrets.');
  return { hmacKey, proxyToken, allowLocalLoopback: env.AI_REPORTS_ALLOW_LOCAL_LOOPBACK === 'true' };
}

/** Call with trusted proxy headers on the server, never a network field from the body. */
export function reportNetwork(headers: IncomingHttpHeaders, remoteAddress: string, config: AIReportConfig, now = new Date()): TrustedFeedbackNetwork {
  let network: string;
  try { network = trustedClientNetwork(headers, remoteAddress, config.proxyToken, config.allowLocalLoopback); }
  catch { throw new ServiceError('ai_reports_unavailable', 503); }
  return createHmac('sha256', Buffer.from(config.hmacKey, 'hex'))
    .update(`mural-ai-report\n${now.toISOString().slice(0, 10)}\n${network}`).digest('hex') as TrustedFeedbackNetwork;
}

// This catches common credential shapes; the UI still asks the user to review
// their excerpt. Never add an API key, bearer or complete archive to this DTO.
export function containsReportCredential(text: string): boolean {
  return /\bsk-[A-Za-z0-9_-]{16,}/.test(text) ||
    /\bBearer[ \t]+[A-Za-z0-9._~+/-]{16,}={0,2}/i.test(text) ||
    /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/.test(text) ||
    /(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])/.test(text);
}

export function parseAIReport(body: unknown): AIReportInput {
  if (!body || typeof body !== 'object' || Array.isArray(body) || Buffer.isBuffer(body)) throw new ServiceError('invalid_ai_report');
  const input = body as Record<string, unknown>;
  if (Object.keys(input).some(key => !['reportID', 'languageID', 'reason', 'excerpt', 'consentVersion'].includes(key)) ||
      typeof input.reportID !== 'string' || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(input.reportID) ||
      typeof input.languageID !== 'string' || !/^[a-z]{2,3}$/.test(input.languageID) ||
      typeof input.reason !== 'string' || !(AI_REPORT_REASONS as readonly string[]).includes(input.reason) ||
      input.consentVersion !== AI_REPORT_CONSENT_VERSION || typeof input.excerpt !== 'string' ||
      input.excerpt.length > AI_REPORT_MAX_EXCERPT) throw new ServiceError('invalid_ai_report');
  const excerpt = input.excerpt.replaceAll('\r\n', '\n').replaceAll('\r', '\n').trim();
  if (!excerpt || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f\u202a-\u202e\u2066-\u2069]/.test(excerpt) ||
      /[\uD800-\uDFFF]/u.test(excerpt)) throw new ServiceError('invalid_ai_report');
  if (containsReportCredential(excerpt)) throw new ServiceError('ai_report_contains_credential');
  return { reportID: input.reportID.toLowerCase(), languageID: input.languageID, reason: input.reason as AIReportReason,
    excerpt, consentVersion: AI_REPORT_CONSENT_VERSION };
}

export class AIReports {
  constructor(readonly db: Database, readonly config?: AIReportConfig) {}

  async submit(body: unknown, network: TrustedFeedbackNetwork): Promise<{ accepted: true; reportID: string }> {
    if (!this.config) throw new ServiceError('ai_reports_unavailable', 503);
    if (typeof network !== 'string' || !/^[a-f0-9]{64}$/.test(network)) throw new ServiceError('ai_reports_unavailable', 503);
    const input = parseAIReport(body);
    const accepted = await transaction(this.db, async sql => {
      const now = (await sql.query<{ now: Date }>('SELECT now()')).rows[0]!.now;
      const day = new Date(now); day.setUTCHours(0, 0, 0, 0);
      const hour = new Date(now); hour.setUTCMinutes(0, 0, 0);
      // Every request locks global buckets in the same order. Denied attempts
      // commit their saturated bucket, without draining other networks' quota.
      if (await increment(sql, 'global_day', 'all', day, AI_REPORT_LIMITS.globalDaily, 48) > AI_REPORT_LIMITS.globalDaily) return false;
      if (await increment(sql, 'global_hour', 'all', hour, AI_REPORT_LIMITS.globalHourly, 2) > AI_REPORT_LIMITS.globalHourly) {
        await undo(sql, 'global_day', 'all', day); return false;
      }
      if (await increment(sql, 'network_day', network, day, AI_REPORT_LIMITS.networkDaily, 48) > AI_REPORT_LIMITS.networkDaily) {
        await undoGlobals(sql, day, hour); return false;
      }
      if (await increment(sql, 'network_hour', network, hour, AI_REPORT_LIMITS.networkHourly, 2) > AI_REPORT_LIMITS.networkHourly) {
        await undo(sql, 'network_day', network, day); await undoGlobals(sql, day, hour); return false;
      }
      // The client keeps one random report ID for an explicit retry. Responses
      // reveal neither whether that ID existed nor any previously stored text.
      await sql.query(`INSERT INTO ai_feedback_reports(id,language_id,reason,excerpt,consent_version)
        VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING`,
      [input.reportID, input.languageID, input.reason, input.excerpt, input.consentVersion]);
      return true;
    });
    if (!accepted) throw new ServiceError('ai_report_rate_limit', 429);
    return { accepted: true, reportID: input.reportID };
  }
}

async function increment(sql: PoolClient, scope: string, identifier: string, window: Date, limit: number, expiryHours: number): Promise<number> {
  return (await sql.query<{ hits: number }>(`INSERT INTO ai_feedback_limits(scope,identifier,window_start,expires_at,hits)
    VALUES($1,$2,$3,$4,1) ON CONFLICT(scope,identifier,window_start)
    DO UPDATE SET hits=LEAST(ai_feedback_limits.hits+1,$5) RETURNING hits`,
  [scope, identifier, window, new Date(window.getTime() + expiryHours * 3_600_000), limit + 1])).rows[0]!.hits;
}
async function undo(sql: PoolClient, scope: string, identifier: string, window: Date): Promise<void> {
  await sql.query('UPDATE ai_feedback_limits SET hits=hits-1 WHERE scope=$1 AND identifier=$2 AND window_start=$3', [scope, identifier, window]);
}
async function undoGlobals(sql: PoolClient, day: Date, hour: Date): Promise<void> {
  await undo(sql, 'global_day', 'all', day); await undo(sql, 'global_hour', 'all', hour);
}

/** The database function permits retention cleanup without runtime access to report text. */
export async function pruneAIReports(db: Database): Promise<{ reports: number; limits: number }> {
  const row = (await db.query<{ deleted_reports: number; deleted_counters: number }>('SELECT * FROM prune_ai_feedback()')).rows[0]!;
  return { reports: row.deleted_reports, limits: row.deleted_counters };
}
