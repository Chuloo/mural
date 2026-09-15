# Minute commerce configuration reference

`configuredMinuteCommerce(db, env, dependencies)` constructs optional AI-value payment services and historical minute reconciliation. It returns `undefined` when disabled. Construction reads only explicitly named files and saved receipt scopes; it makes no provider requests and starts no timers.

## Environment

All flags accept only `true` or `false`. All file paths are absolute. Files are regular, have one hard link, are owned by the process user or root, and have no group or other permissions; `0400` and `0600` are accepted. Final-path symlinks, invalid UTF-8, malformed JSON and files over their limit are rejected. No file contents or paths appear in configuration errors.

| Variable | Default | Meaning |
| --- | --- | --- |
| `MURAL_MINUTE_COMMERCE_ENABLED` | `false` | Constructs commerce services when true. Disabled construction does not read files or query the database. |
| `MURAL_MINUTE_SALES_ENABLED` | `false` | Enables catalog visibility and new purchases. Requires commerce enabled and catalog approval. Existing payment reconciliation remains available with sales disabled. |
| `MURAL_MINUTE_ALLOW_LIVE` | `false` | Permits a manifest with environment `live`. A test manifest with this flag true is rejected. |
| `MURAL_MINUTE_COMMERCE_CONFIG_FILE` | absent | Required manifest, at most 64 KiB. |
| `MURAL_MINUTE_CATALOG_FILE` | absent | Required canonical product catalog, at most 256 KiB. |
| `MURAL_MINUTE_CATALOG_APPROVED_SHA256` | absent | Lowercase SHA-256 of the exact catalog file bytes. Required for sales. A supplied digest must match even when sales are disabled. |
| `MURAL_MINUTE_RECEIPT_KEYS_FILE` | absent | Required receipt encryption key ring, at most 64 KiB. |
| `MURAL_MINUTE_STRIPE_CREDENTIALS_FILE` | absent | Required exactly when the manifest includes Stripe. |
| `MURAL_MINUTE_PLAY_SERVICE_ACCOUNT_FILE` | absent | Required exactly when the manifest includes Play. |
| `MURAL_MINUTE_PLAY_BINDING_KEY_FILE` | absent | Required exactly when the manifest includes Play. |

Sales enabled while commerce is disabled is an error. Missing, partial or mismatched active configuration raises `minute_commerce_configuration_invalid` with status 503. Separate test and live configurations cannot share a database containing receipts from both environments.

## File formats

The manifest contains `version: 1`, `environment: "test" | "live"`, at least one provider, and these optional fields:

| Field | Shape |
| --- | --- |
| `webOrigin` | HTTPS origin without credentials, port, query, fragment or non-root path. Required for Stripe. |
| `stripe` | `{ "accountID": "acct_…", "managedPayments": false }`; mode defaults to `false` and accepts only a boolean. |
| `play` | `{ "packageName": "chat.mural.android", "currencyExponents": { "usd": 2 } }` |
| `runner` | Optional limits listed below. |

`chat.mural.android` is the permanent Play package. Each configured Play currency has an explicit exponent from 0 to 3. Catalog currency names use lowercase ISO-style three-letter identifiers.

The active catalog contains `{ "version": 2, "products": [...] }`. It has at most 100 canonical `AIValueProduct` objects and no default price. Generate each product with `makeAIValueProduct` from `src/ai-value-purchases.ts`; do not manually calculate or insert the derived fields. Each product has exactly these fields:

| Field | Accepted value |
| --- | --- |
| `provider` | `stripe` or `play` |
| `environment` | The configured provider environment |
| `merchant` | The pinned Stripe account ID or Play package |
| `sku` | Server SKU, 1–128 characters using letters, numbers, `.`, `_`, `:`, `-` |
| `providerProduct` | Actual Stripe Price ID or Play product ID, at most 200 characters |
| `currency` | Three lowercase letters |
| `totalMinor` | Original quote in integer currency minor units, 1–100,000,000. For Managed Payments this is the pre-tax base; Stripe calculates tax and any local-currency presentation at Checkout. |
| `entitlementKind` | `ai_value` |
| `billingBasis` | `actual-ai-usage` |
| `estimate` | `true` |
| `aiValueNanoUSD` | Positive decimal integer string; only this amount becomes usable AI value |
| `estimatedMilliseconds` | Safe integer estimate computed from the allocation and pinned estimate rate |
| `quote` | Exact object described below |

The `quote` fields are:

| Fields | Type and meaning |
| --- | --- |
| `policyVersion`, `serviceFeeBasisPoints` | Integers matching the current database policy when an order is created |
| `aiValueMinor`, `serviceFeeMinor`, `processingEstimateMinor`, `processingBufferMinor`, `paymentFeeMinor`, `totalMinor` | Integer checkout-currency amounts; the factory verifies the complete arithmetic |
| `currency`, `currencyExponent` | Lowercase currency and exponent 0–3 |
| `processingRateBasisPoints`, `processingFixedMinor`, `processingBufferBasisPoints` | Reviewed channel/market assumptions; rate plus buffer must be below 10,000 basis points |
| `exchangeRateNumerator`, `exchangeRateDenominator`, `exchangeRateVersion` | Positive decimal integer strings and an operator-reviewed version; USD major units per checkout-currency major unit |
| `estimatedNanoUSDPerMinute`, `estimateRateVersion` | Positive decimal integer string and version for the displayed duration estimate |

