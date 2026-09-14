# Android PR security review — 14 September 2026

Reviewed all 37 open CodeQL findings reported for [PR #17](https://github.com/Chuloo/mural/pull/17) at `c8668df1dbc10d241c547400657ee82b69410e71`: three critical and 34 high. The Python and JavaScript SARIF data flows were retrieved from analyses `1770657374` and `1770658023`; this review uses their exact sources and sinks.

No production exploit was established by these 37 findings. Thirty-five are false positives for the reported rule, with evidence below. Two identify imprecise assertions in tests; those assertions have been strengthened. This is a review of the reported flows, not a claim that the whole application is vulnerability-free. No alerts were dismissed and no scanning configuration was weakened. The remote CodeQL gate remains failed until GitHub processes changes and reviewed alert dispositions.

All source line numbers below refer to the scanned commit. An alert number links to `https://github.com/Chuloo/mural/security/code-scanning/NUMBER`.

## Critical: local capture executables

| Alert | Reported location | Data-flow source | Classification |
| --- | --- | --- | --- |
| 15 | `scripts/capture_android_design.py:23` | Operator `ANDROID_HOME` / `ANDROID_SDK_ROOT` selects adb | False positive: trusted local tool configuration |
| 51 | `scripts/capture_android_play.py:31` | Same environment variables select adb | False positive: trusted local tool configuration |
| 52 | `scripts/capture_android_play.py:27` | Same environment variables select aapt2 | False positive: trusted local tool configuration |

These scripts run on a developer's machine, without elevated application privileges or an HTTP entry point. Choosing the installed SDK executable is an intended operator capability. An actor who controls that process environment already controls which local tools it runs. A malicious SDK installation is outside the trust boundary these scripts claim to enforce.

The subprocess calls use argument arrays and do not enable a host shell. The emulator identifier must have a literal `emulator-` prefix followed by digits; the Play capture script requires ASCII digits. The older design script accepts some Unicode digits, which can cause an unknown serial but cannot introduce whitespace, an adb option, or a second command. The serial is one argument after `-s`. Gradle task names are constants. Play APK paths are resolved to absolute paths before `aapt2 dump badging` and `adb install`, so a filename beginning with `-` cannot become a tool option. The script checks the isolated package names before installation. None of these command lines receives an app user's text, bearer token, or API request field.

## High: route limits already run before handlers

All 19 reported handlers execute after the global Fastify `onRequest` hook in `services/api/src/app.ts:65–107`. It uses `request.routeOptions.url`, so encoded spellings share the matched route's policy. The only exemptions are the health endpoint and two endpoints with their own durable admission; none is among these 19 alerts.

| Alerts | Scanned handler lines | Applied admission |
| --- | --- | --- |
| 18, 19 | 215, 220 — guest linking and welcome grant | Durable account-operation limit |
| 26, 27, 28, 29 | 242, 259, 264, 281 — create/status/verify/recover purchase | Durable account-operation limit |
| 40, 41 | 289, 302 — wallet and account deletion | Durable account-operation limit |
| 47, 48, 54 | 128, 131, 199 — readiness, pricing, minute balance | Shared per-network process limit |
| 42, 43 | 310, 317 — legacy checkout and signed Stripe webhook | Shared per-network process limit |
| 30, 31, 32, 33, 34, 35 | 326, 331, 344, 349, 353, 359 — live capability/create/status/current/close/helpers | Shared per-network process limit, followed by authenticated ownership and funded session admission |

The durable path is selected by `accountPaths` at lines 28–29 and invokes `AuthAdmission.enter('account', ...)` at lines 78–87. `auth-admission.ts:16–47` atomically enforces 600 account operations per network per UTC hour and 20,000 globally per hour. These are shared quotas, not a fresh allowance for each endpoint. Missing account admission fails closed. Exceeding the quota returns 429 with `Retry-After`.

The other routes use the 120-request, 60-second network window at lines 91–106. It rejects new buckets once the map reaches 20,000 entries. This control is per process and resets on restart; it is not described as a durable fleet-wide quota. When proxy configuration exists, the hook requires the trusted proxy token and hashes the verified network address. Arbitrary `X-Forwarded-For` cannot create a fresh bucket. Without proxy configuration it uses the socket-derived address; Fastify has `trustProxy: false`.

`tests/accounts.test.ts` already covers durable admission across instances, encoded routes, spoofed forwarding, global exhaustion and the 121st public-read request. This review inspected those tests. Their coverage supports the shared hook; it does not claim a new load test of every endpoint. The helper path also reserves funds atomically, bounds concurrency and rejects repeated request IDs before contacting the provider.

## High: trusted configuration, migrations and bounded helper timers

| Alert | Location | Evidence and classification |
| --- | --- | --- |
| 37 | `src/access-requests-admin.ts:9` | The CLI intentionally accepts an operator-selected absolute export destination. It has no server route. `open(path, 'wx', 0o600)` creates a new private file and refuses an existing target. This is not a request-controlled path traversal. |
| 20 | `src/main.ts:32` | The Apple private-key path comes only from the server operator's `APPLE_PRIVATE_KEY_PATH`, read at startup. It is not selected by an HTTP request. The application does not expose the file contents. False positive for request path injection. |
| 36 | `src/main.ts:18` | The flagged condition exits when `DATABASE_URL` is absent. Supplying the operator's database configuration is required to start the process; it does not bypass authentication. The SARIF source is `process.env`, not an app user. |
| 38 | `src/migrate.ts:15` | Migration SQL comes from the repository's fixed migration directory relative to `import.meta.url`. Filenames are enumerated there and restricted to `.sql`. Executing checked-in migration statements is the migration owner's intended operation. No HTTP body or user-selected path enters the SQL. |
| 25 | `src/hosted-helpers.ts:170` | SARIF taints `budget.timeout_ms` because it is read from PostgreSQL. It is an immutable server configuration snapshot: constructor validation requires 50–60,000 ms; migration 012 applies the same CHECK and an immutability trigger; runtime grants cannot update this field. The timer takes the smaller of this timeout and the reserved deadline, and is cleared in `finally`. Atomic per-session and global concurrency limits run before it. A caller cannot request an unbounded timer. |

## High: isolated test harnesses

| Alerts | Location | Evidence and classification |
| --- | --- | --- |
| 21, 22 | `tests/feedback-http.test.ts:47`, `tests/feedback.test.ts:22` | Execute the fixed checked-in `010_ai_feedback.sql` fixture in isolated generated schemas. No input is interpolated into the SQL file. False positives for SQL injection. |
| 23, 24, 16 | `tests/feedback.test.ts:150`, `tests/hosted-helpers.test.ts:322`, `tests/minute-runtime.test.ts:24` | Read fixed repository grant files. The substituted roles use constant prefixes plus locally generated UUID hex. The test database URL chooses the connection, not the SQL role or statement. False positives for SQL injection. |
| 53 | `tests/hosted-paid.test.ts:235` | Both grant filenames come from a two-item literal array. The replacement role is `paid_runtime_` plus locally generated UUID hex. This is permission testing under the migration-owner role, not production query construction from user data. |
| 46 | `tests/access-requests.test.ts:201` | Reads a test-created export in a fresh `mkdtemp` directory after checking its mode. That directory is private to the test process. The preceding `stat` is an assertion, not an access-control decision. Production export uses exclusive creation. No demonstrated exploitable check/use race. |
| 50 | `tests/public-minutes.test.ts:56` | A local Map stores synthetic provider callbacks keyed by locally generated session IDs. The database lookup selects one of those callbacks for the test's own fixture. No dynamic property on a prototype or application module is invoked. A missing key would fail the test, not dispatch arbitrary code. |

These test files check that `TEST_DATABASE_URL` points to a name ending in `_test`. That guard prevents accidental production use; the stronger reason the flagged SQL is safe is its fixed checked-in source and generated identifier provenance. The API request path does not import these test modules.

## Two test assertions strengthened

- Alert 44, `tests/access-requests.test.ts:94`: the substring check was a negative assertion that invalid responses did not leak a fixture email domain. It was never a URL allowlist. It now compares the complete response to `{ error: { code: 'invalid_access_request' } }`, proving the expected safe payload.
- Alert 45, `tests/minute-providers.test.ts:116`: the checkout URL regex had unescaped dots. The test now parses the URL and checks the exact `https://checkout.stripe.com` origin. Production `stripe-minute-provider.ts:118` already requires HTTPS, the exact hostname, no port and no user information, so the weak test did not expose a production redirect bypass.

The two modified API suites passed **33/33 tests**, with no skips, against a new local disposable `mural_codeql_review_test` database. No provider calls or live credentials were used. Log: `/private/tmp/mural-codeql-api-assertions-2026-09-14.log`.

## Android CI failure

The separate emulator failure was `PlayStoreCaptureTest.longSpanishReplyKeepsMeaningVisibleAndBothPassagesCanScroll`. The translation can fit completely at the CI device geometry, so requiring a positive scroll offset is incorrect. The revised assertion checks the complete text bounds when the scroll range is zero; otherwise it scrolls to the end and requires the final offset to reach the maximum. Independent target/meaning regions, visible first lines, fixed navigation and the microphone control remain asserted. No production layout or version changed for this correction.

The focused regression passed on the local API 36 ARM64 emulator in isolated user 12 at **1080 × 2400, density 420**, matching the CI Pixel 6 geometry. This was a local geometry reproduction, not a rerun of GitHub's x86_64 job. The display returned to its original 1080 × 2424 size and density 420, and user 12 remained active. Personal app data was untouched. Build and test logs: `/private/tmp/mural-codeql-ui-regression-build-2026-09-14.log` and `/private/tmp/mural-codeql-long-reply-pixel6-2026-09-14.log`.

## Scanner evidence

| Evidence | SHA-256 |
| --- | --- |
| Python SARIF, `/private/tmp/mural-codeql-python-pr17.sarif` | `bbf647a3d0e94438331a8b7f0111fc2c28131805923c4d1b014fc5c61609ce81` |
| JavaScript SARIF, `/private/tmp/mural-codeql-js-pr17.sarif` | `844f186354a3739bc0920b5eea48a6bd785841528fe8b366fb03b607f8568502` |

The 35 remaining alerts require a reviewer to accept this evidence before setting their individual GitHub dispositions. This document does not perform that action or predict the next scan's result.

## GitHub status after the fixes

At `d3910dc`, alerts 44 and 45 closed after their test assertions were corrected. The 35 remaining PR-scoped alerts are still open. The root review accepted the documented classifications, but automatic approval review rejected applying the bulk false-positive dispositions without explicit owner approval. No dismissal occurred and no scanning configuration changed. The aggregate CodeQL gate remains failed pending that disposition.
