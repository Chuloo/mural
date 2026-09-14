# Conversation minutes

Mural's planned minute packs grant conversation time. A 30-minute pack grants 1,800 seconds of connected conversation, independent of model token counts. Pauses within an active conversation count; ending the conversation stops the clock. Normal meanings and teaching assessments belong in the pack price, with bounded helper usage. Final pack prices and live sales remain pending cost verification.

Provider costs and user time have different ledgers. The existing money ledger records financial amounts. The minute ledger records time grants, reservations, settlement and release using integer milliseconds. Model price changes must not change time already purchased. The old sandbox dollar-credit endpoints remain temporarily for compatibility; they are not the consumer minute purchase implementation.

## New-user allowance

The server stores a versioned welcome policy: whether new users receive an offer, its length, and daily and lifetime allocation budgets. The shipped default is disabled, with a ten-minute proposed allowance and zero allocation budgets. Enabling an offer requires explicit budgets.

The first verified guest trial receives the current allowance without collecting a name or email. Someone who signs up first instead receives a fixed offer at account creation. Repeated login or guest access cannot create a new allowance. Reducing the allowance or pausing new offers leaves existing offers and issued balances unchanged. An offer is credited only after verified eligibility, subject to the allocation budgets. The current production attestation adapters remain unconfigured, so no public free time is being activated by this change.

Allocation budgets cap the amount of welcome time issued, measured on UTC days. They are not a real-time dollar spending cap. Existing grants can be used later, and helper/provider costs require separate operational budgets and session cutoffs. Reducing an allocation budget does not confiscate time already issued.

A separate funding policy reserves a conservative dollar cost for every new welcome grant. It has daily and lifetime budgets, starts with zero funding, and retains each grant's original reserve when its rate changes. Dollar and minute limits are checked in the same transaction as the grant. Repeated claims, guest-to-member transfer and spending time cannot replenish either budget. This bounds new funding commitments; provider usage still needs enforced session and helper limits.

Signing in transfers the guest's remaining balance after the current conversation settles. For example, using three of ten minutes as a guest leaves seven minutes after signup. Transfer requires both the signed-in account and the guest session, is safe to retry, and retires the old guest session. An account that already claimed free time cannot stack another device's trial. The old experimental trial route must not be advertised as the finished guest flow.

## Gifts

An operator can prepare a grant for selected account IDs or all current accounts. Preparation freezes the recipients and returns the count, total minutes and confirmation digest. Applying the reviewed campaign credits at most 200 recipients per transaction. Interrupted runs can resume; each recipient receives the grant once. New accounts created afterward are excluded, and accounts deleted before application are skipped.

Every campaign needs an operator label, a reason and a maximum total allocation. Minute entries are immutable. A correction must be an explicit compensating operation, not an edit to a previous journal row. There is no public administrative HTTP endpoint or admin credential embedded in the app.

Gifts and purchases have no automatic expiration in the current design. Purchase closeout/refund rules remain part of the commercial implementation. Deleting a free account forfeits unused promotional time and removes identity data; an active reservation or unresolved paid balance requires closeout first.

## Availability and remaining work

The new `/v1/minutes` endpoint reports a guest or member's time balance. `/v1/guest/minutes` starts or resumes a verified trial; `/v1/minutes/link-guest` transfers its remainder after sign-in. `/v1/minutes/welcome` verifies an account-bound eligibility proof before granting a signup-first offer. The server's pricing response explicitly reports minute purchases as unavailable until real pack checkout and settlement are implemented.

These controls are a backend foundation. Hosted voice still uses the restricted experimental controller and has not yet been switched to minute reservations. Verified Android attestation, client integration, paid pack fulfillment/refunds, helper budgets and the consumer minute UI must be connected before production activation.

See [how to manage free minutes](manage-free-minutes.md) for operator commands.
