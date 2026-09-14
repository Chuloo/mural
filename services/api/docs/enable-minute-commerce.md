# Enable actual-cost purchases

Purchases add an AI-value balance. The app presents estimated minutes and separates the AI allocation, Mural fee, estimated processing cost and payment buffer. Existing free minutes remain usable and are spent first. Historical fixed-minute catalogs are reconciliation-only.

## Prepare one verified channel

1. Apply migrations through 018 and the existing minute purchase, provider delivery, commerce runner, voice and helper grants. Apply `operations/actual-value-runtime-grants.sql` last. Runtime must be able to call `lock_ai_pricing_policy()` while policy/audit writes remain denied. Do not mark historical cash wallets verified without reconciling their source.
2. Review the actual merchant channel fee, processing buffer, tax treatment, launch countries and AI allocation per offer. The database policy defaults to 15%, version 1; read the deployed policy before generating quotes. The service fee is applied to AI allocation, while processing estimates account for the entire collected amount. Do not reuse sandbox fixture prices or assume Stripe and Play have the same fee.
3. Create the approved one-time Stripe Price or Play consumable product. Pin its exact product ID, currency and total. Generate the version 2 catalog with `makeAIValueProduct`; see the [complete schema](minute-commerce-configuration.md). Review the displayed estimate and itemized quote, then compute SHA-256 of the exact final file bytes.
4. Put the manifest, catalog, receipt key ring and provider credentials in protected files outside Git and web roots. Mount them read-only into the API container. Each file must be readable by the Node process, owned by root or its user, have one hard link, and have no group/other permissions. Back up the receipt key ring independently of the database. Keep the Play binding key stable.
5. Configure only the ready provider. Stripe alone is supported; Play can be added later. An enabled manifest must have one environment. Existing orders/receipts must match it, so sandbox testing uses a separate database from production. Google license-test payments are test evidence and must not enter the live database or become spendable public AI value.

## Verify before exposing checkout

`main.ts` already constructs commerce, starts the durable runner after HTTP startup, and drains it on shutdown. Deployment still has to forward the environment variables and mount files; the base Compose file does not do this.

For test verification, use `MURAL_MINUTE_COMMERCE_ENABLED=true`, `MURAL_MINUTE_ALLOW_LIVE=false`, a test manifest and the approved version 2 test catalog. Sales need `MURAL_MINUTE_SALES_ENABLED=true` and an exact `MURAL_MINUTE_CATALOG_APPROVED_SHA256`. The runtime also requires `HOSTED_PAID_VALUE_ENABLED=true`, `HOSTED_VOICE_ACCESS=public-minutes`, `HOSTED_VOICE_EXPERIMENTAL=true`, `HOSTED_VOICE_BILLING_UNIT=milliseconds`, and `HOSTED_HELPERS_EXPERIMENTAL=true`. Keep test endpoints isolated. All file-path variables are listed in the [configuration reference](minute-commerce-configuration.md).

Verify these paths through the deployed proxy, without granting access to unrelated administrative routes:

| Path | Required access |
| --- | --- |
| `GET /v1/minutes/products?provider=stripe` or `play` | Public reviewed catalog |
| `POST /v1/minutes/orders` | Authenticated member, trusted proxy admission, idempotency header |
| `GET /v1/minutes/orders/:id` | Authenticated owner |
| `POST /v1/minutes/orders/:id/play` | Authenticated owner; Play only |
| `POST /v1/minutes/play/recover` | Authenticated member; Play only |
| `POST /v1/webhooks/stripe/minutes` | Original raw request body and Stripe signature; no bearer requirement |

Preserve the existing trusted proxy headers for member routes. Cross-origin web checkout also needs OPTIONS and the existing origin policy. Do not transform webhook JSON before signature verification. The old `/v1/checkout` and `/v1/webhooks/stripe` endpoints belong to the legacy sandbox and must remain off.

