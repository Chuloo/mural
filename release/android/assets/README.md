# Mural Google Play artwork

Eight English (U.S.) phone screenshots are ordered for the Play listing at 1080 × 1920 pixels. They show the Android app itself, rendered in an isolated `chat.mural.android.uitest` build with sample conversations and vocabulary. The contextual word explanation is returned by an offline test interceptor. No account, microphone, payment, or live AI service was used. The screenshots have no marketing overlays, device frames, crops, or retouching.

| Order | File | What the visitor sees |
| --- | --- | --- |
| 1 | `en-US/01-conversation.png` | A Spanish café exchange, English meaning, and voice controls. |
| 2 | `en-US/02-word-meaning.png` | A contextual explanation for *café* inside the conversation. |
| 3 | `en-US/03-mandarin.png` | A Mandarin exchange with pinyin and English meaning. |
| 4 | `en-US/04-themes.png` | Everyday topics to start a conversation. |
| 5 | `en-US/05-words.png` | Saved Spanish words with meanings and recall indicators. |
| 6 | `en-US/06-conversation-history.png` | A saved café conversation with both speakers' turns. |
| 7 | `en-US/07-italian.png` | An Italian café exchange and English meaning. |
| 8 | `en-US/08-languages.png` | The app's language selector during onboarding. |

`feature-graphic.png` is a 1024 × 500 composition rendered with Mural's native brand, orb, colors, and type. Its copy is “It starts with a hello. Learn by talking.” The feature graphic is listing artwork, rather than an app screen. The existing `icon.png` is unchanged.

The final phone images and feature graphic are opaque RGB PNGs. `raw/en-US/` retains the unedited emulator captures used for each final phone image. `capture-evidence.json` records the app and test APK hashes, capture fixture hashes, source snapshot, output hashes, and restoration of emulator settings. These assets document the UI from the isolated capture build; they are not evidence of a Play-signed bundle or a successful purchase.

`review-contact-sheet.png` is a scaled overview for visual review. It is not a Play upload asset.

## Recreate the images

Use a dedicated API 36 emulator without existing display overrides. Build the isolated app and instrumentation APKs from the release source, then run the guarded capture script:

```sh
cd apps/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/william/Library/Android/sdk \
./gradlew --no-daemon --rerun-tasks --no-build-cache :app:assembleUiTest :app:assembleUiTestAndroidTest
cd ../..
ANDROID_HOME=/Users/william/Library/Android/sdk python3 scripts/capture_android_play.py \
  --serial emulator-5554 \
  --app-apk apps/android/app/build/outputs/apk/uiTest/app-uiTest.apk \
  --test-apk apps/android/app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk
python3 scripts/check_android_release.py --require-assets
```

The script accepts only an emulator and the isolated test package, restores the emulator display settings, and writes the image and source record. Inspect all eight full-size images and a carousel-size contact sheet before uploading them. Repeat the capture if the app layout, visible copy, or language behavior changes.
