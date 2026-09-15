# Android release declarations

This record describes the version 4 free-trial/personal-key preview submitted to **Play production review on 14 September 2026**. The [submission evidence](evidence/play-submission-2026-09-14.json) records `in_review`; no later approval or publication is established here. That candidate offers funded guest conversations, Google sign-in and an optional personal OpenAI key, with paid checkout disabled. AI-output reporting was verified on the production API, including a synthetic report from the sealed version 4 preview APK. [Candidate evidence](signed-candidate-2026-09-14-v4.md) records the exact files, configuration and local tests. These declarations do not cover later direct Stripe configurations; see [candidate scopes](candidate-scopes.md).

## Data flows in the submitted v4 preview

| Data | Processing and storage | Source |
| --- | --- | --- |
| Microphone audio | Streams to OpenAI over WebRTC after AI consent and microphone permission; no raw audio file saved by Mural | `apps/android/app/src/main/java/chat/mural/network/LiveTransport.kt` |
| Transcripts, selected text, local learning context | Stored in the app; relevant portions sent to OpenAI for speech, meanings, correction and assessment | `LearningRepository.kt`, `network/APIClient.kt`, `core/TeachingPolicy.kt` |
| Topic searches | Sent to OpenAI's search tool; topic summaries and source URLs saved on the device | `network/APIClient.kt`, `MuralViewModel.kt` |
| OpenAI key | Encrypted using an Android Keystore key; used only for provider authorization; excluded from learning exports | `network/CredentialStore.kt` |
| Learning archive | User-selected JSON export/import; can contain transcripts and vocabulary; never contains provider or Mural bearer credentials | `LearningRepository.kt`, `MainActivity.kt` |
| Google identity | The full provider ID token and nonce are sent to Mural for verification. The token can contain a name, which Mural processes transiently and does not persist. The database stores provider subject, account UUID and nullable verified email; names and avatars are not stored | `network/ManagedAccountClient.kt`, `services/api/src/auth.ts` |
| Mural session, when configured | Bearer stored encrypted on the device, bound to app and API origin. Server stores a token hash and expiry | `network/AccountSessionStore.kt`, `services/api/src/auth.ts` |
| Guest trial identity and balance | A separate encrypted installation credential accesses a server guest account. Mural retains trial eligibility, granted and remaining time, funding commitments and transfer records. A daily network HMAC limits claims; it does not reliably identify a physical phone after reinstall | `network/GuestInstallationStore.kt`, `services/api/src/guest-minutes.ts` |
| Hosted voice and helper requests, when enabled | Voice uses the hosted session lease. Selected teaching context passes transiently through Mural’s helper gateway to OpenAI; instructions, input, output and schemas are not stored by that gateway. Mural retains session ownership, reservation, usage and cost records needed for billing and reconciliation | `services/api/docs/hosted-helpers.md`, `network/HostedAPIClient.kt` |
| Signup admission records | Server retains bounded counters with a daily network HMAC. This is a pseudonymous abuse-control identifier, not anonymous data | `services/api/docs/accounts-reference.md` |
| AI-output report | After review and explicit consent, Mural receives a selected excerpt of at most 2,000 UTF-16 units with language, reason, consent version and receipt ID. It expires after 30 days. Audio, the rest of the conversation and account credentials are not included. Production HTTP/database verification and native version 4 submission pass | `services/api/docs/ai-reporting.md`, [production evidence](evidence/ai-reporting-live-2026-09-14.json) |
| Report admission records | Separate network HMAC counters persist for at most 48 hours from the bucket's start; they are not attached to report rows. Report and admission contents are excluded from daily and deployment backups | `services/api/src/feedback.ts`, [production evidence](evidence/ai-reporting-live-2026-09-14.json) |
| Automatic Android backup | Disabled for cloud backup and device transfer. Users must export/import their own learning backup | `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml` |

No ad or analytics SDK is configured in the reviewed Android dependencies. The system language classifier is platform supplied; behavior may vary across device vendors. The app does not request contacts, location, camera or broad storage access. The inspected version 4 release includes Play Billing 9.1.0 and `com.android.vending.BILLING`; purchases are disabled in its build configuration. Its merged manifest also includes Credential Manager dependencies' `USE_BIOMETRIC` and legacy `USE_FINGERPRINT` permissions, plus the app's signature-protected dynamic-receiver permission. A biometric permission does not mean Mural receives a fingerprint or biometric template.

## Submitted Data safety answers

