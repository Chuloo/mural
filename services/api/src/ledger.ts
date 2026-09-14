import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { RATE_VERSION } from './pricing.js';

type Kind = 'purchase' | 'reversal' | 'reserve' | 'settle' | 'release';
export async function lockWallet(sql: PoolClient, account: string, requireActive = false, requireMember = false) {
  // Every account mutation uses this lock order, including deletion and reconciliation.
  const owner = (await sql.query('SELECT deleted_at,is_guest FROM accounts WHERE id=$1 FOR UPDATE', [account])).rows[0];
  if (!owner || (requireActive && owner.deleted_at)) throw new ServiceError('account_not_found', 404);
  if (requireMember && owner.is_guest) throw new ServiceError('sign_in_required',401);
  const result = await sql.query('SELECT * FROM wallets WHERE account_id=$1 FOR UPDATE', [account]);
  const wallet = result.rows[0];
  if (!wallet) throw new ServiceError('account_not_found', 404);
  const balance = BigInt(wallet.balance_nano), reserved = BigInt(wallet.reserved_nano);
  const sandboxBalance = BigInt(wallet.sandbox_balance_nano), fundedBalance = balance - sandboxBalance;
  return { balance, reserved, sandboxBalance, fundedBalance, isGuest: Boolean(owner.is_guest),
    cashProvenanceVerified: Boolean(wallet.cash_provenance_verified),
    fundedAvailable: wallet.cash_provenance_verified && fundedBalance > reserved ? fundedBalance - reserved : 0n };
}

export async function lockPaidWallet(sql: PoolClient, account: string, requireActive = true) {
  const wallet = await lockWallet(sql, account, requireActive, true);
  if (!wallet.cashProvenanceVerified) throw new ServiceError('cash_balance_reconciliation_required', 409);
  return wallet;
}

export async function paidAIBalance(db: Database, account: string) {
  return transaction(db, async sql => {
    const wallet = await lockWallet(sql, account, true, true);
    return { balanceNanoUSD: wallet.fundedBalance.toString(), reservedNanoUSD: wallet.reserved.toString(),
      availableNanoUSD: wallet.fundedAvailable.toString(), cashProvenanceVerified: wallet.cashProvenanceVerified };
  });
}

/** Caller holds the account lock; only public, verified funds may back a provider attempt. */
export async function reservePaidInTransaction(sql: PoolClient, account: string, id: string, key: string, amount: bigint, rateVersion: string) {
  const wallet = await lockPaidWallet(sql, account);
  if (amount <= 0n || wallet.fundedAvailable < amount) throw new ServiceError('insufficient_credit', 402);
  await sql.query('INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version) VALUES($1,$2,$3,$4,$5)',
    [id, account, key, amount.toString(), rateVersion]);
  await appendEntry(sql, account, `reservation:${id}`, 'reserve', 0n, amount, rateVersion);
}

