# Apple regional pricing reference

## Catalog and credit

Apple regional rows use `quote.apple.pricingBasis = fixed-usd-allocation`. The three product IDs are `chat.mural.ios.minutes.small.v1`, `medium.v1` and `large.v1` under the same prefix. Each pack grants 369, 766 or 1161 USD cents of AI credit, multiplied by quantity. The planning basis remains 30% Apple commission. Listed Apple proceeds are retained separately and do not recalculate that credit.

The internal quote uses USD with exponent 2. Its total contains the AI allocation and the 15% Mural service fee. Checkout currency and total come from the separate Apple price snapshot.

## Apple price snapshot

| Field | Contract |
| --- | --- |
| `storefront` | Exact uppercase ASCII three-letter StoreKit country code |
| `regionCode` | Reviewed uppercase two-letter country identity |
| `currency`, `currencyExponent` | Lowercase currency and its exact reviewed ISO 4217 exponent, 0–3 |
| `unitTotalMinor` | Exact local customer price in minor units; 1–100,000,000 |
| `customerPrice`, `proceeds` | Original positive decimal strings from Apple; listed proceeds may contain fractional minor units |
| `mayAdjustAutomatically` | Apple's exported price adjustment flag |
| `scheduleVersion` | Immutable reviewed catalog version |
| `source` | Export/API source kind, exact Apple URL, UTC capture time and SHA256; API sources also require a price point ID; schedule ID is optional |

Decimal conversion is exact and rejects fractional customer minor units. Checkout quantity is 1–10; total and refund evidence are bounded at 1,000,000,000 minor units. Stripe and Play retain their 100,000,000 limits.

Apple [price schedules](https://developer.apple.com/help/app-store-connect/manage-in-app-purchases/set-a-price-for-an-in-app-purchase/) can change automatic regional prices. A changed StoreKit price requires a matching reviewed catalog snapshot. Apple's [signed transaction price](https://developer.apple.com/documentation/AppStoreServerAPI/price) is expressed in milliunits and normally includes quantity. The existing verified sandbox unit-price compatibility remains limited to test purchases.

## Country review and provenance

`apple-storefronts.json` records 175 Apple export labels and reviewed country identities. On 2026-10-04, 162 matched the [supported service countries](https://help.openai.com/en/articles/5347006-openai-api-supported-countries-and-territories). Regional service restrictions still apply. Future Apple storefront availability is disabled.

Currency exponents come from the [ISO 4217 Maintenance Agency list](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml), published 2026-09-17; the source hash is recorded in `store-currency-exponents.ts`. Country identities use [UN M49](https://unstats.un.org/unsd/methodology/m49/overview/), with explicit Apple display-name aliases, Apple's `XKS` code for Kosovo, and `TW`/`TWN` for Taiwan. Checkout currency comes from each Apple export; countries sharing a currency retain separate storefront bindings.

## Migration and retained purchases

Migration `033_apple_regional_quotes.sql` adds Apple-specific amount constraints and regional quote validation. It preserves the legacy USA/NOR and Play branches, all stored rows, and the existing function privileges; no new runtime grants are required.

Pending order retries, receipt recovery and refunds use the immutable stored quote. A replacement catalog affects new orders. Live and test purchases retain separate verified environments and wallet funding scopes. Older server images may defer newly created regional receipts, especially totals above the old amount limit; retaining their encrypted receipts allows recovery after the current server is restored.

See [How to prepare the catalog](prepare-regional-catalog.md).
