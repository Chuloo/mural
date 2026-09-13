# Mural for Android

Native Kotlin and Jetpack Compose client for Android 8.0 or later. It offers voice and written conversation, eight learning languages, 24 themes, meanings, vocabulary and local history, using the owner's OpenAI API key. Mandarin captions link each word and show optional pinyin. Interface copy lives in `res/values` (English) and `res/values-es` (Spanish); Android picks the translation from the phone's language.

See [install and build](../docs/run-on-android.md), [design](../docs/android/design.md) and [verification](../verification/android-validation.md).

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Requirements: Java 17, Android SDK 35 and Build Tools 35.0.0. The API key is entered inside the app and must never be configured in Gradle. `local.properties`, private signing keys and build outputs are excluded from Git.
