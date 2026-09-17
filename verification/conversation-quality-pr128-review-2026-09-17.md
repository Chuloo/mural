# PR #128 review — 17 September 2026

Reviewed the native caption, translation, teaching, saved-evidence, and typed-turn changes and the hosted helper streaming and final-usage changes against main at `60bd6d3`. The reviewed implementation is `e2a11d7`; this review adds focused HTTP regressions and corrects streaming header negotiation.

## Security alert #59

CodeQL analysis `1793363643` flags `services/api/src/app.ts:393–429` for `js/missing-rate-limiting`. The authorization call is real, but the claimed missing limit is a false positive: the global Fastify `onRequest` hook runs before the route and enforces the shared 120-request/60-second network window. The helper route is not exempt. Both JSON and streaming requests use that hook before authentication or funded helper admission. The streaming change occurs inside the already-limited handler.

The added `hosted-http.test.ts` regression sends 120 alternating JSON/SSE requests, including encoded route spellings, then confirms both formats receive JSON 429 responses without another authentication query or helper call. Changing `X-Forwarded-For` cannot reset the window. The test exercises direct socket identity and trusted-proxy identity, rejects a forged proxy token before authentication, and verifies a different authenticated client network has its own window. The test and TypeScript check pass.

This is a per-process network limit, not a durable fleet-wide quota. Funded helper admission separately limits concurrency, attempts, and available budget. The disposition does not disable scanning or change rate limits.

## Runtime review

Transcript assembly preserves word continuations. Saved assessments select legacy assembly only when the version is absent, preserving earlier evidence decisions. New translation cache keys keep repaired captions distinct from old translations.

Streaming parsers bound event, text, and total response sizes and require a completed terminal response. Hosted admission errors remain ordinary HTTP errors before any stream starts. A client disconnect does not restart the funded provider attempt; settlement occurs before the completion event. Android UI cancellation retains the underlying funded request and delivers usage once.

The provider close change ignores only `context_injection_incomplete`, allowing final usage to arrive after pending context is cancelled. Other provider errors retain conservative failure handling. Nine live post-deployment Android calls already verified normal closure without held test minutes.

## User-visible behavior and remaining limits

The changes preserve whole words, show translated meanings earlier, avoid translation text jumping back to its first word, and keep typed replies in conversation order. Teaching guidance is shorter and localized; asynchronous assessment notes are no longer injected into speech. Learning assessments remain saved and pace still adapts.

The device evidence supports merging these improvements, with conversation quality tracked as unfinished work. Final Norwegian correction cases passed 6/6; final explicit topic confirmation passed 7/8 languages and failed once in English. Earlier broader trials exposed occasional false corrections, an assumed detail, and learner-text repetition in the Android French typed flow. These are documented behavioral limitations, not a claim that every conversation-quality requirement is complete. The final Android wording was compiled and unit-tested, but its full live language matrix was not repeated.

Native and hosted tests, device coverage, timing samples, and rollback details are recorded in the PR description and the local verification report. The review also reproduced and fixed mixed-case streaming headers and zero-quality streaming opt-outs. Header regression cases fail before the fix and pass afterward. The matching backend update requires deployment with the existing configuration and a fresh encrypted backup before handover.

Header behavior follows the media-type case and quality-value rules in [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.1). Streaming remains explicit opt-in so wildcard clients retain the existing JSON contract.
