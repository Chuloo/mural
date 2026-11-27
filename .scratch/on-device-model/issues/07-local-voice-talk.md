# 07: Local voice Talk

**What to build:** The full offline voice conversation: the learner's speech is understood through the active model's native audio input, the model streams its reply, and the phone's built-in text-to-speech speaks it sentence by sentence as generation continues. Turn-taking, check-ins, mute, the 30-second idle end, the timed session limit and the end-of-session report sheet behave exactly as they do on cloud providers today. Demoable: airplane mode on, talk to Mural, hear it answer — no network.

**Blocked by:** 02 (On-Device Model provider + offline typed conversations), 05 (Offline Helper coverage)

**Status:** claimed

- [ ] A voice session runs entirely on-device through the existing live-session seam: creation, streaming, close
- [ ] Learner speech reaches the model through the active model's native audio input; captions of the learner's speech appear as on cloud providers
- [ ] Teacher replies stream and are spoken sentence-by-sentence by the platform text-to-speech, starting before generation finishes
- [ ] Turn-based behavior matches today exactly: check-ins, idle end after the existing quiet interval, mute, and the existing sheets — no new home-screen stages
- [ ] Timed on-device sessions use the existing conversation-length preference, end with the same limit reason, and show the identical report and Continue/New choice
- [ ] No network call occurs during a session (verified in airplane mode)
- [ ] Live-session lifecycle and sentence-chunked speech are unit tested at the existing seams with the scripted fake runtime

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/07-local-voice-talk`, branch `ticket/07-local-voice-talk`, base `feature/on-device-model` @ 9a9cd90.
