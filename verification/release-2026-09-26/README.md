# September 26 release review

Release branch: `codex/release-september-26`, based on `6a51ae7`. Targets: iOS 1.0 (4) for internal TestFlight and Android 0.1, version code 9, for direct APK distribution. The pending Apple App Store submission remains 1.0 (3); this release does not replace either store submission.

## Product and code review

The review favored a quiet Talk screen, clear Settings hierarchy, native navigation, and consistent account behavior. The cream/brown palette and orange microphone remain. iOS uses native Form, sheets and menus; Android uses Material sheets and dialogs.

| Finding | Change included |
| --- | --- |
| Settings mixed learning preferences, account actions and provider configuration. | Both apps now order Account, Your learning, Conversation, Your data, Advanced, and Help & privacy. Account holds identity and account actions; access selection and API key controls live in Advanced. |
| Account and Talk could imply that personal API usage had a Mural allowance. | Removed persistent Talk meters and redundant balance refreshes. Account shows verified Mural minutes only for a member using Mural access. Local activity is labeled as device-recorded activity. |
| The redesigned views reintroduced provider names in primary labels. | Aligned “My API key,” “API key,” “Manage API keys,” “Usage and billing,” and “Session limit.” OpenAI remains named where needed to explain billing and data handling. |
| Android's orb was cropped at its outer edge. Long Settings rows split into narrow columns. | Inset and scaled the orb geometry; setting labels and values stack when their measured width exceeds the available space. |
| iOS Meaning requested updates too often and could overwrite a newer cached meaning with an older result. | Added sentence/quiet-time pacing and request spacing, retained admitted requests, rejected stale results, and prevented duplicate requests from Retry. Helper failures now use Meaning-specific guidance. |
| Personal-key recovery could present a new sheet while the old sheet was dismissing. | The Mural access confirmation opens after key details dismiss. Switching requires a fresh eligible balance and explicit confirmation. |
| A failed balance refresh could retain a previous number. | Clear the displayed balance before refreshing. Distinguish credit, spending, usage, authentication and rate failures. |
| Old Account parameters, home balance state, usage estimates and string resources were unused. | Removed those paths and 34 unused Android resource names in both locales. Updated notices/history tests to follow their new Settings destinations. |

The integrated personal-key path uses `gpt-6-luna`, as requested in the prior model-label task. Hosted helpers retain `gpt-5.6-luna` and existing cost accounting. This release introduces no payment product or new commerce activation.

## Verification

| Check | Result |
| --- | --- |
| Swift core | 119 passed, including admitted-request and stale-cache Meaning cases |
| Android unit tests | 354 passed |
| Python script tests | 54 passed |
| Shared content and cross-platform checks | Passed |
| iOS full simulator UI suite | 32 passed |
| iOS recovery follow-up | Key details and existing source confirmation passed; the new recovery-sheet test passed after waiting for the destination and reading its combined accessibility label |
| Android full isolated UI suite | 73 of 75 passed initially; both failures were old Settings navigation paths. Both passed after updating the test navigation. |
| Android enlarged text | Settings, Advanced and keyboard reachability passed at font scale 1.6; original scale restored |
| Android lint and release build | Passed; direct APK and unsigned AAB built |
| Android signing/layout | APK signature matches the installed direct build and previous public APK. v2/v3 verification and 16 KB alignment passed. Bundle/native asset validation passed. |
| iOS archive | Signed Release archive for bundle `chat.mural.ios`, build 4; signature verification passed |

UI evidence: [iOS Talk](ios-talk.png), [iOS guest Account](ios-account.png), [Android Talk](android-talk.png), [Android Settings](android-settings.png), [Android Advanced](android-advanced.png), [large Settings](android-large-settings.png), [large Advanced](android-large-advanced.png), [large keyboard](android-large-keyboard.png).

Local test logs and result bundles use the `/private/tmp/mural-release-*` prefix. The [earlier account review](../account-experience/README.md) records physical checks on preceding revisions. This release's automated fixtures establish navigation and state behavior; they do not establish a fresh live voice, billing, cross-device balance, or screen-reader pass on these exact binaries. Those remain follow-up beta checks. No live purchase or destructive account test was performed for this release.

## Server compatibility

Production was inspected through its existing deployment wrapper. The source checkout is clean at `92f668424146d21039e2863c56fb6f78c6469545`. Its `services/api` tree is `9266b611b9425d82f415ef2c4133038be70802a1`, identical to the release source. The running API image is `sha256:18606a281647222843ce9951b30270fa9f9a5d33fd28645f4a67dca8a4373d75`; it has no source-revision image label, so the checkout/tree and image identity are recorded separately.

The API and database are healthy. Public health/readiness report hosted voice, guest minutes, database and live payments ready. The running helper-request limit is 48, matching the earlier build-3 review fix. The native updates use existing account, minutes, auth and helper contracts. No server code, migration or new configuration is required, so no deployment or restart was performed.

Direct Android configuration preserves the prior public APK's production API, Google client, and live Stripe purchase channel. The APK is signed with the existing direct-distribution identity to allow an in-place update. The AAB is validation output only and is not sent to Google Play.

Distribution results are recorded separately after upload; this report establishes the reviewed source and local build checks.
