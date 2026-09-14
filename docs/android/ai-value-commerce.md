# Paid AI value and estimated conversation time

Paid purchases add a prepaid AI allocation to the existing nano-USD wallet. They do not grant a fixed number of minutes. Free welcome time and minute gifts keep their separate time ledger.

## Quote and fulfillment

`makeAIValueProduct` computes an operator-reviewed quote from the AI allocation in checkout-currency minor units, currency exponent, rational USD exchange rate, service-fee policy, processing rate/fixed cost/buffer, and estimate rate. USD quotes require exponent 2 and a 1:1 exchange rate. The constructor recomputes the amount and rejects supplied AI credit, fees, or estimated time that disagree with those inputs.

The quote separates AI allocation, Mural fee, payment-cost estimate, and payment buffer. The allocation alone becomes usable AI value. Its estimated time is calculated at the pinned estimate rate and carries `estimate: true`; it is not a time entitlement.

Orders use the existing provider-order registry with `entitlement_kind = ai_value` and no `allowance_ms`. Migration 017 adds an immutable quote table and AI-value transaction records. The quote retains currency, exchange-rate version, pricing-policy version, processing assumptions, and estimate-rate version. A policy change stops new orders from an old catalog; an existing order keeps its original quote.

Runtime order creation reads and locks the policy through `lock_ai_pricing_policy()`. This narrow `SECURITY DEFINER` function fixes its search path to `pg_catalog` and names the migration schema explicitly. Runtime receives permission to call it while policy and audit writes remain denied. A restricted-role regression verifies order creation, fulfillment and refunds, rejects policy edits, prevents temporary-table substitution, and confirms that the lock holds off concurrent fee changes until the transaction ends.

Stripe and Play keep their existing trusted verification, account binding, encrypted receipt storage, acknowledgment/consumption retries, and void reconciliation. `PurchaseFulfillmentRouter` verifies provider evidence once, reads the immutable entitlement type, and sends the result to the matching fulfillment service. Neither browser redirects nor client assertions grant value.

`applyVerifiedEvidence` is an internal server entry point used by that router. HTTP handlers must use `reconcile`, which invokes the configured provider verifier. They must never pass request bodies directly to `applyVerifiedEvidence`.

Historical minute receipts still reconcile through `MinutePurchases`. Both services reject the other entitlement type. Shared transaction locks, event IDs, and receipt bindings prevent a provider purchase from being claimed across the two ledgers.

## Refunds, holds, and sandbox funds

A successful partial refund reverses the original AI allocation in proportion to the refunded checkout amount, rounded up once in nano-USD. A full refund or void reverses the full allocation. Fees never become AI credit. Older or duplicate provider snapshots cannot restore reversed value.

Refunds post immediately to the existing currency ledger. An active reservation remains recorded and can settle its actual AI cost. A spent refund can leave negative AI value; new purchases first offset that debt. New public reservations use verified funded availability and cannot spend held or refunded value.

Sandbox grants and reversals change `balance_nano` and the signed `sandbox_balance_nano` by the same amount. Public funded balance is their difference, and available value is clamped to zero after reservations. Migration 017 marks wallets with older ledger activity as unverified; they require review before public paid spending. Legacy Stripe sandbox purchases also retain sandbox provenance.

An unverified wallet cannot create or reopen a checkout. Owned status, receipt recovery, and refund reconciliation remain available so existing payments can resolve without offering another charge.

## Runtime interface

`configuredMinuteCommerce` returns:

- `purchases`: historical minute reconciliation, with sales disabled.
- `aiPurchases`: the new optional catalog, order creation, status, and AI-value fulfillment.
- `fulfillment`: the shared verified webhook, Play recovery, and delivery-worker dispatcher.
- Existing Stripe/Play adapters, receipt vault, worker, runner, environment, and sales-state fields.

Catalog version 1 is accepted for historical reconciliation only. Activating its sales flag fails. Version 2 contains canonical `AIValueProduct` objects. It requires the existing protected file checks, matching provider environment, approved catalog hash, separate sales flag, and separate live flag. Missing configuration performs no credential discovery or provider work.

Products and orders include `entitlementKind: ai_value`, `billingBasis: actual-ai-usage`, `aiValueNanoUSD` as a decimal string, `estimatedMilliseconds`, `estimate: true`, and the immutable `quote`. Orders add `orderID`. Status includes state, granted/reversed AI value as decimal strings, and `fulfillmentRecorded`. `reversalOutstandingNanoUSD` is zero because reversals post immediately; outstanding spent value is represented by wallet debt.

The wallet endpoint uses `paidAIBalance` from `ledger.ts`. Account deletion blocks unconfirmed orders, nonzero balances, debt, and active reservations. Resolved purchase records retain an account tombstone after identifying signup data is removed.

## Activation and verification

Live sales remain disabled, and no launch catalog was configured. The old fixed-minute offers must remain disabled. Play market pricing, processing assumptions, tax treatment, and final customer-facing estimates still require approved configuration and real-provider verification.

Before enabling Play sales, prepared orders that never produce a purchase token need a safe abandonment/deletion workflow. They currently remain unresolved and prevent account deletion. This is a release blocker for paid Play access.

Focused tests cover quote arithmetic, immutable policies, exactly-once grants, pending/void states, cumulative refunds, held-session debt, sandbox isolation, historical receipt compatibility, signed Stripe HTTP delivery, owned Play recovery, and account deletion. All prices and provider transports in these tests are synthetic.

A separate Stripe sandbox run on 14 September 2026 completed hosted Checkout with a test card and received genuine signed payment and refund webhooks. Replaying delivery granted the AI allocation once, excluded checkout fees from usable value, and kept sandbox funds out of public conversations. Partial and full refunds reversed the original AI allocation proportionally; the receipt remained encrypted. Both refunds succeeded, and the temporary product and price were archived.

The sandbox quote was a test fixture, not a launch offer or a claim about actual processing fees. The [public verification record](../../release/android/evidence/ai-value-stripe-sandbox-2026-09-14.json) includes the quote and results without account, payment, or credential identifiers. The combined API suite passed 292 tests with zero failures or skips. Live payments, real Google Play purchases, and production activation still require separate verification.
