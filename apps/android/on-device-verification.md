# On-Device Model: Android Verification & Handover Record

## Scope & Server-Deployment Assessment

- **Scope**: Strictly Android-only (`apps/android/`). `apps/ios/`, `services/api/`, `scripts/`, `shared/fixtures/`, `Languages.kt`, and `Models.kt` archive struct fields are untouched (`minSdk = 26` preserved).
- **Server deployment required**: **None for On-Device Model.**
  - All On-Device Model features (Conversation Provider selection, `.litertlm` model catalogue/download/import, LiteRT-LM & AICore runtimes, offline Helpers, two-pass defensive assessments, local voice Talk, untimed conversations toggle, Parakeet speech recognition, Kokoro 82M & PocketTTS 100M voices, Typed-only Modules, and Japanese/Mandarin on-device wiring) execute strictly on the phone with zero API contract, database migration, or backend configuration changes.
  - **Hosted cloud note (`Mural Minutes`)**: The new Android-first voice Language Modules (`pt-PT` Portuguese Portugal and `ja-JP` Japanese) work offline on On-Device Model and online via Personal Key (`APIClient` sends instructions directly to OpenAI without server locale admission). **Mural Minutes refuses both on the client**: `HostedAPIClient.validateCreate` admits only the cross-platform `LanguageRegistry` locales that `services/api/src/live-provider.ts` lists, and `MuralViewModel.start()` fails before the foreground service and before any provider call with `hosted_language_unsupported`, which names *On-device model* and *My API key* in Settings → Advanced and never switches the learner's provider. Adding `pt-PT` or `ja-JP` to the hosted allowlist still needs a separate backend PR and deployment, after which both guards are relaxed together. Typed-only Modules (`kok-Latn` Roman Konkani, `jam` Jamaican Patois) never open voice sessions on any provider.

## 1. Local Automated Verification (Completed)

Executed inside `mural-android-builder` (`linux/amd64`) and repository contract scripts against integration branch `feature/on-device-model` (base `origin/main` @ `607c3bb`):

| Check | Command | Result |
| --- | --- | --- |
| Android JVM unit suite | `./gradlew :app:testDebugUnitTest` | **PASS** — 591 tests, 0 failures, 0 errors |
| Android Lint | `./gradlew :app:lintDebug` | **PASS** — 0 errors |
| Debug APK build | `./gradlew :app:assembleDebug` | **PASS** — `app-debug.apk` built |
| UI test APK build | `./gradlew :app:assembleUiTestAndroidTest` | **PASS** — `app-uiTest-androidTest.apk` built (incl. `ConversationProviderStoreTest`, `TypedOnlyModuleTest`, `CaptionParityTest`) |
| Generated language contract | `python3 scripts/export_android_content.py --check` | **PASS** (`Languages.kt` byte-identical) |
| Cross-platform contract | `python3 scripts/check_cross_platform.py` | **PASS** (prompts, constants, archive fields) |
| Repository script tests | `python3 -m unittest discover -s scripts/tests -t .` | **PASS** — 91 tests |

## 2. Live Hardware Verification on Pixel 9 (Pending Human Run — Ticket 13)

No physical Pixel 9 was attached (`adb devices` empty) during the automated agent run. Before Play release, run the following checks on a physical 12 GB Pixel 9 with Airplane Mode enabled after model downloads complete:

