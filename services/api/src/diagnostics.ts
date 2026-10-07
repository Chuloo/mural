import { AsyncLocalStorage } from 'node:async_hooks';
import { randomUUID } from 'node:crypto';
import { diagnosticErrorCodes } from './diagnostic-error-codes.js';

export type DiagnosticEvent = 'request_completed' | 'request_failed' | 'provider_completed' | 'provider_failed' |
  'voice_active' | 'voice_close_requested' | 'voice_closed' | 'voice_connection_lost' |
  'voice_watchdog_failed' | 'voice_hangup_failed' | 'background_failed' | 'service_started' | 'service_failed' | 'apple_catalog';
export interface DiagnosticFields {
  operation?: string; reference?: string; sessionReference?: string;
  status?: number; durationMilliseconds?: number; providerStatus?: number; providerRequestID?: string;
  environment?: 'test' | 'live'; storefront?: string; admissionReady?: boolean; offerCount?: number;
}
export interface DiagnosticRecord extends DiagnosticFields {
  timestamp: string; level: 'info' | 'warn' | 'error'; event: DiagnosticEvent; reason?: string; source?: string;
}
export type DiagnosticSink = (record: DiagnosticRecord) => void | Promise<void>;
const contexts = new AsyncLocalStorage<{ reference: string }>();
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
export function errorReference(id: string): string {
  return (uuid.test(id) ? id : randomUUID()).replaceAll('-', '').slice(0, 12).toLowerCase();
}
export function failureReason(error: unknown): string {
  const code = error && typeof error === 'object' && 'code' in error ? error.code : undefined;
  if (typeof code === 'string' && diagnosticErrorCodes.has(code)) return code;
  switch (code) {
    case '42501': return 'database_permission';
    case '23502': case '23503': case '23505': case '23514': return 'database_constraint';
    case '40001': case '40P01': case '57014': return 'database_retry';
    case '08001': case '08006': case '53300': case '57P01': return 'database_unavailable';
    case '42P01': case '42703': return 'database_schema';
    case 'ECONNREFUSED': case 'ECONNRESET': case 'ENOTFOUND': case 'ETIMEDOUT': return 'network_unavailable';
    default: return 'internal';
  }
}

/** Records only deliberately selected metadata. Raw errors and request objects never reach the sink. */
export class Diagnostics {
  constructor(private readonly sink: DiagnosticSink = () => {}) {}
  run<T>(reference: string, work: () => T): T { return contexts.run({ reference }, work); }
  record(event: DiagnosticEvent, fields: DiagnosticFields = {}, error?: unknown): void {
    const record: DiagnosticRecord = { timestamp: new Date().toISOString(),
      level: event.endsWith('failed') ? ((fields.status ?? fields.providerStatus ?? 500) >= 500 ? 'error' : 'warn') : 'info', event };
    const reference = fields.reference ?? contexts.getStore()?.reference;
    if (reference && /^[a-f0-9]{12}$/.test(reference)) record.reference = reference;
    if (fields.sessionReference && /^[a-f0-9]{12}$/.test(fields.sessionReference)) record.sessionReference = fields.sessionReference;
    if (fields.operation && /^[a-zA-Z0-9_ /:.-]{1,120}$/.test(fields.operation)) record.operation = fields.operation;
    for (const key of ['status', 'providerStatus'] as const) {
      const value = fields[key];
      if (Number.isInteger(value) && value! >= 100 && value! <= 599) record[key] = value;
    }
    if (Number.isFinite(fields.durationMilliseconds) && fields.durationMilliseconds! >= 0)
      record.durationMilliseconds = Math.round(Math.min(fields.durationMilliseconds!, 86_400_000));
    if (fields.providerRequestID && /^[A-Za-z0-9_-]{1,128}$/.test(fields.providerRequestID)) record.providerRequestID = fields.providerRequestID;
    if (event === 'apple_catalog') {
      if (fields.environment === 'test' || fields.environment === 'live') record.environment = fields.environment;
      if (fields.storefront === 'unsupported' || (typeof fields.storefront==='string' && /^[A-Z]{3}$/.test(fields.storefront))) record.storefront = fields.storefront;
      if (typeof fields.admissionReady === 'boolean') record.admissionReady = fields.admissionReady;
      if (Number.isInteger(fields.offerCount) && fields.offerCount! >= 0 && fields.offerCount! <= 20) record.offerCount = fields.offerCount;
    }
    if (error !== undefined) {
      record.reason = failureReason(error);
      // Retain an application source location, never error messages, SQL, paths or raw stacks.
      const stack = error instanceof Error ? error.stack : undefined;
      const frames = error instanceof Error && stack ? stack.slice(stack.indexOf(error.message) + error.message.length) : undefined;
      const frame = frames?.match(/\/src\/([a-z][a-z0-9-]*\.(?:ts|js):\d+:\d+)/);
      if (frame) record.source = frame[1];
    }
    try { void Promise.resolve(this.sink(record)).catch(() => {}); } catch { /* Observers cannot change outcomes. */ }
  }
}
