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
3. For a receiptless Play order, request recovery from the original signed-in Play account and inspect the actual store result. If no token arrives, do not invent a void or delete its binding. A delayed purchase can complete after the app closes; the current worker knows only saved tokens. The case remains for explicit operator resolution until authoritative evidence or a separately reviewed late-payment closeout design resolves it.
4. Agree the refund amount and fee treatment before issuing it through the provider dashboard. Keep the provider action manual. Then verify its succeeded state and the resulting immutable ledger reversal. The backend has no operation that initiates refunds, forgives debt or writes off unused paid value.
5. Use the existing authenticated account-deletion flow after financial blockers clear. A separately reviewed operator invocation of the same `deleteAccount` function must preserve its checks; do not directly update `deleted_at` or remove order rows to bypass them. It removes email, identities and auth sessions. Settled financial history retains an opaque account reference; a signup-only account is removed entirely. Apple revocation must succeed when applicable.
6. Confirm completion to the requester and explain any necessary financial retention. Include backup retention in the privacy disclosure. Conversations and ordinary learning data remain on the device; account deletion does not remotely erase a user's local archive.

If resolution is pending, explain the specific pending payment or refund rather than saying the account has been deleted. Support requests need an owner and a documented response schedule before paid launch; this runbook does not set an unapproved deadline.

## Current refund arithmetic and limits

The app does not automatically refund a customer. Stripe or Google confirms a refund initiated outside Mural, and the verified fulfillment path then updates AI value. A full refund or void removes the full original AI allocation. Partial refunds remove `ceil(original AI allocation × cumulative refunded gross / original checkout gross)`. Replays and older provider snapshots cannot grant the same value twice or reverse the same portion twice.

The original processing and currency-conversion fees are not returned by Stripe, and a refund may have additional costs under the merchant's fee schedule. Mural's quote separates estimated processing costs and a buffer; the backend does not retrieve the actual processor fee or automatically deduct that loss from the customer's refund. [Stripe refund fees](https://support.stripe.com/questions/understanding-fees-for-refunded-payments)

Consequently, refunding the checkout amount minus a retained processing fee leaves a proportional AI remainder. It cannot be used as a full account-closeout shortcut. Refunding only unused AI value, retaining Mural's fee, or retaining processing costs requires an explicit customer-facing policy and an accounting design for the remaining allocation. Do not silently forfeit it.

A refund after spending may leave negative AI value. New paid sessions cannot spend that debt, and a later purchase offsets it before adding availability. Existing authorized voice/helper holds remain so their real cost can settle. There is no automatic refund-request spending freeze; staff must account for activity between inspection and refund. A full refund of already-used AI can therefore cost Mural both the AI spend and unrecovered payment fees.

Stripe disputes conservatively void the entitlement. That state is intentionally monotonic; winning or withdrawing a dispute does not restore value automatically. A restoration needs a separately reviewed, audited adjustment after authoritative evidence. Do not edit the original purchase or reversal history.

Known Play receipts are rechecked by the worker and void polling. There is no authenticated real-time notification intake for undiscovered tokens. Manual email support does not resolve that discovery gap, and elapsed time is not sufficient proof that an abandoned pending purchase cannot finish later.
