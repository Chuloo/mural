# Android release declarations

Source inventory reviewed on 13 September 2026. This is a preparation record, not a submitted Data safety form. The current debug preview offers funded guest conversations, Google sign-in and an optional personal OpenAI key. Paid checkout and AI-output reporting remain disabled. Availability depends on build configuration and server capabilities. The [preview readiness record](preview-readiness-2026-09-13.md) identifies the inspected APK and the limits of its live test.

## Current data flows

| Data | Processing and storage | Source |
| --- | --- | --- |
| Microphone audio | Streams to OpenAI over WebRTC after AI consent and microphone permission; no raw audio file saved by Mural | `apps/android/app/src/main/java/chat/mural/network/LiveTransport.kt` |
| Transcripts, selected text, local learning context | Stored in the app; relevant portions sent to OpenAI for speech, meanings, correction and assessment | `LearningRepository.kt`, `network/APIClient.kt`, `core/TeachingPolicy.kt` |
| Topic searches | Sent to OpenAI's search tool; topic summaries and source URLs saved on the device | `network/APIClient.kt`, `MuralViewModel.kt` |
| OpenAI key | Encrypted using an Android Keystore key; used only for provider authorization; excluded from learning exports | `network/CredentialStore.kt` |
| Learning archive | User-selected JSON export/import; can contain transcripts and vocabulary; never contains provider or Mural bearer credentials | `LearningRepository.kt`, `MainActivity.kt` |
| Google identity, when configured | Provider ID token and nonce sent to Mural for verification. Database stores provider subject, account UUID and nullable verified email; names and avatars are not stored | `network/ManagedAccountClient.kt`, `services/api/src/auth.ts` |
| Mural session, when configured | Bearer stored encrypted on the device, bound to app and API origin. Server stores a token hash and expiry | `network/AccountSessionStore.kt`, `services/api/src/auth.ts` |
| Guest trial identity and balance | A separate encrypted installation credential accesses a server guest account. Mural retains trial eligibility, granted and remaining time, funding commitments and transfer records. A daily network HMAC limits claims; it does not reliably identify a physical phone after reinstall | `network/GuestInstallationStore.kt`, `services/api/src/guest-minutes.ts` |
| Hosted voice and helper requests, when enabled | Voice uses the hosted session lease. Selected teaching context passes transiently through Mural’s helper gateway to OpenAI; instructions, input, output and schemas are not stored by that gateway. Mural retains session ownership, reservation, usage and cost records needed for billing and reconciliation | `services/api/docs/hosted-helpers.md`, `network/HostedAPIClient.kt` |
| Signup admission records | Server retains bounded counters with a daily network HMAC. This is a pseudonymous abuse-control identifier, not anonymous data | `services/api/docs/accounts-reference.md` |
| AI-output report, when configured | User reviews and consents to a selected excerpt of at most 2,000 UTF-16 units. Mural stores it with language, reason, consent version and receipt ID; it expires after 30 days. Audio, the rest of the conversation and account credentials are not included | `services/api/docs/ai-reporting.md`, `services/api/src/feedback.ts` |
| Report admission records, when configured | Separate network HMAC counters persist for at most 48 hours from the bucket's start; they are not attached to report rows | `services/api/src/feedback.ts` |
| Automatic Android backup | Disabled for cloud backup and device transfer. Users must export/import their own learning backup | `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` |

No ad or analytics SDK is configured in the reviewed Android dependencies. The system language classifier is platform supplied; behavior may vary across device vendors. The app does not request contacts, location, camera or broad storage access. The inspected unsigned release includes Play Billing 9.1.0 and `com.android.vending.BILLING`; purchases are disabled in its build configuration. Its merged manifest also includes Credential Manager dependencies' `USE_BIOMETRIC` and legacy `USE_FINGERPRINT` permissions, plus the app's signature-protected dynamic-receiver permission. A biometric permission does not mean Mural receives a fingerprint or biometric template. Recheck the merged release manifest and all packaged SDKs before filing.

## Draft Data safety inventory

