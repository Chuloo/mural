# Stripe Managed Payments verification reference

Mural supports standard Stripe Checkout and Stripe Managed Payments through the same receipt and fulfillment worker. The installed Stripe SDK is `22.6.2`, whose default API version is `2026-08-26.dahlia`. Google Play verification is separate and unchanged.

## Configuration and order binding

The protected commerce manifest accepts `stripe.managedPayments`, an optional boolean with default `false`. It selects the mode for new Stripe checkout attempts. Existing sales and live-environment gates still apply.

Migration `021_stripe_managed_payments.sql` adds the immutable mode to `minute_stripe_checkout_attempts`. The server records it before contacting Checkout. Retries and webhook verification use that saved selection, even after configuration changes. Historical attempts remain standard Checkout. Historical Stripe receipts without an attempt receive a standard binding during migration. Verification rejects missing bindings and provider mode mismatches.

Managed checkout requires an exclusive-tax Price. Its request includes `managed_payments.enabled: true` and omits `automatic_tax` and `adaptive_pricing`. The product needs an eligible tax code and the Stripe account needs Managed Payments activation. These are Stripe requirements; the configuration flag alone does not activate the account. [Stripe integration requirements](https://docs.stripe.com/payments/managed-payments/update-checkout)

## Amount verification

Adaptive Pricing keeps Checkout Session and PaymentIntent amounts in the integration currency. The buyer's local currency and amount appear separately in `presentment_details`. Refund requests use the integration currency, and Stripe handles the original exchange rate. Mural never converts presentment amounts into AI entitlement. [Stripe Adaptive Pricing](https://docs.stripe.com/payments/currencies/localize-prices/adaptive-pricing?payment-ui=stripe-hosted)

| Evidence | Required relationship for Managed Payments |
| --- | --- |
| Account, environment, order reference, metadata | Exact existing merchant and order bindings |
| Session and PaymentIntent | `managed_payments.enabled === true` |
| Session and single line item | Original quote currency; quantity one; exact saved Price ID |
| `amount_subtotal` | Original pre-tax `order.total_minor` |
| Session discount and shipping totals | Zero |
| Line discount and tax | Zero discount; tax equals Session tax |
| Session and line `amount_total` | Original base plus verified tax |
| PaymentIntent | Succeeded; original currency; `amount` and `amount_received` equal Session gross |
| Charge | Correct PaymentIntent; paid, captured, succeeded; `amount` and `amount_captured` equal Session gross |
| Presentment details, when present | Positive safe integer amount and currency; provided Session, PaymentIntent and Charge details agree |

Catalog base amounts remain limited to `100,000,000` minor units. Verified gross, tax and refund amounts use a separate representation bound of `9,007,199,254,740,991` (`Number.MAX_SAFE_INTEGER`); this is a parsing limit, not a promise that Stripe accepts charges of that size. Migration `022_stripe_provider_amount_bounds.sql` applies the same gross bound in PostgreSQL. Base-plus-tax comparison and refund normalization use `BigInt`, and cumulative refunds are checked against the remaining gross before addition.

`minute_stripe_paid_totals` preserves the verified gross and tax in integration-currency minor units. The row is immutable. Subsequent reconciliation rejects a changed gross or tax instead of changing a refund's denominator. Runtime privileges permit only read and insert access to this table.

## Refund evidence

The provider accepts the documented `ch_` and `py_` Charge prefixes and `re_` and `pyr_` Refund prefixes. Other prefixes and path characters are rejected. [Stripe Charge identifiers](https://support.stripe.com/questions/getting-started-with-stripe-through-a-third-party-platform?locale=en-GB), [Stripe non-card refund examples](https://docs.stripe.com/refunds#handle-failed-refunds).

The provider retrieves all refund pages through the authenticated Stripe API. Each successful refund must reference the verified PaymentIntent, Charge and integration currency. Failed, canceled, pending and action-required refunds do not count as completed refunds. Duplicate refund IDs, incomplete pagination and totals above the verified gross fail reconciliation.

The existing entitlement ledger expects refunds in the original quote basis. Managed refunds are normalized with integer arithmetic:

```text
normalizedRefund = ceil(originalBase × successfulGrossRefunds / verifiedGross)
```

The ledger then reverses that proportion of the original AI allocation. Tax, payment fees and the Mural fee never become spendable AI value. A full gross refund revokes the full original allocation; partial refunds round conservatively toward reversal. Replayed notifications cannot grant or reverse the same value twice. Existing dispute handling still voids the entitlement.

Link can issue refunds, including without approval after a support escalation goes unanswered for 48 hours. Stripe's documented refund support includes certain transactions within 60 days. Managed refunds include tax; some jurisdictions still require the original tax to be remitted. [Managed Payments support and refunds](https://docs.stripe.com/payments/managed-payments/how-it-works)

Original processing fees are not returned by Stripe. Refunds go to the original payment method. [Stripe refund behavior](https://docs.stripe.com/refunds)

The ledger retains the highest observed reversal. A refund that later fails does not automatically restore spent or reversed value; that case requires support reconciliation. Link-side deletion can also make provider objects unavailable. A failed lookup does not establish either payment or refund success.

## Validation limits

Offline provider fixtures and PostgreSQL tests cover mode binding, taxed totals, local presentment, exact AI allocation, partial and full refunds, duplicate delivery, missing or inconsistent evidence, standard-mode compatibility and runtime privileges. These tests do not establish that the production Stripe account, tax classification, local payment methods or webhook delivery are configured correctly. Those require completed Stripe test checkouts and separate live deployment verification.
