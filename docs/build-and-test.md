# How to build and test Mural

Run commands from the repository root unless a step changes directory. The iPhone project and Swift package live in `apps/ios/`. Core tests need Swift 6. Native builds need Xcode 26 or later.

## Run offline checks

```sh
swift test --package-path apps/ios
xcodebuild -project apps/ios/Mural.xcodeproj -scheme Mural \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
```

Create an iPhone 17 simulator in Xcode’s **Devices and Simulators** window. If you name it `iPhone 17`, run UI tests with:

```sh
xcodebuild -project apps/ios/Mural.xcodeproj -scheme Mural \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  -parallel-testing-enabled NO test
```

The core suite covers evidence validation, transcript revisions, language isolation, recall spacing, archive validation, translation cancellation, and managed-account configuration and security parsing. Native UI tests exercise the screens with in-memory data. Neither suite needs an API key. Configured provider sign-in and account deletion need the separate device checks in [managed accounts](managed-accounts.md).

## Check the Android port and the cross-platform contracts

```sh
(cd apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
```

From the repository root, run the same checks CI runs on every pull request:

```sh
python3 -m unittest discover -s scripts/tests -t .
python3 scripts/export_android_content.py --check
python3 scripts/check_cross_platform.py
```

The last two catch generated language content and a Swift core change without its Kotlin counterpart, respectively. See [how Mural keeps languages independent](language-architecture.md) for what each contract covers.

## Preview without saving learning data

In **Product → Scheme → Edit Scheme → Run → Arguments**, add `--preview`. The app opens with temporary storage and skips onboarding. In a Debug build, add `--ended-conversation` to exercise the ended-conversation state. Preview fixtures make no API calls.

Remove preview arguments before testing normal persistence. For actual speech, [install on an iPhone](run-on-iphone.md) and use the key saved through Settings.

## Update the generated project

After adding or removing files under `apps/ios/App/`, run:

```sh
python3 scripts/generate_project.py
```

The generator moves a team selected in Xcode into the ignored `apps/ios/Config/Local.xcconfig`. The public `apps/ios/Config/Signing.xcconfig` includes that file when present. You can also copy `apps/ios/Config/Local.example.xcconfig` to `apps/ios/Config/Local.xcconfig` and enter your team ID there. Keep repeatable project settings in the generator; other manual project edits can be replaced on the next run. Swift Package Manager discovers files under `apps/ios/Core/` automatically.

## Verify live changes

After changing audio, prompts or a language module, check a short conversation on a real iPhone: greeting, learner reply, correction, subtitles, interruption, mute and final closure. Check speaker and headphones separately. Try cellular with the Mac disconnected.

