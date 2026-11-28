# On-Device Model: Android Verification & Handover Record

## Scope & Server-Deployment Assessment

- **Scope**: Strictly Android-only (`apps/android/`). `apps/ios/`, `services/api/`, `scripts/`, `shared/fixtures/`, `Languages.kt`, and `Models.kt` archive struct fields are untouched (`minSdk = 26` preserved).
- **Server deployment required**: **None for On-Device Model.**
  - All On-Device Model features (Conversation Provider selection, `.litertlm` model catalogue/download/import, LiteRT-LM & AICore runtimes, offline Helpers, two-pass defensive assessments, local voice Talk, untimed conversations toggle, Parakeet speech recognition, Kokoro 82M & PocketTTS 100M voices, Typed-only Modules, and Japanese/Mandarin on-device wiring) execute strictly on the phone with zero API contract, database migration, or backend configuration changes.
  - **Hosted cloud note (`Mural Minutes`)**: The new Android-first voice Language Modules (`pt-PT` Portuguese Portugal and `ja-JP` Japanese) work offline on On-Device Model and online via Personal Key (`APIClient` sends instructions directly to OpenAI without server locale admission). Enabling `pt-PT` or `ja-JP` on **Mural Minutes** (`services/api/src/live-provider.ts`) will require a separate backend locale-allowlist PR and server deployment before hosted voice calls admit those two locales. Typed-only Modules (`kok-Latn` Roman Konkani, `jam` Jamaican Patois) never open voice sessions on any provider.

## 1. Local Automated Verification (Completed)

Executed inside `mural-android-builder` (`linux/amd64`) and repository contract scripts against integration branch `feature/on-device-model` (base `origin/main` @ `607c3bb`):

| Check | Command | Result |
| --- | --- | --- |
| Android JVM unit suite | `./gradlew :app:testDebugUnitTest` | **PASS** — 587 tests, 0 failures, 0 errors |
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