The corrected form below belongs to the submitted v4 preview. The data types are marked **collected, not shared**, applying Google's service-provider exception to processing on Mural's behalf. This declaration does not mean that data stays on the device: relevant audio and text go to OpenAI, and account, trial and selected-report data reach Mural's backend. A provider's `store: false` option alone does not establish ephemeral processing. [Google Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

| Play category | Saved collection setting | Explanation |
| --- | --- | --- |
| Personal info → Name | Optional; processed ephemerally | A name can arrive inside the full Google ID token during optional sign-in. Mural does not store the name |
| Personal info → Email address | Optional | Collected during optional account signup |
| Personal info → User IDs | Required | Guest and member identities support hosted access, trial balances and account management |
| Device or other IDs | Required | Installation identifiers and pseudonymous network admission records support trial eligibility and abuse controls |
| App activity → App interactions | Required | Hosted session, usage and admission records support app operation and trial accounting; no analytics SDK is asserted |
| Audio files → Voice or sound recordings | Optional | The learner can use typed replies instead of microphone input |
| App activity → In-app search history | Optional | Collected when the learner uses current-topic search |
| App activity → Other user-generated content | Required; app functionality and personalization | Conversations, typed replies and teaching context are needed for the learning experience. Sending a selected AI-output report remains a separate optional action with explicit consent |

No financial/payment information is selected for this preview: checkout and paid access are disabled. Before enabling commerce, review and update the form for purchase history, provider receipts and reconciliation records. The saved collection choices do not authorize enabling paid features without those checks.

The saved form declares encryption in transit. OAuth exchange uses HTTPS; device account credentials are encrypted at rest. Voice uses WebRTC encryption. This is not a claim of end-to-end encryption that prevents the AI provider from reading content. Local learning deletion and server account deletion are separate actions. Account deletion removes signup identities and sessions; required financial, trial or security records may have limited retention. There is **no blanket promise that all data is deleted within 90 days**. The deletion resource is `https://mural.chat/support/#delete-account`; its process and published retention periods must match [the backend record inventory](../../services/api/docs/accounts-reference.md).

## Console fields at v4 submission

| Declaration | Prepared answer or remaining decision |
| --- | --- |
| App name / type / category | Mural: Language Practice / App / Education |
| Operator | Hackmamba Inc., United States |
| Contact | hi@hackmamba.io; https://mural.chat/ |
| Privacy URL | https://mural.chat/privacy/; hosted-trial, selected-report and adult-preview disclosures are published |
| Ads | No ads in the reviewed build |
| Target audience | Adults 18+ is owner approved and saved in Play. The onboarding consent includes this confirmation |
| Content rating | Corrected IARC questionnaire saved: generated online content and possible spoken references to violence, offensive language and drugs; no visual depictions, sexual material, peer sharing or digital purchases. Result includes ESRB Everyone 10+, PEGI Parental guidance and IARC 12+. The app’s separate target audience remains adults 18+ |
| App access | The submission record confirms Google reviewer sign-in and two hosted conversations started and settled, with no pending reservations. Reviewer credentials remain private |
| Account deletion | In-app automatic deletion and a manual support dialog are included in version 4. The verified external request page is https://mural.chat/support/#delete-account |
| AI-generated content | Production reporting is active. HTTP submission, replay, expiry, restricted reviewer access and backup exclusions pass. William Imoh owns manual review via hi@hackmamba.io. Submission from the sealed version 4 preview APK is verified; an external email link alone is not the in-app reporting path |
| Permissions | Microphone runtime permission for conversation; Internet, audio routing and network-state permissions. Review dependency-added permissions in the merged release manifest |
| Payments | Paid checkout is disabled in the submitted version 4 build. The v4 evidence does not verify purchases or the current API's sales configuration. Version 5 direct Stripe distribution has a separate scope; a paid Play release requires its own product/provider setup and purchase/refund/recovery evidence |
| App signing / package | `chat.mural.android`; upload key backed up as requested. The Play signing certificate is registered with an Android Google OAuth client; an actual Play-signed login remains unverified |
| Countries and regions | 162 Play markets selected from the OpenAI-supported coverage with owner approval |
| Release status | The 14 September submission record places version 4 in production review and records verified Google reviewer sign-in plus two settled hosted conversations. It does not establish public approval or a tested Play-signed installation |

The registered Android Google OAuth client is `1034240936303-774v7n4qad7s7un8sb1vjrhl3bi79sa3.apps.googleusercontent.com`, with Play signing SHA-1 `EA:A2:9C:7D:43:D4:59:3A:F6:B4:81:40:1A:3D:47:39:0A:96:40:5F`. The owner confirmed that the current and previous Play signing fingerprints are the same. These are public client identifiers, not credentials. Registration does not establish successful login from a Play-delivered installation.

Google requires an in-app account deletion path and an external web resource for apps offering accounts, including optional signup. The web resource must work for people who have already uninstalled the app. [Account deletion rules](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)

Generative AI apps must let users report offensive output inside the app, and the developer must use reports to improve filtering and moderation. [AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)
