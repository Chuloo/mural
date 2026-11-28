# 18: Zero server telemetry — no automatic information egress

**What to build:** A verified guarantee that Mural sends no analytics, diagnostics, or any other information to any server unless the learner explicitly initiated the request. Today the app can phone home automatically in a few places (hosted readiness refreshes, error-reference diagnostics); On-Device Model sessions already promise zero network but nothing enforces it against future regressions. This ticket inventories every outbound call, removes or gates every automatic one, and locks the guarantee with tests. Demoable: with On-Device Model active — even with an API key saved, an account signed in, and minutes available — a full conversation makes zero network requests; with airplane mode off, nothing leaves the phone unprompted.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] A written egress inventory lands in the verification documentation: every outbound network path in the app, categorized as user-initiated functional (sign-in, minutes/purchases, hosted conversation setup) or automatic, with the automatic ones removed or gated — the AI report is no longer server traffic at all, since reports are now stored offline
- [ ] Automatic calls are removed or gated: hosted readiness refreshes and any startup/diagnostic probes run only while the learner has chosen a cloud provider for that action, never in the background on their own, and never while On-Device Model is the active provider
- [ ] Error-reference diagnostics sent to the server are removed or made strictly local; nothing about a failure — error codes included — leaves the phone unless the learner is using a provider that needs the request
- [ ] The AI report path becomes offline storage: tapping report saves the submission (report ID, language, reason, the bound and credential-scrubbed excerpt, consent version, date) to on-device storage only — the words are never transmitted to the server. The network report client is no longer used and is removed with its server route, the sheet confirms with language meaning "saved on this phone", and saved reports remain on the device and exportable with the existing backup tooling
- [ ] A unit test pins the On-Device guarantee: a scripted on-device conversation against fail-loud fake network clients asserts zero calls to any server client (conversation, helper, report, diagnostics) from session start to end, including with keys and accounts configured
- [ ] A dependency and merged-manifest audit confirms no analytics, crash-reporting or telemetry SDK is present, and the inventory records that the app stays free of them
- [ ] On-Device Model sessions in the existing instrumented suite extend to assert no network attempt occurs (airplane-mode style check, as in ticket 13's runbook)
- [ ] The inventory and this ticket's outcome are recorded in the verification documentation with the standard handover note (no server deployment required)

**Out of scope (do not remove):** user-initiated functional traffic the learner explicitly asked for — account sign-in, minute balance checks on demand, purchases, and hosted conversation setup while a cloud provider is chosen. The distinction is consent: the app may ask a server only when the learner's action requires that server, never on its own.
