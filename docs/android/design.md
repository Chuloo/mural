# Mural on Android

## Decision and scope

The original app is SwiftUI and SwiftData and cannot be built as an APK. The Android client is a separate native app in `android/`, written in Kotlin with Jetpack Compose. The iPhone client and the server are unchanged. Rewriting iPhone with Flutter or React Native would widen the scope unnecessarily, and wrapping a web view would neither reuse the SwiftUI interface nor solve native audio.

The Android client keeps the eight language modules, the 24 themes and their cultural variants, WebRTC voice, written replies, meanings, word lookup, current topics with sources, history, corrections, vocabulary and learning projection. It adds written conversation without the microphone permission. Managed accounts, payments and sync stay disabled, as on iPhone.

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
| `CFStringTokenizer` word readings for Mandarin | ICU word segmentation for caption links, and the ICU Han-Latin transform for pinyin (Android 10+; readings are omitted on older versions) |
| File exporter and importer | Storage Access Framework, no broad storage permission |
| Background events | `Activity.onStop` and resource cancellation |

## Data contract and privacy

The iPhone JSON archive (`schemaVersion` 2) and the v1 Norwegian migration are preserved. Dates are seconds since 1 January 2001, not the Unix epoch. Import merges new conversations, keeps local settings and revalidates evidence. Files over 30 MB, duplicates, unknown languages and invalid records are rejected. Exports from both platforms are semantically compatible; key order differs because Swift sorts keys.

The microphone works only in the foreground. Ending the conversation, losing audio focus, leaving the app or cancelling the connection releases audio and WebRTC. Only the permissions Mural needs are requested, and no recordings are stored. System backups and device transfers exclude the archive and credentials; learners move their learning through explicit export.

The repository protocol is unchanged: OpenAI `POST /v1/live/sessions` with `gpt-live-1`, voice `marin` and the `oai-events` channel, and helper operations through `POST /v1/responses` with `gpt-5.6-luna`. The key is entered on the device, never in code. There is no shared key, no required Mural server and no paid call in automated tests.

## Keeping both clients in sync

Language content is generated from the Swift modules by `scripts/export_android_content.py`. `scripts/check_cross_platform.py` compares teaching prompts, learning constants and archive fields between `Core/` and `android/app/src/main/java/chat/mural/core/`, and `Tests/Fixtures/cross-platform/` holds an archive that both `swift test` and the Gradle tests decode, project and re-encode. See [the language architecture](../language-architecture.md).

## Platform and verification

Minimum Android 8.0 (API 26); compile and target SDK 35. Java 17, Gradle 8.11.1 with a verified checksum, AGP 8.9.2 and pinned dependencies. The APK bundles WebRTC for ARM and x86 emulators. Google Play publishing and a release signing key are outside a personal installation.

Verification covers models, backups, evidence, networking without OpenAI, the shared fixture, the meaning and final assessment queues, build, Android Lint, native library alignment and on-device interface tests. A real conversation is verified separately with the owner's key; offline tests cannot prove model access for a given project or audio quality.

References: [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [WebRTC Android](https://github.com/webrtc-sdk/android), [Android permissions](https://developer.android.com/training/permissions/requesting), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [TextClassifier](https://developer.android.com/reference/android/view/textclassifier/TextClassifier), [original transport](../../App/LiveTransport.swift).
