# 16: Patois approximate teacher voice

**What to build:** An opt-in Approximate Voice for Jamaican Patois: when enabled, the teacher's typed replies in a Patois conversation are also read aloud by the phone's platform text-to-speech using its English (Jamaica) voice — an English voice reading Patois, stated plainly in the UI. The learner's side stays typed on every provider: the microphone never appears, and Roman Konkani is unaffected. Demoable: toggle on, hold a Patois conversation, hear the teacher speak while you type.

**Blocked by:** 07 (Local voice Talk — done), 12 (Jamaican Patois Typed-only Module — done)

**Status:** ready-for-agent

- [ ] An opt-in toggle appears in Settings > Advanced for Jamaican Patois only (default off), persists, and is never offered for any other module
- [ ] When on, teacher replies in Patois conversations are spoken through the platform text-to-speech, streamed sentence-by-sentence through the existing voice pipeline, on every provider including On-Device Model
- [ ] Voice selection prefers the platform's English (Jamaica) voice and falls back to another English voice when it is absent, without error
- [ ] The settings row and the conversation UI state the approximation plainly (the teacher is read by a Jamaican English voice)
- [ ] The microphone is never offered in Patois on any provider, with or without the toggle; learner input remains typed
- [ ] Roman Konkani behaviour is unchanged (no Approximate Voice offered)
- [ ] Unit tests cover the toggle policy (Patois only), voice preference and fallback; the instrumented typed-only suite gains the spoken-teacher case
