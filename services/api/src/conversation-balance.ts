import type { PurchaseEnvironment } from './minute-purchases.js';
import { transaction, type Database } from './db.js';
import { minuteBalance } from './minutes.js';
import { lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';
import { estimatedConversationMilliseconds } from './ai-top-up-pricing.js';

export interface PaidBalancePolicy { enabled: boolean; estimatedNanoUSDPerMinute: bigint; minimumSessionNanoUSD: bigint }

/** Display only. Admission reserves funds again under the account lock. */
export async function conversationBalance(db: Database, account: string, publicMinutes = false, policy?: PaidBalancePolicy,environment?:PurchaseEnvironment) {
  return transaction(db, async sql => {
    const owner = (await sql.query('SELECT is_guest,minutes_revision FROM accounts WHERE id=$1 AND deleted_at IS NULL FOR UPDATE', [account])).rows[0];
    if (!owner) throw new ServiceError('account_not_found',404);
    const minuteWalletBalance = await minuteBalance(sql,account,publicMinutes);
    // A verified public sandbox request has only sandbox paid funding. The
    // underlying free wallet still participates in settlement detection below.
    const free = publicMinutes && environment==='test' ? {...minuteWalletBalance,
      balanceMilliseconds:0,reservedMilliseconds:0,availableMilliseconds:0} : minuteWalletBalance;
    const wallet = owner.is_guest ? undefined : await lockWallet(sql,account,true,true,environment);
    const supported = !!policy?.enabled && !!wallet?.cashProvenanceVerified;
    const available = supported ? wallet!.fundedAvailable : 0n;
    const estimate = supported ? estimatedConversationMilliseconds(available,policy!.estimatedNanoUSDPerMinute) : 0;
    const active = (await sql.query("SELECT state FROM hosted_sessions WHERE account_id=$1 AND state<>'closed' LIMIT 1",[account])).rows[0]?.state;
    const settling = active==='closing' || active==='incomplete' || (!active && (minuteWalletBalance.reservedMilliseconds>0 || (wallet?.reserved??0n)>0n));
    const paid = supported ? {currency:'USD' as const,billingBasis:'actual-ai-usage' as const,
      balanceNanoUSD:wallet!.fundedBalance.toString(),reservedNanoUSD:wallet!.fundedReserved.toString(),availableNanoUSD:available.toString(),
      estimatedMilliseconds:estimate,estimatedNanoUSDPerMinute:policy!.estimatedNanoUSDPerMinute.toString(),
      minimumSessionNanoUSD:policy!.minimumSessionNanoUSD.toString(),available:available>=policy!.minimumSessionNanoUSD} : undefined;
    const paidUnknown = !owner.is_guest && !!wallet && (!wallet.cashProvenanceVerified || (!supported && wallet.fundedBalance>0n));
    const ready = free.availableMilliseconds>0 || paid?.available===true;
    const presentation = {schemaVersion:1,asOf:new Date().toISOString(),revision:String(owner.minutes_revision),
      freeAvailableMilliseconds:free.availableMilliseconds,paidEstimatedMilliseconds:paidUnknown?null:estimate,
      hasPurchasedRemainder:available>0n,totalDisplayMilliseconds:paidUnknown?null:free.availableMilliseconds+estimate,
      displayKind:available>0n?'approximate':'exactFree',
      estimateRateVersion:supported?`nano-usd-per-minute-${policy!.estimatedNanoUSDPerMinute}`:null,
      availabilityReason:settling?'settling':active?'active_conversation':paidUnknown?'account_action_needed':ready?'ready':'insufficient_remaining_time',
      settlementState:settling?'pending':active?'in_use':'settled',paidSupported:supported};
    return {...free,...(paid?{paid}:{}),presentation};
  });
}
