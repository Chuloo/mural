# 14: Japanese Language Module (Android)

**What to build:** Japanese (`日本語 · Japan`, `ja-JP`) as a selectable full-voice Android Language Module alongside the existing languages: polite everyday standard Japanese guidance (`です/ます` default adapting naturally to casual plain forms), natural Kanji + Hiragana + Katakana writing without spaces, dictionary-form lemmas, localized Japanese theme situations, Japanese caption word-tap segmentation so words are individually tappable without spaces, and CJK sentence chunking (`。` `！` `？` `」`) so streamed on-device voice replies start speaking immediately sentence by sentence.

**Blocked by:** 07 (Local voice Talk)

**Status:** claimed

- [ ] Japanese (`id = "ja"`, `locale = "ja-JP"`, `nativeName = "日本語"`, `variety = "Japan"`) is selectable in Onboarding and Settings on Android with full voice and typed support
- [ ] Speech, writing and lemma guidance cover natural spoken Japanese, polite/plain register, Kanji + Kana orthography without spaces, and dictionary-form lemmas while preserving observed forms and quotes
- [ ] Localized theme overrides reflect real Japanese everyday situations (kissaten/café, shotengai/market, train trip, hanami/park walk, izakaya/teishoku meal, ryokan/weekend trip, seasonal customs)
- [ ] `CaptionWords.segments` segments spaceless Japanese text into tappable words while preserving every source character
- [ ] `SentenceChunker` splits CJK full-width sentence endings (`。`, `！`, `？`, plus closing `」`/`』`/`）`) without requiring trailing whitespace so streamed Japanese and Chinese sentences speak immediately
- [ ] Unit tests cover registry validation, theme overrides, Japanese word-tap segmentation, CJK streamed sentence chunking, speech redirects, archive round-trips, and vocabulary isolation from Mandarin (`zh`)

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/14-japanese-language-module`, branch `ticket/14-japanese-language-module`, base `feature/on-device-model` @ 76bd804.