Sending data off-device counts as collection under Google's definition, including provider and SDK traffic. A `store: false` provider option alone does not establish ephemeral processing. The final answer must reflect applicable OpenAI retention and the configured project, plus Mural's own security records. Service-provider and user-initiated-transfer exceptions affect the **sharing** answer separately. [Google Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

| Play category | Current treatment to review | Purpose |
| --- | --- | --- |
| Audio files → Voice or sound recordings | Collected during voice use; assess sharing against actual provider terms and consent; do not declare ephemeral without evidence | App functionality |
| App activity → Other user-generated content | Collected for transcripts, typed replies and teaching context sent to the provider | App functionality |
| App activity → In-app search history | Collected when current-topic search is used | App functionality |
| Personal info → Email address; User IDs | Collected if Google signup is enabled; optional account use | Account management; security and fraud prevention where applicable |
| Device or other IDs | Review the network HMAC, provider identifiers and any future Integrity verdict before final selection | Security and fraud prevention |
| Financial info → Purchase history | Conditional on enabled commerce: minute orders, store tokens, refunds and payment records need disclosure. Play Billing code is packaged; a disabled server capability is not evidence about all SDK behavior | App functionality; accounting and fraud prevention |
| App activity / Other app performance data | Review exact hosted metering, failure diagnostics and security fields before activation; no blanket analytics claim | App functionality; fraud prevention |
| App activity → Other user-generated content, for AI reports | Optional selected text is collected by Mural only after review and consent, when reporting is configured. Review the deployed expiry, backup and support handling before enabling the feature | App functionality; support and safety |

Transport uses HTTPS/WebRTC encryption. This is not a claim of end-to-end encryption that prevents the AI provider from reading content. Local learning deletion and server account deletion are separate actions. Account deletion removes signup identities/sessions; settled financial or trial records may require limited retention. Published retention periods and any unresolved-balance closeout procedure must match the implementation. See [the backend record inventory](../../services/api/docs/accounts-reference.md).

## Console fields and status

| Declaration | Prepared answer or remaining decision |
| --- | --- |
| App name / type / category | Mural: Language Practice / App / Education |
| Operator | Hackmamba Inc., United States |
| Contact | hi@hackmamba.io; https://mural.chat/ |
| Privacy URL | https://mural.chat/privacy/; final Android and hosted disclosures require review |
| Ads | No ads in the reviewed build; verify merged dependencies before submission |
| Target audience | Owner approved adults 18+ for the first release. The onboarding consent includes this confirmation. The Play questionnaire is pending its prerequisite reviewer-access section |
| Content rating | Complete the current IARC questionnaire for the actual conversational AI and current-topic behavior; no rating has been assigned here |
| App access | Working reviewer access must cover speech and any account/purchase restrictions without requiring reviewers to fund an OpenAI account. Keep credentials and instructions private |
| Account deletion | In-app account deletion exists. A functional web request path must be verified and entered if accounts are offered. A prominent support-email flow may qualify; the page must explain Mural's deletion process |
| AI-generated content | In-app reporting is required. An external email link alone does not meet the instruction to report without exiting the app. Reporting UI and storage/admission tests exist; deployed submission, reviewer ownership, privacy/backup handling and retention still require verification |
| Permissions | Microphone runtime permission for conversation; Internet, audio routing and network-state permissions. Review dependency-added permissions in the merged release manifest |
| Payments | Play Billing and minute purchase client code are packaged; store product setup and Play-signed purchase/recovery/refund tests remain release gates. Stripe web payments need separately verified eligibility and routing |
| App signing / package | `chat.mural.android` is owner approved. Confirm Play App Signing enrollment, upload-key custody and Play signing certificate OAuth clients before release |
| Countries and regions | Owner decision pending; do not enable every market by default |

Google requires an in-app account deletion path and an external web resource for apps offering accounts, including optional signup. The web resource must work for people who have already uninstalled the app. [Account deletion rules](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)

Generative AI apps must let users report offensive output inside the app, and the developer must use reports to improve filtering and moderation. [AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)
