# Review of five Android PR CodeQL alerts

Reviewed current workspace source on 13 September 2026 for draft PR #17. These five reported sites are false positives for the stated command-injection, missing-rate-limit and SQL-injection rules. This review does not certify the entire application or alter any GitHub alert disposition.

## Capture command

**Site:** `scripts/capture_android_design.py:23`, uncontrolled command line.

The only command argument taken from the command line is `args.serial`. Lines 14–15 require the literal `emulator-` prefix and a nonempty suffix satisfying `isdigit()`. Line 23 passes it as a single list element immediately after adb's `-s`; `subprocess.check_output` does not enable a shell. It cannot become a separate `-H`, `-P`, `shell` or Gradle option. Whitespace, shell separators, quotes, newlines and additional option text fail the suffix check.

Python's `isdigit()` accepts some non-ASCII digits. Such a value can produce an unknown adb serial, but cannot introduce a command or option. An ASCII-only serial check would make the accepted format more precise; it is not needed to close an injection path demonstrated here.

The Gradle executable is resolved from this script's repository location, and both task names are fixed at line 27. The adb executable is selected from the local developer's SDK environment or PATH at lines 16–17. Those are trusted execution-environment inputs, not values received by an app or API. The local operator can already choose which SDK executable to run. Device shell and `exec-out` subcommands at lines 30–36 contain fixed arguments and fixed screenshot names. `--output` only selects where five fixed PNG filenames are written; it is not passed to adb or Gradle.

## Minute routes

The API installs one Fastify `onRequest` hook before registering its routes (`services/api/src/app.ts:43–76`). The hook uses the matched route name, including for encoded spellings, at line 46. The reported handler bodies do not need to repeat the limiter.

| Reported site | Applied control | Result |
| --- | --- | --- |
| `app.ts:142`, `GET /v1/minutes` | Falls through to the bounded in-memory network window at lines 60–75. The 121st request in a 60-second window returns 429. The map rejects new buckets when 20,000 entries are retained. Authentication follows admission | Rate limiting exists; it is per process and resets with that process, not a durable or fleet-wide quota |
| `app.ts:145–149`, `POST /v1/minutes/link-guest` | Explicitly included in `accountPaths` at line 17. Lines 47–56 invoke `AuthAdmission.enter('account', ...)` before the handler | Durable account-operation quota: 600 requests per network per UTC hour and 20,000 globally per UTC hour |
| `app.ts:150–153`, `POST /v1/minutes/welcome` | Same `accountPaths` membership and durable admission path | Same shared account-operation quota; unavailable account configuration fails closed with 503 |

`services/api/src/auth-admission.ts:21–44` verifies the trusted network identity and atomically updates PostgreSQL counters. Exceeding a quota throws `rate_limit` with HTTP 429. The calling hook adds `Retry-After: 3600`. These two minute mutations share the account-operation allowance with other account routes; they do not each receive a separate 600-request quota.

The default limiter uses the socket-derived `request.ip` with Fastify `trustProxy: false`. When Mural proxy configuration exists, `app.ts:61–66` requires `trustedClientNetwork`; `services/api/src/access-requests.ts:66–73` validates the proxy token before accepting the client-address header. An arbitrary `X-Forwarded-For` does not select a fresh bucket.

Existing tests cover durable admission across instances, spoofed proxy headers, global limits, encoded routes and the default 120-request limiter (`services/api/tests/accounts.test.ts:190–205`, `220–269`). They were inspected for this review; this read-only audit did not rerun or change them. The reported minute routes are governed by those same hooks.

## Restricted-role database test

**Site:** `services/api/tests/minute-runtime.test.ts:24`, SQL query built from a source.

The SQL text comes from a fixed repository file, `../operations/minute-runtime-grants.sql`, resolved relative to `import.meta.url` at line 23. The substituted role is `runtime_test_` followed by `randomUUID().replaceAll('-', '')`, constructed locally at line 17. It contains a constant identifier prefix and generated hexadecimal characters. No HTTP field, user-selected file path, database content or environment string enters that SQL text.

`TEST_DATABASE_URL` controls the isolated test connection, not the interpolated role or schema. The suite checks that the database path ends in `_test` at lines 13–14. This is test-runner code that deliberately exercises migration-owner grants; it is not imported into the API request path. A contributor changing the checked-in SQL file is changing executable test code, rather than injecting data across a runtime trust boundary. The alert does not establish SQL injection at this site.
