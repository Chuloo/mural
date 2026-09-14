# Minute purchase accounting

`MinutePurchases` records fixed conversation-minute entitlements in the existing minute wallet. It currently has no HTTP routes, default products or launch prices. Optional Stripe/Play adapters and the delivery worker are documented in [provider integration](minute-provider-integration.md). New sales default to disabled. Configuring this module alone does not make Stripe or Play purchases available.

## Catalog and order binding

The server supplies the catalog. Each entry binds an SKU to one provider, test/live environment, merchant, provider product, currency, final amount in currency minor units, and an integer number of minutes between 1 and 1,440. Quantity is always one. Regional prices need separate validated catalog entries; dynamic taxes or discounts must be resolved into a trusted final quote before an order is created.

`createOrder` requires an active member account. It stores an immutable quote and returns the same quote when the account retries its idempotency key, even after the catalog price changes. A guest must sign in first. The order UUID is the purchase binding; the provider adapter must attach it to the transaction before payment and verify it from the provider response. Use a stable obfuscated account identifier separately where Play requires account association.

## Verification and fulfillment

`reconcile(provider, input)` sends the input to the configured server verifier. The verifier must authenticate notifications and retrieve authoritative purchase state. It must validate the merchant/package, test/live environment, account/order binding, product, quantity, currency and paid amount. Unsigned notifications, browser return pages and client purchase states cannot become verified evidence.

The processor hashes transaction and event identities before storing them. For Play, the stable transaction identity is the purchase token; do not substitute the optional order ID. The optional provider adapters store encrypted references and schedule a worker to retrieve, consume and reconcile purchases after the client disconnects. Deployment must configure their encryption keys and run that worker. [Play security guidance](https://developer.android.com/google/play/billing/security).

Pending transactions grant no time. Completed purchases append one immutable grant. A second event for the same transaction does not grant again, and a transaction cannot move to another order or account. Event reuse with changed facts fails. The wallet change, transaction state and event receipt commit together; a failed database operation leaves the event retryable.

After the grant commits, a Play adapter must consume the consumable purchase through the server API, with durable retries and reconciliation. Consumption also acknowledges delivery. Unacknowledged purchases are automatically refunded after three days. Do not advertise Play purchases until this worker and real provider tests are complete. [Play one-time purchase lifecycle](https://developer.android.com/google/play/billing/lifecycle/one-time).

## Refunds and shortfalls

The verifier supplies cumulative **successful** refunded money in the original currency. Pending or failed refund requests must not count as successful refunds. Multiple partial refunds can exist, and their sum cannot exceed the original payment. [Stripe refunds](https://docs.stripe.com/refunds).

The processor calculates the reversal against the original immutable quote:

```text
reversal milliseconds = ceil(original milliseconds × cumulative refunded minor units / original total minor units)
```

The calculation uses integers and rounds the cumulative result once. An older notification cannot lower the reversal or restore refunded time. A void or chargeback revokes the entire pack and remains terminal. Reinstatement after a reversed dispute requires a separate audited policy and is not implemented.

Refunds remove only unreserved time. If some minutes have already been spent or reserved for a live call, the transaction retains the unrecovered amount. A database trigger blocks new minute reservations while that shortfall exists, preserving the existing wallet invariant that reserved time never exceeds its balance. Existing reservations may settle or release normally. Call `reconcileAccount` after either operation and from a retry worker to recover newly available time.

A later purchase can repay an older shortfall before increasing the available balance. This behavior needs a clear account notice and support/closeout flow before sales are enabled. Existing live sessions also need an explicit refund/cancellation policy; this module preserves their reservation rather than cancelling their provider connection.

## Deployment and remaining integration

Apply migration `008_minute_purchases.sql`, then `operations/minute-purchase-runtime-grants.sql` after the baseline runtime grants. Orders and event receipts permit only runtime reads/inserts; the transaction summary permits updates. The immutable table triggers also protect history from accidental owner-level edits.

Before enabling purchases:

- Wire the optional Stripe/Play adapters, protected receipt storage, notifications and reconciliation workers into the deployed service; verify them against real sandbox providers.
- Add authenticated catalog/order/status endpoints with bounded requests, rate limits and stable error mapping. Avoid exposing provider input or transaction tokens in errors and logs.
- Run the Play delivery worker and verify consumption retries and purchase restoration through the member's account ledger against a real Play test purchase.
- Integrate refund shortfall status into balance, session start, account UI and account deletion. Purchase-order foreign keys retain billing records, so the current empty-account hard-delete path must handle these orders explicitly.
- Confirm final minute packs, channel prices, tax treatment and the refund/shortfall policy. The synthetic test amounts are not commercial prices.
- Test sandbox failures and a separately authorized live payment/refund before activation.

Run the focused suite against an isolated database whose name ends in `_test`:

```sh
TEST_DATABASE_URL=postgresql://localhost/mural_billing_test npx tsx --test tests/minute-purchases.test.ts
```

The tests create and remove their own schema. The restricted-role test also needs permission to create and remove an isolated PostgreSQL role.
