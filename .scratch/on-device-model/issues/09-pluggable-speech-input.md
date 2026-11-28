# 09: Pluggable speech-input alternates

**What to build:** An escape hatch for languages where the active model's native audio input disappoints: a dedicated speech recognition model (Parakeet TDT 0.6B, which covers European Portuguese, French and Italian) can be downloaded from Advanced and chosen as the learner-speech input in place of native audio input. Its transcriptions feed the conversation exactly as native input does — captions, evidence, everything downstream. Demoable: switch input to Parakeet and spoken European Portuguese is transcribed accurately.

**Blocked by:** 07 (Local voice Talk)

**Status:** claimed

- [ ] A dedicated speech recognition model can be downloaded and deleted through the same Advanced catalogue experience
- [ ] The learner can choose it as the speech-input engine for on-device sessions and switch back to native audio input
- [ ] Transcriptions from the alternate engine flow into the conversation identically to native input: captions, session records, assessments
- [ ] An unavailable or not-yet-downloaded alternate never blocks the native input path
- [ ] The picker notes which languages the alternate covers (European Portuguese, French, Italian)
- [ ] Input-engine selection and its fallback behavior are unit tested at the existing seams

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/09-pluggable-speech-input`, branch `ticket/09-pluggable-speech-input`, base `feature/on-device-model` @ 76bd804.
