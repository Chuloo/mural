import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { reserveWelcomeFunding } from './welcome-funding.js';

export interface MinuteAttestor {
  // Verify an unexpired, one-time proof bound to this authenticated account and purpose.
  verify(accountID: string, proof: unknown): Promise<{ deviceReference: string; previouslyClaimed: boolean }>;
}
export class UnconfiguredMinuteAttestor implements MinuteAttestor {
  async verify(_accountID: string, _proof: unknown): Promise<never> { throw new ServiceError('trial_attestation_unavailable', 503); }
}

export const MS_PER_MINUTE = 60_000;
export function millisecondsForMinutes(minutes: number): number {
  if (!Number.isSafeInteger(minutes) || minutes < 0 || minutes > 1440) throw new ServiceError('invalid_minutes');
  return minutes * MS_PER_MINUTE;
}
type EntryKind = 'welcome' | 'gift' | 'purchase' | 'reserve' | 'settle' | 'release' | 'forfeit' | 'transfer';
const validAmount = (value: number) => Number.isSafeInteger(value) && value >= 0;

export async function lockMinuteWallet(sql: PoolClient, account: string, active = true) {
  const owner = (await sql.query('SELECT deleted_at FROM accounts WHERE id=$1 FOR UPDATE', [account])).rows[0];
  if (!owner || (active && owner.deleted_at)) throw new ServiceError('account_not_found', 404);
  await sql.query('INSERT INTO minute_wallets(account_id) VALUES($1) ON CONFLICT DO NOTHING', [account]);
  const row = (await sql.query('SELECT balance_ms,reserved_ms,sandbox_balance_ms,sandbox_reconciled FROM minute_wallets WHERE account_id=$1 FOR UPDATE', [account])).rows[0];
  return { balance: Number(row.balance_ms), reserved: Number(row.reserved_ms), sandbox: Number(row.sandbox_balance_ms),
    sandboxReconciled: Boolean(row.sandbox_reconciled) };
}

