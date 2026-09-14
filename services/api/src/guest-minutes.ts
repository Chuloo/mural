import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { ServiceError } from './errors.js';
import { appendMinuteEntry, lockMinuteWallet } from './minutes.js';
import { reserveWelcomeFunding } from './welcome-funding.js';

export interface GuestMinuteAttestor {
  readonly requiresTrustedAdmission?: boolean;
  // Validate the configured guest proof; the capped installation beta proves possession only.
  verify(proof: unknown): Promise<{ deviceReference: string; previouslyClaimed: boolean }>;
}
/** Capped beta proof of possession, not hardware attestation. Reinstallation may create another token. */
export class InstallationGuestMinuteAttestor implements GuestMinuteAttestor {
  readonly requiresTrustedAdmission = true;
  async verify(proof: unknown) {
    if (!proof || typeof proof !== 'object' || Array.isArray(proof) || Object.keys(proof).length !== 1 ||
      !('installationToken' in proof) || typeof proof.installationToken !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(proof.installationToken) ||
      Buffer.from(proof.installationToken, 'base64url').toString('base64url') !== proof.installationToken)
      throw new ServiceError('invalid_trial_proof', 400);
    return { deviceReference: `install:${hashToken(proof.installationToken)}`, previouslyClaimed: false };
  }
}
export class UnconfiguredGuestMinuteAttestor implements GuestMinuteAttestor {
  async verify(_proof: unknown): Promise<never> { throw new ServiceError('trial_attestation_unavailable', 503); }
}
const hashToken = (token: string) => createHash('sha256').update(token).digest('hex');

/** Issues or resumes the same device allowance. No name, email or signup is collected. */
export async function startGuestMinutes(db: Database, proof: unknown, attestor: GuestMinuteAttestor) {
  const verified = await attestor.verify(proof);
  if (!/^[A-Za-z0-9:_-]{8,200}$/.test(verified.deviceReference)) throw new ServiceError('invalid_trial_proof', 403);
  const token = randomBytes(32).toString('base64url');
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const policy = (await sql.query('SELECT * FROM minute_policy WHERE singleton')).rows[0];
    const claim = (await sql.query(`SELECT c.*,a.is_guest,a.deleted_at FROM minute_welcome_claims c
      JOIN accounts a ON a.id=c.account_id WHERE proof_reference=$1`, [verified.deviceReference])).rows[0];
    let account: string;
    if (claim) {
      if (!claim.is_guest || claim.deleted_at) throw new ServiceError('sign_in_to_continue', 403);
      account = claim.account_id;
    } else {
      if (verified.previouslyClaimed) throw new ServiceError('trial_already_claimed', 403);
      const allowance = Number(policy.welcome_ms);
      if (!policy.welcome_enabled || !allowance) throw new ServiceError('welcome_minutes_unavailable', 503);
      account = randomUUID();
      await sql.query('INSERT INTO accounts(id,is_guest) VALUES($1,true)', [account]);
      await reserveWelcomeFunding(sql, account, allowance);
      await sql.query('INSERT INTO minute_welcome_claims(proof_reference,account_id,allowance_ms) VALUES($1,$2,$3)',
        [verified.deviceReference, account, allowance]);
      await appendMinuteEntry(sql, account, `welcome:${account}`, 'welcome', allowance, 0);
    }
    const balance = await lockMinuteWallet(sql, account);
    // Keep a bounded number of guest sessions so a retry does not break an in-flight response.
    await sql.query(`DELETE FROM auth_sessions WHERE account_id=$1 AND (expires_at<=now() OR revoked_at IS NOT NULL OR id IN
      (SELECT id FROM auth_sessions WHERE account_id=$1 ORDER BY created_at DESC,id DESC OFFSET 4))`, [account]);
    await sql.query("INSERT INTO auth_sessions(id,account_id,token_hash,expires_at) VALUES($1,$2,$3,now()+interval '24 hours')",
      [randomUUID(), account, hashToken(token)]);
    return { guestID: account, accessToken: token, expiresInSeconds: 86_400,
      remainingMilliseconds: balance.balance - balance.reserved, resumed: Boolean(claim) };
  });
}

/** The signed-in target and guest credentials are both required; email never links balances. */
export async function linkGuestMinutes(db: Database, member: string, guestToken: string) {
  if (!/^[A-Za-z0-9_-]{43}$/.test(guestToken)) throw new ServiceError('invalid_guest_session', 401);
  const tokenHash = hashToken(guestToken);
  return transaction(db, async sql => {
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const previous = (await sql.query('SELECT * FROM minute_guest_links WHERE guest_token_hash=$1', [tokenHash])).rows[0];
    if (previous) {
      if (previous.member_account_id !== member) throw new ServiceError('guest_already_linked', 403);
      await lockMinuteWallet(sql, member);
      return { transferredMilliseconds: Number(previous.transferred_ms), alreadyLinked: true,outcome:previous.outcome as 'transferred'|'member_trial_already_claimed' };
    }
    const session = (await sql.query(`SELECT s.account_id FROM auth_sessions s JOIN accounts a ON a.id=s.account_id
      WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.is_guest AND a.deleted_at IS NULL`, [tokenHash])).rows[0];
    if (!session || session.account_id === member) throw new ServiceError('invalid_guest_session', 401);
    const guest = session.account_id as string;
    for (const id of [member, guest].sort()) await lockMinuteWallet(sql, id);
    const target = (await sql.query('SELECT is_guest FROM accounts WHERE id=$1', [member])).rows[0];
    if (target.is_guest) throw new ServiceError('sign_in_required', 401);
    const balance = await lockMinuteWallet(sql, guest);
    if (balance.reserved) throw new ServiceError('finish_guest_conversation_first', 409);
    // An existing member keeps its own allowance and paid balance. The duplicate guest is retired,
    // rather than trapping a successful login behind a transfer that can never be permitted.
    const alreadyClaimed=Boolean((await sql.query('SELECT 1 FROM minute_welcome_claims WHERE account_id=$1', [member])).rowCount);
    const outcome=alreadyClaimed ? 'member_trial_already_claimed' as const : 'transferred' as const;
    const transferred=alreadyClaimed ? 0 : balance.balance;
    await appendMinuteEntry(sql, guest, `guest-link-out:${guest}`, alreadyClaimed ? 'forfeit' : 'transfer', -balance.balance, 0);
    if (!alreadyClaimed) {
      await appendMinuteEntry(sql, member, `guest-link-in:${guest}`, 'transfer', balance.balance, 0);
      await sql.query('UPDATE minute_welcome_claims SET account_id=$2 WHERE account_id=$1', [guest, member]);
    }
    await sql.query('INSERT INTO minute_guest_links(guest_account_id,member_account_id,guest_token_hash,transferred_ms,outcome) VALUES($1,$2,$3,$4,$5)',
      [guest, member, tokenHash, transferred,outcome]);
    await sql.query('UPDATE auth_sessions SET revoked_at=now() WHERE account_id=$1', [guest]);
    await sql.query('UPDATE accounts SET deleted_at=now() WHERE id=$1', [guest]);
    return { transferredMilliseconds: transferred, alreadyLinked: false,outcome };
  });
}
