# 04: AICore runtime (Gemini Nano, zero-download option)

**What to build:** The second Model Runtime: the system-managed Gemini Nano served by AICore, offered in the model picker as a zero-download option when the device reports it available, gated to the Android version AICore requires. A learner on a supported phone can use on-device mode without any multi-gigabyte download. Demoable: on-device conversations run with no model download at all.

**Blocked by:** 03 (Curated model catalogue + in-app download)

**Status:** ready-for-agent

- [ ] AICore implements the Model Runtime seam alongside the LiteRT-LM adapter; providers cannot tell the two runtimes apart
- [ ] The AICore option appears in the picker only when the device reports availability, honoring its API-33 gate; other phones never see it
- [ ] Fully offline typed conversations run on AICore with zero model download
- [ ] When AICore reports unavailable mid-session or at selection, the existing provider-issue messaging explains it without switching providers
- [ ] Typed-reply behavior is unit tested against a fake AICore runtime through the same seam tests
