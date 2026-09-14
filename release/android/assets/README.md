# Play listing assets

Prepared on 14 September 2026. The six phone images show the actual Android interface at 1080 × 1920 pixels and 420 dpi, with English controls, Spanish from Spain and English meanings. They use the separate `chat.mural.android.uitest` app and explicitly synthetic conversation/vocabulary fixtures. No microphone, provider, account or purchase requests were made.

The capture build includes the compact Talk and onboarding layout fixes and is newer than the named September 13 debug preview. [Evidence](../evidence/play-assets-2026-09-14.json) records APK, fixture, source and image hashes. It does not identify these images as a capture of a Play-signed release. Compare or regenerate them after the final candidate changes.

The 1024 × 500 feature graphic is rendered in Compose using the production `Brand`, `MuralOrb`, colors and typeface. Its English copy is “It starts with a hello.” and “Learn by talking.” It is a listing composition, not an app screen. The image and six screenshots are opaque 24-bit PNGs; the unchanged 512-pixel listing icon is RGBA. No screenshots were cropped, stretched, retouched or assembled from separate interface pieces.

## Files and alt text

| File | Alt text |
| --- | --- |
| [Feature graphic](feature-graphic.png) | Mural's warm orange and lilac orb beside “It starts with a hello. Learn by talking.” |
| [Greeting](en-US/01-greeting.png) | Mural greets you in Spanish, with an English meaning and a microphone control to start talking. |
| [Conversation](en-US/02-conversation.png) | A Spanish café conversation with an English meaning, the learner's reply and voice controls. |
| [Themes](en-US/03-themes.png) | Everyday conversation themes, including a café and the weekend, with a choice to talk without a theme. |
| [Words](en-US/04-words.png) | Spanish vocabulary from conversation, with English meanings and recall bars for each word. |
| [Language selection](en-US/05-languages.png) | The welcome screen with Spanish from Spain selected and a Continue button. |
| [Settings](en-US/06-settings.png) | Learning and meaning languages, subtitle controls, gentle corrections and optional API key settings. |

The native screen tests check that English and Spanish captions remain fully visible on the compact display, both onboarding dropdowns fit above the footer, and onboarding remains usable at double text size. Four selected tests passed. The capture script restored display size, density, font scale and status-bar settings, then returned the original user-zero Mural installation to the foreground.

## Refresh from prebuilt isolated APKs

Build the isolated application and instrumentation APKs from the intended source snapshot using the project's existing Android toolchain:

```sh
cd apps/android
./gradlew --no-daemon :app:assembleUiTest :app:assembleUiTestAndroidTest
cd ../..
python3 scripts/capture_android_play.py \
  --serial emulator-5554 \
  --app-apk apps/android/app/build/outputs/apk/uiTest/app-uiTest.apk \
  --test-apk apps/android/app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk
python3 scripts/check_android_release.py --require-assets
```

Set `ANDROID_HOME` and Java 17 first. The script uses SDK Build Tools 36.0.0, refuses physical-device identifiers and non-isolated APK package names, and requires a running emulator without an existing display-size or density override. It performs no build or upload itself. Coordinate emulator use before running it; the original application data is not cleared. Retain the evidence path printed by the script and inspect all seven generated images before using them in Console.
