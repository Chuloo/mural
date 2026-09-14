import { ServiceError } from './errors.js';
import { TRIAL_MS } from './pricing.js';

// A reducer for trusted server-side provider events, not an HTTP API accepting app usage.
// The hosted controller accepts this reducer's input only from its authenticated sideband adapter.
export class VoiceMeter {
  milliseconds = 0;
  finalized = false;
  closeRequested = false;
  constructor(readonly sessionID: string, readonly limitMilliseconds = TRIAL_MS) {
    if (!Number.isSafeInteger(limitMilliseconds) || limitMilliseconds <= 0 || limitMilliseconds > 3_600_000)
      throw new ServiceError('invalid_trial_limit');
  }
  receive(attachedSessionID: string, event: unknown) {
    if (attachedSessionID !== this.sessionID) throw new ServiceError('wrong_provider_session');
    if (!event || typeof event !== 'object') return;
    const data = event as { type?: unknown; usage?: { seconds?: unknown } };
    if (data.type !== 'session.usage.updated' && data.type !== 'session.closed') return;
    const seconds = data.usage?.seconds;
    if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0 || seconds > Number.MAX_SAFE_INTEGER / 1000)
      throw new ServiceError('invalid_provider_usage');
    const milliseconds = Math.ceil(seconds * 1000);
    if (this.finalized) {
      if (milliseconds !== this.milliseconds) throw new ServiceError('usage_after_finalization');
      return;
    }
    if (milliseconds < this.milliseconds) {
      if (data.type === 'session.closed') throw new ServiceError('final_usage_regressed');
      return;
    }
    this.milliseconds = milliseconds;
    if (data.type === 'session.closed') this.finalized = true;
    if (milliseconds >= this.limitMilliseconds && !this.finalized) this.closeRequested = true;
  }
  deadlineExpired() { if (!this.finalized) this.closeRequested = true; }
}
