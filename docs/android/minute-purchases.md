# Android minute purchases

The Android purchase foundation is implemented but disabled by default. It does not activate a catalog, create Play products or charge anyone. The account screen and activity lifecycle now connect it through `MinutePurchaseViewModel` and `MinutePurchaseSheet`; the account entry is hidden while the capability is disabled.

## Application integration

`MinutePurchaseViewModel.enabled` checks the explicit build flag, HTTPS API configuration, valid `test` or `live` environment, and permanent Android package. Build properties are `mural.minutePurchasesEnabled` (default `false`) and `mural.minutePurchaseEnvironment` (default `test`). They generate `MINUTE_PURCHASES_ENABLED` and `MINUTE_PURCHASE_ENVIRONMENT`; the isolated `uiTest` variant always forces `false` and `test`. No local configuration was changed to enable them.

The app shell first calls `purchases.bindAccountState(account.state, account.transitionBusy)`. Both flows belong to the retained `AccountViewModel`; they must survive activity recreation and contain no Activity references. `transitionBusy` covers sign-out, deletion and the Google account chooser. The shell also sends `onAccountChanged` synchronously when an account transition starts, then observes the combined account and transition flows for later changes.

The shell calls `onForeground(AccountState)` on foreground, `refresh()` to check purchases, and `launch(Activity, sku)` for a pack tap. It collects `balanceChanges` to refresh the account wallet. Immediately before opening Play, after the order request finishes, the ViewModel reads the retained account and transition flows again. It requires the same account and purchase-intent revision, no active account transition, and the original Activity still in `RESUMED` state without being destroyed or finishing. The check and Play launch run synchronously on the main thread. Backgrounding, rotation, account changes or a completed account transition can therefore invalidate a tap made before the network request.

The ViewModel holds the Activity weakly during that request and closes its BillingClient when cleared. The member provider uses the existing encrypted account store and verifies `/v1/account` once per bearer; that server route excludes guests. Sign-out and account changes invalidate cached membership and hide the previous wallet.

`MinutePurchaseSheet(state, signedIn, onBuy, onSignIn, onRefresh, onDismiss, accountBusy)` renders the controller's state. The sheet uses the existing warm background, orb and typography, shows the 15-second minimum before the purchase buttons, and supplies English and Spanish resources. Busy, pending, account-transition and unsigned-in states cannot trigger a purchase.

Create one `PlayBillingAdapter` for the active application owner, using the application context. Keep that instance across activity recreation. Create a `MinutePurchaseController` in the same owner's coroutine scope, normally the main dispatcher. Close both through `controller.close()` when that owner is destroyed. Do not create another BillingClient in a screen.

The controller receives:

- `api`: `MinuteCommerceClient`, constructed only from a valid `MinuteCommerceConfiguration` HTTPS origin.
- `store`: the single `PlayBillingAdapter`.
- `readMember`: a suspend function returning the current, unexpired **member** `AccountSession`, or `null`. A guest token must not be supplied here. The server also enforces membership.
- `enabled`: an explicit capability flag. Its default is `false` in both the controller and adapter.
- `expectedEnvironment`: `test` by default; production activation must explicitly select `live` after the release review.
- `onBalanceChanged`: a suspend callback to refresh the account's displayed balance after server verification.

Collect `controller.state` for the minute-pack sheet. It exposes localized prices, minute counts, the last server balance, operation state and a notice enum. It contains no bearer token, purchase token or provider binding. Translate notice enums in Android string resources; do not display exception text.

Call `controller.onForeground()` when the app returns to the foreground and after an account change. The call refreshes the catalog and recovers unconsumed purchases, including purchases completed while the app was closed. Serialize application account transitions with this owner, so the account screen clears its own previous balance immediately on sign-out. The controller also rejects results if the member changes during a request.

Route pack taps through `MinutePurchaseViewModel.launch(activity, sku)` so the final account and Activity checks surround the controller's launch callback. The adapter uses the Activity only during `launchBillingFlow`. `OPENED` means the Play sheet opened; it does not confirm payment.

`controller.refreshOrder(orderID)` can check a known order. `dismissNotice()` clears a rendered notice. A canceled purchase or unavailable store must leave the user able to close the sheet and continue using any existing minutes.

