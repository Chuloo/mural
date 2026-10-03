import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import type { MinuteDeliveryWorker, MinuteReceiptVault } from './minute-provider-delivery.js';
import type { PlayMinuteProvider } from './play-minute-provider.js';
import type { AppleMinuteProvider } from './apple-minute-provider.js';
import { PlayNotificationTransportError, type PlayRtdnSubscriber } from './google-play-rtdn.js';

const day = 86_400_000;
export interface CommerceRunnerOptions {
  intervalMilliseconds?: number;
  deliveryLimit?: number;
  reconciliationLimit?: number;
  voidPagesPerRun?: number;
  onFailure?: (code: 'minute_delivery_failed' | 'minute_reconciliation_failed' | 'play_void_reconciliation_failed' | 'apple_history_reconciliation_failed' |
    'play_notification_failed' | 'play_notification_unavailable' | 'play_notification_timeout', providerStatus?: number) => void;
}

/** Replay notification history without advancing beyond an unverified or unqueued page. */
export class AppleHistoryReconciler {
  constructor(readonly db:Database,readonly apple:Pick<AppleMinuteProvider,'environment'|'merchant'|'pollHistory'>) {}
  async page():Promise<{pages:number;scheduled:number;more:boolean}> {
    return transaction(this.db,async sql=>{
      const locked=(await sql.query('SELECT pg_try_advisory_xact_lock(hashtextextended($1,0)) AS acquired',
        [`mural-apple-history:${this.apple.environment}:${this.apple.merchant}`])).rows[0].acquired;
      if(!locked)return {pages:0,scheduled:0,more:false};
      await sql.query('INSERT INTO apple_notification_cursors(environment,merchant) VALUES($1,$2) ON CONFLICT DO NOTHING',[this.apple.environment,this.apple.merchant]);
      const row=(await sql.query('SELECT * FROM apple_notification_cursors WHERE environment=$1 AND merchant=$2',[this.apple.environment,this.apple.merchant])).rows[0];
      if(row.page_token===null && row.next_poll_after.getTime()>Date.now())return {pages:0,scheduled:0,more:false};
      const now=Date.now(),retention=(this.apple.environment==='test'?30:180)*day;
      const end=row.window_end_ms===null?now-60_000:Number(row.window_end_ms);
      const start=row.window_start_ms===null?(row.completed_through_ms===null?now-retention+day:Number(row.completed_through_ms)-300_000):Number(row.window_start_ms);
      if(start<now-retention+60_000)throw new ServiceError('apple_history_cursor_expired',503);
      if(start>=end || (row.page_token===null && row.completed_through_ms!==null && end<=Number(row.completed_through_ms)))
        return {pages:0,scheduled:0,more:false};
      const result=await this.apple.pollHistory(start,end,row.page_token??undefined);
      if(result.nextPageToken && result.nextPageToken===row.page_token)throw new ServiceError('apple_history_cursor_stalled',502);
      await sql.query(`UPDATE apple_notification_cursors SET completed_through_ms=CASE WHEN $5::text IS NULL THEN $4::bigint ELSE completed_through_ms END,
        window_start_ms=CASE WHEN $5::text IS NULL THEN NULL ELSE $3::bigint END,window_end_ms=CASE WHEN $5::text IS NULL THEN NULL ELSE $4::bigint END,
        page_token=$5,next_poll_after=CASE WHEN $5::text IS NULL THEN now()+interval '15 minutes' ELSE now() END,updated_at=now()
        WHERE environment=$1 AND merchant=$2`,[this.apple.environment,this.apple.merchant,start,end,result.nextPageToken??null]);
      return {pages:1,scheduled:result.scheduled,more:!!result.nextPageToken};
    });
  }
}

/** Checkpoints a page only after its verified voids and retry jobs have committed. */
export class PlayVoidReconciler {
  constructor(readonly db: Database, readonly play: Pick<PlayMinuteProvider, 'environment' | 'merchant' | 'pollVoids'>) {}
  async page(): Promise<{ skipped: boolean; pages: number; scheduled: number; more: boolean }> {
    return transaction(this.db, async sql => {
      const locked = (await sql.query('SELECT pg_try_advisory_xact_lock(hashtextextended($1,0)) AS acquired',
        [`mural-play-void-cursor:${this.play.environment}:${this.play.merchant}`])).rows[0].acquired;
      if (!locked) return { skipped: true, pages: 0, scheduled: 0, more: false };
      await sql.query(`INSERT INTO minute_play_void_cursors(environment,merchant) VALUES($1,$2) ON CONFLICT DO NOTHING`,
        [this.play.environment, this.play.merchant]);
      const row = (await sql.query('SELECT * FROM minute_play_void_cursors WHERE environment=$1 AND merchant=$2',
        [this.play.environment, this.play.merchant])).rows[0];
      if (row.page_token === null && row.next_poll_after.getTime() > Date.now())
        return { skipped: false, pages: 0, scheduled: 0, more: false };
      const now = Date.now(), end = row.window_end_ms === null ? now - 60_000 : Number(row.window_end_ms);
      const start = row.window_start_ms === null
        ? row.completed_through_ms === null ? Math.max(0, now - 29 * day) : Math.max(0, Number(row.completed_through_ms) - 300_000)
        : Number(row.window_start_ms);
      // Do not silently skip a gap that Google's thirty-day history can no longer cover.
      if (start < now - 30 * day + 60_000) throw new ServiceError('play_void_cursor_expired', 503);
      if (start >= end || (row.page_token === null && row.completed_through_ms !== null && end <= Number(row.completed_through_ms)))
        return { skipped: false, pages: 0, scheduled: 0, more: false };
      const result = await this.play.pollVoids(start, end, row.page_token ?? undefined);
      if (result.nextPageToken === row.page_token) throw new ServiceError('play_void_cursor_stalled', 502);
      await sql.query(`UPDATE minute_play_void_cursors SET completed_through_ms=CASE WHEN $5::text IS NULL THEN $4::bigint ELSE completed_through_ms END,
        window_start_ms=CASE WHEN $5::text IS NULL THEN NULL ELSE $3::bigint END,
        window_end_ms=CASE WHEN $5::text IS NULL THEN NULL ELSE $4::bigint END,page_token=$5,
        next_poll_after=CASE WHEN $5::text IS NULL THEN now()+interval '15 minutes' ELSE now() END,updated_at=now()
        WHERE environment=$1 AND merchant=$2`, [this.play.environment, this.play.merchant, start, end, result.nextPageToken ?? null]);
      return { skipped: false, pages: 1, scheduled: result.scheduled, more: !!result.nextPageToken };
    });
  }
}

