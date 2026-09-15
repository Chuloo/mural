import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import type { PoolClient } from 'pg';
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
      if (!claim.is_guest || claim.deleted_at || (await sql.query('SELECT 1 FROM minute_guest_link_intents WHERE guest_account_id=$1',[claim.account_id])).rowCount) throw new ServiceError('sign_in_to_continue', 403);
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

type LinkOutcome = 'transferred'|'member_trial_already_claimed'|'member_deleted';
export interface GuestLinkResult {transferredMilliseconds:number;alreadyLinked:boolean;outcome:LinkOutcome|'pending';pending?:boolean}
type LinkIntent = { guest_account_id:string; member_account_id:string; guest_token_hash:string };
const pendingLink = () => ({transferredMilliseconds:0,alreadyLinked:false,outcome:'pending' as const,pending:true});

async function lockLinkAccounts(sql:PoolClient,member:string,guest:string,allowDeleted=false){
  for(const id of [member,guest].sort())await lockMinuteWallet(sql,id,!allowDeleted);
  const target=(await sql.query('SELECT is_guest,deleted_at FROM accounts WHERE id=$1',[member])).rows[0];
  if(target.is_guest)throw new ServiceError('sign_in_required',401);
  return target;
}
async function guestReady(sql:PoolClient,guest:string){
  const balance=await lockMinuteWallet(sql,guest,false);
  const unresolved=(await sql.query("SELECT 1 FROM hosted_sessions WHERE account_id=$1 AND state<>'closed' LIMIT 1",[guest])).rowCount;
  return !balance.reserved&&!unresolved;
}
/** Caller holds the welcome lock and both account locks. No provider usage is inferred here. */
async function transferGuest(sql:PoolClient,member:string,guest:string,tokenHash:string,deleted=false){
  const balance=await lockMinuteWallet(sql,guest,false);
  const alreadyClaimed=Boolean((await sql.query('SELECT 1 FROM minute_welcome_claims WHERE account_id=$1',[member])).rowCount);
  const outcome:LinkOutcome=deleted?'member_deleted':alreadyClaimed?'member_trial_already_claimed':'transferred';
  const transferred=outcome==='transferred'?balance.balance:0;
  await appendMinuteEntry(sql,guest,`guest-link-out:${guest}`,outcome==='transferred'?'transfer':'forfeit',-balance.balance,0);
  if(outcome==='transferred'){
    await appendMinuteEntry(sql,member,`guest-link-in:${guest}`,'transfer',balance.balance,0);
    await sql.query('UPDATE minute_welcome_claims SET account_id=$2 WHERE account_id=$1',[guest,member]);
  }
  if(!deleted)await sql.query('INSERT INTO minute_guest_links(guest_account_id,member_account_id,guest_token_hash,transferred_ms,outcome) VALUES($1,$2,$3,$4,$5)',
    [guest,member,tokenHash,transferred,outcome]);
  await sql.query('UPDATE auth_sessions SET revoked_at=now() WHERE account_id=$1',[guest]);
  await sql.query('UPDATE accounts SET deleted_at=now() WHERE id=$1',[guest]);
  return {transferredMilliseconds:transferred,alreadyLinked:false,outcome};
}
async function finishIntent(sql:PoolClient,intent:LinkIntent,activeMember=false){
  const target=await lockLinkAccounts(sql,intent.member_account_id,intent.guest_account_id,true);
  if(activeMember&&target.deleted_at)throw new ServiceError('account_not_found',404);
  const done=(await sql.query('SELECT outcome,transferred_ms FROM minute_guest_link_completions WHERE guest_account_id=$1',[intent.guest_account_id])).rows[0];
  if(done)return {transferredMilliseconds:Number(done.transferred_ms),alreadyLinked:true,outcome:done.outcome as LinkOutcome,pending:false};
  if(!await guestReady(sql,intent.guest_account_id))return pendingLink();
  const result=await transferGuest(sql,intent.member_account_id,intent.guest_account_id,intent.guest_token_hash,Boolean(target.deleted_at));
  await sql.query('INSERT INTO minute_guest_link_completions(guest_account_id,outcome,transferred_ms) VALUES($1,$2,$3)',
    [intent.guest_account_id,result.outcome,result.transferredMilliseconds]);
  return {...result,pending:false};
}

