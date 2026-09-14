# Android commerce and voice verification

Verified on 13 September 2026 during the Android release work. The checks below use the current local release branch; the signed Play candidate still needs its own verification.

## Real Stripe sandbox

The new minute-purchase adapter created a 30-minute test pack in Mural's dedicated Stripe sandbox. The $3.00 USD amount was a test fixture, not an approved launch price. No real money, production accounts or production database records were involved.

Checkout was completed in Stripe's hosted form with its documented test card and synthetic contact details. Genuine signed notifications reached the new `/v1/webhooks/stripe/minutes` route. The checks confirmed:

- Retrying checkout returned the same payment session.
- A completed payment granted exactly 1,800,000 milliseconds once.
- Replaying the signed payment notification did not add another grant.
- A $1.00 partial refund left exactly 20 minutes.
- Refunding the remaining $2.00 left zero minutes and no outstanding reversal.
- Replaying refund notifications did not subtract time twice.
- The provider reference was encrypted in the receipt table.
- Adaptive Pricing was disabled, preserving the quoted USD amount.

The temporary product and price were archived after the test. The browser return exposed a missing website page; the website fix was committed separately as `268a365`. The return page does not infer payment success from a query string. Private evidence contains only the test identifiers and assertions; signing secrets and raw webhook bodies were not written to the evidence report.

This verifies card checkout and partial/full refund delivery in Stripe's sandbox. Play purchases, regional prices, taxes, disputes, live payments and candidate-app purchase restoration remain separate release checks. [Stripe test payments](https://docs.stripe.com/testing)

## Real provider shutdown

One local browser WebRTC session used a synthetic silent audio track and the dedicated server key. It sent no microphone audio or learner history. The server sent `session.close` after 20 seconds and received an authoritative final event reporting 19 seconds. There was one create request, one usage update, no connection loss, and no fallback hangup. The test kept no transcript or audio recording.

This confirms the provider's server-control path on this account. It does not establish Android microphone quality or end-to-end minute settlement. [GPT-Live server controls](https://developers.openai.com/api/docs/guides/voice-server-controls)

## Local accounting and recovery

The shared voice controller now supports either legacy money reservations or minute reservations. Eighteen HTTP/WebSocket integration tests passed against an isolated local PostgreSQL database, including exact minute settlement, guest balances without money wallets, short remainders, setup delay, duplicate creates, uncertain creation, final-usage regression, recovery, cutoff overrun, refunds during speech and sign-out. Voice admission reserves teaching funding in the same transaction, so an unfunded helper budget cannot leave a billed voice session running.

The original controlled tests used exact connected-time charging. That policy is superseded for new hosted-minute sessions by the owner's approved 15-second minimum. Final charges are capped by the reserved balance; existing sessions keep their original policy. A cancellation before any provider attempt costs zero. Unknown creation or missing final usage keeps the hold, and Mural still absorbs provider cutoff overrun.

Teaching requests now earn their funding and request limits from authoritative charged time. Closing a short conversation immediately releases the unearned portion of its teaching reservation, while retaining its earned post-conversation allowance and any uncertain provider holds. This prevents repeated short starts from spending the full ten-minute teaching budget on every connection. Public activation still requires funded grant commitments, an overrun reserve and provider reconciliation.

The restricted-runtime test also exercised guest-to-account transfer. Claim ownership can move, while device proof and original allowance remain protected from modification.

After the approved minimum-charge change, the complete API suite passed 238 tests with no skips, and TypeScript checks passed. Tests include 40 zero-length finalized sessions consuming one ten-minute grant, a final two-second balance, pre-provider cancellation, recovery before any provider attempt, earned teaching limits, post-close funding release, immutable earlier policy and wrong-account reauthentication. These use synthetic data and local provider doubles. The earlier source secret scan found no credentials; native purchase testing and deployed runtime checks remain separate gates.

## Guest beta decision

The owner approved one free allowance per installation for the first public beta, accepting that a reinstall may claim another allowance. New grants must stay within the approved $25/day and $100 total funding commitments. Device Recall approval is no longer a prerequisite for this beta; actual cost accounting, installation proof and the enforced grant limits still require verification before public activation.

## Deployment and limit review

Commit `b0fbc88` was deployed on 13 September 2026 after an encrypted database backup. Additive migrations through 014 and the restricted database grants applied successfully. Public health and readiness checks returned 200; the database was healthy and both Google sign-in configurations remained available. Hosted voice, free trial admission, optional AI reporting and minute sales remain disabled. Their public routes still return the configured unavailable response.

The owner requested an explanation of the limit experience before further funding changes. Per-entitlement funding implementation is paused, with no migration 015 or funding policy changes made. The intended rule is to stop new free grants at the campaign budget while preserving signup, purchases and previously issued minutes. The graceful no-free-offer response and its native onboarding path remain to be implemented. The current experimental lifetime voice cap and hardcoded signup capacity must be reviewed before public launch. This deployment does not establish that the intended public funding behavior is ready.