For Stripe, verify the pinned account is Mural, charges are enabled, and the one-time Price matches the canonical total and currency. Add only the supported events to the dedicated endpoint: `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`, `checkout.session.expired`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`, `refund.created`, `refund.updated`, and `refund.failed`. Save that endpoint's signing secret with the matching environment key. `webOrigin` must serve `/payment-return`; a redirect is not proof of payment. The adapter currently disables automatic tax and adaptive pricing. Tax treatment and any tax-inclusive price must be resolved before selling in a market; turning on automatic tax without changing quote validation can make captured totals fail verification. [Stripe Checkout parameters](https://docs.stripe.com/api/checkout/sessions/create)

For Play, use the permanent package `chat.mural.android`. Supply an existing Google service account with Android Publisher access to this app and permissions to read purchases/orders and consume products. Use a Play-installed signed test build and a license tester to verify localized price/currency matching, purchase-token binding, pending payment, cancellation, recovery after restart/reinstall, exactly-once value and server consumption. A successful sideloaded Google login does not test Play Billing. The backend deliberately refuses to credit a product whose authoritative order total does not match its immutable quote. [Google purchase verification](https://developer.android.com/google/play/billing/security)

The Stripe sandbox AI-value flow has verified real signed payment delivery, replay, encrypted receipts and partial/full refunds. Play transports and ledger behavior have offline coverage; a real Play purchase remains a separate gate. Hosted paid spending also needs a controlled end-to-end check: use an approved account with verified live funds, exhaust its free allowance, confirm voice and helpers debit actual AI value, retain the unused balance, and stop correctly at its limit. Test funds are excluded from public spending, so a sandbox purchase cannot establish this last result.

## Smallest live activation sequence

1. Finish the channel verification and support/refund decisions above. Start with the smallest approved catalog and markets. Do not use a production customer as an unannounced payment test.
2. Install live protected files, the exact approved catalog hash, and read-only mounts. Use a live manifest with `MURAL_MINUTE_COMMERCE_ENABLED=true`, `MURAL_MINUTE_ALLOW_LIVE=true`, `MURAL_MINUTE_SALES_ENABLED=false`, and `HOSTED_PAID_VALUE_ENABLED=true`. Keep guest funding unchanged. Start the configured runner and verify database, provider access, keys and route behavior without exposing a payable offer.
3. Enable `MURAL_MINUTE_SALES_ENABLED=true` only for the reviewed catalog, then make one explicitly authorized controlled live purchase through the real client. Verify owner binding, one grant despite replay, actual hosted debit, retained remainder, and the agreed refund outcome. Record only sanitized evidence. A test purchase and a real refund can incur unrecoverable fees.
4. Expose the purchase UI for that channel only after the result passes. `/readyz` and `/v1/minutes/products` report configuration and catalog visibility; they do not independently certify provider credentials, taxes, checkout or fraud settings.
5. Monitor `minute_delivery_failed`, `minute_reconciliation_failed`, `play_void_reconciliation_failed`, overdue `minute_provider_jobs`, wallet debt, and mismatched provider orders. The runner retries saved receipts; it cannot discover a Play purchase token never delivered to Mural. Unknown late Play purchases and manual closeout remain explicit operational cases. Do not claim automatic refunds or automatic account closeout.

## Pause and recover

Set `MURAL_MINUTE_SALES_ENABLED=false` to stop new offers. Keep commerce, provider access, receipt keys and the runner enabled so existing purchases and refunds can reconcile. Pausing sales should not strand paid balances or stop refund verification.

A new catalog or service-fee policy requires a reviewed quote and hash. Existing orders retain their old allocation and fees. Encryption rotation adds a key and keeps historical keys; removing one can strand receipts. Do not delete billing history or rewrite balances to pass startup checks.

Handle refund/deletion requests through the [manual closeout runbook](account-closeout-support.md). In particular, withholding a processing fee from a refund does not automatically remove the entire AI allocation, and a won dispute does not automatically restore a previously voided entitlement.
