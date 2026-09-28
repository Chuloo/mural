# Mural minute packs: implementation and verification

Updated 28 September 2026. The approved scope is one-time Apple and direct Android/Stripe packs, quantities 1–10, with US and Norway as the initial Apple storefronts. Apple US prices are $7/$13/$20; Norway prices are NOK 89/179/249. The existing internal allocations remain unchanged. Planning still uses 30% Apple commission even though the current App Store Connect proceeds table implies 15% after tax.

## Status

The backend is deployed and both native implementations are available for review. Apple sandbox purchases, paid conversations, recovery and a full refund have passed on the physical iPhone. Production Apple sales remain disabled. The pending App Store 1.0 (3) submission has not changed. This record does not approve a public payment launch.

## UI and behavior changes

- Account shows one Mural minutes figure. Paid and mixed balances use whole-minute estimates from the server; internal financial amounts and fee rows are absent from customer usage screens.
- Add Mural minutes offers compact pack selection, a bounded quantity control, the combined estimate and localized total. Mural's Continue buttons are orange; Apple's confirmation and refund sheets retain their native appearance.
- Android's Account Add minutes button uses the same orange and a minimum 52 dp height. iOS hides top-up promotion in My API key mode. Purchase recovery remains reachable in that mode on both platforms.
- Pending attempts retain the original account, order and quantity. Verified delivery refreshes the balance from the server, clears the completed attempt and finishes the StoreKit transaction. Check purchases does not recreate spent credit.
- iOS adds recent Apple purchase history, refund requests and account-deletion support. Google linking requires fresh identity proofs and rejects conflicting ownership.
- Paid leases, final settlement and reservation recovery work on iOS. A free-to-paid boundary preserves context and offers Continue conversation after settlement. An account-bound local checkpoint preserves that continuation through relaunch.
- The free-minutes explanation and Continue/New conversation choices now share a native sheet. Dismissal leaves Talk clear; the microphone reopens the pending choice. No extra inline actions remain.
- Android launches on a plain cream background; the static splash logo and separate loading orb are removed. The animated orb first appears in Talk.
- Existing Settings order, Account orb, Talk controls, source selection and local learning storage are preserved. Buying minutes does not switch conversation source.

## Automated verification

| Area | Result | Evidence |
| --- | --- | --- |
| Server full suite | 404 passed; no skips | `/private/tmp/mural-payment-ci-final.log` |
| Latest Apple adapter and late-closeout tests | 14 passed; no skips, including the new test added after the full suite | `/private/tmp/mural-apple-closeout-final.log` |
| Request admission and account tests | 24 passed; no skips | `/private/tmp/mural-payment-admission-regression.log` |
| Server type checking | Passed | Same focused verification logs |
| Swift core | 129 passed | `/private/tmp/mural-swift-boundary-race.log` |
| Payment monitor | 12 focused tests passed; live read-only inspection passed | [Monitor evidence](payment-monitor-dry-run.json) |
| Final minimal Talk sheets | iOS 2 passed, including largest text; Android 12 launch/conversation/checkout tests passed | `.build/PaymentMinimalHome.xcresult`; `/private/tmp/mural-android-startup-final-ui.log` |
| iOS full interface suite | 44 passed; zero failures | `.build/PaymentFinalRegression.xcresult` |
| Final iOS Account and accessibility changes | 2 passed | `.build/PaymentFinalAccountRegression.xcresult` |
| Android unit tests | 361 passed | `/private/tmp/mural-android-boundary-race-build.log` |
| Android full interface suite | 77 passed | `/private/tmp/mural-android-release-regression-final2.log` |
| Final Android Account and checkout changes | 15 passed | `/private/tmp/mural-android-final-account-checkout.log` |
| Android checkout screenshot capture | 6 passed | `/private/tmp/mural-android-final-visual-capture.log` |
| Release scripts and shared contracts | 66 passed; generated content and cross-platform checks passed | `/private/tmp/mural-payment-release-contracts.log` |
| Android debug/release compilation and lint | Passed | Android build logs and [bundle inspection](android-release-bundle.json) |
| iOS device build and distribution export | Passed | [Candidate verification](ios-production-candidate.json) |

The native accessibility audit checks contrast, hit regions, descriptions, clipping and traits in checkout and history. Largest iOS text and Spanish Android large text have automated coverage and visual inspection. These checks do not replace the outstanding full VoiceOver/TalkBack walkthrough.

Earlier test failures were resolved: preview continuation needed an explicit simulator fixture, Spanish checkout assertions needed the new title, and the Android report test needed to dismiss its keyboard before selecting consent. The final full iOS run passed. The final Android changes passed the affected 15-test suite after the full 77-test baseline.

## Security review

CodeQL alerts 60 and 61 report missing rate limits in the new Apple receipt routes and close-intent route. Both run after the existing Fastify admission hook. The focused tests confirm that Apple delivery/recovery share a durable 600-request network allowance per hour, which survives app recreation. Close intents reject request 121 within a minute. Rejections occur before authentication or provider work. Encoded routes and forged proxy headers cannot bypass these limits; other trusted networks retain their own allowance. The reviewed findings are false positives; no runtime protection was removed.

## Actual provider and device checks

