import type { Database } from './db.js';
import { minuteBalance } from './minutes.js';
import { paidAIBalance } from './ledger.js';
import { estimatedConversationMilliseconds } from './ai-top-up-pricing.js';

export interface PaidBalancePolicy { enabled: boolean; estimatedNanoUSDPerMinute: bigint; minimumSessionNanoUSD: bigint }

/** Display only. Admission reserves funds again under the account lock. */
export async function conversationBalance(db: Database, account: string, publicMinutes = false, policy?: PaidBalancePolicy) {
  const free = await minuteBalance(db, account, publicMinutes);
  if (!policy?.enabled) return free;
  const owner = (await db.query('SELECT is_guest FROM accounts WHERE id=$1 AND deleted_at IS NULL', [account])).rows[0];
  if (!owner || owner.is_guest) return free;
  const wallet = await paidAIBalance(db, account);
  // Historical sandbox value requires an explicit operator reconciliation before public use.
  if (!wallet.cashProvenanceVerified) return free;
  const available = BigInt(wallet.availableNanoUSD);
  return { ...free, paid: { currency: 'USD' as const, billingBasis: 'actual-ai-usage' as const,
    balanceNanoUSD: wallet.balanceNanoUSD, reservedNanoUSD: wallet.reservedNanoUSD, availableNanoUSD: wallet.availableNanoUSD,
    estimatedMilliseconds: estimatedConversationMilliseconds(available, policy.estimatedNanoUSDPerMinute),
    estimatedNanoUSDPerMinute: policy.estimatedNanoUSDPerMinute.toString(), minimumSessionNanoUSD: policy.minimumSessionNanoUSD.toString(),
    available: available >= policy.minimumSessionNanoUSD } };
}
