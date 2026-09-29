# Android release declarations

This record describes the version 4 free-trial/personal-key preview submitted to **Play production review on 14 September 2026**. The [submission evidence](evidence/play-submission-2026-09-14.json) records `in_review`; no later approval or publication is established in that historical record. That candidate offers funded guest conversations, Google sign-in and an optional personal OpenAI key, with paid checkout disabled. AI-output reporting was verified on the production API, including a synthetic report from the sealed version 4 preview APK. [Candidate evidence](signed-candidate-2026-09-14-v4.md) records the exact files, configuration and local tests. The prepared v13 payment answers appear below; they do not claim a Console change or a live purchase. These declarations do not cover later direct Stripe configurations; see [candidate scopes](candidate-scopes.md).

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

## Payment implementation review — 28 September 2026

The direct Android payment build extends Stripe checkout to quantities 1–10 and shows approximate whole Mural minutes. It retains the existing account, trial, voice, helper, reporting and local-history data flows. Orders additionally retain quantity and the immutable combined quote; recovery remains bound to the original Mural account and environment. Payment credentials stay with Stripe/Link. Mural retains verified transaction references and refund/reconciliation records, with sensitive receipt references encrypted at rest. Connecting Google to an existing Apple identity adds a verified provider link; matching email addresses never merge accounts.

At this review, a future commerce-enabled Play release needed **Financial info → Purchase history** assessed as optional collection for app functionality, account management and fraud prevention. Card/account-number collection did not follow merely from a payment provider processing checkout; Mural did not receive those details. The published Play build was still the free-trial/personal-key preview. The prepared v13 answers below supersede this planning note without asserting that a Console form was changed or purchases enabled.

## Prepared v13 paid Play answers — 29 September 2026

These are answers to enter and verify for a paid v13 Play release. They have **not** been saved in Play Console here. Version 12 is a signed, isolated internal license-test build; version 13 is a separately signed production candidate. A signed bundle and a prepared form do not establish that a Play-installed purchase works or that products are active.

| Play payment flow | Data Mural handles |
| --- | --- |
| Checkout and binding | An account holder chooses a one-time product. The app sends Google Play keyed, obfuscated hashes of the Mural account and order IDs; the plain account ID is not sent to Play in this binding. Play returns a purchase token to the app, which sends it to Mural's API over HTTPS. The hash remains pseudonymous because Mural can match it to its order. |
| Verification and delivery | Mural checks the token and authoritative order with the Google Play Developer API, including product, currency, total, purchase state and refunds, before granting or reversing AI value. The private Play notification subscriber can later deliver a token for recovery. Mural consumes a verified one-time product through Play. |
| Stored records | The API retains the account-linked order and immutable quote; product, currency, total, quantity and fee/value allocation; obfuscated binding hashes; encrypted purchase-token reference and its hash; verified transaction and refund state; and normalized reconciliation events. Sensitive token references are encrypted at rest. The financial journal has no automatic deletion date; completed account deletion removes email, sign-in identities and sessions but may retain an opaque account reference and the order history for financial, fraud-prevention and dispute needs. Encrypted backups have their separate expiry. |
| Payment credentials | Google Play handles the user's payment method and checkout. Mural does not receive a full card number, security code or financial account number. |

| Console item for v13 | Prepared answer and basis |
| --- | --- |
| Data safety → Financial info → Purchase history | **Collected: Yes. Optional: Yes. Not ephemeral.** Purchases are optional; guest access and personal-key practice remain available without buying. **Purposes: App functionality, Account management, Fraud prevention, security and compliance.** The purchase and refund ledger supports delivery, recovery, account closeout and duplicate/fraud checks. |
| Data safety → Financial info → User payment info | **No Mural collection** of card or financial-account details: Google Play collects them directly under its terms. Do not select this solely because Play processes checkout. Recheck if an SDK or future integration changes what the app accesses. |
| Data safety → Personal info → User IDs | Keep the existing collection answer. The Play binding sends keyed account/order hashes to Google, so do not treat those hashes as anonymous. For the sharing question, review the user-initiated checkout exception against the exact in-app disclosure; do not assume every Google transfer is a service-provider transfer. |
| Data safety → Sharing of purchase history | **No separate sharing of Mural's retained purchase journal is identified.** Purchase verification and consumption go to the payment provider as part of the user-initiated Play transaction. Confirm this classification in the final Console form, especially if Google changes its sharing guidance or the integration changes. |
| Content rating → Digital purchases | **Yes.** One-time AI-value packs are digital goods bought inside the app. Resubmit the IARC questionnaire; do not copy the v4 rating outcome forward without its new certificate. Other AI-content answers remain subject to review against the release build. |
| Products and availability | Three Play one-time products are drafted for the US and Norway at US $7/$13/$20 and NOK 89/179/249. Confirm exact live localized prices, tax treatment and enabled markets before publishing. The listing says purchases are available only **where available**; broad app availability does not imply paid packs in every Play market. |

The existing policy at [mural.chat/privacy](https://mural.chat/privacy/) already explains purchase records, encrypted receipt references, no card details, financial retention and backup expiry, but its currently published copy describes the Play preview as purchases-disabled and names Stripe/Link and Apple without a Play-specific payment paragraph. Prepare and publish the Google Play addition before enabling paid Play checkout. The terms need the matching Play refund/support route and a correction that Play buys one pack at a time. Do not claim the Console form, IARC answer, product catalog or public policies have changed until each is verified in place. [Google Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en-AE) distinguishes purchase history from payment details collected directly by a provider and requires one form to cover all distributed versions. [Google's digital-purchases guidance](https://support.google.com/googleplay/android-developer/answer/6161124?hl=en) covers in-app digital goods.
