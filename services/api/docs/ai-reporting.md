# AI-output reports: removed from the server, saved on the phone

Mural no longer accepts reported AI output. Since ticket 18 (zero server telemetry) the Android app
saves a report — report ID, learning language, reason, the bounded and credential-checked excerpt,
consent version and date — in the learner's own archive on the phone, and exports it with the
learner's backup. Nothing is transmitted, so this service has no report endpoint:

- `POST /v1/feedback/ai` and `GET /v1/feedback/capabilities` are removed. Both answer `404
  not_found` like any other unknown path (`tests/feedback-http.test.ts` pins this).
- The `AI_REPORTS_ENABLED`, `AI_REPORTS_HMAC_KEY`, `AI_REPORTS_PROXY_TOKEN` and
  `AI_REPORTS_ALLOW_LOCAL_LOOPBACK` settings are inert: nothing reads them. Remove them from the
  protected server configuration at the next deployment, together with the reverse proxy's
  report-path token rules.
- The error codes `invalid_ai_report`, `ai_report_contains_credential`, `ai_report_rate_limit` and
  `ai_reports_unavailable` are gone from `src/diagnostic-error-codes.ts`; they can no longer occur.

## What stays, and why

Reports stored before the removal keep the thirty-day expiry the published disclosure promised, so
`pruneAIReports` in `src/feedback.ts` and its startup and 15-minute tasks in `src/main.ts` remain
until the tables are empty. Migration `010_ai_feedback.sql` and
`operations/feedback-runtime-grants.sql` also stay: production has applied both, and deleting an
applied migration file would misrepresent the deployed schema.

`tests/feedback.test.ts` covers exactly that remainder — expiry cleanup and a runtime role that
cannot read stored report text — and needs `TEST_DATABASE_URL` pointing at a database whose name
ends in `_test`.

## Operator steps, in order

1. Deploy this revision. The app change needs nothing from the server, so an older app keeps
   working against it: a submission attempt from an app built before ticket 18 receives `404`, which
   that app already reports as "reporting unavailable".
2. Keep the retention task running for at least 30 days after the last accepted report, then confirm
   both tables are empty: `SELECT count(*) FROM ai_feedback_reports; SELECT count(*) FROM
   ai_feedback_limits;`.
3. Only then, as a separate authorized change, drop the tables and their grants in a new migration,
   and remove the two inert report settings and the proxy rules.
4. Update the published privacy text and Play's Data safety declaration: reported text no longer
   reaches Mural's server at all. `release/android/declarations.md` still describes the report
   admission counters that this revision removed.

Historical evidence of the activated feature and its verification is unchanged:
[September 14 production verification](../../../release/android/evidence/ai-reporting-live-2026-09-14.json)
and the [native Android v4 check](../../../release/android/evidence/native-reporting-2026-09-14-v4.json).
