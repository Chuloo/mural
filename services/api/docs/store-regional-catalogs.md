# Regional store catalogs

Google Play minute packs use a fixed USD AI allocation with a separate local checkout price. The shared `StoreMarketPrice` model can support Apple pricing later; this change leaves Apple's current storefront catalog and activation unchanged.

## Client contract

A Play client reads its country from `BillingClient.getBillingConfigAsync()`, then requests `/v1/minutes/products?provider=play&regionCode=GB`. The server returns only that country's products. An enabled catalog with no products for the requested country returns `available: false` and `availabilityReason: "unsupported_country"`.

A regional product has `quote.play.pricingBasis: "fixed-usd-allocation"`. Its product `currency` and `totalMinor` match `quote.play.currency` and `quote.play.unitTotalMinor`; price matching uses `quote.play.currencyExponent`. The remaining quote arithmetic is USD accounting: `quote.currency` is `usd`, and the allocation is independent of the local purchase price.

Creating an order requires the chosen SKU, `regionCode`, and `scheduleVersion` from that product. The server compares all three before opening checkout and preserves them in the immutable order. Reusing an idempotency key with a different region or schedule fails. Recovery and refunds use the original order even after the catalog changes.

Requests without `regionCode` return only the unregionalized compatibility rows used by shipped clients. Each Play response is limited to 100 rows and 120 KB of product data. The protected catalog may contain 4,096 rows within an 8 MiB file; its exact bytes still require approval before sales can start.

## Price and receipt checks

The catalog generator accepts reviewed Google prices and either conversion tax amounts or Console tax-rate estimates. Estimates carry `taxBasis: "console-rate-estimate"`, the displayed rate, and the location-override flag. Tax, commission, and proceeds estimates are planning values; they neither grant credit nor replace receipt verification.

Google purchase verification requires the saved country, product, account/order binding, environment, quantity, and listed price. It checks actual order and line totals against Google's tax treatment. An exact Play Points coupon may explain a Google-funded discount; unidentified discounts and developer offer variants are rejected. Successful cumulative refunds reverse the original allocation proportionally, with a full refund reversing the entire allocation. [Google Orders API](https://developers.google.com/android-publisher/api-ref/rest/v3/orders)

Apply migration `031_play_regional_quotes.sql` before enabling regional rows. Preserve existing compatibility rows, historical receipts, provider credentials, and Apple/Stripe configuration when replacing the approved catalog.