/** No timers or provider requests begin until start() or runOnce() is explicitly called. */
export class MinuteCommerceRunner {
  readonly #interval: number;
  readonly #deliveryLimit: number;
  readonly #reconciliationLimit: number;
  readonly #voidPages: number;
  readonly #onFailure: NonNullable<CommerceRunnerOptions['onFailure']>;
  #timer?: ReturnType<typeof setTimeout>;
  #flight?: Promise<void>;
  #started = false;
  #stopped = false;
  constructor(readonly vault: Pick<MinuteReceiptVault, 'scheduleReconciliation'>,
    readonly worker: Pick<MinuteDeliveryWorker, 'runBatch'>, readonly voids?: PlayVoidReconciler,
    options: CommerceRunnerOptions = {}, readonly appleHistory?: AppleHistoryReconciler,
    readonly playNotifications?: Pick<PlayRtdnSubscriber, 'poll' | 'isOperational'>,
    readonly additionalAppleHistories:readonly AppleHistoryReconciler[] = []) {
    this.#interval = options.intervalMilliseconds ?? 60_000;
    this.#deliveryLimit = options.deliveryLimit ?? 5;
    this.#reconciliationLimit = options.reconciliationLimit ?? 100;
    this.#voidPages = options.voidPagesPerRun ?? 2;
    if (!Number.isSafeInteger(this.#interval) || this.#interval < 1000 || this.#interval > 3_600_000 ||
      !Number.isSafeInteger(this.#deliveryLimit) || this.#deliveryLimit < 1 || this.#deliveryLimit > 10 ||
      !Number.isSafeInteger(this.#reconciliationLimit) || this.#reconciliationLimit < 1 || this.#reconciliationLimit > 1000 ||
      !Number.isSafeInteger(this.#voidPages) || this.#voidPages < 1 || this.#voidPages > 5)
      throw new ServiceError('minute_runner_configuration_invalid', 503);
    this.#onFailure = options.onFailure ?? (() => {});
  }
  #failure(code: Parameters<NonNullable<CommerceRunnerOptions['onFailure']>>[0], providerStatus?: number) {
    try { this.#onFailure(code, providerStatus); } catch { /* An observer cannot stop durable delivery. */ }
  }
  runOnce(): Promise<void> {
    if (this.#stopped) return Promise.resolve();
    if (this.#flight) return this.#flight;
    this.#flight = this.#run().finally(() => { this.#flight = undefined; });
    return this.#flight;
  }
  async #run(): Promise<void> {
    if (this.playNotifications) {
      try { await this.playNotifications.poll(); }
      catch (error) {
        if (error instanceof PlayNotificationTransportError) this.#failure(error.code, error.providerStatus);
        else this.#failure('play_notification_failed');
      }
    }
    try { await this.vault.scheduleReconciliation(this.#reconciliationLimit); }
    catch { this.#failure('minute_reconciliation_failed'); }
    for (let index = 0; index < this.#deliveryLimit && !this.#stopped; index++) {
      try {
        const result = await this.worker.runBatch(1);
        if (result.retried) this.#failure('minute_delivery_failed');
        if (!result.processed) break;
      } catch { this.#failure('minute_delivery_failed'); break; }
    }
    for (let index = 0; this.voids && index < this.#voidPages && !this.#stopped; index++) {
      try { if (!(await this.voids.page()).more) break; }
      catch { this.#failure('play_void_reconciliation_failed'); break; }
    }
    for(const history of [...(this.appleHistory?[this.appleHistory]:[]),...this.additionalAppleHistories]) {
      for(let index=0;index<this.#voidPages && !this.#stopped;index++){
        try {if(!(await history.page()).more)break;}
        catch {this.#failure('apple_history_reconciliation_failed');break;}
      }
    }
  }
  start(): void {
    if (this.#started || this.#stopped) return;
    this.#started = true;
    const cycle = async () => {
      await this.runOnce();
      if (!this.#stopped) { this.#timer = setTimeout(cycle, this.#interval); this.#timer.unref(); }
    };
    void cycle();
  }
  /** Stops launching work, then drains the current bounded provider operation before DB shutdown. */
  async stop(): Promise<void> {
    this.#stopped = true;
    if (this.#timer) clearTimeout(this.#timer);
    await this.#flight;
  }
}
