# 16: Patois approximate teacher voice

**What to build:** An opt-in Approximate Voice for Jamaican Patois: when enabled, the teacher's typed replies in a Patois conversation are also read aloud by the phone's platform text-to-speech using its English (Jamaica) voice — an English voice reading Patois, stated plainly in the UI. The learner's side stays typed on every provider: the microphone never appears, and Roman Konkani is unaffected. Demoable: toggle on, hold a Patois conversation, hear the teacher speak while you type.

**Blocked by:** 07 (Local voice Talk — done), 12 (Jamaican Patois Typed-only Module — done)

**Status:** ready-for-human

- [x] An opt-in toggle appears in Settings > Advanced for Jamaican Patois only (default off), persists, and is never offered for any other module
- [x] When on, teacher replies in Patois conversations are spoken through the platform text-to-speech, streamed sentence-by-sentence through the existing voice pipeline, on every provider including On-Device Model
- [x] Voice selection prefers the platform's English (Jamaica) voice and falls back to another English voice when it is absent, without error
- [x] The settings row and the conversation UI state the approximation plainly (the teacher is read by a Jamaican English voice)
- [x] The microphone is never offered in Patois on any provider, with or without the toggle; learner input remains typed
- [x] Roman Konkani behaviour is unchanged (no Approximate Voice offered)
- [x] Unit tests cover the toggle policy (Patois only), voice preference and fallback; the instrumented typed-only suite gains the spoken-teacher case

## Comments

- 2026-11-28: Implemented by a ticket worker. Uncommitted, in the worktree `../mural-worktrees/integration` on branch `feature/on-device-model` (base `add42d1`). **Not on `feature/local-llm`**: that branch has no `ondevice/` package, no `jam` module and no `TypedOnlyModuleTest`, so this ticket's blockers (07, 12) and everything it extends exist only here.
  - New `core/ApproximateVoicePolicy.kt`: one map `jam → en-JM`, plus `offered()`, `speaks()` and `localeTag()` — the wanted tag, else another variety of the same language, else null, which changes nothing. No `kok-Latn` entry: that single map is what keeps Roman Konkani unaffected.
  - Persistence copies the Untimed Conversation pattern: `ConversationProviderStore.Snapshot.approximateVoice` + `setApproximateVoice()`, keyed `approximate_voice`, local only, so the exported archive and `Models.kt` are untouched.
  - `MuralViewModel`: opt-in state, `speaksApproximateVoice`, and `speakApproximateVoice()`, which segments the finished reply with the existing `SentenceChunker` into a lazily created `PlatformSpeechOutput`. It is called from the typed branch of `sendTyped()` and `help()` — the one path every provider shares — and stopped in `finish()` and when the toggle goes off.
  - `PlatformSpeechOutput` caches the engine's voice tags once in `activate()` and resolves the sentence locale through `ApproximateVoicePolicy.localeTag` in `speakNow()`, keeping the existing missing-language-pack fallback.
  - UI: a Patois-gated `SettingsSwitchRow` + note in Settings > Advanced, outside the On-Device block so it shows on every provider, and `talk_status_written_approximate` on the existing `conversation-status` node. No new Talk element; the microphone gate is untouched because `isTypedOnly` and `typedOnlyIDs` are unchanged. Strings mirrored into `values-es`.
  - Deliberately unchanged: `AndroidLanguages.typedOnlyIDs`, `isTypedOnly`, `jamaicanPatois.speechGuidance`, `TeachingPolicy.kt`. Their "no text-to-speech / typed only" sentences stay true — there is still no *Patwa* voice, the stand-in is English — and editing them would break `JamaicanPatoisTest`, `RomanKonkaniTest` and `check_cross_platform.py`.
  - No server change is needed: no API contract, prompt, capability, configuration, migration or runtime permission sits in the dependency chain of any checkbox. Synthesis is the phone's own `android.speech.tts.TextToSpeech`, so there is no server audio path and nothing to deploy.
- 2026-11-28: Checks that ran, from the worktree root.
  - `python3 -m unittest discover -s scripts/tests -t .` — 91 tests, OK.
  - `python3 scripts/export_android_content.py --check` and `python3 scripts/check_cross_platform.py` — both clean.
  - Docker (`mural-android-builder`): `./gradlew --no-daemon --no-watch-fs :app:testDebugUnitTest :app:lintDebug :app:assembleUiTest :app:assembleUiTestAndroidTest` — BUILD SUCCESSFUL in 3m17s. 594 JVM tests across 74 suites, 0 failures, including the new `ApproximateVoicePolicyTest` (3/3) and the pinned `JamaicanPatoisTest`, `RomanKonkaniTest`, `SentenceChunkerTest`, `OnDeviceLiveSessionTest`, `OpenWeightSpeechOutputTest` and `ConversationProvidersTest`. `lintDebug` reports no `MissingTranslation`; the only warning on new code is the pre-existing `UseKtx` hint already raised on every `preferences.edit()` line in that store.
  - `javap` on the compiled `uiTest` `MuralViewModel` confirms the private field really is named `approximateOutput`, so the instrumented test's reflection resolves at runtime.
- 2026-11-28: Checks skipped, and what a human still has to do — the reason for `ready-for-human`.
  - `:app:connectedUiTestAndroidTest` (the extended `ConversationProviderStoreTest` round-trip and the new `TypedOnlyModuleTest` spoken-teacher case) — **not run**: no device or emulator is reachable here (no host `adb`, no `emulator` binary, no AVD, and macOS Docker passes no USB). Both files do compile, via `:app:assembleUiTestAndroidTest`.
  - The demoable line (toggle on, hold a Patois conversation, hear the teacher) — **not run**, needs a phone. It is also the only coverage of the one-line `sendTyped`/`help` → `speakApproximateVoice` wiring, which no instrumented test in this repo drives end to end. The policy at both ends is unit-tested.
  - `swift test` / `xcodebuild` — skipped: no Swift or cross-platform-mirrored core file changed.
  - Limits left on purpose: there is no foreground service for a typed conversation, so backgrounding mid-read stops the audio exactly as it already ends the conversation; and the orb stays static while the teacher reads, because `outputLevel` is fed only by a voice session.
  - `.scratch/on-device-model/spec.md` lines 75 and 99 still say no voice output is offered for a Typed-only Module, which `GLOSSARY.md` now supersedes with the Approximate Voice term. A doc follow-up; not rewritten here.
- 2026-11-28: Committed and pushed, superseding the "Uncommitted" note above.
  - Code is `8a97642` on `feature/on-device-model`, pushed to `origin`. That branch is the head of the open PR alt-ctrl-dev/mural#1 (base `main`, the whole On-Device Model effort), so ticket 16 lands inside that stacked PR rather than a new one — its blockers 07 and 12 are only there. The push also carried the unrelated tracker commits another process had already made on this branch (tickets 17, 18, 19).
  - A comment pass went over every changed file first; the Docker check above was re-run afterwards and is green again (`BUILD SUCCESSFUL` in 2m39s, `testDebugUnitTest` + `lintDebug` + both `assembleUiTest*`). Comments only, so no behaviour changed.
  - `Status: ready-for-human` stays. In `triage-labels.md` that label reads "requires human implementation"; here the implementation is done and the remaining step is human — run the instrumented suite on a phone and check the demoable line. There is no canonical label for "awaiting human device verification", so this is the closest.
  - Flip to `done` with the merge revision once PR #1 (or a stacked PR cut from it) merges.
