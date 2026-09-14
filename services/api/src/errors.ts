export class ServiceError extends Error {
  constructor(readonly code: string, readonly status = 400) { super(code); }
}

/** Admission failed before a request or provider attempt was recorded. */
export class HelperSessionLimitError extends ServiceError {
  readonly retryable: boolean;
  constructor(readonly retryAfterMilliseconds?: number) {
    super('helper_session_limit', 429);
    if (retryAfterMilliseconds !== undefined && (!Number.isSafeInteger(retryAfterMilliseconds) ||
        retryAfterMilliseconds < 1000 || retryAfterMilliseconds > 60_000)) throw new Error('Invalid helper retry delay.');
    this.retryable = retryAfterMilliseconds !== undefined;
  }
}
