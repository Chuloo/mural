# Stripe and Play minute providers

The optional adapters implement provider verification and durable fulfillment for the minute ledger. They are not installed by `app.ts` or `main.ts`, and missing configuration leaves them unavailable. Production requires an explicit `allowLive: true` capability as well as the matching live environment. This is separate from enabling new checkout/purchase flows.

## Service composition

Create one `MinuteReceiptVault` with a protected 32-byte encryption key and a stable key ID. Keep its key ring outside the database and repository. Existing receipts can be read with older keys in the ring; retain those keys when selecting a new active key. Receipts use AES-256-GCM with order, provider, merchant and environment authenticated as associated data.

For Stripe, configure `StripeMinuteProvider` with the dedicated account ID, API key, webhook signing secret, web origin and test/live environment. `StripeSDKMinuteTransport` uses the official Stripe SDK. The provider checks the key's account before processing financial state. `checkoutEnabled` defaults to false.

For Play, construct `GoogleServiceAccountTokens` from protected existing service-account credentials and pass it to `GooglePlayHTTPTransport`. Both the access-token source and HTTP transport are injectable for tests. The token source signs an RS256 assertion for Google's token endpoint with the Android Publisher scope, coalesces refreshes and caches tokens until one minute before expiry. Requests use fixed Google origins, encoded path segments, timeouts, bounded responses and no redirects. [Google service-account authorization](https://developers.google.com/identity/protocols/oauth2/service-account).

`PlayMinuteProvider` requires the package name, test/live environment, a protected 32-byte binding key, and an explicit map of currency minor-unit exponents, such as `{ usd: 2 }`. Its `purchasesEnabled` flag defaults to false. Pass the configured providers as `verifiers` to `MinutePurchases`, along with the approved server catalog and its separate sales activation flag.

## Stripe sandbox harness

With configured `db`, `receiptKey`, `stripeConfig` and an approved sandbox `catalog`:

```ts
const vault = new MinuteReceiptVault(db, 'receipt-v1', new Map([['receipt-v1', receiptKey]]));
const stripe = new StripeMinuteProvider(db, vault, {
  ...stripeConfig,
  environment: 'test',
  checkoutEnabled: true,
});
const purchases = new MinutePurchases(db, {
  catalog,
  verifiers: [stripe],
  salesEnabled: true,
});
const worker = new MinuteDeliveryWorker(db, purchases, [stripe]);

const order = await purchases.createOrder(accountID, 'stripe', sku, idempotencyKey);
const checkout = await stripe.checkout(accountID, order.orderID);
// Present checkout.checkoutURL and complete payment through Stripe's sandbox.
// In the signed webhook handler, retain the exact request bytes:
await purchases.reconcile('stripe', { kind: 'webhook', raw: rawBody, signature });
await worker.runBatch();
const status = await purchases.status(accountID, order.orderID);
```

A return-page visit does not grant time. Send the authentic signed completion again to verify replay behavior. Issue a sandbox partial/full refund and deliver the signed refund event; the adapter retrieves current Checkout, PaymentIntent, charge, line-item and successful-refund state before updating minutes. The same old notification can retrieve newer provider state without conflicting with an earlier snapshot.

Checkout persists an attempt before creating a session and uses the immutable order ID for Stripe idempotency. An unmapped attempt older than 23 hours requires reconciliation before another create request. A checkout URL is returned only after its receipt and retry job commit. Original price, quantity, amount, currency, account metadata and environment must match. Taxes/discounts must already be represented by the trusted final quote; dynamic tax and promotion entry are currently disabled.

The adapter accepts Checkout completed/expired/async-payment events, charge refund/dispute events and refund lifecycle events. A handler that shares an endpoint with legacy billing must verify and dispatch unrelated events separately. Event IDs are not sufficient evidence: current provider facts determine fulfillment and successful refunds. [Stripe Checkout retrieval](https://docs.stripe.com/api/checkout/sessions/retrieve), [refund listing](https://docs.stripe.com/api/refunds/list).

## Android purchase binding and verification

After creating an authenticated member's Play order, call `play.prepare(accountID, orderID)`. Supply the returned `obfuscatedAccountID` and `obfuscatedProfileID` to `BillingFlowParams`. The first is a stable HMAC of the member identity; the second binds this server order. Neither exposes an email address or raw account ID.

Submit the token through the authenticated backend:

```ts
await purchases.reconcile('play', {
  kind: 'client',
  accountID: authenticatedAccountID,
  orderID,
  purchaseToken,
});
```

Derive `authenticatedAccountID` from the server session. Never copy it from request JSON. The adapter retrieves `ProductPurchaseV2`, resolves the returned order binding, and checks both identifiers, the product, quantity and test environment. A token cannot be redirected to another member or another order.

`ProductPurchaseV2` does not include the price. Completed purchases therefore also use the Orders API to verify the token, single product, original final amount and currency. Successful partial refund events are summed; pending refunds are ignored. Promo purchases without an order ID cannot satisfy this paid-order verification and are rejected. Regional discounts or taxes that change the expected final amount also require a reviewed catalog/quote change before acceptance. [Product purchase fields](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2), [Orders API fields](https://developers.google.com/android-publisher/api-ref/rest/v3/orders).

## Delivery and reconciliation jobs

Receipt storage and job creation commit before ledger fulfillment. If the process stops between those steps, `MinuteDeliveryWorker.runBatch()` retrieves the protected receipt, re-verifies provider state and completes the grant. Pending payments wait without granting or consuming. Play consumption occurs only after the ledger commits; a retry first checks whether Google already consumed it, covering a response lost after success. [Play consumption API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/consume).

Workers lease one job at a time, retry failures with bounded backoff, and recover expired leases. A notification arriving during a lease increments its generation so the new work cannot be overwritten by completion of the older job. Errors retain a fixed operational code, not provider payloads, tokens or keys.

Run the worker continuously with operational alerts for delayed delivery. Periodically call `vault.scheduleReconciliation(limit)` to recheck completed purchases older than six hours if notifications were missed. Poll `play.pollVoids(startMilliseconds, endMilliseconds, pageToken)` over overlapping windows within Google's 30-day limit, persist the cursor after successful processing, and drain returned pages. Authenticated full voids for known receipts are retained so a stale purchase response cannot restore revoked time. [Voided Purchases API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.voidedpurchases/list).

Apply migration `009_minute_provider_delivery.sql` and `operations/minute-provider-runtime-grants.sql` after the earlier minute migrations and baseline grants. No payment endpoint, provider credentials, catalog price, production activation, worker scheduling or live test is configured by these files. Complete API/auth integration, monitoring, closeout UX, real sandbox verification and separately approved live checks before enabling sales.
