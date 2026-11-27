# On-Device Model: fully offline Mural conversations

Status: ready-for-agent

## Problem Statement

Mural today needs the internet for every conversation: voice Talk runs on a cloud live-speech provider (the learner's own OpenAI key or prepaid Mural Minutes), and every Helper — meaning subtitles, word lookup, typed replies, post-turn assessments — is a cloud API call. A learner with no connection has no Mural, and a learner without an API key or minutes has no Mural at all. The learner wants to practise on a plane, on metro, abroad without data, or simply without paying for cloud AI — and wants the same warm conversation experience, the same learning records, on their own phone.

Additionally, the learner's target languages include European Portuguese (a variety Mural does not offer), and two heritage languages — Roman Konkani and Jamaican Patois — for which no speech recognition or text-to-speech voice exists on any provider, cloud or local.

## Solution

Add **On-Device Model** as a first-class third **Conversation Provider** alongside Personal Key and Mural Minutes, chosen explicitly in Settings > Advanced. While it is active, the entire conversation runs on the learner's phone: a downloaded open-weight LLM (default Gemma 4 E4B via LiteRT-LM, or the system Gemini Nano via AICore where available) drives voice Talk and every Helper. Sessions are strictly offline — no network call is made — and web-dependent requests degrade gracefully using each language's existing unavailable-reply. Learning records, assessments, flashcards and iPhone-compatible JSON backups work identically, with the session's provider recorded as provenance.

Voice Talk stays turn-based exactly as the app behaves today: the learner speaks (the model's native audio input, with pluggable dedicated speech recognition as an alternate), the model streams its reply, and the phone's platform text-to-speech speaks it in sentences. On-device sessions are timed by default using the existing conversation-length preference, with an untimed option; they never consume minutes. Three new **Language Modules** join the registry: Portuguese (Portugal) with full voice, and Roman Konkani and Jamaican Patois as **Typed-only Modules** — typed conversation on every provider, honestly, because no voice exists for these languages anywhere.

## User Stories

1. As a learner, I want to choose the On-Device Model as my conversation provider in Settings > Advanced, so that I can practise without an internet connection.
2. As a learner, I want the provider choice to be explicit and sticky, so that an unavailable provider never silently switches me to another one that might cost money.
3. As a learner, I want a curated list of downloadable on-device models in Settings > Advanced, so that I can pick one without researching model formats.
4. As a learner, I want Gemma 4 E4B offered as the default model, so that I get the best conversation quality on my capable phone.
5. As a learner, I want lighter models (Gemma 4 E2B, Qwen2.5-1.5B) listed alongside the default, so that I can trade quality for speed or memory.
6. As a learner, I want to import my own `.litertlm` model file or paste a model URL, so that I can use models nobody curated for me.
7. As a learner, I want models to download in-app with progress indication, so that I know whether to keep waiting or come back later.
8. As a learner, I want to delete downloaded models from the same screen, so that I can reclaim storage.
9. As a learner, I want the system model (Gemini Nano via AICore) offered as a zero-download option when my device supports it, so that I can try on-device mode before a multi-gigabyte download.
10. As a learner, I want one global active model for all my languages, so that the settings stay simple and memory use is predictable.
11. As a learner, I want a voice conversation entirely on my phone, so that I can practise anywhere without connectivity.
12. As a learner, I want my speech understood via the model's native audio input, so that pronunciation and tone reach the model directly.
13. As a learner whose language's audio input quality disappoints, I want to switch to a dedicated speech recognition model (such as Parakeet) in Advanced, so that captions of my speech are accurate.
14. As a learner, I want the teacher's voice to use my phone's built-in text-to-speech by default, so that the first on-device conversation works with zero extra downloads.
15. As a learner, I want to opt into higher-quality open-weight voices (Kokoro, PocketTTS) per language in Advanced, so that I can upgrade the teacher's voice when I want.
16. As a learner, I want the conversation to stay turn-based exactly like today (speak, listen, check-ins, idle end), so that on-device Talk feels like the Mural I already know.
17. As a learner, I want streamed replies spoken sentence by sentence, so that the teacher feels responsive instead of freezing while a long reply generates.
18. As a learner, I want meaning subtitles generated on my phone, so that translation help works offline too.
19. As a learner, I want word lookups explained on my phone, so that vocabulary help works offline.
20. As a learner, I want to type my side of the conversation when speaking is inconvenient, so that typed replies work offline as online.
21. As a learner, I want my conversations assessed by the on-device model, so that flashcards, word strength levels and learner goals keep updating offline.
22. As a learner, I want assessment evidence tagged with the provider that produced it, so that weaker on-device judgments can be told apart from cloud judgments without losing data.
23. As a learner, I want a request for current facts to be answered honestly offline, so that the teacher uses my language's existing "couldn't check that just now" reply instead of inventing news.
24. As a learner, I want the "current topic" conversation theme to be unavailable or adapt offline, so that I am never promised a web search that cannot happen.
25. As a learner, I want on-device sessions timed by the same conversation-length preference as today, so that my routine (15-minute practice) is unchanged.
26. As a learner, I want an "untimed on-device conversations" toggle in Advanced, so that long practice sessions are not cut off when I want to keep going.
27. As a learner, I want a timed on-device session to end exactly like today — same limit reason, same report sheet, same Continue/New choice, so that the flow feels identical.
28. As a learner, I want untimed sessions to end only through natural conversation limits (going quiet, ending manually), so that nothing else interrupts them.
29. As a learner, I want on-device sessions to never consume or display minutes, so that there is no billing confusion for AI my phone generates for free.
30. As a learner, I want my learning records, sessions, flashcards and backups to be identical in format whether a conversation ran on-device or in the cloud, so that my data is portable and never split.
31. As a learner, I want my iPhone-compatible JSON backup to include on-device sessions, so that my history survives device moves.
32. As a learner, I want to resume or continue a conversation that ended mid-session, so that a low-memory interruption does not lose my practice.
33. As a learner of European Portuguese, I want it as its own language distinct from Brazilian Portuguese, so that pronunciation guidance, vocabulary and themes fit Portugal.
34. As a learner of Brazilian Portuguese, I want my existing module untouched, so that nothing changes in how I practise today.
35. As a learner of Roman Konkani, I want it as a learning language written in the Roman script only, so that I practise the script I actually use.
36. As a learner of Jamaican Patois, I want it as a learning language with its own orthography and character, so that it is treated as a language, not "broken English".
37. As a learner of a Typed-only Module, I want typed conversations on every provider, so that I can still converse, look up words, be assessed and build flashcards even though no voice exists for my language.
38. As a learner of a Typed-only Module, I want the teaching prompts to accept code-switching with English (and for Konkani, Hindi/Marathi), so that valid borrowings are not marked as errors.
39. As a learner, I want English meaning subtitles for all my languages, so that comprehension support stays consistent.
40. As a learner, I want the on-device choice to live only in Settings > Advanced with the app's existing native patterns, so that Talk's minimal home screen stays unchanged.
41. As a learner whose phone is mid-download of a model, I want to be told when the provider cannot start a session yet, so that I understand why Talk is waiting.
42. As a learner on an older or low-memory phone, I want the on-device provider hidden or explained rather than broken, so that the app fails gracefully.
43. As the developer, I want both local runtimes (LiteRT-LM and AICore) behind one interface, so that adding models or runtimes later does not fork the provider logic.
44. As the developer, I want on-device inference to route through the same Helper and live-session interfaces as the cloud providers, so that a future Custom Service (such as OpenRouter) slots in as just another provider without new seams.
45. As the developer, I want the app's compatibility floor unchanged, so that the branch merges back without rework and existing installs keep updating.

## Implementation Decisions

- **Provider model**: `On-Device Model` is a third explicit **Conversation Provider** value. It inherits the existing never-silently-substituted rule: provider readiness, selection and provenance reuse the existing provider store and policy functions. An unavailable on-device provider (no model downloaded, still initializing) never falls back to Personal Key or Mural Minutes.
- **Strict offline guarantee**: while On-Device Model is the active provider of a session, no network call is made. Web-dependent features degrade through each language's existing `lookupUnavailableReply` and by treating the "current topic" theme's web-search path as unavailable. The delegation Helper is not offered offline.
- **Dual Model Runtime**: both LiteRT-LM (`.litertlm` model files) and AICore (system Gemini Nano) are supported behind a single new **Model Runtime** interface — the only new seam in this feature. Runtime selection follows the chosen model; AICore appears only when the device reports it available and Android meets its API-33 requirement. The app's `minSdk` stays unchanged.
- **Existing seams reused**: Helpers run through the existing `TeachingClient` interface; voice sessions through the existing `LiveSessionProvider` interface, so the ViewModel, Talk UI and session lifecycle are untouched by which provider is active.
- **Model catalogue**: Settings > Advanced shows a curated, downloadable list — Gemma 4 E4B (default), Gemma 4 E2B, Qwen2.5-1.5B — plus an import row accepting a local `.litertlm` file or a direct URL, and the zero-download AICore option when available. One global active model. Downloads show progress and are deletable.
- **Voice pipeline**: turn-based, matching the existing conversation state machine. Learner speech defaults to the active model's native audio input; a dedicated speech recognition model (e.g. Parakeet, which covers European Portuguese) is a pluggable alternate in Advanced. Teacher speech defaults to the Android platform text-to-speech; open-weight voices (Kokoro, PocketTTS) are opt-in picks. Model output streams into sentence-chunked speech so replies start speaking before generation finishes.
- **Timed by default, untimed opt-in**: on-device sessions use the existing per-learner conversation-length preference and the identical end-of-session flow ("Time limit" reason, report sheet, Continue/New choice). A single "untimed on-device conversations" toggle in Advanced (default off) makes sessions end only via the existing idle and manual-end rules. No minutes are consumed, displayed or required for any on-device session.
- **Assessments**: the assessment Helper runs on-device with the strict JSON schema plus defensive parsing and a bounded retry (schema attempt, then one unconstrained attempt), following the pattern the reference app uses for constrained decoding. Evidence keeps provider provenance so Learning Engine records distinguish on-device judgments.
- **New Language Modules**: Portuguese (Portugal) joins the registry with European pronunciation guidance, vocabulary and localized themes, following the established add-language pattern; the existing Portuguese (Brazil) module is untouched. Roman Konkani and Jamaican Patois join as **Typed-only Modules**: Roman script only for Konkani; teaching guidance tolerates code-switching with English (and Hindi/Marathi for Konkani) without treating borrowings as errors; conversations are typed on every provider. No voice input or voice output is offered for Typed-only Modules.
- **Records and backups**: sessions, fragments, assessments, flashcards and the JSON backup format are unchanged; on-device sessions carry their provider as provenance exactly like hosted sessions do today.
- **UI placement**: everything user-facing lives in Settings > Advanced using existing components and the orange primary action. Talk's home screen, orb and sheets gain no new stages; provider unavailability surfaces through the existing provider-issue messaging.
- **No server dependency**: this feature changes no API contract and requires no server deployment; the handover must state that explicitly.
- **Deferred**: a future **Custom Service** provider (e.g. OpenRouter) will slot in as another Conversation Provider over the same seams — no plugin abstraction is built now. Barge-in interruption and a Hugging Face browse UI are also deferred.

## Testing Decisions

Good tests observe external behavior through the seams, not implementation internals: given a provider selection and a scripted model, assert what the learner experiences (streamed text, spoken sentences, end reasons, records written) rather than how engines were invoked.

- **Model Runtime (new seam — the only one)**: JVM unit tests fake the runtime to return canned token streams and schema-valid/invalid JSON, verifying that Helpers, defensive assessment parsing and retry behavior respond correctly without real models.
- **TeachingClient (existing seam)**: on-device helper behavior — prompts per Helper purpose, meaning streaming, lookup replies, unavailable-topic degradation — is tested against the fake runtime the same way the cloud client is tested against a mock web server today.
- **LiveSessionProvider (existing seam)**: voice session lifecycle — creation, close, timed end reasons, untimed idle-only end — is tested as the existing providers are.
- **Provider policy (pure functions)**: readiness, selection persistence, timed/untimed switching, provenance and minute-accounting exclusion are plain JVM unit tests alongside the existing provider policy tests.
- **Language Modules (existing fixtures)**: Portuguese (Portugal), Roman Konkani and Jamaican Patois content flows through the shared cross-platform fixtures (language text cases, greetings, theme sets) and the Android language registry validation, the same way every existing module is covered.
- **Real hardware**: true engine behavior — E4B memory headroom, native audio input quality for French/Italian/Portuguese, AICore availability — is verified on the learner's Pixel 9 through the existing device-verification build type, not simulated in CI.

Prior art: the existing JVM suite for core policy and caption logic, the mock-server network tests, the cross-platform fixture tests, and the instrumented interface-test build types.

## Out of Scope

- Barge-in (interrupting the teacher mid-speech) — later milestone.
- A Hugging Face browse/search UI — curated list plus URL/file import only.
- Custom Service providers (OpenRouter or any learner-chosen cloud endpoint) — deferred; the seams accommodate it.
- Voice input or output for Roman Konkani and Jamaican Patois — no voice exists on any platform today.
- Any change to Personal Key or Mural Minutes behavior, pricing, or the hosted backend.
- Raising the app's minimum Android version; supporting phones below the on-device hardware floor (the provider is hidden or explained there, not fixed).
- iOS on-device support — Android first; the record formats stay compatible so nothing blocks a later iPhone port.
- Model fine-tuning, quantization work, or building `.litertlm` conversion tooling — downloading/importing existing models only.

## Further Notes

- Glossary terms for this feature live in `GLOSSARY.md` (Conversation Provider, On-Device Model, Model Runtime, Typed-only Module, Untimed Conversation, Custom Service, Session Provenance, Language Module) and should be used verbatim in issue titles, code names and test names.
- Consciously accepted risks: Gemma 4 E4B sits at the 12 GB RAM floor of the reference device (mitigated by platform TTS and native audio input keeping other models out of RAM; E2B is one tap away); native audio input language coverage is unverified until the first on-device run (dedicated speech recognition is the escape hatch); on-device assessment quality is weaker than cloud (provenance tag plus defensive parsing contain the impact); Konkani and Patois model quality is inherently limited (typed-only scope is set accordingly).
- Design history: this spec synthesizes a five-round design session (scope → runtimes/models → languages → sessions → timing) recorded in `GLOSSARY.md`. The reference implementation studied is Google AI Edge Gallery (LiteRT-LM runtime, dual-runtime pattern, streamed sentence-chunked TTS, two-pass schema extraction).
- Milestones: (1) local Helpers + provider plumbing + Advanced settings + Portuguese (Portugal) module; (2) local voice Talk; (3) Typed-only Modules (Roman Konkani, Jamaican Patois). Each milestone is independently usable.
