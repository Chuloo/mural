import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendMinuteEntry, lockMinuteWallet } from './minutes.js';
import { recoverMinutePurchaseShortfalls } from './minute-purchases.js';

/** Operator-only support adjustment. It ends the customer's hold, not provider reconciliation.
 * No public route imports this module; runtime roles must have no access to the audit table. */
export async function recoverHostedStartup(db: Database, input: {
  sessionID: string; accountID: string; expectedReservedMilliseconds: number; actor: string; reason: string;
}) {
  const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
  if (!uuid.test(input.sessionID) || !uuid.test(input.accountID) || !Number.isSafeInteger(input.expectedReservedMilliseconds) ||
      input.expectedReservedMilliseconds<=0 || input.actor.trim().length<3 || input.actor.length>200 ||
      input.reason.trim().length<3 || input.reason.length>500 || /[\x00-\x1f]/.test(input.actor+input.reason))
    throw new ServiceError('invalid_startup_recovery');
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-hosted-funding-cap'))");
    await lockMinuteWallet(sql,input.accountID,false);
    const existing=(await sql.query('SELECT * FROM hosted_startup_recoveries WHERE session_id=$1',[input.sessionID])).rows[0];
    if (existing) {
      if (existing.account_id!==input.accountID || Number(existing.released_ms)!==input.expectedReservedMilliseconds)
        throw new ServiceError('startup_recovery_review_again',409);
      return {recovered:true,alreadyRecovered:true,releasedMilliseconds:Number(existing.released_ms),providerCostStatus:'unconfirmed'};
    }
    const row=(await sql.query(`SELECT *,deadline<now()-interval '1 minute' AS expired
      FROM hosted_sessions WHERE id=$1 AND account_id=$2 FOR UPDATE`,[input.sessionID,input.accountID])).rows[0];
    if (!row || row.state!=='incomplete' || row.funding_mode!=='minutes' || !row.minute_reservation_id ||
      row.provider_session_id || !row.provider_attempted_at || !row.close_requested_at || !row.expired ||
      Number(row.observed_ms)!==0 || row.charged_ms!==null || row.provider_cost_nano!==null ||
      row.provider_rejection_status!==null || Number(row.reserved_ms)!==input.expectedReservedMilliseconds ||
      BigInt(row.funding_exposure_nano)<=0n ||
      (await sql.query('SELECT 1 FROM hosted_helper_requests WHERE session_id=$1 LIMIT 1',[input.sessionID])).rowCount)
      throw new ServiceError('startup_recovery_review_again',409);
    const hold=(await sql.query('SELECT * FROM minute_reservations WHERE id=$1 FOR UPDATE',[row.minute_reservation_id])).rows[0];
    if (!hold || hold.account_id!==input.accountID || hold.state!=='open' || Number(hold.amount_ms)!==input.expectedReservedMilliseconds)
      throw new ServiceError('startup_recovery_review_again',409);
    // This insert also enforces operator authorization before any ledger mutation.
    await sql.query(`INSERT INTO hosted_startup_recoveries(session_id,account_id,actor,reason,released_ms,retained_exposure_nano)
      VALUES($1,$2,$3,$4,$5,$6)`,[input.sessionID,input.accountID,input.actor,input.reason,hold.amount_ms,row.funding_exposure_nano]);
    await appendMinuteEntry(sql,input.accountID,`startup-support:${input.sessionID}`,'settle',0,-Number(hold.amount_ms),row.public_minutes?'funded':'mixed');
    await sql.query("UPDATE minute_reservations SET state='settled',used_ms=0 WHERE id=$1",[hold.id]);
    await recoverMinutePurchaseShortfalls(sql,input.accountID);
    // Closed here means financially released for the learner. Provider cost remains unknown;
    // the original exposure and audit record remain for operator reconciliation.
    await sql.query(`UPDATE hosted_sessions SET state='closed',charged_ms=0,
      close_reason='operator_funded_startup_recovery' WHERE id=$1`,[input.sessionID]);
    return {recovered:true,alreadyRecovered:false,releasedMilliseconds:Number(hold.amount_ms),providerCostStatus:'unconfirmed'};
  });
}
