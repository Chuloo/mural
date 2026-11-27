# 10: Opt-in open-weight voices

**What to build:** Better teacher voices as a deliberate per-language upgrade: open-weight text-to-speech models (Kokoro 82M, PocketTTS 100M) downloadable from Advanced and selectable per learning language, with the platform text-to-speech remaining the default everywhere. A chosen voice that is missing or fails falls back to the platform voice without interrupting the session. Demoable: pick Kokoro for Portuguese, hear a noticeably better teacher voice offline.

**Blocked by:** 07 (Local voice Talk)

**Status:** ready-for-agent

- [ ] Kokoro 82M and PocketTTS 100M can be downloaded and deleted through the same Advanced catalogue experience
- [ ] The learner picks the teacher voice per learning language; platform text-to-speech remains the default until picked
- [ ] The choice persists and applies to on-device voice sessions
- [ ] A missing or failing chosen voice falls back to the platform voice mid-session without error or silence
- [ ] The picker shows which languages each voice covers (Kokoro: Portuguese, French, Italian, Mandarin, others; PocketTTS: French, Italian, Portuguese, German, Dutch, English)
- [ ] Voice selection and fallback are unit tested at the existing seams
