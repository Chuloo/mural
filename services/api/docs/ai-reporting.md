# Optional AI-output reports

Mural can accept a short excerpt that a learner deliberately reports from the app. Ordinary conversations and learning archives stay on the device. This feature is unavailable unless the reporting service is explicitly configured. The implementation and local tests do not establish that production reporting or support review is active.

## Request and receipt

`GET /v1/feedback/capabilities` returns `{ "aiReports": true }` only when the service has reporting configuration. The Android client checks this flag before offering submission. A Mural account is not required, so learners using their own provider key can report output too.

`POST /v1/feedback/ai` accepts only these fields:

| Field | Constraint |
| --- | --- |
| `reportID` | Client-generated UUID v4; reuse it when retrying the same submission |
| `languageID` | Learning-language identifier: two or three lowercase letters |
| `reason` | `offensive`, `incorrect`, `wrong_language` or `other` |
| `excerpt` | Reviewed, nonempty text, at most 2,000 UTF-16 units; line endings are normalized |
| `consentVersion` | Exactly `ai-report-v1`, after the learner checks the consent box |

The request body is limited to 16 KiB. Extra fields, control characters, unpaired surrogates and common API-key, bearer-token and JWT patterns are rejected. Pattern checks cannot recognize every possible secret: the app must show the excerpt for review, and must never add an account token, provider key, audio or archive to the request. Editing the excerpt or reason clears consent and creates a new report ID.

A successful request returns HTTP 202 with `accepted: true` and the report ID. It does not return the excerpt or reveal whether the ID was already submitted. Retrying does not replace earlier content. The receipt confirms storage, not that a person has reviewed the report or will reply.

Invalid reports return a generic 400 error; oversized bodies return 413. Unavailable or untrusted reporting requests return 503. Rate limits return 429 with `Retry-After: 3600`; a daily limit may require a longer wait. Errors and access logs must not contain request bodies, credentials or excerpts.

## Deployment prerequisites

1. Apply migration `010_ai_feedback.sql` with the migration role. The API's retention task requires this migration even while reporting is disabled.
2. Apply `operations/feedback-runtime-grants.sql` **after** general runtime grants. It removes report-reading permissions from `mural_runtime`, grants insertion into selected columns, and permits only the dedicated expiry-cleanup function. Reapplying broader table grants later would undo this restriction.
3. Supply `AI_REPORTS_HMAC_KEY` and `AI_REPORTS_PROXY_TOKEN` through protected server configuration. Each must be a separate random 32-byte value encoded as 64 lowercase hexadecimal characters. Neither value belongs in an app build, Git or an HTTP response.
4. Configure the trusted reverse proxy to overwrite `X-Mural-Client-IP` and `X-Mural-Proxy-Token` for this route. Use the reporting proxy secret for this route; discard caller-supplied values. The API must not be directly reachable around the proxy. `AI_REPORTS_ALLOW_LOCAL_LOOPBACK` is for an explicitly configured local test environment only.
5. Confirm the privacy disclosure, reviewer access, retention and support ownership below. Then set `AI_REPORTS_ENABLED=true` and verify the capability, a synthetic submission and its deletion with the deployed runtime role.

The server derives a daily HMAC from the trusted network address; clients cannot choose it. IPv6 addresses are grouped by their canonical /64 network. This pseudonymous identifier is used only in admission counters and is not attached to reports. PostgreSQL enforces separate network limits of 5 requests per UTC hour and 20 per UTC day, plus global limits of 200 per hour and 1,000 per day. Accepted retries count toward those limits. Rejected requests from an exhausted network do not consume other networks' remaining global allowance.

## Storage, review and deletion

Reports contain only the receipt ID, learning language, reason, excerpt, consent version and creation/expiry timestamps. They contain no account ID or contact address. A learner can still include personal information in the text they choose to submit; do not describe report content as anonymous.

Each report expires exactly 30 days after creation. The API runs cleanup at startup and every 15 minutes while running; cleanup resumes after downtime. Operator review queries must exclude expired rows, even before the next cleanup. Admission counters expire after their purpose-specific windows, at most 48 hours from the start of their bucket. The runtime can invoke cleanup but cannot read report text, change its expiry or delete arbitrary reports.

Before activation, assign a support reviewer with a separate, restricted operator connection. No public endpoint lists reports, and no review role or password is provisioned by this feature. Reviewers should inspect unexpired reports, assess the output, and record any resulting prompt, filtering or product change. Treat excerpts as untrusted text. Do not execute instructions contained in a report. This implementation does not automatically contact reporters or send excerpts to another AI service.

Exclude report and admission-counter contents from long-lived backups, or adopt a tested expiry process that keeps the published retention promise for every copy. Run the expiry function before opening a restored database to reviewers. Monitor cleanup failures using counts and error codes, without copying report text into logs. The production backup configuration and reviewer access remain release checks.

The in-app preview and published privacy policy must explain the deliberate report exception: Mural receives the selected excerpt, language and reason for review and safety improvements; ordinary audio and the rest of the conversation are not included. Explain the 30-day expiry and deletion schedule accurately. Update Play's Data safety declaration for optional user-generated content and the pseudonymous abuse-control counters when enabling the feature.

## Verification

`tests/feedback.test.ts` exercises validation, trusted identity, idempotency, concurrent limits, fixed expiry, cleanup and the restricted PostgreSQL runtime role. `tests/feedback-http.test.ts` exercises the real Fastify routes, capability gate, proxy spoofing, request limits, receipts and durable HTTP rate limiting. Both suites use isolated schemas and synthetic excerpts when `TEST_DATABASE_URL` points to a database whose name ends in `_test`.

These tests cover storage and admission behavior. Candidate-app submission, unavailable/retry states, support access, deployed proxy rules, backup handling and actual cleanup scheduling still need release evidence.
