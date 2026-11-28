# 13: Pixel 9 live verification record

**What to build:** Measure the two consciously accepted risks on real hardware and record the results in the repo's verification documentation: Gemma 4 E4B's memory headroom at the 12 GB floor during live sessions, and the native audio input's transcription quality for French, Italian and both Portuguese varieties. Also confirm AICore availability on the device, long-session stability under the untimed toggle, and that a low-memory interruption recovers without losing the conversation. The handover must distinguish local tests from live verification and state that no server deployment is needed for this feature.

**Blocked by:** 03 (Curated model catalogue + in-app download), 07 (Local voice Talk)

**Status:** needs-human

- [ ] Memory headroom measured with E4B active during a voice session (platform TTS, native audio input); a comparison note with E2B recorded
- [ ] Native audio input quality assessed for French, Italian and both Portuguese varieties; if it disappoints, the Parakeet alternate is exercised and the recommendation recorded
- [ ] AICore availability as reported by the device is recorded
- [ ] An untimed long session runs to its natural end; if the low-memory killer intervenes, recovery preserves the conversation and that is recorded
- [ ] Results land in the repo's verification documentation with the deployed-revision style handover: local tests vs live verification clearly separated
- [ ] The handover states explicitly that no server deployment is required for this feature and why

## Comments

- 2026-11-27: Local automated verification record, explicit server-deployment assessment, and step-by-step Pixel 9 hardware measurement runbook written to `apps/android/on-device-verification.md`. Marked `needs-human` per `foreman.md` because no physical Pixel 9 is attached via `adb` (`adb devices` empty) and live-device checks require real hardware + downloaded models.
