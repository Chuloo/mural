# App Store and TestFlight metadata for Mural 1.1 (25)

Prepared on 3 October 2026 for the payment-enabled candidate. The signed archive and exported IPA use `chat.mural.ios`, `https://api.mural.chat`, Apple purchases enabled and automatic Apple environment selection. This document contains proposed copy; it does not establish App Store approval or publication. Store metadata is English, separate from the learning and meaning languages.

## Listing fields

The name is confirmed from App Store Connect on 3 October. The category retains the existing repository draft. The current saved subtitle is unverified; this document does not propose changing it.

| Field | Value |
| --- | --- |
| Name | Mural: Speak Languages |
| Subtitle | Existing saved App Store Connect value; the earlier “Learn through conversation” draft is unverified |
| Primary category | Education |
| Secondary category | None proposed |
| Copyright | 2026 Hackmamba |
| Marketing URL | `https://mural.chat/` |
| Support URL | `https://mural.chat/support/` — hi@hackmamba.io |
| Privacy Policy URL | `https://mural.chat/privacy/` |
| Repository | https://github.com/Chuloo/mural |
| Operator | Hackmamba Inc., incorporated in the United States |
| Seller | Existing enrolled Apple Developer seller; the September 25 distribution record identifies Hackmamba Inc. |
| App Review contact | William Imoh · hi@hackmamba.io · telephone supplied privately in App Store Connect |
| Age rating | Existing saved questionnaire; `[REQUIRED: confirm it covers this build's AI conversation and search behavior]` |
| SKU | Existing App Store Connect identifier; `[REQUIRED: retain the saved value]` |
| Version and build | 1.1 (25) |
| Minimum iOS version | 26.1, unchanged from the current project |

### Promotional text

> Start with a hello. Practise a conversation, check the meaning when you need it, and revisit words as they become familiar.

### Keywords

```text
language,speaking,conversation,norwegian,spanish,french,english,vocabulary,practice,immersion
```

This retained draft uses 93 ASCII bytes within Apple's 100-byte limit. [Apple field reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)

## Description

Mural helps you practise a language by talking. Start with a hello or choose an everyday setting, then follow a conversation that responds to how you are getting on.

Mural speaks in the language you are learning. Turn on meaning subtitles when you need help, ask for a simpler explanation, or use a typed reply. Corrections happen within the conversation so you can try again while the context is still fresh.

Choose from 24 themes, from ordering coffee to discussing a film. Conversations can continue while the app is in the background or the screen is locked. Mural pauses when another app interrupts its audio.

Your vocabulary grows from words you use in conversation. Three recall bars reflect repeated practice over time. Each language keeps its own conversations and progress; the bars and ability observations are guidance, not a language qualification.

Practise Norwegian Bokmål, Spanish from Spain, international English, French from France, German, Italian, Brazilian Portuguese, Mandarin, Serbian, Greek or Tagalog (Filipino). Mandarin uses Simplified Chinese with optional pinyin; Serbian uses Latin script. Your conversations and learning records stay on your device. You can export a backup and import it on another installation. Account sign-in does not sync learning history.

Eligible iPhones can try Mural-hosted conversation with up to 10 free minutes, without signing in. Sign in with Apple or Google to add a one-time minute pack. Packs are currently offered in the United States and Norway. Purchased value does not expire; the minutes shown are estimates that vary with actual AI use. Each conversation has a 15-second minimum charge.

The optional personal OpenAI key setting remains available under Advanced. Use with your own key is billed by OpenAI to your project. Current-topic search outside a conversation uses that key and includes source links.

An internet connection is required. Audio and selected text are sent to OpenAI while you practise; provider retention policies apply. Mural does not save raw audio. Its source is available under the MIT License at github.com/Chuloo/mural.

## One-time Apple packs

| Pack | Product ID | United States | Norway |
| --- | --- | --- | --- |
| Small | `chat.mural.ios.minutes.small.v1` | US$7 | NOK 89 |
| Medium | `chat.mural.ios.minutes.medium.v1` | US$13 | NOK 179 |
| Large | `chat.mural.ios.minutes.large.v1` | US$20 | NOK 249 |

The checkout displays Apple's local price and the selected quantity, from 1 to 10. The pack grants prepaid AI value; displayed conversation time is approximate. These are consumable one-time products. The first consumables and the app version belong in the same App Review submission. [Apple submission requirements](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-in-app-purchase/)

## Screenshot set

The existing English (U.S.) 6.9-inch set contains eight opaque 1320 × 2868 images in [screenshots/en-US-2026-09-25](screenshots/en-US-2026-09-25/README.md). They show the conversation, Mandarin pinyin, Italian, a meaning sheet, themes, saved words, Settings and the opening screen. They use sample learning data and a simulated guest balance. They do not show a completed payment or establish payment verification.

