# Mural on Android

## Decision and scope

Mural keeps two native clients: SwiftUI and SwiftData in `apps/ios/`, and Kotlin with Jetpack Compose in `apps/android/`. The API lives in `services/api/`. Shared fixtures and contracts keep learning data compatible while each app retains its platform audio, accessibility and animation tools.

The Android client keeps the eight language modules, the 24 themes and their cultural variants, WebRTC voice, written replies, meanings, word lookup, current topics with sources, history, corrections, vocabulary and learning projection. It adds written conversation without the microphone permission. Android account and minute-purchase integration is in progress. The public hosted conversation service remains disabled; no cloud learning sync is planned.

## Visual system

The iOS screens in `release/screenshots/en-US/` are the visual reference. Android uses the same cream, orange, peach, lilac, sage and butter colors, with bundled [Nunito](https://github.com/google/fonts/tree/main/ofl/nunito) for rounded typography. The font and its OFL license ship with the app. The lowercase wordmark uses tight spacing and the warm gradient dot.

Talk, Themes and Words sit in a floating capsule, with an animated selected tab and a soft fade over content below. Settings opens from the top-right control as a sheet. Original vector icons replace text glyphs throughout navigation and primary controls. Themes use an adaptive two-column grid, switching to larger cards with larger text.

The orb has a smooth twelve-point outline, warm blended color, a feathered shadow and two small floating dots. Android 13 and later use an AGSL shader; older versions have a layered-gradient fallback. Orb and onboarding motion pause outside the foreground and respect disabled system animations. Interface tests disable continuous motion for repeatable captures.

Both onboarding steps use dropdown menus over a softly moving warm background. Continue stays at the bottom; content can scroll at larger text sizes. Reply entry opens in a rounded sheet with keyboard padding and a visible send action. Search, settings and other input fields share the same rounded surfaces and warm focus color.

Caption taps open a cream bottom sheet with the selected word, its original sentence and an automatic contextual explanation, following the iOS lookup flow. All eight languages use this interaction. The sentence stays fixed while the conversation advances; closing the sheet cancels its request. Lookup has its own loading state so another helper cannot leave the sheet empty or have its work cancelled by dismissal.

Mandarin keeps the inline Show/Hide pinyin control between the Chinese caption and the English meaning. The Chinese passage and its reading scroll together; the meaning retains its own visible area. Small screens and large system text use a scrolling page. Source characters and caption tap boundaries remain independent of pronunciation phrases.

Pinyin uses the MIT-licensed [phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data/tree/cee0ed6e6e4898580cafd2bd5e3723e20b214aa0) dictionary (version 0.19.0), bundled offline with its license in `app/src/main/resources/mandarin/`. Local overrides cover everyday neutral-tone and erhua forms. The dictionary supplies written pronunciation aids, not acoustic tone assessment or guaranteed disambiguation of every sentence. Common ambiguous characters without a resolving entry retain their Han text. Apple’s tokenizer and Android’s ICU/dictionary can differ in segmentation, spacing, regional readings and coverage. Android 8–9 retain caption links without pinyin.

## Components

- `core/`: serializable models, the v1/v2 archive, language modules, evidence rules, teaching policy, `MeaningController`, `FinalAssessmentQueue`, session limits and usage summary. No Android dependencies, so it runs in JVM tests.
- `network/`: direct HTTPS client for OpenAI, AES-GCM credentials protected by Android Keystore, native WebRTC transport and the platform language detector.
- `LearningRepository`: private `learning.json` written with `AtomicFile`. It never contains the API key.
- `MuralViewModel`: conversation state, duration and inactivity limits, cancellation, assessments, meanings and persistence. Isolated copies prevent a late response from changing another conversation.
- `ui/` and `MainActivity`: Compose screens, explicit consent, system permissions, export and import through the Android document picker, and accessibility. Copy lives in `res/values` (English) and `res/values-es` (Spanish).

## Platform mapping

| iPhone | Android |
| --- | --- |
| SwiftUI / Observation | Compose / view model state |
| SwiftData with one logical archive | Private JSON with atomic writes |
| Keychain | Android Keystore AES-GCM and private encrypted preferences |
| AVAudioSession | AudioManager and audio focus |
| stasel/WebRTC | webrtc-sdk for Android |
| iOS recording permission | Runtime `RECORD_AUDIO` |
| NaturalLanguage recognizer | `TextClassifier` language detection (Android 10+; skipped on older versions) |
| `CFStringTokenizer` word readings for Mandarin | ICU word segmentation for caption links; bundled phrase readings with a guarded ICU fallback for pinyin (Android 10+; readings are omitted on older versions) |
| File exporter and importer | Storage Access Framework, no broad storage permission |
| Background events | `Activity.onStop` and resource cancellation |

## Data contract and privacy

The iPhone JSON archive (`schemaVersion` 2) and the v1 Norwegian migration are preserved. Dates are seconds since 1 January 2001, not the Unix epoch. Import merges new conversations, keeps local settings and revalidates evidence. Files over 30 MB, duplicates, unknown languages and invalid records are rejected. Exports from both platforms are semantically compatible; key order differs because Swift sorts keys.

The microphone works only in the foreground. Ending the conversation, losing audio focus, leaving the app or cancelling the connection releases audio and WebRTC. Only the permissions Mural needs are requested, and no recordings are stored. System backups and device transfers exclude the archive and credentials; learners move their learning through explicit export.

The repository protocol is unchanged: OpenAI `POST /v1/live/sessions` with `gpt-live-1`, voice `marin` and the `oai-events` channel, and helper operations through `POST /v1/responses` with `gpt-5.6-luna`. The key is entered on the device, never in code. There is no shared key, no required Mural server and no paid call in automated tests.

## Keeping both clients in sync

Language content is generated from the Swift modules by `scripts/export_android_content.py`. `scripts/check_cross_platform.py` compares teaching prompts, learning constants and archive fields between `apps/ios/Core/` and `apps/android/app/src/main/java/chat/mural/core/`, and `shared/fixtures/cross-platform/` holds an archive that both `swift test` and the Gradle tests decode, project and re-encode. See [the language architecture](../language-architecture.md).

## Platform and verification

Minimum Android 8.0 (API 26); compile and target SDK 36. Java 17, Gradle 8.11.1 with a verified checksum, AGP 8.10.1 and pinned dependencies. The APK bundles WebRTC for ARM and x86 emulators. Google Play publishing and a release signing key are outside a personal installation.

Verification covers models, backups, evidence, networking without OpenAI, the shared fixture, the meaning and final assessment queues, build, Android Lint, native library alignment and on-device interface tests. A real conversation is verified separately with the owner's key; offline tests cannot prove model access for a given project or audio quality.

References: [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes), [WebRTC Android](https://github.com/webrtc-sdk/android), [Android permissions](https://developer.android.com/training/permissions/requesting), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [TextClassifier](https://developer.android.com/reference/android/view/textclassifier/TextClassifier), [original transport](../../apps/ios/App/LiveTransport.swift).
