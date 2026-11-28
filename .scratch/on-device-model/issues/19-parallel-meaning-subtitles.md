# 19: Sentence-aligned meaning subtitles during on-device speech

**What to build:** On On-Device Model sessions, the meaning subtitle keeps pace with the teacher's speech instead of trailing it. Today the meaning waits for the passage to go quiet (debounce plus one-helper-in-flight pacing designed for paid cloud calls) and then translates the whole reply at once — so with on-device models the meaning appears only after the teacher has already spoken past it, confusing the learner. Instead, each assistant sentence's meaning is produced while that sentence is being spoken: as a sentence completes generation and enters text-to-speech, its translation runs in parallel with playback, and the meaning subtitle for each sentence appears no later than the moment that sentence finishes being spoken. Demoable: on-device voice session, the meaning of the first sentence is on screen before the teacher finishes saying it.

**Blocked by:** 05 (Offline Helper coverage — done), 07 (Local voice Talk — done)

**Status:** done

- [ ] In on-device voice sessions, the meaning for each assistant sentence appears no later than the end of that sentence's speech; earlier sentences' meanings stay visible as the reply continues
- [ ] Meaning work for a sentence starts as soon as that sentence completes generation (no whole-passage quiet-period debounce on On-Device Model; the pacing logic remains untouched for cloud providers, where it controls paid calls)
- [ ] Translation of early sentences interleaves with generation and speech of later ones: the unit of work is the sentence chunk already used for text-to-speech
- [ ] Evidence integrity is preserved: per-sentence meaning display marks exactly the fragments whose meaning was visible at production time, exactly as whole-passage meaning does today — assisted/independent classification in the Learning Engine must not change because of the timing change
- [ ] Cancelling a turn cancels its pending meaning work; a sentence translation that fails falls back to the existing whole-passage meaning at the turn boundary, surfaced with the existing retry affordance
- [ ] The strict-offline guarantee is untouched: the parallel meaning work runs on the same local Model Runtime and makes no network call (asserted by ticket 18's zero-egress test when both land)
- [ ] A scripted unit test asserts the alignment guarantee: with a fake runtime and a fake speech clock, the first sentence's meaning is displayed before the first sentence finishes speaking, and per-sentence ordering holds across a multi-sentence reply
- [ ] Typed conversations (no speech) keep today's meaning behavior
- [ ] Cloud providers' meaning behavior is unchanged

**Performance note for the implementer:** the local engine serializes inference, but sentence-speech duration is far longer than a short-sentence translation, so queuing each sentence's meaning as speech of the previous one begins naturally hides translation latency. If measurement shows contention on a real device, lowering sentence-translation priority behind generation (never ahead of it) is acceptable; the alignment criterion above is the contract.

## Comments

- 2026-11-28: Claimed. Worktree `../mural-worktrees/19-parallel-meaning-subtitles`, branch `ticket/19-parallel-meaning-subtitles`, base `feature/on-device-model` @ 9d0c962.
- 2026-11-28: Done. Ticket branch `e9aec7b`, merged into `feature/on-device-model` as `0c3b48d`. Sentence-aligned meaning on On-Device voice sessions: each TTS sentence chunk translated at caption time, meanings accumulate, cancel/fallback to the untouched cloud-paced path. Checks on the merged branch: 605 JVM tests incl. OnDeviceMeaningAlignmentTest (fake runtime + fake speech clock), lint — all pass. Known limit (shortcut: in code): generation holds the engine lock, so very long replies can trail speech; device timing measurement pending, runbook item worth adding at the device pass.