Close iPhone Mirroring before live voice checks. [Apple disables access to the iPhone microphone during mirroring](https://support.apple.com/en-us/120421). Install and launch through USB, unlock the physical phone for launch, and use the phone itself for Home and screen-lock actions. The content-free reports below can be retrieved over USB without mirroring.

Debug-only `--verify-audio --verify-language=<language ID>` starts two real voice sessions using the phone’s saved key. `--verify-meaning` adds the translation/reset check. These flags incur API usage, use temporary learning data, and write content-free diagnostics in the app container. Run them only when live testing is intended; they are excluded from Release builds.

For German, Italian, Brazilian Portuguese, Mandarin, Serbian, Greek or Tagalog, `--verify-audio --verify-language-flow --verify-language=<de|it|pt|zh|sr|el|tl>` runs one live session with a support-language beginner request and a more complex target-language typed reply. It checks received audio, meanings, word lookup, supported evidence, archive decoding and switching away and back. It records target-language detection separately; detection is unreliable for Tagalog. The microphone is muted once connected. The report is `Documents/language-verification-<ID>.json`; it contains no transcript, audio or credentials. These synthetic typed turns do not verify recognition of human speech or the quality of corrections and pronunciation. Reopen the app without verification flags to return to its persistent learning record.

Add `--verify-background` to that iPhone flow to check two new spoken replies, a helper request and the same active session for at least 30 seconds in the background, then end it there. At `ready-for-background`, send the app Home or lock the physical phone. Up to four 12-second windows, separated by short scripted turns, allow time to act without changing the product's silence policy. This flag leaves the normal idle timer enabled. The report records background entry and protected-storage lock separately, so sending the app Home does not count as a locked-phone check. Verification uses a separate continuation checkpoint and temporary learning data. It waits for an active app and available protected storage before starting and records those conditions with the connection state.

With a person ready to speak, also add `--verify-spoken-background`. Lock the phone at the cue, wait five seconds and say a short sentence. This opt-in mode unmutes the microphone after background entry and checks a non-typed user fragment and a new assistant reply before muting again. It records the lifecycle state at speech input and response, and samples reply audio before any further scripted turn. Protected-storage availability is a separate diagnostic because it may lag physical locking; record the person's confirmation that the phone stayed locked. Language quality still requires listening review. Final learning checks wait for the latest user passage's assessment, rather than accepting an earlier support-language assessment.

On Android, the explicit live variant installs as `chat.mural.android.verification`, preserving an existing Play installation and its data. It disables purchases and account sign-in. With a connected, unlocked phone and live usage authorized, run:

```sh
cd apps/android
./gradlew --no-daemon -Pmural.liveDeviceVerification=true \
  -Pmural.apiOrigin=https://api.mural.chat -Pmural.minutePurchasesEnabled=false \
  -Pandroid.testInstrumentationRunnerArguments.class=chat.mural.LiveLanguageDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.liveVerification=true \
  :app:connectedVoiceVerificationAndroidTest
```

This performs one brief hosted voice call per new language using the test installation's guest minutes. It checks synthetic typed input, received audio, meanings, lookup, archive decoding, switching languages, an Activity stop/resume and the notification's End action. Reports are `files/language-verification-<ID>.json` in that app's container and contain no conversation text, audio or credentials. Activity lifecycle checks do not establish behavior during actual screen lock, human speech recognition or pronunciation quality; check those separately. Ordinary Android interface tests remain offline in the separate `.uitest` installation.

If public welcome minutes are unavailable, keep that spending limit unchanged. The live runner also accepts `-e personalKey true`; the owner must enter an existing key directly in **Mural Verify → Settings → Advanced → API key**. Never pass a key through test arguments or logs. Build the APKs with `:app:assembleVoiceVerification :app:assembleVoiceVerificationAndroidTest` and the same Gradle properties above, then install them with `adb install -r`. Run the installed instrumentation directly to retain the test installation and its credentials between checks:

```sh
adb shell am instrument -w -r -e class chat.mural.LiveLanguageDeviceTest \
  -e liveVerification true -e personalKey true \
  chat.mural.android.verification.test/androidx.test.runner.AndroidJUnitRunner
```

For a separate physical screen-off check, target one method, such as `-e class 'chat.mural.LiveLanguageDeviceTest#serbian'`, and add `-e screenOff true`. Lock the Samsung at the `ready-for-lock` report. The test requires its screen to remain off for at least 30 seconds while two new replies arrive. Add `-e interruptAudio true` to end via an Android audio-focus interruption and verify cleanup; otherwise the notification's End action closes the call. Unlock afterward to inspect the retained conversation. This verifies OS audio-focus loss, not an incoming cellular call. A spoken microphone check remains separate on Android.

Record the build, checks and remaining limitations in `verification/validation.md`. Successful API transport does not establish pronunciation quality or teaching effectiveness.

## Record a scripted Spanish demo

In a Debug build, launch with `--verify-audio --record-spanish-demo`. This uses the saved API key and temporary learning data. After a 30-second setup pause, it starts a café conversation with English meanings, mutes the microphone, and sends two scripted typed replies. Mural’s responses and speech come from the live APIs. The second reply contains a grammar mistake so the conversation can demonstrate a correction.

This is a typed-input demo with live voice output. It does not verify speech recognition or a human conversation. The helper ends the session and writes a content-free `demo-verification.json` status in the app container. Actual recording is separate; select the Mural screen and its app audio in your recorder. The helper has been compiled on-device; a completed recording and playback review remain required.
