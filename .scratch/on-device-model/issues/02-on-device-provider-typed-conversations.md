# 02: On-Device Model provider + offline typed conversations

**What to build:** On-Device Model becomes a real, selectable third Conversation Provider: a learner who has imported a model file picks it in Settings > Advanced and holds a fully offline typed conversation — the model answers their typed replies through the Model Runtime seam. Provider selection, readiness, provenance and minute-accounting all treat it as a first-class provider that is never silently substituted. Demoable: with a model on the phone and airplane mode on, a typed conversation works end to end.

**Blocked by:** 01 (Model Runtime seam + LiteRT-LM adapter + scripted fake)

**Status:** done

- [ ] On-Device Model appears as an explicit third Conversation Provider choice in Settings > Advanced, persists across restarts, and is never silently switched to or from
- [ ] A local `.litertlm` model file can be imported and selected; provider readiness requires an imported, initialized model
- [ ] Typed replies are answered on-device through the Model Runtime seam; the conversation is demoable in airplane mode
- [ ] Sessions record On-Device Model as their provider provenance, identically to how hosted sessions record theirs
- [ ] No minute balance is consumed, displayed, or required for any on-device session
- [ ] An unavailable on-device provider surfaces through the existing provider-issue messaging and never falls back to Personal Key or Mural Minutes
- [ ] Talk's home screen, orb and sheets are unchanged; all new UI lives in Settings > Advanced using existing components
- [ ] On-device sessions appear unchanged in the existing JSON backup format
- [ ] Provider-policy unit tests cover selection persistence, readiness, provenance, and minute exclusion

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/02-on-device-provider-typed-conversations`, branch `ticket/02-on-device-provider-typed-conversations`, base `feature/on-device-model` @ 29dc492.
- 2026-11-27: Done. Ticket branch `4c9cab7`, merged into `feature/on-device-model` as `80f9419`. ON_DEVICE Conversation Provider, SAF `.litertlm` model import/selection, OnDeviceTeachingClient, Session Provenance, minute exclusion, Advanced settings UI, and JVM + compiled androidTest coverage. Offline checks green; airplane-mode hardware demo owed to ticket 13.