/** Both credentials establish ownership. An accepted binding can later be retried using only member authentication. */
export async function linkGuestMinutes(db:Database,member:string,guestToken?:string,deferPending=false,guestAccountID?:string):Promise<GuestLinkResult>{
  if(guestToken!==undefined&&!/^[A-Za-z0-9_-]{43}$/.test(guestToken))throw new ServiceError('invalid_guest_session',401);
  if(guestAccountID!==undefined&&!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(guestAccountID))throw new ServiceError('invalid_request');
  if(!guestToken&&deferPending&&!guestAccountID)throw new ServiceError('invalid_request');
  if(!guestToken&&!deferPending)throw new ServiceError('invalid_guest_session',401);
  const tokenHash=guestToken?hashToken(guestToken):undefined;
  return transaction(db,async sql=>{
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    if(!tokenHash){
      const intent=(await sql.query(`SELECT * FROM minute_guest_link_intents
        WHERE member_account_id=$1 AND guest_account_id=$2`,[member,guestAccountID])).rows[0];
      if(!intent)throw new ServiceError('guest_link_not_found',404);
      return finishIntent(sql,intent,true);
    }
    const previous=(await sql.query('SELECT * FROM minute_guest_links WHERE guest_token_hash=$1',[tokenHash])).rows[0];
    if(previous){
      if(guestAccountID&&previous.guest_account_id!==guestAccountID)throw new ServiceError('guest_link_mismatch',409);
      if(previous.member_account_id!==member)throw new ServiceError('guest_already_linked',403);
      await lockMinuteWallet(sql,member);
      return {transferredMilliseconds:Number(previous.transferred_ms),alreadyLinked:true,outcome:previous.outcome as LinkOutcome,...(deferPending?{pending:false}:{})};
    }
    let intent=(await sql.query('SELECT * FROM minute_guest_link_intents WHERE guest_token_hash=$1',[tokenHash])).rows[0];
    let guest:string;
    if(intent){
      if(intent.member_account_id!==member)throw new ServiceError('guest_already_linked',403);
      guest=intent.guest_account_id;
    }else{
      const session=(await sql.query(`SELECT s.account_id FROM auth_sessions s JOIN accounts a ON a.id=s.account_id
        WHERE s.token_hash=$1 AND s.expires_at>now() AND s.revoked_at IS NULL AND a.is_guest AND a.deleted_at IS NULL`,[tokenHash])).rows[0];
      if(!session||session.account_id===member)throw new ServiceError('invalid_guest_session',401);
      guest=session.account_id;
      intent=(await sql.query('SELECT * FROM minute_guest_link_intents WHERE guest_account_id=$1',[guest])).rows[0];
      if(intent&&intent.member_account_id!==member)throw new ServiceError('guest_already_linked',403);
    }
    if(guestAccountID&&guest!==guestAccountID)throw new ServiceError('guest_link_mismatch',409);
    await lockLinkAccounts(sql,member,guest);
    if(intent){
      if(!deferPending&&!await guestReady(sql,guest))throw new ServiceError('finish_guest_conversation_first',409);
      return finishIntent(sql,intent);
    }
    if(!deferPending){
      if(!await guestReady(sql,guest))throw new ServiceError('finish_guest_conversation_first',409);
      return transferGuest(sql,member,guest,tokenHash);
    }
    intent={guest_account_id:guest,member_account_id:member,guest_token_hash:tokenHash};
    await sql.query('INSERT INTO minute_guest_link_intents(guest_account_id,member_account_id,guest_token_hash) VALUES($1,$2,$3)',[guest,member,tokenHash]);
    // The provider worker still owns the final receipt and original guest accounting.
    await sql.query(`UPDATE hosted_sessions SET close_requested_at=COALESCE(close_requested_at,now()),
      close_reason=COALESCE(close_reason,'user_requested') WHERE account_id=$1 AND state<>'closed'`,[guest]);
    return finishIntent(sql,intent);
  });
}

/** Bounded retry; accepted bindings survive expired/revoked guest credentials and process restarts. */
export async function finalizeDeferredGuestLinks(db:Database,limit=25){
  if(!Number.isSafeInteger(limit)||limit<1||limit>100)throw new ServiceError('invalid_guest_link_batch');
  const candidates=(await db.query(`SELECT i.* FROM minute_guest_link_intents i JOIN minute_wallets w ON w.account_id=i.guest_account_id
    WHERE w.reserved_ms=0 AND NOT EXISTS(SELECT 1 FROM minute_guest_link_completions c WHERE c.guest_account_id=i.guest_account_id)
    AND NOT EXISTS(SELECT 1 FROM hosted_sessions h WHERE h.account_id=i.guest_account_id AND h.state<>'closed')
    ORDER BY i.created_at,i.guest_account_id LIMIT $1`,[limit])).rows;
  let completed=0;
  for(const intent of candidates)await transaction(db,async sql=>{
    await sql.query("SELECT pg_advisory_xact_lock(hashtext('mural-welcome-minutes'))");
    const result=await finishIntent(sql,intent);if(!result.pending&&!result.alreadyLinked)completed++;
  });
  return {examined:candidates.length,completed};
}
