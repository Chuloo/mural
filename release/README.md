# Release status

**The source and website are public, and optional Google accounts are deployed for supported preview builds.** The user completed Google sign-in on the phone, and PostgreSQL account/session records were verified without exposing personal data. Apple sign-in, TestFlight, the App Store, hosted free minutes and credit purchases remain unavailable. Account creation does not enable hosted conversations or payments; practice with a personal OpenAI key remains independent of signup.

## Completed

- [x] Complete the [September 12 security review](security-audit-2026-09-12.md), publish fixes in `0b2cf65`, deploy the corrected backend and install the signed app update. **60 Core tests and two focused UI checks passed.** Installation succeeded at 17:20 CEST; iOS blocked the subsequent launch because the phone was locked.
- [x] Publish Google OAuth to **In production**, verify mural.chat ownership through Cloudflare DNS, and publish the verified Mural consent branding. Sensitive/restricted scope verification is not required for the requested basic identity scopes.
- [x] Publish [the iPhone app repository](https://github.com/Chuloo/mural) under MIT. Earlier CI at `f2f1a78` passed the Swift core and server jobs; the later backend and native checks are recorded separately below.
- [x] Publish [the separate website repository](https://github.com/Chuloo/mural-website), including update `ab20d41`, and make [mural.chat](https://mural.chat/) available over HTTPS.
- [x] Verify the live **138,707-byte, 1733 × 908** JPEG social image, updated OG/X metadata, favicon ICO and Apple touch PNG. The matching Mural icon was uploaded to the live Stripe account and its branding setting persisted. This verifies branding, not payment activation.
- [x] Enable the website's email access list with private database storage, consent, duplicate handling, admission limits and retention. A live browser submission and repeat request produced one database row; the synthetic test address was removed afterward. No invitations are sent automatically.
- [x] Verify the privacy, terms and support pages on the custom domain return HTTP 200 without login. Confirm the operator as **Hackmamba Inc., incorporated in the United States**, with support at hi@hackmamba.io.
- [x] Implement Norwegian, Spanish, English, French, German, Italian, Brazilian Portuguese and Mandarin modules, language/subtitle onboarding, versioned AI consent, local backups and the existing conversation controls.
- [x] Pass **56 Swift core tests, three baseline UI checks and two configured-account UI checks**. The signed personal build was installed and launched on the iPhone at **16:48:47 CEST on September 12**, preserving existing data. These checks do not establish a successful real Google login or a final App Store candidate. The earlier 11-test language/onboarding suite is historical coverage. See [the verification record](../verification/validation.md) for tested builds and limits.
- [x] Deploy backend `0b2cf65` and its matching proxy fix on Hetzner after **77 passing tests with PostgreSQL**. Provider discovery reports Google enabled and Apple disabled. Live checks returned `401` for an anonymous account request, `400` for a malformed identity exchange and `200` for a fresh sign-in challenge. Wallet, trial, Checkout and hosted-voice routes remain gated with `503` responses.
- [x] Prepare four **1320 × 2868** [App Store screenshots](screenshots/en-US/README.md), app icons, first-party and WebRTC privacy manifests, and third-party notices.
- [x] Prepare [listing and review-note drafts](app-store-metadata.md) and a [BYOK privacy inventory](app-privacy.md). These have not been entered or approved in App Store Connect.
- [x] Compile an unsigned **0.1.0 (1)** iOS Release archive with both privacy manifests and debug symbols at the earlier Settings-link checkpoint. This is historical local compilation evidence; it predates the account rollout and is not the final native candidate. Apple distribution signing, upload validation and review remain pending.

The local archive is `.build/ReleasePrep/Mural-0.1.0-unsigned.xcarchive`, refreshed at **14:44 CEST on September 12** with the permanent Settings links. Its simulator build and existing Settings navigation check passed before archiving. Create and verify a fresh archive from the final account-enabled or BYOK-only release candidate before any upload. Publication scope and exclusions are recorded in [the source audit](source-audit.md).

## Finish the BYOK release

| Remaining item | What is needed |
| --- | --- |
| Apple membership and seller | The Apple Developer portal shows **Hackmamba Inc. — Pending**. Complete paid enrollment and confirm the active App Store seller. The operator is incorporated in the United States. William Imoh and hi@hackmamba.io are confirmed contacts; the private App Review telephone is still needed. |
| Apple app record and signing | Register the app identifier, create the App Store Connect record and SKU, accept agreements, and produce a distribution-signed archive. Preserve the personal installation’s signing identity until a migration is planned. |
| Review access | Provision working review access so the reviewer can use speech without purchasing OpenAI access. Supply credentials privately; do not bundle or commit a shared key. |
| Final device checks | Test the candidate on an iPhone: microphone denial, offline/failing API requests, interruptions, cellular use, reset, meanings, export/import and deletion. Review pronunciation and corrections for all eight languages with proficient speakers, including Mandarin pinyin and tones. Automated live checks do not establish teaching quality. |
| Optional-account checks | Google login and profile display are confirmed. Complete session restoration, sign-out and account deletion on the phone. Verify local learning survives account actions. Keep Apple sign-in unavailable until enrollment, credentials, capability and revocation are configured and tested. These checks are required for a candidate that exposes accounts. |
| Store declarations | Finalize privacy, age rating, accessibility claims, export compliance, regions and pricing against the uploaded build. Extend the existing BYOK App Privacy inventory to cover signup and security records if accounts are included. Resolve remaining fields in the listing draft. |
| Distribution | Validate and upload through Xcode, verify an internal TestFlight installation, complete external beta review, then enable and check a public invitation. App Store review is a separate submission. |

Language and subtitle selection, followed by AI consent, are active for new installations. Existing users retain their settings. Supported preview builds offer optional Google sign-in; Apple remains disabled. The app's onboarding and AI-consent screens link to the privacy policy. Settings includes all three release pages, and the account screen presents linked terms and privacy acknowledgement before its available sign-in buttons. The website access list is separate from an app account and is not a technical invitation-only gate for Google signup.

| Page | Canonical URL | Availability |
| --- | --- | --- |
| Privacy | https://mural.chat/privacy/ | Live; HTTPS 200 verified September 12, 2026 |
| Terms | https://mural.chat/terms/ | Live; HTTPS 200 verified September 12, 2026 |
| Support | https://mural.chat/support/ | Live; HTTPS 200 verified September 12, 2026 |

Use [the Apple release checklist](apple-release.md) for the upload sequence and official requirements. Keep final candidate verification distinct from the historical results above.

## Finish hosted trials and payments

A correctly disclosed BYOK release can proceed independently of these items. Optional-account checks are listed above; free minutes, hosted conversations and purchased credits require the work below.

- [x] Deploy the API foundation, PostgreSQL and HTTPS proxy with separate migration and restricted runtime database roles. Public health, readiness and pricing checks pass. Google account routes are available as described above; commercial routes remain gated with `503 commercial_features_not_ready`.
- [x] Verify a Stripe sandbox purchase and full refund with signed webhooks. Credit was added and reversed once despite repeated events. Fixed-USD Checkout was verified against Stripe; that earlier billing checkpoint passed 57 tests with PostgreSQL and no skips. These checks used no real money and do not validate a native purchase flow or enable live payments.
- [x] Configure daily encrypted local database backups and verify one backup by restoring it into a separate temporary database. The first encrypted copy was also retained off the server.
- [ ] Configure recurring encrypted off-server backup storage, verify scheduled recovery and add operational alerts without conversation content. A daily local backup and one off-server copy do not complete this work.
- [x] Configure Google's native OAuth audience and deploy optional account routes with bounded sessions, authentication admission limits, retention and signup-only account deletion. The website privacy and terms describe this account-only preview.
- [ ] Complete the remaining Google session/deletion device checks and, after Apple enrollment, configure and verify Apple sign-in and authorization revocation. Test recovery after device loss and the user-facing resolution path for future accounts with balances or payments; current empty-account deletion does not complete that workflow.
- [ ] Verify the hosted voice adapter against a bounded real provider call, including cutoff, hangup, final usage, network failure and reconciliation. Implement budgets for hosted teaching, subtitles and search.
- [ ] Implement App Attest/DeviceCheck verification, durable trial claims, the ten-minute allowance and a global free-use budget. The current trial attestor rejects requests.
- [ ] Complete StoreKit verification and storefront routing for in-app sales, plus live payment activation, refunds, dispute resolution, taxes and transparent receipts. Stripe sandbox tests alone do not enable App Store purchases.
- [ ] Update privacy, terms, App Privacy answers and review access for actual billing and usage records before commercial activation. Account-only website disclosures do not cover future payment or hosted-conversation processing.

The native account client and Google-enabled server are deployed for the preview. Automated account tests use synthetic identities and provider responses; real Google signup is confirmed, while device session-restoration and deletion checks remain pending. Apple and commercial features remain off. See [native setup](../docs/managed-accounts.md), [account deployment](../services/api/docs/enable-accounts.md), [stored account records](../services/api/docs/accounts-reference.md) and [the server runbook](../services/api/README.md) for configuration and remaining work.