## Verification and recovery

`MinuteCommerceClient` implements these fixed backend endpoints:

| Operation | Endpoint | Request data |
| --- | --- | --- |
| Catalog | `GET /v1/minutes/products?provider=play` | No account bearer |
| Create order | `POST /v1/minutes/orders` | `provider: play`, `sku`, `Idempotency-Key` header |
| Read order | `GET /v1/minutes/orders/:id` | Member bearer |
| Verify known order | `POST /v1/minutes/orders/:id/play` | `purchaseToken` |
| Recover purchase | `POST /v1/minutes/play/recover` | `purchaseToken` |
| Read wallet | `GET /v1/minutes` | Member bearer |

The app never sends a price, minute allowance, account ID override or claimed payment state. Requests do not follow redirects, and response bodies are bounded. Member requests require an unexpired local session. The server remains authoritative for authentication, ownership and fulfillment.

Before opening Play, the controller re-fetches the approved server catalog and eligible Play offers. Product ID, currency and exact integer price must agree. A changed server quote requires another tap. Missing, ambiguous or mismatched offers are unavailable. The app displays Google's localized price rather than formatting a separate price itself.

The server creates the order's obfuscated account and profile IDs. The BillingClient adapter passes both to Play. Purchase callbacks are uploaded to the recovery endpoint, which resolves those stored bindings without relying on an order UUID saved by the app. This supports reinstall recovery and repeated callbacks. The server verifies the provider state and grants minutes once.

Pending tokens are sent to the server for later reconciliation. The client does not grant minutes for `PENDING` or `PURCHASED` SDK callbacks. It reads the resulting server status and wallet. It never calls Play acknowledge or consume methods; the durable backend worker consumes eligible purchases after fulfillment. Receipt tokens are held only in memory and in request bodies, excluded from app archives and renderable state, and redacted by model string representations.

## Release checks still required

The current approved catalog is a pinned currency/amount contract. Play's `formattedPrice` excludes tax in tax-exclusive countries. Before opening sales, verify real Play Orders totals, taxes and region-specific prices against the server's quote rules; otherwise a purchase may be correctly unavailable, or the backend may reject a charged total that differs from its quote. Do not enable untested regions or silently relax the amount check.

The minute-pack sheet, lifecycle recovery, member-only upgrade handoff, account balance refresh and localized notices are wired. Hosted conversations and guest allowances use their existing wallet interfaces; this component does not create a second wallet.

Run the official Play test flow using the permanent `chat.mural.android` package and a license tester. The isolated `.uitest` package is intentionally rejected by this adapter. Confirm approval, decline, cancellation, pending completion, interrupted purchase, reinstall recovery, account switching, duplicate callbacks, server consumption, refund and void reconciliation. Test supported regional price/tax combinations. Non-license users on a test track can be charged, so those are not substitutes for the configured license tester.

The purchase layer now has 39 JVM tests: 13 controller/model tests, 8 HTTP contract tests, 1 SDK response mapping test, 9 membership/capability tests and 8 delayed-launch regressions. The 8 new launch tests and 9 membership tests passed in the latest 38-test focused run. The launch tests suspend a real controller order request, then exercise backgrounding, rotation, sign-out, account replacement, transition completion and a late busy update before the flow collector runs.

The final combined JVM run passes all 205 tests with no failures, errors or skips, including all 39 purchase tests. All 31 isolated UI tests pass on API 36, including five account-sheet tests, four purchase-sheet tests, activity recreation and concurrent encrypted-session access. The Spanish purchase test also passed at the actual 160% system text size. Font and keyboard settings were restored, and the personal app installation was preserved.

The app compiles against Play Billing 9.1.0. These tests use fake store events and a local mock HTTP server; they do not prove Play checkout, microphone behavior or a published product configuration.

References: [Google Play Billing integration](https://developer.android.com/google/play/billing/integrate), [purchase security](https://developer.android.com/google/play/billing/security), [license testing](https://developer.android.com/google/play/billing/test), and [one-time offer price fields](https://developer.android.com/reference/com/android/billingclient/api/ProductDetails.OneTimePurchaseOfferDetails).
