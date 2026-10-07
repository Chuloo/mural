# Apple purchase and funding scope reference

The public API records Apple production and sandbox purchases on the same account. Each order, receipt, entitlement, reservation and hosted paid session retains its own environment. Sandbox value is stored in `sandbox_balance_nano`; production value is the combined balance minus that sandbox amount. Public conversations use verified real account funds and reconciled free or manually granted minutes. Apple sandbox purchase value does not fund new public voice sessions or helper requests.

## Configuration

A live commerce manifest accepts this Apple configuration:

```json
"apple": {
  "bundleID": "chat.mural.ios",
  "appAppleID": 6816001011,
  "sandboxEnabled": true
}
```

`sandboxEnabled` defaults to absent. When true, the runtime constructs separate Apple production and sandbox verifiers, authenticated API clients and notification-history cursors. Both use the existing protected Apple credentials. Stripe and Play continue to use the manifest environment.

Dual Apple checkout requires a completed history window from the last hour for the exact Apple merchant and requested environment. A missing, incomplete, future or stale completion hides that scope's catalog and rejects new checkout with `minute_purchases_unavailable`. A fresh `updated_at` or an in-progress page does not open checkout. Balance reads, delivery, recovery and refunds remain available. Historical single-environment deployments retain their existing admission behavior.

Apple restricts production Server API access until the app has a production release, as confirmed by an [Apple App Store Commerce Engineer](https://developer.apple.com/forums/thread/806452). Before that release, a public runtime can support verified App Review and TestFlight sandbox purchases after sandbox history completes. Live checkout opens automatically when the scheduled production history reconciliation first succeeds; no readiness record is fabricated and no production authorization failure switches a purchase into the sandbox scope. Operators can inspect both gates with `appleScopes.historyReadiness()`.

Payment monitor targets can set `expectedHistoryScopes` to require exact provider, environment and merchant completions. Set `pendingUntilFirstCompletion: true` only on the prerelease Apple live scope. When the monitor observes its first nonfuture completed window, it persists that scope in the target's `activatedHistoryScopes` state before sending alerts. Later missing, null, future or hour-old completions alert even after a restart or failed email delivery. Dry runs read existing activation state without saving newly observed completions or sending mail. Sandbox should be required immediately. Existing `expectedProviders` configurations remain supported.

`MURAL_MINUTE_APPLE_CREDENTIALS_FILE` contains `signingKey`, `keyID`, `issuerID` and `rootCertificates`. The key is EC P-256; certificate roots are base64-encoded DER. The canonical catalog contains reviewed Apple offers for each enabled environment. A SKU may repeat across environments; bindings remain unique within an environment and storefront.

## Request proof

`X-Mural-Apple-App-Transaction` contains StoreKit's signed AppTransaction JWS, at most 32,768 characters. The server verifies Apple's certificate chain, app identity and environment. An unsigned environment claim only selects the pinned verifier. Xcode-local signatures are rejected. Verified scope is cached by JWS hash for five minutes, with at most 1000 entries; proofs are not retained in that cache or request logs.

| Endpoint | Scope behavior |
| --- | --- |
| `GET /v1/minutes/products?provider=apple&storefront=…` | Returns offers for the verified proof environment. Authentication is not required. In dual mode, absent proof fails closed. |
| `POST /v1/minutes/orders` with Apple provider | Requires proof in dual mode; saves the selected environment before StoreKit purchase. |
| Apple delivery and recovery | Require proof in dual mode and verify the transaction with the matching Apple API. The receipt must match its immutable order and authenticated owner. |
| `GET /v1/minutes`, `GET /v1/wallet` | Public hosted access displays real account funds. `/v1/minutes` also includes reconciled free and manual minute grants, excluding sandbox minute value. AppTransaction proof is ignored for these spending reads. |
| `POST /v1/live/sessions` | Public hosted admission uses reconciled non-sandbox minutes first, then verified real paid funds. New paid sessions snapshot `live` before provider creation. AppTransaction proof cannot select sandbox funds. |
| Hosted helper requests, settlement and recovery | Use the environment already saved on the session/reservation. Later requests cannot change its funding source. |

Authentication is required for account balances and conversation admission. Missing, malformed or sandbox AppTransaction proof does not prevent access to legitimate account credit. Google or Apple sign-in, guest linking, conversation status and close requests do not depend on purchase proof. Apple acquisition still requires verified proof, immutable order binding and authenticated receipt ownership.

Public free-minute admission requires at least 15,000 available milliseconds, matching the provider minimum. Smaller remainders remain visible and unspent. A verified real paid balance can fund a new session while preserving that remainder. Restricted legacy minute admission retains its original threshold.

## Public sandbox purchase accounting

Verified sandbox purchase delivery records test value on the signed-in account for receipt, duplicate protection and refund testing. That value is excluded from public spendable balances. TestFlight and App Review users can use the same account's real purchases, free allowance and manual grants without a verified AppTransaction for spending. No purchase or proof failure creates a free grant.

Existing test-funded sessions retain their original funding scope for trusted final usage, already-reserved helper results, budget expiry and refunds. The public helper gateway rejects new provider requests on an `ai-value` session with `funding_environment='test'`. It preserves previously reserved work and settlement evidence. Credits on an isolated restricted sandbox server remain in that server's database; its bounded policy is unchanged.

## Accounting and recovery

Migration 032 adds immutable `funding_environment` fields to paid reservations, voice sessions and helper pools. Available funds subtract open reservations and helper pools from the same environment. Historical holds without a mapped funding record remain unavailable in both environments until reconciled.

The runtime grant files are `actual-value-runtime-grants.sql` and `apple-purchase-runtime-grants.sql`, alongside the existing minute, purchase, provider and hosted helper grants. The former includes helper-pool reads and restricts funding-scope mutation; the latter includes Apple notification/cursor access and signed transaction revision updates. Both apply after migration 032. The existing production hosted policy remains `public-minutes` with its existing provider limits.

Signed Apple notifications select the matching verifier and update only mapped orders. Verified sandbox notifications for an order outside this database are acknowledged without a grant, allowing history to include purchases from the older isolated sandbox. Receipt recovery still rejects absent orders, wrong accounts and mismatched environments. Production unknown orders remain errors.

Sandbox-only accounts with verified Apple test credits can be deleted after in-flight holds resolve. Unused test value is forfeited through an audited `sandbox-deletion` ledger reversal. Financial tombstones preserve orders and receipts for late refunds. Real paid balances, unresolved live orders and existing Stripe/Play deletion guards continue to block deletion. A later sandbox refund may produce test debt on the tombstone without changing production value.