### A. Install verification build
```sh
docker run --rm --platform linux/amd64 \
  -v "$PWD:/workspace" -v mural-gradle-cache:/root/.gradle -v mural-android-home:/root/.android \
  mural-android-builder \
  ./gradlew --no-daemon --no-watch-fs :app:assembleDebug
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

### B. Measurements to record
1. **Gemma 4 E4B vs E2B memory headroom (12 GB floor)**:
   - Download **Gemma 4 E4B** in **Settings → Advanced → On-device models**, enable Airplane Mode, and hold a multi-turn voice session (native audio input + platform TTS).
   - Capture memory footprint mid-generation:
     ```sh
     adb shell dumpsys meminfo chat.mural.android | grep -E "TOTAL PSS|Native Heap|Graphics|GL mtrack"
     ```
   - Switch to **Gemma 4 E2B**, repeat, and record peak PSS/swap comparison and first-token latency.
2. **Native audio input vs Parakeet TDT 0.6B transcription quality**:
   - Test spoken turns in **French (`fr-FR`)**, **Italian (`it-IT`)**, **Portuguese Brazil (`pt-BR`)**, **Portuguese Portugal (`pt-PT`)**, **Mandarin (`zh-CN`)**, and **Japanese (`ja-JP`)** using native Gemma 4 audio input (`<heard>` captions).
   - Download & select **Parakeet TDT 0.6B** in **Settings → Advanced → On-device models → Learner speech input**, re-test `pt-PT`, `fr-FR`, and `it-IT`, and confirm automatic fallback to native audio when switching to `zh-CN`, `ja-JP`, or `pt-BR`.
3. **Open-weight voices (Kokoro 82M & PocketTTS 100M)**:
   - Download **Kokoro 82M** and **PocketTTS 100M**; verify streamed sentence-by-sentence playback in `pt`, `fr`, `it`, `zh` (Kokoro `zf_xiaoxiao`), and `ja` (Kokoro `jf_alpha`), plus automatic fallback to platform TTS when deleted.
4. **AICore (Gemini Nano) availability**:
   - Check whether **Gemini Nano (System AICore)** appears in the model picker on the Pixel 9 (`FeatureStatus.AVAILABLE` vs package-allowlist `UNAVAILABLE`) and record status.
5. **Untimed long session & low-memory recovery**:
   - Enable **Untimed on-device conversations**, run past `sessionMinutes`, confirm no `"Time limit"` cutoff occurs, and verify 30 s idle end still closes cleanly.
   - Simulate process death / low-memory kill mid-session (`adb shell am kill chat.mural.android` while backgrounded) and confirm local conversation history & provenance (`providerID = "on-device"`) remain intact on relaunch.
   - Confirm the relaunch offers **Continue conversation / New conversation** for the killed session: `LearningRepository` recovers it as `"App closed before finalization"`, `ConversationContinuationPolicy.recoveredOnDeviceSession` picks it up, and the sheet resumes it ready at once with no minutes to settle.
6. **Model RAM follows the provider choice**:
   - With On-Device Model chosen and a model loaded, switch to **My API key** or **Mural Minutes** in Settings → Advanced and confirm `dumpsys meminfo chat.mural.android` drops the model footprint (`OnDeviceModelController.unload()` closes the `RuntimeSession`).
   - Switch back to **On-device model** and confirm the same active model reloads with no re-download and no lost selection.
   - With **My API key** chosen, download or import a model and confirm it is stored as the active selection without being held in RAM.
7. **Talk's idle home screen**:
   - On-Device Model with a model that hears the active language (native audio input, or Parakeet for a language it covers): confirm the idle home screen shows the orb and microphone only, with no **Type** button — identical to a cloud voice provider.
   - On-Device Model with a text-only model and no speech recognition alternate: confirm **Type** is offered from idle.
   - Confirm **Type** appears once a conversation is `active` on every provider.
8. **Typed / non-voice Continue**:
   - On a Typed-only Module (`kok-Latn`, `jam`) and on a text-only On-Device Model, end a conversation at its time limit and tap **Continue conversation**: confirm it resumes a typed conversation and opens the typed reply sheet, with no `RECORD_AUDIO` prompt and no no-op.
9. **Zero server telemetry (ticket 18)** — run this one with Airplane Mode **off**, so an attempt would really leave the phone:
   - Save an API key, sign in to an account with minutes available, and choose **On-device model**.
   - Cold-start the app, send it to the background and return, change the Language Module, and open and close the account sheet. Confirm no hosted readiness, account or minute-store request is made:
     ```sh
     adb shell dumpsys netstats detail | grep -A3 chat.mural.android
     ```
     shows no new transmitted bytes for the UID between the steps.
   - Hold a full voice conversation, ask for a meaning subtitle and a word lookup, then end it, and re-read the same counters: a complete on-device session adds zero transmitted bytes.
   - Report a reply from the transcript: the sheet says the report is saved on this phone, and **Settings → Data → Export backup** contains it. No report request reaches the service.
   - Repeat the conversation on **Mural Minutes** to confirm the gated probes still serve a provider that needs them: readiness and balance appear and the conversation is hosted.

## 3. Standards & Spec Review Fixes (this branch)

Applied on `ticket/review-fixes`; all still strictly Android-only.

| Fix | Where | Verified by |
| --- | --- | --- |
| Third-party notices for LiteRT-LM, ML Kit GenAI Prompt/Common and MediaPipe Tasks Audio (Apache-2.0) | `app/src/main/assets/THIRD-PARTY-NOTICES.txt` | `:app:assembleDebug` packages the asset; `OpenSourceNoticesTest` opens it |
| Mural Minutes refuses `pt-PT` / `ja-JP` on the client, with a localized message naming the two providers that can | `HostedAPIClient.validateCreate`, `MuralViewModel.start()`, `values{,-es}/hosted_conversations.xml` | `HostedAPIClientTest.androidFirstVoiceLocalesAreRefusedUntilHostedAdmitsThem` |
| Seventeen learning languages stated accurately (13 cross-platform + 4 Android-first), Typed-only Modules and the On-Device Model provider noted | `apps/android/README.md` | Read against `LanguageRegistry.all` (13) and `AndroidLanguages.extraModules` (4) |
| Test-only `ScriptedModelRuntime` no longer ships in the APK/AAB | `src/main` → `src/test` (`git mv`) | `:app:assembleDebug`, `:app:assembleUiTestAndroidTest` |
| `OpenWeightSpeechOutput.close()` stops its worker, so a conversation never leaks its voice thread | `OpenWeightSpeechOutput.kt` | `OpenWeightSpeechOutputTest.closeStopsTheVoiceWorkerSoAConversationNeverLeaksItsThread` |
| Model RAM lifecycle: load only for On-Device Model, `unload()` on switching away, selection preserved | `OnDeviceModelController.kt`, `MuralViewModel.selectConversationProvider` | `OnDeviceModelControllerTest` (instrumented — needs a device, see §2) |
| Low-memory / process-death continuation checkpoint, and typed Continue for Typed-only and text-only setups | `ConversationProviders.kt`, `ConversationContinuationPolicy.kt`, `MuralViewModel.kt`, `TalkScreen.kt` | `ConversationProvidersTest.anOnDeviceContinuationSurvivesRelaunchWithNoOwnerAndNeverLeaksIntoTheHostedOne`, `…continueResumesTypedWhenThisPhoneHasNoVoiceForTheSavedConversation` |
| Talk's idle home screen stays minimal when the On-Device Model can hear (`canHearOnDeviceVoice`) | `MuralViewModel.kt`, `TalkScreen.kt` | Device check §2.7 |
| Mixed-provider recall: one situation practised on cloud and on-device counts once, provenance kept in `Assessment.context` | `LearningEngine.project` | `CoreTest.theSameThemeOnCloudAndOnDeviceIsOneSituationAndTwoOnDeviceThemesAreTwo`; `scripts/check_cross_platform.py` constants unchanged |
| Copy accuracy for Qwen2.5 1.5B and Gemini Nano (no built-in audio input), EN + ES parity; `IMPORTING_SUFFIX`, `onDevice` wording, one provider-label helper | `values{,-es}/on_device.xml`, `OnDeviceModelController.kt`, `OnDeviceLiveSession.kt`, `MuralViewModel.kt`, `SettingsScreen.kt` | `:app:lintDebug`, `:app:testDebugUnitTest` |

**Deployment**: still none required — every fix above is client-side. The Mural Minutes locale guard makes the Android client *stricter* than production `services/api`, so it needs no server change to be correct; relaxing it later does.

## 4. Zero server telemetry (ticket 18)

The egress inventory, the gates on automatic probes, the offline report storage, the dependency and merged-manifest audit and the tests that pin the guarantee are recorded in [`verification/zero-server-telemetry.md`](../../verification/zero-server-telemetry.md). Offline: `OnDeviceZeroEgressTest` (JVM). On a device: `OnDeviceZeroNetworkTest` (instrumented) and §2.9 above with a real service origin.

The app change needs **no server deployment**. `services/api` no longer accepts a report (`POST /v1/feedback/ai` and `GET /v1/feedback/capabilities` are removed), which ships whenever a human next deploys; retention of rows stored before the removal continues until then. See `services/api/docs/ai-reporting.md`.
