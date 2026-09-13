# How to build and test Mural

Run commands from the directory containing `Package.swift` and `Mural.xcodeproj`. Core tests need Swift 6. Native builds need Xcode 26 or later.

## Run offline checks

```sh
swift test
xcodebuild -project Mural.xcodeproj -scheme Mural \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
```

Create a dedicated iPhone 17 simulator in Xcode’s **Devices and Simulators** window. Most tests use temporary preview storage; the normal-relaunch test also writes language preferences to the simulator's persistent store. If you name it `iPhone 17`, run UI tests with:

```sh
xcodebuild -project Mural.xcodeproj -scheme Mural \
  -destination 'platform=iOS Simulator,name=iPhone 17,arch=arm64' \
  -derivedDataPath .build/DerivedData \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  -parallel-testing-enabled NO test
```

The core suite covers evidence validation, transcript revisions, language isolation, recall spacing, archive validation, translation cancellation, and managed-account configuration and security parsing. Native UI tests exercise the screens with synthetic data and verify language selection across relaunch. Neither suite needs an API key. Configured provider sign-in and account deletion need the separate device checks in [managed accounts](managed-accounts.md).

## Preview without saving learning data

In **Product → Scheme → Edit Scheme → Run → Arguments**, add `--preview`. The app opens with temporary storage and skips onboarding. In a Debug build, add `--ended-conversation` to exercise the ended-conversation state. Preview fixtures make no API calls. Debug `--preview --active-conversation --preview-language=tl` supplies a temporary active conversation for checking the disabled language picker; it opens no microphone or network session. `--preview --ended-conversation --preview-language=tl` supplies the corresponding ended transcript and cached English meaning.

Remove preview arguments before testing normal persistence. For actual speech, [install on an iPhone](run-on-iphone.md) and use the key saved through Settings.

## Update the generated project

After adding or removing files under `App/`, run:

```sh
python3 scripts/generate_project.py
```

The generator moves a team selected in Xcode into the ignored `Config/Local.xcconfig`. The public `Config/Signing.xcconfig` includes that file when present. You can also copy `Config/Local.example.xcconfig` to `Config/Local.xcconfig` and enter your team ID there. Keep repeatable project settings in the generator; other manual project edits can be replaced on the next run. Swift Package Manager discovers files under `Core/` automatically.

## Verify live changes

After changing audio, prompts or a language module, check a short conversation on a real iPhone: greeting, learner reply, correction, subtitles, interruption, mute and final closure. Check speaker and headphones separately. Try cellular with the Mac disconnected.

Debug-only `--verify-audio --verify-language=<language ID>` starts two real voice sessions using the phone’s saved key. `--verify-meaning` adds the translation/reset check. These flags incur API usage, use temporary learning data, and write content-free diagnostics in the app container. Run them only when live testing is intended; they are excluded from Release builds.

For German, Italian, Brazilian Portuguese, Mandarin or Tagalog, `--verify-audio --verify-language-flow --verify-language=<de|it|pt|zh|tl>` runs one live session with a support-language beginner request and a more complex target-language typed reply. It checks received audio, detected target language, meanings, word lookup, supported evidence, archive decoding and switching away and back. The microphone is muted once connected. The report is `Documents/language-verification-<ID>.json`; it contains no transcript, audio or credentials. These synthetic typed turns do not verify recognition of human speech or the quality of corrections and pronunciation. The report exposes mechanical `flowPassed` separately from `passed`, which additionally requires reliable target-language detection. Tagalog detection is unreliable on the tested Apple recognizer, so `passed` remains false even when the mechanical flow succeeds; its actual detected label/confidence is recorded and `languageQualityReview` remains `pending`. Review Tagalog output with a proficient speaker rather than forcing a passing detector result. Reopen the app without verification flags to return to its persistent learning record.

Record the build, checks and remaining limitations in `verification/validation.md`. Successful API transport does not establish pronunciation quality or teaching effectiveness.

## Record a scripted Spanish demo

In a Debug build, launch with `--verify-audio --record-spanish-demo`. This uses the saved API key and temporary learning data. After a 30-second setup pause, it starts a café conversation with English meanings, mutes the microphone, and sends two scripted typed replies. Mural’s responses and speech come from the live APIs. The second reply contains a grammar mistake so the conversation can demonstrate a correction.

This is a typed-input demo with live voice output. It does not verify speech recognition or a human conversation. The helper ends the session and writes a content-free `demo-verification.json` status in the app container. Actual recording is separate; select the Mural screen and its app audio in your recorder. The helper has been compiled on-device; a completed recording and playback review remain required.
