# How to release Mural through Apple

**Current status, September 25, 2026:** Mural 1.0 (2) is uploaded for internal TestFlight testing, and its App Store version is a saved draft. The checklist below records the September 12 preparation state and should not be used as the current status. See [the build 2 release record](testflight-2026-09-25.md) before another upload or review submission.

Use this checklist for the uploaded build, not just the source checkout. Requirements were checked against Apple’s official documentation on 12 September 2026.

The current completed work and remaining credentials, code and verification tasks are summarized in [release status](README.md). An unsigned archive does not complete the distribution or review steps below.

## Resolve before uploading

- [ ] Activate Apple Developer Program membership; the owner confirmed it is not active yet. Confirm the intended seller entity. A Personal Team installation cannot be distributed through TestFlight. [Apple distribution guide](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases)
- [x] Confirm the operator as **Hackmamba Inc., incorporated in the United States**. William Imoh and hi@hackmamba.io are confirmed contacts. Use the copyright holder recorded in [LICENSE](../LICENSE); the App Store seller name follows the enrolled account.
- [ ] Obtain the App Review telephone privately and confirm the enrolled Apple seller entity when membership is active.
- [ ] Register the existing app identifier with the distribution team, if available. Keep the installed personal build’s signing and bundle identifier stable until an explicit migration is planned.
- [ ] Create Mural’s App Store Connect app record and choose the bundle ID, SKU and primary metadata language. Confirm name availability there.
- [ ] Accept current developer agreements. Complete business/trader, banking and tax fields that apply to the selected distribution and payment model.
- [x] Add persistent Settings links to `https://mural.chat/privacy/`, `https://mural.chat/terms/` and `https://mural.chat/support/`. The onboarding and existing-user consent screens also link to privacy.
- [x] Verify all three URLs over HTTPS without login. Privacy, terms and support returned HTTP 200 on September 12, 2026; the support page links to hi@hackmamba.io.
- [ ] Decide the review-access route. The current BYOK build needs a provisioned review credential or a working managed-access flow. A reviewer should not need to buy their own OpenAI access. Never embed a shared key in the app or commit review credentials.

## Validate the binary

- [ ] Run core and UI tests, then test the final build on a real iPhone. Cover first launch, microphone denial, no network, failed provider billing, interruptions, backgrounding, subtitle toggling, reset and export/import.
- [ ] Review speech and corrections for every advertised learning language with a competent speaker. Record remaining limitations.
- [ ] Verify archive compatibility with existing learning data and confirm **Delete all conversations and learning** also removes the legacy migration backup. User-exported files remain under the user’s control.
- [ ] Increment the build number for each upload. Confirm the public version, minimum iOS version, icon, portrait support and release configuration.
- [ ] Archive for **Any iOS Device** with the distribution team. Run Xcode’s archive validation and inspect the generated privacy report.
- [ ] Verify both `Mural.app/PrivacyInfo.xcprivacy` and the embedded WebRTC framework’s manifest. Inspect Apple’s upload diagnostics for missing required-reason declarations or SDK signing issues. The presence of a source manifest alone does not validate an archive. [Apple privacy manifests](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api), [SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/)
- [ ] Confirm third-party notices match the shipped WebRTC artifact and all redistributable components.
- [ ] Recheck export compliance. The current `ITSAppUsesNonExemptEncryption = false` is a build declaration, not a completed legal determination for every future dependency.

## Publish a TestFlight link

1. Upload the archive using **App Store Connect** distribution. Do not select **TestFlight Internal Only** if it needs a public beta link.
2. Wait for processing and resolve any compliance or validation questions. Add beta description, feedback email, review contact and review access instructions.
3. Create an internal testing group and verify installation through TestFlight.
4. Create an external group, add the build and enter the testing notes from [the metadata draft](app-store-metadata.md). Submit the first build for TestFlight App Review.
5. After approval, enable the public invitation link. Set a tester limit suitable for the available AI budget and require compatible iPhones/iOS versions. Verify the link from an account outside the App Store Connect team before adding it to the website.

Apple allows up to 10,000 external testers per app; the first external build receives a full review. Beta approval is separate from App Store release approval. [Apple’s external testing process](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)

## Prepare the App Store version

- [ ] Complete the listing fields in [the metadata draft](app-store-metadata.md), including description, keywords, category, support URL, copyright and review contact. [Required properties](https://developer.apple.com/help/app-store-connect/reference/app-information/required-localizable-and-editable-properties/)
- [x] Prepare four **1320 × 2868** PNGs without transparency in [screenshots/en-US](screenshots/en-US/README.md). They show the current core interface with Spanish sample content. Recapture if these screens change before upload. Apple accepts one to ten screenshots per set; the current target is iPhone-only. [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)
- [ ] Complete the age-rating questionnaire from actual AI conversation and search behavior. Do not assume a child rating because this is an education app.
- [ ] Complete App Privacy using [the data inventory](app-privacy.md), including relevant third-party processing. Do not select “Data Not Collected” merely because Mural’s operator has no conversation database.
- [ ] Make only accessibility claims verified on the release build. Check VoiceOver labels, Dynamic Type, Reduce Motion, contrast, touch targets and keyboard behavior.
- [ ] Confirm every supported region, availability date and price. Remove unavailable CTA links and draft placeholders from the app and website.
- [ ] Submit the build with full review access and explanatory notes. Keep required services available through review. [App Review preparation](https://developer.apple.com/app-store/review/guidelines/)

## Gates for accounts and credits

An account-deletion route exists in the disabled account foundation. Before activation, verify it against the real server and complete the unresolved-balance/payment workflow. Deactivation alone does not meet Apple’s requirement. Automatically created guest accounts count too; explain any legally required billing retention. Verify Sign in with Apple token revocation during deletion on a real device. [Apple account-deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app/)

A paid service needs server-verified entitlements, purchase reconciliation, refund handling and revised privacy disclosures. The proposed Stripe flow and Google sign-in must be checked against the selected storefront rules before submission; the present checklist does not approve them.