The factory input is `AIValueProductInput`: `provider`, `environment`, `merchant`, `sku`, `providerProduct`, `currency`, `currencyExponent`, `aiValueMinor`, `policyVersion`, `serviceFeeBasisPoints`, `processing: { rateBasisPoints, fixedMinor, bufferBasisPoints }`, `exchangeRate: { numerator, denominator, version }`, and `estimate: { nanoUSDPerMinute, rateVersion }`. USD requires exponent 2 and a 1:1 exchange rate. The application currently displays estimates using 100,000,000 nano-USD per minute; use the matching reviewed rate/version for the catalog or update both together.

Version 1 retains the historical `minutes` integer field instead of AI entitlement and quote fields. It is accepted only with sales disabled. Enabling version 1 sales fails configuration validation; historical fixed-minute test products must never become launch offers.

Product identifiers begin with a letter or number. Duplicate SKU bindings and duplicate provider-product/currency bindings are rejected. Sales require a nonempty catalog. Provider fulfillment compares the saved final amount, product, quantity and currency against authoritative provider state. The approved digest covers whitespace as well as product values. Historical orders retain their own immutable quotes after catalog changes.

The receipt key ring contains `{ "activeKeyID": "key-id", "keys": { "key-id": "<base64>" } }`. It supports 1–10 keys; each value is a canonical base64 encoding of exactly 32 bytes. Key IDs use 1–64 letters, numbers, `_` or `-`. The active ID must exist. Startup rejects removal of any key ID referenced by saved receipts, or provider scope referenced by receipts or purchase orders. Existing encrypted receipts continue to use their original key IDs.

Stripe credentials contain exactly `secretKey` and `webhookSecret`. The secret key must match the environment. The existing adapter verifies the authenticated Stripe account before processing payments.

Play credentials use an existing Google service-account JSON file with `type: "service_account"`, `client_email`, and an RSA PKCS#8 `private_key` of at least 2048 bits. Standard Google metadata fields are accepted. A supplied `token_uri` must be `https://oauth2.googleapis.com/token`; a supplied `universe_domain` must be `googleapis.com`. Credentials are loaded locally and are not created or discovered by the module.

The Play binding file contains exactly `{ "key": "<base64>" }`, encoding 32 bytes. This key binds accounts and orders to Play purchases. Changing it breaks preparation of existing orders; it has no rotation mechanism in this version.

## Service and runner interface

The configured return value contains `purchases` for historical minute reconciliation, `aiPurchases` for AI-value offers and accounting, `fulfillment` for verified entitlement routing, optional `stripe` and `play`, `vault`, `worker`, `runner`, `environment`, `salesEnabled`, and `catalogSHA256`. Pass the complete return value to `createApp` as `minuteCommerce`. No credentials are returned as plain configuration fields.

Optional injected dependencies are `stripeTransport`, `playTransport`, `request` for Google HTTP/OAuth, and `onFailure`. Tests can supply transports without contacting providers. Configuration files remain required when transports are injected.

| Runner setting | Default | Range |
| --- | --- | --- |
| `intervalMilliseconds` | 60,000 | Integer 1,000–3,600,000, measured after a cycle finishes |
| `deliveryLimit` | 5 | Integer 1–10 jobs per cycle |
| `reconciliationLimit` | 100 | Integer 1–1000 completed jobs rescheduled per cycle |
| `voidPagesPerRun` | 2 | Integer 1–5 Play pages per cycle |

`runner.start()` begins an immediate cycle and subsequent unreferenced timers. Repeated starts do nothing. `runOnce()` coalesces concurrent calls within a process. Existing delivery leases protect jobs across processes. Each delivery call handles one job, so shutdown does not begin the rest of a batch.

`runner.stop()` permanently prevents new work and drains the current operation. It must complete before the database pool closes. A provider operation can include several requests, each subject to the adapter's timeout and pagination limits; stop does not pretend to cancel an already running payment verification.

`onFailure` receives only `minute_delivery_failed`, `minute_reconciliation_failed`, or `play_void_reconciliation_failed`. It receives no receipt, token, account identity, provider response or underlying exception. Observer exceptions do not stop durable work.

## Play void checkpoint

Migration 013 adds `minute_play_void_cursors`. A transaction-scoped advisory lock allows only one process to advance a given environment/package cursor. Each successful page commits its next token only after known receipts have durable void markers and retry jobs. A crash can repeat a page without granting or reversing minutes twice.

The initial sweep covers the previous 29 days, leaving time to complete pagination within Google's 30-day boundary. Subsequent completed sweeps wait 15 minutes and overlap the prior checkpoint by five minutes. Each window ends one minute before the current time. Partial pagination resumes its exact stored window. A stalled token or an expired history window fails without advancing the checkpoint. The completed watermark cannot move backward.

The cursor stores only environment, package, pagination token, timestamps and watermark; it stores no user or purchase tokens. Receipt reconciliation every six hours also refetches individual provider orders. Google applies void time filters to when its systems observe the void and supports token pagination. [Google Play voided purchases API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.voidedpurchases/list).

Related: [How to enable minute commerce](enable-minute-commerce.md), [provider integration](minute-provider-integration.md), [minute purchase accounting](minute-purchases.md).

Managed mode and refund amount checks are defined in the [Stripe Managed Payments verification reference](stripe-managed-payments.md).
