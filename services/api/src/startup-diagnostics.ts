/** Error references let a learner report a failure without sharing an email or conversation. */
export interface StartupDiagnostic {
  reference: string;
  operation: 'balance' | 'capabilities' | 'start' | 'current' | 'status' | 'close';
  status: number;
  reason: string;
}
const operations: Record<string, StartupDiagnostic['operation']> = {
  'GET /v1/minutes': 'balance',
  'GET /v1/live/capabilities': 'capabilities',
  'POST /v1/live/sessions': 'start',
  'GET /v1/live/sessions/current': 'current',
  'GET /v1/live/sessions/:id': 'status',
  'POST /v1/live/sessions/:id/close': 'close',
};
const reasons = new Set(['sign_in_required', 'sign_in_to_continue', 'invalid_request', 'invalid_json',
  'invalid_live_offer', 'invalid_live_context', 'invalid_session_duration', 'idempotency_key_required',
  'insufficient_minutes', 'insufficient_credit', 'live_session_unresolved', 'live_session_cancelled',
  'live_request_already_created', 'live_session_not_found', 'account_not_found', 'rate_limit',
  'hosted_voice_not_ready', 'hosted_helpers_not_ready', 'hosted_paid_not_ready', 'hosted_funding_cap_reached',
  'provider_reconciliation_required', 'minute_balance_reconciliation_required',
  'minute_purchase_reconciliation_required', 'helper_session_funding_unavailable',
  'provider_create_rejected', 'provider_session_unconfirmed', 'trusted_proxy_required']);

export function startupDiagnostic(method: string, route: string | undefined, requestID: string,
  status: number, code: string, error: unknown): StartupDiagnostic | undefined {
  const operation = operations[`${method} ${route}`];
  if (!operation || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(requestID)) return;
  const sqlState = error && typeof error === 'object' && 'code' in error ? error.code : undefined;
  const databaseReason = sqlState === '42501' ? 'database_permission' :
    ['23502', '23503', '23505', '23514'].includes(String(sqlState)) ? 'database_constraint' :
    ['40001', '40P01', '57014'].includes(String(sqlState)) ? 'database_retry' :
    ['08001', '08006', '53300', '57P01'].includes(String(sqlState)) ? 'database_unavailable' :
    ['42P01', '42703'].includes(String(sqlState)) ? 'database_schema' : 'internal';
  return { reference: requestID.replaceAll('-', '').slice(0, 12), operation, status,
    reason: reasons.has(code) ? code : databaseReason };
}