Talk retains its minimal orb layout. Continue/New conversation choices and explanations about minutes appear together in the native continuation sheet. The Add minutes sheet uses Mural's existing orange primary action. Apple's payment confirmation is a system sheet.

## TestFlight beta description

Try Mural, a voice conversation app for language practice. This build includes Norwegian, Spanish, English, French, German, Italian, Brazilian Portuguese, Mandarin, Serbian, Greek and Tagalog (Filipino), with meaning subtitles, everyday themes, vocabulary recall bars and local learning backups. Conversations continue in the background or with the screen locked, subject to audio interruptions.

Eligible devices can try hosted conversation without an account. Sign in with Apple or Google to test adding a one-time minute pack. Apple purchases in TestFlight are test purchases and will not charge you. Test minutes are separate from your paid balance in the App Store version. The app shows this distinction in Account and Add minutes. Your own OpenAI key is optional; using it bills your OpenAI project. An internet connection is required.

## What to test

Try a short conversation in your selected language and theme. Check meanings, typed replies, mute, audio interruptions and returning after the screen locks. Check learning export/import and whether existing conversations and words survive the update. At a minutes boundary, check the native Continue/New conversation sheet; dismissing it should preserve the conversation.

For purchases, open Settings → Account → Add minutes. Check local pricing, quantities, cancellation, successful delivery and the refreshed test balance. Check purchases should recover an interrupted delivery without adding the same credit twice. After a short hosted conversation, check that the balance updates when usage finishes settling. Test minutes should remain separate from the real paid balance.

Report the learning language, storefront, iOS version, audio route and any displayed error reference. Keep private conversation content, passwords and API keys out of feedback screenshots. TestFlight purchases remain sandbox purchases even when the app connects to the public Mural API. [Apple TestFlight purchase guidance](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testing-subscriptions-and-in-app-purchases-in-testflight/)

## App Review notes

Mural is a language-practice app. The orb is an AI conversation partner, instructed to speak in the selected learning language. Meaning subtitles use the separately selected meaning language. There is no communication with other app users or public conversation feed.

The app provides hosted conversation through Mural's server at `https://api.mural.chat`. Eligible installations receive up to 10 free minutes without an account. Apple and Google sign-in are optional for the free trial and required to buy one-time minute packs. Review does not require a personal OpenAI key or a real paid purchase.

The three consumable packs in this submission are offered in the United States and Norway. US prices are $7, $13 and $20; Norway prices are NOK 89, NOK 179 and NOK 249. Quantity is selectable from 1 to 10. Packs grant prepaid value for hosted AI usage, with approximate conversation time displayed. Unused purchased value does not expire. Each conversation has a 15-second minimum charge. The app displays Apple's localized checkout price before purchase.

Apple's sandbox transactions during review grant separate test value on the public API. They do not add real paid value. Settings → Account labels the test balance, and Add minutes states that a test purchase will not charge the reviewer. Purchases are verified with Apple's server API, tied to the signed-in Mural account and delivered once. Check purchases recovers unfinished delivery; Apple refunds adjust the matching value.

`[REQUIRED: confirm the review device can claim the trial, or supply a working review account privately in App Store Connect. Do not commit its credentials.]`

Review flow:

1. Complete the language and AI consent choices, choosing Spanish from Spain with English meanings.
2. On Talk, tap the microphone and allow microphone access. Reply aloud or use the typed reply action. A personal OpenAI key is not needed for hosted conversation.
3. Toggle Meaning to show or hide subtitles. Briefly lock the screen or leave the app, then return and end the conversation. After an ordinary ending, the screen resets after 15 seconds.
4. Open Themes to select a setting and Words to inspect vocabulary and saved conversations. Settings contains local JSON export/import and learning deletion.
5. Open Settings → Account and sign in with Apple or Google using the review access supplied privately. Open Add minutes, select a pack and quantity, and tap the orange Continue action. Complete the Apple sandbox purchase and check the refreshed test minutes. Check purchases is available for recovery.
6. At a minutes boundary, the native continuation sheet presents the funding explanation with Continue conversation or Add minutes, plus New conversation. Closing that sheet preserves the conversation; the microphone reopens the pending choice.

Settings → Account includes account deletion. Unused test value can be forfeited after in-flight usage settles. Unresolved real paid billing requires resolution; required financial records may be retained against an opaque account ID. Local learning deletion is a separate Settings action.

Conversation and vocabulary records stay in local SwiftData storage. Selected context goes to OpenAI for teaching. OpenAI provides live voice, translation and optional current-topic search. Provider application storage is disabled where supported; abuse-monitoring retention can still apply. The optional personal-key route is under Settings → Advanced and bills that OpenAI project directly.

The App Privacy inventory includes Purchase History for App Functionality, linked to the user and with no tracking, alongside the existing data types. See [the privacy inventory](app-privacy.md). Normal functionality does not depend on a hidden preview or debug mode.
