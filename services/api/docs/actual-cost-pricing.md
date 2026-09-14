# Why paid minutes are estimates

Mural's approved pricing formula uses exact AI cost, a 15% Mural service fee, and separately quoted payment costs and buffer. Purchased balances fund actual provider charges; the app shows estimated conversation minutes. A purchase does not guarantee a fixed duration. The $5.99 offer was not approved.

Voice has a duration-based cost. Meanings, corrections, assessments and topic searches add usage that varies between conversations. Under exact-usage pricing, a currency balance allows Mural to charge for actual provider usage while keeping unused value available for later conversations. Purchased value adds to the existing balance; it does not replace remaining free time. Free and gifted minutes remain duration entitlements and are spent before paid value.

## Fee calculation

The quote preserves the amount allocated to AI usage. Mural's service fee is a percentage of that amount. Payment costs are calculated on the full amount collected, because a processor's percentage also applies to the fees in the payment. Any payment buffer is shown separately from the estimated processing charge.

For example, a hypothetical $2.00 AI allocation with the 15% service fee leaves $2.00 for AI and $0.30 for Mural before payment costs. No live processor rate or checkout total is implied by this example. Channel, currency, regional fees and taxes need verification before a purchasable offer is created.

The service percentage lives in the database, initially at 1,500 basis points. Operator changes are versioned and audited. The public API can read this policy but cannot change it. A fulfilled order must retain its original quote, AI value, fees, currency and policy version; future fee changes must not rewrite an existing purchase.

## Implementation status

The configurable policy, integer quote calculation and estimated-duration calculation are implemented and tested. The owner confirmed exact AI charges with estimated minutes on September 13, 2026. The public pricing response identifies this model and does not advertise fixed minute packs.

Live exact-cost purchases remain disabled. The new implementation connects verified purchases to AI value, reserves voice and teaching costs separately, settles known provider usage, reverses proportional refunds and shows estimated minutes with itemized fees in Android. The API suite passes 292 tests. A real Stripe sandbox checkout verified a single grant despite repeated delivery, encrypted receipts, partial and full refunds, and exclusion of test funds from public paid availability. These checks do not certify live Stripe or Google Play payments. Channel fees, taxes, launch markets, live configuration and Play purchase verification remain release requirements.

Historical cash wallets require privileged reconciliation before they can accept public paid purchases or conversations. Legacy cash sessions automatically mark their owner's wallet unverified; this cannot be reversed by the API role. Generic direct ledger reserve/settle operations remain private test tools and also require reconciliation before any affected account becomes eligible for public paid use. Do not bulk-approve historical balances.

Free trial funding is independent of payment pricing. The daily and total trial budgets govern new grants across the app. They do not cap signups, revoke existing grants, or set a per-customer spending ceiling.
