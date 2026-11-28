# Mural for Android

Native Kotlin and Jetpack Compose client for Android 8.0 or later. It offers voice and written conversation, seventeen learning languages, 24 themes, meanings, vocabulary and local history.

Thirteen Language Modules come from the generated cross-platform `LanguageRegistry`; four are Android-first and live in `app/src/main/java/chat/mural/core/AndroidLanguages.kt`, which Android resolves instead of the registry, until the matching iPhone module exists: Portuguese (Portugal) `pt-PT`, Roman Konkani `kok-Latn`, Jamaican Patois `jam` and Japanese `ja`. Roman Konkani and Jamaican Patois are Typed-only Modules: no voice exists for them on any provider, so both sides of their conversation are typed.

Word taps in all seventeen languages open a contextual meaning sheet, matching the iOS flow. Mandarin uses a bundled offline phrase dictionary with an ICU fallback; unresolved common ambiguous readings retain their source characters.

A conversation runs on the explicitly chosen Conversation Provider: **Personal Key** (the owner's OpenAI API key), **Mural Minutes** (a prepaid hosted balance), or **On-Device Model**. The On-Device Model provider runs the whole conversation on this phone — a curated or imported `.litertlm` model on the LiteRT-LM runtime, or the zero-download system model on AICore — makes no network call while active, and costs neither minutes nor a key. An unavailable provider is never silently replaced by another.

Interface copy lives in `res/values` (English) and `res/values-es` (Spanish); Android picks the translation from the phone's language.

See [install and build](../../docs/run-on-android.md), [design](../../docs/android/design.md) and [verification](../../verification/android-validation.md).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Requirements: Java 17, Android SDK 36 and Build Tools 35.0.0. The API key is entered inside the app and must never be configured in Gradle. `local.properties`, private signing keys and build outputs are excluded from Git.
