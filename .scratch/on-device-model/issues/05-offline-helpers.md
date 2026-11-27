# 05: Offline Helper coverage

**What to build:** The rest of the Helper work comes on-device so an offline session is a complete Mural session: meaning subtitles stream from the local model, word lookups are explained locally, and post-turn assessments run on-device with the strict JSON schema, defensive parsing and one bounded retry. Web-dependent requests degrade honestly through each language's existing unavailable-reply, and the delegation Helper is never offered offline. Demoable: in airplane mode, meaning subtitles appear, lookups answer, and flashcards and learner goals keep updating.

**Blocked by:** 02 (On-Device Model provider + offline typed conversations)

**Status:** ready-for-agent

- [ ] Meaning subtitles stream on-device during on-device sessions, using the existing Helper seam
- [ ] Word lookup works offline; a request needing live web facts receives the language's existing couldn't-check-that reply instead of an invented answer
- [ ] The assessment Helper runs on-device: strict schema attempt first, defensive parsing of the reply, one unconstrained retry when the constrained attempt yields nothing usable, bounded output length
- [ ] Assessment evidence records On-Device Model provenance so Learning Engine records distinguish on-device judgments
- [ ] Flashcards, word strength and learner goals update from on-device sessions exactly as from cloud sessions
- [ ] The current-topic theme never promises a web search offline, and the delegation Helper is not offered in on-device sessions
- [ ] An airplane-mode session exercises every Helper successfully
- [ ] Helper behavior is unit tested against the scripted fake runtime for each Helper purpose, including schema-valid, schema-invalid and empty model replies
