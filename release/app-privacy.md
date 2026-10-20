# Mural iOS App Privacy inventory

Reviewed for build 1.0 (3) on 25 September 2026. The App Store Connect privacy label was published for the hosted guest trial, optional Google and Apple accounts, and personal OpenAI keys. It declares no tracking. The nine collected data types are **Email Address, Audio Data, Other User Content, Search History, User ID, Device ID, Product Interaction, Other Usage Data, and Other Diagnostic Data**. Each is declared for App Functionality and linked to the user. This conservative label includes the app's service providers and account-linked use, not only data retained in Mural's database. Apple's [privacy guidance](https://developer.apple.com/app-store/app-privacy-details/) requires third-party processing to be included.

## Payment implementation — 28 September 2026

The payment build adds **Purchase History**, linked to the user for App Functionality, with no tracking. This includes account-bound orders, product and quantity, storefront, currency, verified Apple transaction references, fulfillment, refunds and recovery records. Sensitive receipt references are encrypted at rest. The app sends an opaque order identifier to Apple as its purchase account token; it does not send the Mural email address for that purpose. Apple processes payment credentials; Mural does not receive full card details. Production Apple purchases remain gated pending the release checks.

The manifest now includes the nine existing label types plus Purchase History. The App Store Connect label was updated and published on 28 September 2026 with all ten types. Purchase History is for App Functionality, linked to identity, and not used for tracking. The pending build 3 submission was unchanged.

## Data flows

| Data | Handling |
| --- | --- |
| Speech and selected learning text | Sent to OpenAI during live conversation, meanings, feedback and optional topic search. With hosted time, Mural's server helps start the session and passes the needed text; with a personal key, the app connects to OpenAI directly. Mural does not save raw audio or ordinary transcript text in its server database. OpenAI's abuse-monitoring retention can still apply. |
| Local learning history | Conversations, vocabulary, evidence, language preferences and interests are kept in the iPhone's local archive. Users can export a JSON backup. Account sign-in does not sync this archive. |
| Optional account | Mural stores a random account ID, identity provider and provider subject, verified email when supplied, account dates and hashed session credentials. Apple may supply a relay email. Credentials stay in the iPhone Keychain; provider passwords are never sent to Mural. |
| Guest installation and time | The app creates a random installation token. Mural stores its hash, a guest identifier, trial eligibility and balance, session duration, usage and settlement records. A guest-to-account link is recorded if unused time is transferred after sign-in. |
| Operations | Request/session IDs, timing, usage totals and bounded diagnostic events support limits, reliability and abuse prevention. Ordinary prompts, transcripts and raw audio are excluded from application logs. |
| Personal OpenAI key | Saved in the iPhone Keychain, excluded from learning exports and not sent to Mural's server. OpenAI receives it to authorize the user's direct API requests. |

No advertising SDK or tracking identifier is enabled. Build 3 had no in-app purchases; the new payment build uses StoreKit only when its purchase configuration is enabled. The public policy is at [mural.chat/privacy](https://mural.chat/privacy/), with [privacy choices](https://mural.chat/privacy/#privacy-choices). Account deletion is available in Settings → Account; deleting local learning history is a separate Settings action. Apple's account deletion requirements and Apple authorization revocation are implemented in the client and server. [OpenAI's API data controls](https://developers.openai.com/api/docs/guides/your-data) describe provider retention, including default abuse-monitoring records that can persist for up to 30 days.

## Archive checks

`App/PrivacyInfo.xcprivacy` is bundled in the app, and the pinned WebRTC framework has its own manifest. The first-party manifest declares UserDefaults with reason `CA92.1` for app-only preferences and purchase recovery, and SystemBootTime with reason `35F9.1` for elapsed event timing and timers. No system boot timestamp is transmitted. These declarations follow [Apple’s required-reason API list](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype). Inspect both manifests in each final archive and address any upload diagnostics. The app has microphone and camera purpose strings: the voice framework references camera APIs, although Mural does not capture images or video in this version.

Update this inventory and the published label before enabling purchases, new analytics, remote learning sync or any new data use. The website policy already describes conditional purchase records; that description does not mean iOS purchases are active.
