# Android verification

Last updated: 13 September 2026.

Result: 78 JVM tests, 28 Python contract tests and 9 instrumented tests (on a virtual device; 7 of them also on a physical device) pass; the cross-platform contracts report no drift; Android Lint reports no errors. On GitHub Actions, `checks.yml` runs 63 Swift tests on macOS, including the shared archive fixture test, and `contracts.yml` and `android.yml` pass.

## Environment

- Linux x86_64 with Java Temurin 17, Gradle 8.11.1, AGP 8.9.2 and Kotlin 2.1.20.
- Android SDK and Build Tools 35; minimum Android 8.0 (API 26).
- Physical device: OPPO CPH2599, Android 16 (API 36), ARM64, over USB and wireless debugging.
- Virtual device: Pixel 6 profile, Android 15 (API 35), x86_64 with Google APIs.
- App ID `chat.mural.android`; interface tests install `chat.mural.android.uitest`.

## Automated checks

- `python3 -m unittest discover -s scripts/tests -t .`: 28 tests pass.
- `python3 scripts/export_android_content.py --check`: generated language content matches the eight Swift modules, including the meaning-language list, and the exporter rejects a module without six teaching stages.
- `python3 scripts/check_cross_platform.py`: teaching prompts, shared constants and archive fields agree. Injected drift was reported with the file to edit: a new Swift `SessionRecord` field, a field required only by Kotlin, a changed prompt word, a renamed prompt, a changed Kotlin constant and a deleted required-key check.
- `:app:testDebugUnitTest`: 78 tests in 17 classes, covering models, archive compatibility, evidence, the HTTP client against a local mock server, the shared cross-platform fixture, `MeaningController` and `FinalAssessmentQueue` (tests ported from `Tests/MeaningTests.swift` and `Tests/FinalAssessmentTests.swift`), session limits, usage summary, inline Markdown, caption links, language detection helpers, communication audio route selection, Mandarin readings, caption segments and word merging across segments (against a dictionary stand-in), canonical Unicode equivalence of vocabulary keys, the shared language-redirect cases and error message mapping.
- The fixture test compares the re-encoded archive with the source by content (values and collection sizes, nulls removed, numbers compared numerically), fails when a field is added to `Tests/Fixtures/cross-platform/archive.json` that Android does not keep, and Gradle reruns it when the fixture changes.
- `:app:lintDebug`: 0 errors, 15 warnings (dependency version suggestions and style).
- `:app:assembleDebug`, `:app:assembleUiTest` and `:app:assembleUiTestAndroidTest` succeed. English and Spanish resources define the same 215 keys.

## Device checks

Device checks ran on the final build of this branch.

Instrumented suite on the virtual device (9 tests) and on the OPPO (the 7 that predate the language update): onboarding and consent, settings details, open-source notices, test isolation, platform language detection for Spanish, Norwegian, French and English, Keystore credentials and a local WebRTC offer. SHA-256 hashes of every data file of the learner's installation were identical before and after five full runs on an earlier build, and the suite passed again on the final build. One run right after installing the test app failed once and passed in the four runs that followed; its log was not captured. The onboarding test has previously failed when the screen was asleep.

Manual checks with the owner's OpenAI key:

- Voice on the final build: connection, greeting in the practice language, ending, and the microphone released afterwards. Contextual replies, corrections, and mute and unmute were checked with the learner speaking on the build before the meaning and assessment queues were extracted.
- Written conversation: reply and correction in about 5 seconds, meaning shortly after, and the conversation still open after 179 seconds without activity.
- Meanings appear for the latest assistant reply; new words were saved after ending a conversation.
- Leaving the app ends a voice conversation and a written conversation alike; `appops` showed the microphone recording stop at that moment, and the Talk screen read "Conversation ended" on return.
- "Talk about this" during a written conversation keeps it open, shows the topic-ready notice, and the next typed question about the topic is answered from the sourced brief. Checked with a second topic found while the first was still being discussed.
- Word detail shows the recall explanation in the interface language.
- On the virtual device: the shared fixture backup imports through the file picker, and the typed-reply and correction fields stop at 2,000 and 10,000 characters, the lengths that are kept.
- On the virtual device: Settings lists the eight learning languages; with Mandarin selected the Talk screen shows 你好！ with the pinyin toggle and the reading nǐhǎo. ICU on the device reads the checked words (银行, 旅行, 音乐, 觉得, 一点儿…) and the sample sentence exactly as the iPhone dictionary does.
- Tapping a caption word opens the lookup with the word filled in and the explanation loading.
- Current topic: sourced text renders links and bold text, links open the browser, "Talk about this" carries the sources into the conversation, and the Sources button opens them.
- The latest learner passage appears under the caption; Settings shows recorded voice time, the voice estimate, search calls, corrections, the API key link, the model names and version 0.1.
- English interface checked on the test app with the `en-US` app locale; Spanish on the owner's installation.
- No crashes, no ANR, and no API-key-like text in logcat.

## Limits

- A compiled build, a local SDP offer and mock HTTP tests do not prove a given OpenAI project's model access or audio quality.
- Language redirect uses `TextClassifier`, available from Android 10; older versions skip it. Script and region tags such as `zh-Hans` count as the practice language, as on iPhone (shared cases in `Tests/Fixtures/cross-platform/redirect-cases.json`). The redirect itself was not forced on a device, because the model stayed in the practice language during testing.
- Bluetooth headsets: on Android 12 or later a connected headset is chosen over the speaker, after a wired or USB headset; on Android 8–11 the speaker is used. The route is chosen when the conversation connects, so a headset connected during a conversation is not adopted. Not verified with a headset.
- Pinyin comes from ICU's Han-Latin transform on Android 10 or later, which reads character by character; a table of everyday words with word-dependent readings and erhua forms (simplified and traditional) corrects it, adjacent segments that form one of those words are joined first, and a two-character word is joined like a dictionary reading. Rarer polyphonic words can read differently from iPhone, which uses Apple's word dictionary. Word links use ICU segmentation, which occasionally groups a short phrase such as 我想去 into one link.
- Incoming calls, other physical Android devices, Google Play distribution and an iPhone regression run in Xcode were not covered here.
- Behaviour shared with iPhone that device QA surfaced is reported upstream as [#5](https://github.com/Chuloo/mural/issues/5), [#6](https://github.com/Chuloo/mural/issues/6) and [#7](https://github.com/Chuloo/mural/issues/7); the Android client alone does not fix it.
- Usage records are estimates, not a billing cap; the OpenAI dashboard is authoritative.

## Manual test with the owner's key

1. Complete languages and consent, then save the key in Settings.
2. Start talking and allow the microphone; confirm the greeting and a reply to your voice.
3. Try meanings, mute and unmute, Type instead, A little help and tapping a caption word.
4. End the conversation, check history and words, switch to another app and confirm the microphone is released.
5. Start a written conversation without the microphone.
6. Find a current topic, open its sources, talk about it, then export and import a backup.
7. With an iPhone backup, import it and compare languages, dates and conversations.
