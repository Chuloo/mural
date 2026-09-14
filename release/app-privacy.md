# App Privacy inventory

Source reviewed on 12 September 2026. This describes the direct-to-OpenAI BYOK build. It is a submission draft, not a claim that App Store Connect answers have been entered. Accounts, credits and hosted access require a revised inventory before release.

## Actual data flow

| Data | Where it lives or goes | Source |
| --- | --- | --- |
| Conversations, vocabulary, evidence, language preferences and interests | Local SwiftData archive; selected text and learning context go to OpenAI for conversation and teaching | `App/Storage.swift`, `App/ConversationCoordinator.swift`, `apps/ios/Core/TeachingPolicy.swift` |
| Microphone audio | Streamed to OpenAI over WebRTC during a live conversation; no raw-audio file written by Mural | `App/LiveTransport.swift` |
| Meaning subtitles and lookup text | Selected text sent to OpenAI; translations cached in the local conversation | `App/APIClient.swift`, `App/ConversationCoordinator.swift` |
| Topic search requests | Sent to OpenAI’s web-search tool; topic summaries and source URLs saved locally | `App/APIClient.swift`, `App/ConversationCoordinator.swift` |
| API key | Device-only Keychain item; used as authorization only for OpenAI requests; absent from learning exports | `App/Storage.swift`, `App/APIClient.swift` |
| Learning backup | A user-selected JSON export can leave the sandbox through Files or the share destination; no automatic Mural upload | `App/LibraryViews.swift` |
| Legacy migration backup | Protected file in Application Support on upgraded installations; removed by Delete all learning | `App/Storage.swift` |
| Debug verification | Content-free local diagnostics, only in an explicitly invoked Debug verification run | `App/AudioVerification.swift` |

The current iPhone build does not connect to a Mural account database. It has no ad SDK, analytics SDK, tracking identifier collection, CloudKit sync or saved raw audio. Account and server foundations exist in source but remain disabled. Ordinary iOS device backups are controlled by the user and Apple; local storage does not mean that a user-created backup can never leave the phone.

## Provider retention

`store: false` is sent for Live sessions and Responses. OpenAI’s published default includes up to 30 days of abuse-monitoring retention for those endpoints. It is separate from stored application state and is not switched off by that flag. BYOK project policies may differ. [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)

## Draft App Privacy answers

Apple’s definition includes relevant third-party retention, not only the app operator’s servers. Free-form speech/text are covered by Audio Data and Other User Content; arbitrary personal details volunteered within that content do not require separately guessing every possible category. Data used only on-device is treated separately. [Apple App Privacy guidance](https://developer.apple.com/app-store/app-privacy-details/)

| Category | Draft treatment | Basis and remaining check |
| --- | --- | --- |
| Audio Data | Collected; App Functionality; linked to user; no tracking | Sent to a provider using the user’s OpenAI account; default retention applies |
| Other User Content | Collected; App Functionality; linked to user; no tracking | Transcripts, interests and learning context sent through the same account |
| Search History | Collected; App Functionality; linked to user; no tracking | Current-topic searches are sent to the provider |
| User ID / Other Usage Data | Verify provider handling before final answers | The API credential identifies a provider project; provider billing records exist, but Mural sends no separate Mural user ID |
| Device identifiers, contacts, location, advertising, purchases | No collection by the current app | Recheck when adding authentication, payment or abuse-prevention services |
| Customer Support | Reassess with the chosen support channel | No in-app support form currently sends content to Mural’s operator |

The first-party manifest conservatively declares audio, text and search as linked because requests use the learner’s provider account. It declares no tracking. Final App Store answers must include any additional provider or future backend processing confirmed during release review. Do not use a blanket “we don’t save anything” promise for the whole service.

## Privacy manifests

`App/PrivacyInfo.xcprivacy` is bundled by the project generator. The source audit found no direct first-party use of Apple’s listed required-reason APIs: there is no UserDefaults, boot-time API, file-timestamp access, disk-space query or active-keyboard enumeration. Ordinary `Date` values and file existence checks do not justify inventing a reason code. The first-party accessed-API array is empty; check the final archive’s report and validation diagnostics. [Required-reason API declarations](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)

The pinned WebRTC artifact includes its own manifest. It declares system-boot-time reasons `35F9.1` and `8FFB.1`, file-timestamp reason `C617.1`, no tracking and no SDK-collected data. Verify the embedded artifact after archiving; do not replace its manifest with the app’s. WebRTC was not named on Apple’s published commonly used SDK list when checked, but privacy rules and upload validation still apply. [Apple SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/)

## Before accounts or credits ship

Update this inventory for name/email/provider subject, entitlements, credit balance and transaction records, metering, fraud controls and operational logs. Keeping conversations on-device remains possible; a credit service cannot safely promise to retain only signup data. Document required retention and account deletion separately from deleting local learning records.
