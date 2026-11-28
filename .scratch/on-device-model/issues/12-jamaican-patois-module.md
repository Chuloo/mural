# 12: Jamaican Patois Typed-only Module

**What to build:** Jamaican Patois as the second Typed-only Module, reusing the mechanism the Roman Konkani ticket introduced: a selectable learning language with its own orthography and teaching focus — a language, not broken English — where conversation is typed on every provider because no voice exists for it anywhere. Code-switching with English is treated as valid. Demoable: pick Jamaican Patois, hold a typed conversation, save words to flashcards.

**Blocked by:** 11 (Roman Konkani Typed-only Module — introduces the typed-only mechanism)

**Status:** done

- [ ] Jamaican Patois is selectable as a learning language with its own greeting, teaching focus and themes
- [ ] Its content respects Patois orthography and character; code-switching with English is never marked as an error
- [ ] Typed conversation, English meaning subtitles, word lookups, assessments and flashcards work on every provider
- [ ] No voice input or output is offered, reusing the typed-only mechanism unchanged
- [ ] The shared cross-platform fixtures cover the module's text cases, and registry validation passes

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/12-jamaican-patois-module`, branch `ticket/12-jamaican-patois-module`, base `feature/on-device-model` @ 9a9cd90.
- 2026-11-27: Done. Ticket branch `5419cc4`, merged into `feature/on-device-model` as `f054691`. Android-scoped Jamaican Patois (`jam`) Typed-only Module reusing `isTypedOnly`, Patwa orthography & English continuum code-switching tolerance, Jamaican theme overrides, vocabulary isolation from English, and JVM + compiled androidTest coverage.
