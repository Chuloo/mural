# 01: Model Runtime seam + LiteRT-LM adapter + scripted fake

**What to build:** The single new seam for the whole On-Device Model feature: a Model Runtime interface that Helpers and voice sessions call without knowing which engine executes behind it, the real LiteRT-LM engine as its first implementation, and a scripted fake so JVM tests can drive token streams and schema-constrained replies without hardware. Verifiable alone: unit tests pass against the fake, and a device smoke check streams a short reply from a sideloaded `.litertlm` model.

**Blocked by:** None (can start immediately)

**Status:** done

- [ ] A Model Runtime interface exists covering initialize, streaming generation with partial-token/completion/error callbacks, schema-constrained generation requests, mid-stream cancellation, and cleanup
- [ ] A scripted fake implements the interface for JVM tests; the LiteRT-LM adapter implements it against the real engine
- [ ] The adapter initializes from a `.litertlm` model file on the device, streams partial tokens, cancels cleanly mid-generation, and releases memory on cleanup
- [ ] The app builds with the runtime dependency and its minimum Android version is unchanged
- [ ] Unit tests exercise the fake through the same call paths the providers will use
- [ ] A device smoke check streams a short reply from a sideloaded Gemma model and is recorded for the verification ticket

## Comments

- 2026-11-27: Claimed by foreman (in-session run). Worktree `../mural-worktrees/01-model-runtime-seam`, branch `ticket/01-model-runtime-seam`, base `origin/main` @ 607c3bb.
- 2026-11-27: Done. Ticket branch `04f077f`, merged into `feature/on-device-model` as `dda0f36`. ModelRuntime interface + LiteRT-LM 0.18.1 adapter + ScriptedModelRuntime fake; Kotlin bumped 2.1.20 → 2.2.21 with the Gallery-proven metadata flag. Offline checks green (testDebugUnitTest, lintDebug, assembleDebug) before and after merge. Device smoke check NOT run — no phone/model file attached; owed to ticket 13's live verification. Checks ran in Docker (mural-android-sdk-amd64, Rosetta) since the host has no JDK/SDK.
