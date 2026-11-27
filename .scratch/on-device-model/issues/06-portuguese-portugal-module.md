# 06: Portuguese (Portugal) Language Module

**What to build:** European Portuguese as its own selectable Language Module, following the established add-language pattern: European pronunciation and vocabulary guidance (tu/você usage, European words and spellings), localized theme overrides, its own greeting, and cross-platform fixtures. It works with voice on every provider, cloud included, from day one. The existing Portuguese (Brazil) module is untouched. Demoable: pick Portuguese (Portugal), start a voice conversation, and be taught the European variety.

**Blocked by:** None (can start immediately)

**Status:** done

- [ ] Portuguese (Portugal) is selectable as a learning language alongside the unchanged Portuguese (Brazil)
- [ ] Its speech, writing and lemma guidance describe the European variety; teaching focus and themes are localized, with greetings and unavailable-reply in the module's voice
- [ ] Voice conversations work immediately on the existing cloud providers
- [ ] The shared cross-platform fixtures cover the new module's text cases, and registry validation passes on both platforms
- [ ] Theme overrides reflect European situations rather than translating Brazilian ones

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/06-portuguese-portugal-module`, branch `ticket/06-portuguese-portugal-module`, base `feature/on-device-model` @ 29dc492.
- 2026-11-27: Done. Ticket branch `c4179e2`, merged into `feature/on-device-model` as `fee7bfa`. Android-scoped `pt-PT` Language Module via `AndroidLanguages`, European speech/writing/lemma guidance and 7 localized Portugal theme overrides, ML Kit `pt` redirect compatibility, and JVM + compiled androidTest parity coverage while keeping `Languages.kt` and non-Android files untouched.