// Internal transactional primitive. Clients can never append entitlements directly.
export async function appendMinuteEntry(sql: PoolClient, account: string, reference: string, kind: EntryKind,
  balanceDelta: number, reservedDelta: number, source: 'mixed' | 'funded' | 'sandbox' = 'mixed'): Promise<boolean> {
  if (!reference || reference.length > 200 || !Number.isSafeInteger(balanceDelta) || !Number.isSafeInteger(reservedDelta))
    throw new ServiceError('invalid_minute_entry');
  const wallet = await lockMinuteWallet(sql, account, false);
  const existing = (await sql.query('SELECT * FROM minute_entries WHERE reference=$1', [reference])).rows[0];
  if (existing) {
    if (existing.account_id !== account || existing.kind !== kind || Number(existing.balance_delta_ms) !== balanceDelta ||
      Number(existing.reserved_delta_ms) !== reservedDelta || existing.funding_source !== source) throw new ServiceError('idempotency_conflict', 409);
    return false;
  }
  const balance = wallet.balance + balanceDelta, reserved = wallet.reserved + reservedDelta;
  if (!validAmount(balance) || !validAmount(reserved) || reserved > balance) throw new ServiceError('insufficient_minutes', 402);
  const sandboxDelta = balanceDelta>=0 ? source==='sandbox' ? balanceDelta : 0 : source==='funded' ? 0 :
    source==='sandbox' ? balanceDelta : -Math.min(wallet.sandbox,-balanceDelta);
  const sandbox = wallet.sandbox+sandboxDelta;
  if (!validAmount(sandbox) || sandbox>balance) throw new ServiceError('insufficient_minutes',402);
  await sql.query(`INSERT INTO minute_entries(id,account_id,reference,kind,balance_delta_ms,reserved_delta_ms,sandbox_delta_ms,funding_source)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [randomUUID(), account, reference, kind, balanceDelta, reservedDelta,sandboxDelta,source]);
  await sql.query('UPDATE minute_wallets SET balance_ms=$2,reserved_ms=$3,sandbox_balance_ms=$4 WHERE account_id=$1', [account, balance, reserved,sandbox]);
  return true;
}

export async function minuteBalance(db: Database, account: string, publicMinutes = false) {
  const row = (await db.query(`SELECT COALESCE(w.balance_ms,0) AS balance_ms,COALESCE(w.reserved_ms,0) AS reserved_ms,
    COALESCE(w.sandbox_balance_ms,0) AS sandbox_ms,COALESCE(w.sandbox_reconciled,true) AS sandbox_reconciled
    FROM accounts a LEFT JOIN minute_wallets w ON w.account_id=a.id WHERE a.id=$1 AND a.deleted_at IS NULL`, [account])).rows[0];
  if (!row) throw new ServiceError('account_not_found', 404);
  const reserved = Number(row.reserved_ms);
  const available = publicMinutes && !row.sandbox_reconciled ? 0 :
    Math.max(0,Number(row.balance_ms)-reserved-(publicMinutes ? Number(row.sandbox_ms) : 0));
  return { unit: 'milliseconds' as const, balanceMilliseconds: available+reserved,
    reservedMilliseconds: reserved, availableMilliseconds: available,
    billingBasis: 'connected-conversation-time' as const };
}

/** Runs in the signup transaction. Merely signing in again cannot create another offer. */
export async function captureWelcomeOffer(sql: PoolClient, account: string) {
  await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
  const policy = (await sql.query('SELECT * FROM minute_policy WHERE singleton')).rows[0];
  await sql.query(`INSERT INTO minute_welcome_offers(account_id,policy_version,allowance_ms) VALUES($1,$2,$3)
    ON CONFLICT(account_id) DO NOTHING`, [account, policy.version, policy.welcome_enabled ? policy.welcome_ms : 0]);
}

/** A verified claim consumes both a device allowance and the campaign budget atomically. */
export async function claimWelcomeMinutes(db: Database, account: string, proof: unknown, attestor: MinuteAttestor) {
  const verified = await attestor.verify(account, proof);
  if (!/^[A-Za-z0-9:_-]{8,200}$/.test(verified.deviceReference)) throw new ServiceError('invalid_trial_proof', 403);
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const policy = (await sql.query('SELECT * FROM minute_policy WHERE singleton')).rows[0];
    await lockMinuteWallet(sql, account);
    if((await sql.query(`SELECT 1 FROM minute_guest_link_intents i WHERE i.member_account_id=$1
      AND NOT EXISTS(SELECT 1 FROM minute_guest_link_completions c WHERE c.guest_account_id=i.guest_account_id) LIMIT 1`,[account])).rowCount)
      throw new ServiceError('finish_guest_conversation_first',409);
    const previous = (await sql.query('SELECT * FROM minute_welcome_claims WHERE account_id=$1 OR proof_reference=$2',
      [account, verified.deviceReference])).rows;
    if (previous.some(row => row.account_id !== account || row.proof_reference !== verified.deviceReference))
      throw new ServiceError('trial_already_claimed', 403);
    if (previous.length) return { grantedMilliseconds: Number(previous[0].allowance_ms), alreadyClaimed: true };
    if (verified.previouslyClaimed) throw new ServiceError('trial_already_claimed', 403);
    const offer = (await sql.query('SELECT allowance_ms FROM minute_welcome_offers WHERE account_id=$1', [account])).rows[0];
    const allowance = Number(offer?.allowance_ms ?? 0);
    if (!allowance) throw new ServiceError('welcome_minutes_unavailable', 403);
    await reserveWelcomeFunding(sql, account, allowance);
    await sql.query('INSERT INTO minute_welcome_claims(proof_reference,account_id,allowance_ms) VALUES($1,$2,$3)',
      [verified.deviceReference, account, allowance]);
    await appendMinuteEntry(sql, account, `welcome:${account}`, 'welcome', allowance, 0);
    return { grantedMilliseconds: allowance, alreadyClaimed: false };
  });
}

export async function reserveMinutes(db: Database, account: string, key: string, amount: number) {
  if (!validAmount(amount) || amount === 0 || amount > millisecondsForMinutes(1440) || key.length < 8 || key.length > 128)
    throw new ServiceError('invalid_minute_reservation');
  return transaction(db, async sql => {
    const wallet = await lockMinuteWallet(sql, account);
    const prior = (await sql.query('SELECT * FROM minute_reservations WHERE account_id=$1 AND idempotency_key=$2', [account, key])).rows[0];
    if (prior) {
      if (Number(prior.amount_ms) !== amount) throw new ServiceError('idempotency_conflict', 409);
      if (prior.state !== 'open') throw new ServiceError('reservation_closed', 409);
      return prior.id as string;
    }
    if (wallet.balance - wallet.reserved < amount) throw new ServiceError('insufficient_minutes', 402);
    const id = randomUUID();
    await sql.query('INSERT INTO minute_reservations(id,account_id,idempotency_key,amount_ms) VALUES($1,$2,$3,$4)', [id, account, key, amount]);
    await appendMinuteEntry(sql, account, `minute-reserve:${id}`, 'reserve', 0, amount);
    return id;
  });
}

export async function finishMinuteReservation(db: Database, id: string, usedMilliseconds: number | null) {
  if (usedMilliseconds !== null && !validAmount(usedMilliseconds)) throw new ServiceError('invalid_usage');
  return transaction(db, async sql => {
    const owner = (await sql.query('SELECT account_id FROM minute_reservations WHERE id=$1', [id])).rows[0];
    if (!owner) throw new ServiceError('reservation_not_found', 404);
    await lockMinuteWallet(sql, owner.account_id, false);
    const hold = (await sql.query('SELECT * FROM minute_reservations WHERE id=$1 FOR UPDATE', [id])).rows[0];
    const state = usedMilliseconds === null ? 'released' : 'settled';
    if (hold.state === state && (state === 'released' || Number(hold.used_ms) === usedMilliseconds)) return;
    if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
    if (usedMilliseconds !== null && usedMilliseconds > Number(hold.amount_ms)) throw new ServiceError('usage_exceeds_reservation', 409);
    await appendMinuteEntry(sql, owner.account_id, `minute-finish:${id}`, state === 'released' ? 'release' : 'settle',
      -(usedMilliseconds ?? 0), -Number(hold.amount_ms));
    await sql.query('UPDATE minute_reservations SET state=$2,used_ms=$3 WHERE id=$1', [id, state, usedMilliseconds]);
  });
}
