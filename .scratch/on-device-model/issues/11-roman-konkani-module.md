# 11: Roman Konkani Typed-only Module

**What to build:** Roman Konkani as a selectable learning language, written only in the Roman script, and the mechanism that makes it a Typed-only Module: no voice input or voice output is offered for it on any provider — cloud included — so conversation, word lookups, meaning subtitles, assessments and flashcards all flow through typing, honestly, because no speech recognition or text-to-speech voice exists for Konkani anywhere. Teaching guidance treats code-switching with English, Hindi and Marathi as valid, never as errors. Demoable: pick Roman Konkani, hold a typed conversation, build flashcards — no microphone offered.

**Blocked by:** None (can start immediately)

**Status:** done

- [ ] Roman Konkani is selectable as a learning language; all its content and guidance use the Roman script only
- [ ] The Typed-only Module mechanism lands here: no microphone or spoken-teacher UI is offered for it on any provider, and typed conversation is the complete experience
- [ ] Teaching guidance accepts code-switching with English, Hindi and Marathi without marking borrowings as errors
- [ ] Meaning subtitles in English, word lookups, assessments and flashcards all work through typed evidence on every provider
- [ ] The shared cross-platform fixtures cover the module's text cases, and registry validation passes
- [ ] Selecting another language afterwards restores voice options normally

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/11-roman-konkani-module`, branch `ticket/11-roman-konkani-module`, base `feature/on-device-model` @ 7fb0398.
- 2026-11-27: Done. Ticket branch `c52bbee`, merged into `feature/on-device-model` as `70b8c63`. Android-scoped Roman Konkani (`kok-Latn`) Language Module in Roman script only, reusable Typed-only Module mechanism (`isTypedOnly`), English/Hindi/Marathi code-switching tolerance, mic/voice suppression across providers with immediate voice restoration on language switch, and JVM + compiled androidTest coverage.