| Check | Observed result |
| --- | --- |
| Apple US quantities 1/2/10 | $7/$14/$70 sandbox confirmations; exactly 1/2/10 allocations delivered |
| Repeat Check purchases and relaunch | US balance remained about 479 minutes; no duplicate grant |
| Apple Norway catalog | Physical iPhone displayed NOK 89/179/249 and the orange Continue action |
| Norway quantity 1 | NOK 89 delivered once; balance became about 515 minutes |
| Norway full refund | Apple request accepted; signed REFUND received; exactly 3,690,000,000 nano-USD reversed; phone showed About 479 min and Refund recorded |
| Norway quantity 2 | NOK 178 delivered once; phone showed About 552 min |
| Norway 50% refund request | Apple accepted GRANT_PRORATED but its signed API result was REFUND_FULL / 100000 (100%). The server correctly reversed the full two-pack grant; a quantity-2 partial refund is still unverified. |
| Stripe quantities 1/2/10 | Real Stripe test checkouts, partial/full refunds and nine event replays passed; synthetic account ended at zero |
| Physical Samsung | Apple-funded balance 479 → 478 after a 49-second call; a $5 Stripe test pack delivered once and updated both phones to about 515 min; recovery and relaunch retained it |
| Real free-to-paid boundary | Audited 20-second sandbox allowance; remaining 2 seconds preserved after settlement; checkpoint survived relaunch; server-close race fixed; paid continuation completed for 40 seconds |
| Apple partial-refund retry | A fresh NOK 89 purchase returned signed REFUND_PRORATED / 50000 (50%). Normal reconciliation reversed exactly 1,845,000,000 nano-USD and NOK 44.50; prior quantity-2 full-refund result remains unchanged |
| Paid iPhone voice and helpers | User completed two calls (35 and 43 seconds); 11 helper requests settled; total provider cost $0.068367401; zero reserved value |

The latest audit shows zero reserved value, no unsettled sandbox sessions and total provider test cost of $0.198861469.

See [sandbox record](sandbox-setup-2026-09-26.json), [latest provider audit](latest-provider-audit.json), [Stripe evidence](stripe-real-sandbox.json), and [catalog/proceeds review](apple-catalog-review.json).

Apple sandbox returned unit price in signed quantity purchases. A narrowly scoped test-environment compatibility rule accepts the exact unit price multiplied by the signed quantity. Live verification continues to require Apple's documented total-price representation. The controlled live test remains mandatory.

## Deployment and artifacts

Production source: `0f8a0b106bd2e149be98ed24e1dd82720b9f60d0`.

Production image: `sha256:2a97a66affb2f664d6c3d3883c4f0509d55fb8c0fbd0f41812802ce49170d733`.

Rollback: `/opt/mural/deploy/before-payment-closeout-20260928T095941Z`. The previous image/configuration and encrypted database backup are retained. Migrations through 030 and runtime grants are applied. Active-call checks preceded migration/restart. Private production configuration was preserved. Health, readiness, authentication and old/new catalog contracts passed; Apple sales are off and existing Stripe sales remain available.

Sandbox uses a separate API, database and receipt scope. Its current image and rollback are in the sandbox record. Only the approved test account can spend provider funds, with a $1.50 lifetime exposure cap and 60-second call limit. Observed usage remains within the user's $2 authorization.

The corrected log audit reads both numeric and text severity labels. It detects repeated hangup retries for 21 unresolved production calls from September 14–16. One unpaid Stripe checkout from September 27 remains pending with zero grant; the existing runner labels ordinary pending retries as delivery warnings. These historical records are preserved. The read-only monitor distinguishes pending payments from failed delivery and reports the overdue settlements. See [operations inspection](payment-operations-inspection.json).

- iPhone sandbox build 18 is installed with existing learning data preserved; a pre-test backup is retained privately.
- iOS 1.1 (19) is exported with distribution signing, production API configuration and Apple sales disabled. It has not been uploaded.
- Android v10 has a staged direct APK using the same signing certificate as v9. It is a release payload with debugging disabled. The matching sandbox code is verified on a physical Samsung Galaxy S9 running Android 10. The production candidate has not been published.
- The corresponding Android test build points to the isolated sandbox.
- Public Terms and Privacy were published in website commit `9102c3e`. App Store App Privacy includes purchase history. Play declarations remain a draft for any future paid Play release.

Artifacts are retained locally in `deliverables/mural-payments-candidate-2026-09-28` beside the repository. Signing keys, credentials, receipts and learning backups remain outside Git.

## Remaining release gates

1. Verify a real prorated refund for quantity 2. The original quantity-2 request returned an explicit full refund; the later quantity-1 retry returned a real 50% refund and passed exact accounting. Multi-quantity partial arithmetic and out-of-order events pass automated tests.
2. Finish Android purchase → iPhone spend. Apple purchase → Android spend and shared balance updates on both devices pass. The remaining iPhone microphone step requires direct device use.
3. Exercise fresh Apple-to-Google linking on devices. Server conflict/ownership tests pass; matching email addresses do not merge accounts.
4. Confirm the same real boundary flow on iPhone. Physical Android now passes free settlement, returned remainder, relaunch recovery and paid continuation. Both native clients have regression coverage for the server-close race.
5. Complete the full manual VoiceOver/TalkBack, reduced-motion/transparency and device-state matrix from the brief.
6. Configure and verify email delivery for the tested payment monitor. William approved hi@hackmamba.io, William as owner, and replies within one business day. SMTP provider credentials are still needed; no email service is configured or timer activated. See the [operations runbook](../../release/operations/README.md).
7. Submit the first Apple consumables with the new app version after acceptance, obtain approval, then obtain separate authorization for the controlled live purchase/refund. Configure and verify production Apple credentials/notifications as part of that activation. Existing pending submissions must stay intact.

Recommendation: finish the device gates against the isolated sandbox, then submit the Apple payment release. Keep production Apple sales disabled until store approval and the authorized live verification pass. Google Play sales remain gated pending their separate catalog, acknowledgement-alert and store-specific device checks.
