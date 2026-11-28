# 15: Mainland Mandarin on-device voice, captions and TTS wiring (Android)

**What to build:** Complete on-device support for the existing Mainland Mandarin (`普通话 · Mainland China`, `zh-CN`) Language Module across On-Device Model voice Talk, Helpers, captions/pinyin, and open-weight voices: streamed Mandarin sentences speak immediately on CJK punctuation (`。` `！` `？`), platform TTS and Kokoro 82M (`zh-CN` voices) resolve Mandarin cleanly with fallback, `<heard>` learner speech captions and pinyin reading toggles work on-device, and defensive on-device assessment extraction preserves Simplified Chinese lemmas and quotes cleanly into flashcards.

**Blocked by:** 07 (Local voice Talk), 10 (Opt-in open-weight voices), 14 (Japanese Language Module — introduces CJK `SentenceChunker`)

**Status:** ready-for-agent

- [ ] On-device voice sessions in Mainland Mandarin (`zh` / `zh-CN`) stream spoken sentences immediately at `。`/`！`/`？` boundaries and route `zh-CN` cleanly to platform TTS and Kokoro 82M (`zf_*` / `zm_*` voices)
- [ ] Kokoro 82M's voice picker lists and applies both Mainland Mandarin (`zh-CN`) and Japanese (`ja-JP`) voices per language, falling back to platform TTS if missing or failing
- [ ] Learner `<heard>` captions, `MandarinPinyin` reading toggle (`talk_pinyin_show`/`talk_pinyin_hide`), and `CaptionWords.segments` Han word lookup work end-to-end in on-device sessions
- [ ] On-device assessment extraction (`OnDeviceAssessmentExtractor` + `LearningEngine.validate`) preserves Simplified Chinese multi-character lemmas, quotes and observed forms into flashcards with On-Device Model provenance
- [ ] Unit tests verify Mandarin on-device live voice streaming, Kokoro `zh-CN`/`ja-JP` voice selection & fallback, pinyin/caption segmentation, and on-device Chinese assessment -> flashcard projection