/** Known usage only. An overrun remains reserved for reconciliation instead of being guessed. */
export async function settlePaidInTransaction(sql: PoolClient, account: string, id: string, actual: bigint) {
  await lockWallet(sql, account);
  const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 AND account_id=$2 FOR UPDATE', [id, account])).rows[0];
  if (!hold) throw new ServiceError('reservation_not_found', 404);
  if (hold.state === 'settled' && BigInt(hold.actual_nano) === actual) return;
  if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
  const reserved = BigInt(hold.reserved_nano);
  if (actual < 0n || actual > reserved) throw new ServiceError('usage_exceeds_reservation', 409);
  await appendEntry(sql, account, `settlement:${id}`, 'settle', -actual, -reserved, hold.rate_version);
  await sql.query("UPDATE reservations SET state='settled',actual_nano=$2 WHERE id=$1", [id, actual.toString()]);
}
// Internal only. There is deliberately no HTTP endpoint that creates journal entries.
export async function appendEntry(sql: PoolClient, account: string, reference: string, kind: Kind,
  balanceDelta: bigint, reservedDelta: bigint, version: string | null = null, sandboxDelta: bigint = 0n): Promise<boolean> {
  const wallet = await lockWallet(sql, account);
  const existing = (await sql.query('SELECT * FROM ledger WHERE reference=$1', [reference])).rows[0];
  if (existing) {
    if (existing.account_id !== account || existing.kind !== kind || BigInt(existing.balance_delta_nano) !== balanceDelta ||
        BigInt(existing.reserved_delta_nano) !== reservedDelta || existing.rate_version !== version || BigInt(existing.sandbox_delta_nano)!==sandboxDelta)
      throw new ServiceError('idempotency_conflict', 409);
    return false;
  }
  if (wallet.reserved + reservedDelta < 0n) throw new ServiceError('invalid_reservation', 409);
  await sql.query(`INSERT INTO ledger(id,account_id,reference,kind,balance_delta_nano,reserved_delta_nano,rate_version,sandbox_delta_nano)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, [randomUUID(), account, reference, kind, balanceDelta.toString(), reservedDelta.toString(), version,sandboxDelta.toString()]);
  await sql.query('UPDATE wallets SET balance_nano=balance_nano+$2, reserved_nano=reserved_nano+$3,sandbox_balance_nano=sandbox_balance_nano+$4 WHERE account_id=$1',
    [account, balanceDelta.toString(), reservedDelta.toString(),sandboxDelta.toString()]);
  return true;
}
/** Legacy ledger test harness. It does not snapshot sandbox spending allocation.
 * Direct experiments require operator provenance review before public paid access.
 * Public paid adapters use reservePaidInTransaction instead. */
export async function reserve(db: Database, account: string, key: string, amount: bigint): Promise<string> {
  if (amount <= 0n || !key || key.length > 128) throw new ServiceError('invalid_reservation');
  return transaction(db, async sql => {
    const wallet = await lockWallet(sql, account, true);
    const previous = (await sql.query('SELECT * FROM reservations WHERE account_id=$1 AND idempotency_key=$2', [account, key])).rows[0];
    if (previous) {
      if (BigInt(previous.reserved_nano) !== amount) throw new ServiceError('idempotency_conflict', 409);
      return previous.id;
    }
    if (wallet.balance - wallet.reserved < amount) throw new ServiceError('insufficient_credit', 402);
    const id = randomUUID();
    await sql.query('INSERT INTO reservations(id,account_id,idempotency_key,reserved_nano,rate_version) VALUES($1,$2,$3,$4,$5)',
      [id, account, key, amount.toString(), RATE_VERSION]);
    await appendEntry(sql, account, `reservation:${id}`, 'reserve', 0n, amount, RATE_VERSION);
    return id;
  });
}
/** Legacy test settlement; public paid voice/helpers use settlePaidInTransaction.
 * Do not approve a directly exercised legacy wallet for public use without reconciliation. */
export async function settle(db: Database, id: string, actual: bigint): Promise<void> {
  if (actual < 0n) throw new ServiceError('invalid_usage');
  await transaction(db, async sql => {
    const owner = (await sql.query('SELECT account_id FROM reservations WHERE id=$1', [id])).rows[0];
    if (!owner) throw new ServiceError('reservation_not_found', 404);
    await lockWallet(sql, owner.account_id);
    const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [id])).rows[0];
    if (hold.state === 'settled' && BigInt(hold.actual_nano) === actual) return;
    if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
    const reserved = BigInt(hold.reserved_nano);
    if (actual > reserved) throw new ServiceError('usage_exceeds_reservation', 409);
    await appendEntry(sql, hold.account_id, `settlement:${id}`, 'settle', -actual, -reserved, hold.rate_version);
    await sql.query("UPDATE reservations SET state='settled',actual_nano=$2 WHERE id=$1", [id, actual.toString()]);
  });
}
export async function release(db: Database, id: string): Promise<void> {
  await transaction(db, async sql => {
    const owner = (await sql.query('SELECT account_id FROM reservations WHERE id=$1', [id])).rows[0];
    if (!owner) throw new ServiceError('reservation_not_found', 404);
    await lockWallet(sql, owner.account_id);
    const hold = (await sql.query('SELECT * FROM reservations WHERE id=$1 FOR UPDATE', [id])).rows[0];
    if (hold.state === 'released') return;
    if (hold.state !== 'open') throw new ServiceError('reservation_closed', 409);
    await appendEntry(sql, hold.account_id, `release:${id}`, 'release', 0n, -BigInt(hold.reserved_nano), hold.rate_version);
    await sql.query("UPDATE reservations SET state='released' WHERE id=$1", [id]);
  });
}
