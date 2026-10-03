# Manual refund and account-deletion support

Users can ask `hi@hackmamba.io` to delete their Mural account. Support must verify the account, resolve its financial obligations and then remove identifying account data. This is a manual request path; it does not waive refund rights, prove that an unpaid-looking order cannot charge later, or authorize forfeiting prepaid value. Google permits a customer-service email as part of an accessible external deletion path; the linked page must clearly identify Mural and explain how to make the request. An in-app path is also required. [Google account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)

## Inspect a verified support case

Verify the requester owns the signed-in account before acting on its UUID. An arbitrary email or copied order ID alone is not ownership proof. Do not ask the user to send an API key, bearer token, password or full card details. Keep the support case and provider receipts private.

The operator command is read-only and uses a repeatable-read, read-only database transaction:

```sh
node dist/src/account-closeout-admin.js inspect < /absolute/private/account-selector.json
```

The selector contains exactly `{ "accountID": "<verified-account-uuid>" }`. Supply `DATABASE_URL` through the existing protected operator environment, not shell arguments. The result contains only the account/order UUIDs and booleans. It excludes email, identity-provider subjects, purchase tokens, encrypted receipt content and provider secrets. The command changes no balance, entitlement, session or account data.

`readyForExistingDeletionChecks` is a triage result from that snapshot, not deletion authorization. It checks for cash value/debt/holds, purchased minutes, pending orders, active conversations and unresolved helper usage. `appleRevocationRequired` is separate: Apple-linked accounts need the existing fresh-authorization revocation flow. Recheck the actual deletion guard after resolving a case because new activity can occur after inspection.

## Resolve the recorded blockers

1. Ask the user to end the current conversation. Allow trusted voice/helper settlement and any outstanding provider verification to finish. Unknown usage keeps its reservation; elapsed time alone does not prove its cost is zero.
2. For each order with a saved receipt, use the configured provider reconciliation path or let its durable job run. Stripe Checkout expiry and provider-confirmed voids can resolve an unpaid order. A failed refund, pending refund, cancellation request or screenshot is not a confirmed completed refund.
3. A quote with no receipt and no verified transaction stops blocking account deletion after 24 hours. Play uses this window only while its private notification subscriber is operational; otherwise the receiptless order still blocks deletion. This permits removal of identifying account data but does not prove cancellation. The opaque account, wallet, order, immutable quote and provider binding remain. A known pending transaction or saved receipt still requires reconciliation. Never invent a provider void or remove an order binding.
4. If a charge arrives after account deletion, verified server delivery remains bound to that retained account. It cannot be spent, transferred to a new signup or accessed with a revoked session. Support must reconcile the provider charge and refund through the provider’s normal process. Apple notifications and historical reconciliation retain the transaction automatically. For a receiptless Play charge discovered through support, verify the provider token against the retained binding before saving it to the protected receipt vault; the existing worker then verifies and reconciles it. Do not accept a screenshot as payment evidence.
5. Agree the refund amount and fee treatment before issuing it through the provider dashboard. Keep the provider action manual. Then verify its succeeded state and the resulting immutable ledger reversal. The backend has no operation that initiates refunds, forgives debt or writes off unused paid value.
6. Use the existing authenticated account-deletion flow after financial blockers clear. A separately reviewed operator invocation of the same `deleteAccount` function must preserve its checks; do not directly update `deleted_at` or remove order rows to bypass them. It removes email, identities and auth sessions. Settled financial history retains an opaque account reference; a signup-only account is removed entirely. Apple revocation must succeed when applicable.
7. Confirm completion to the requester and explain any necessary financial retention. Include backup retention in the privacy disclosure. Conversations and ordinary learning data remain on the device; account deletion does not remotely erase a user's local archive.

If resolution is pending, explain the specific pending payment or refund rather than saying the account has been deleted. William Imoh owns these cases through hi@hackmamba.io. Before paid launch, agree the response schedule and review retained deleted-account balances; `account-closeout-admin inspect` exposes `latePaymentNeedsReview` without disclosing identity or receipts.

## Current refund arithmetic and limits

The app does not automatically refund a customer. Apple, Stripe or Google confirms the refund, and verified fulfillment then updates AI value. Apple’s in-app refund sheet submits a request; it does not by itself confirm a reversal. Apple signed refund revisions support retry-safe reversal and restoration when Apple reverses a refund. A full refund or void removes the full original AI allocation. Partial refunds remove `ceil(original AI allocation × cumulative refunded gross / original checkout gross)`. Replays and older provider snapshots cannot grant the same value twice or reverse the same portion twice.

The original processing and currency-conversion fees are not returned by Stripe, and a refund may have additional costs under the merchant's fee schedule. Mural's quote separates estimated processing costs and a buffer; the backend does not retrieve the actual processor fee or automatically deduct that loss from the customer's refund. [Stripe refund fees](https://support.stripe.com/questions/understanding-fees-for-refunded-payments)

Consequently, refunding the checkout amount minus a retained processing fee leaves a proportional AI remainder. It cannot be used as a full account-closeout shortcut. Refunding only unused AI value, retaining Mural's fee, or retaining processing costs requires an explicit customer-facing policy and an accounting design for the remaining allocation. Do not silently forfeit it.

A refund after spending may leave negative AI value. New paid sessions cannot spend that debt, and a later purchase offsets it before adding availability. Existing authorized voice/helper holds remain so their real cost can settle. There is no automatic refund-request spending freeze; staff must account for activity between inspection and refund. A full refund of already-used AI can therefore cost Mural both the AI spend and unrecovered payment fees.

Stripe disputes conservatively void the entitlement. That state is intentionally monotonic; winning or withdrawing a dispute does not restore value automatically. A restoration needs a separately reviewed, audited adjustment after authoritative evidence. Do not edit the original purchase or reversal history.

Known Play receipts are rechecked by the worker and void polling. When the private Play Pub/Sub subscriber is configured and its recent pull has succeeded, a late purchase token can be discovered after deletion and verified against its retained order binding. Without that operational subscriber, receiptless Play orders continue to block deletion beyond 24 hours. A late paid purchase on a deleted account is credited only to the opaque retained account and requires support review for refund; it cannot fund a new signup.

## Retained account review

Review this aggregate with the protected operator database connection. Any nonzero result needs a private support case; it must not trigger an automatic transfer or write-off.

```sql
SELECT count(*) AS deleted_accounts_requiring_payment_review
FROM accounts a JOIN wallets w ON w.account_id=a.id
LEFT JOIN minute_wallets m ON m.account_id=a.id
WHERE a.deleted_at IS NOT NULL
  AND (w.balance_nano<>0 OR w.reserved_nano<>0 OR COALESCE(m.balance_ms,0)>0 OR COALESCE(m.reserved_ms,0)>0);
```

The 24-hour rule applies only to receiptless, unverified purchase quotes, with operational notification intake required for Play. It does not clear a paid balance, debt, reservation, known pending transaction or legacy checkout. Tests cover deletion, late verified delivery, duplicate delivery and a subsequent full reversal against the retained account.
