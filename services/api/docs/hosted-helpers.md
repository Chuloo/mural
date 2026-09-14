# Hosted teaching helpers

`HostedHelpers` is an experimental gateway for an explicitly allowed account's minute-funded voice session. It accepts the Android teaching prompts transiently, fixes the model and tools, reserves provider funding, then returns a normalized result. Public hosted activation and final consumer prices remain pending.

`main.ts` connects the gateway to `OpenAIHostedResponses` and `POST /v1/live/sessions/:id/helpers`. It starts only when `HOSTED_HELPERS_EXPERIMENTAL=true`, minute billing is selected, and `HOSTED_HELPER_BUDGET_PER_MINUTE_NANO` supplies a positive reviewed cost allowance. Voice and helpers use the same account allowlist and aggregate cap. Search defaults to disabled. `/v1/live/capabilities` advertises hosted minutes only when both services allow the authenticated account. Cleanup releases unused helper funding every 15 minutes; the request window still expires on time between cleanup runs.

`GET /v1/live/sessions/current` returns the authenticated account's unresolved session or `null`. Clients use this after losing a create response, then close that original session. They must not start a replacement while its usage remains unresolved. Signing out also records a durable server close request before revoking credentials.

## Interface

`new HostedHelpers(db, transport, config).request(account, sessionID, body)` accepts an authenticated account ID and an owned session ID. The account ID must come from server authentication, including an authenticated guest principal where enabled. A dollar-funded session, deleted account, released minute reservation or unresolved purchase reversal cannot authorize a helper call.

| Request field | Contract |
| --- | --- |
| `requestID` | UUID v4; identifies one provider attempt globally |
| `purpose` | `meaning`, `assessment`, `lookup`, `delegation`, `typed_reply`, `topic` or `help` |
| `instructions` | Nonempty UTF-8 text, at most 16 KiB |
| `input` | Nonempty UTF-8 text, at most 24 KiB |
| `schema` | Required for assessment, otherwise absent; at most 12 KiB and limited to the Android assessment schema subset |
| `search` | Optional Boolean; true only for delegation or topic requests |

The body limit is 64 KiB. Unknown fields, files, custom tools, model overrides, remote schema references and malformed Unicode are rejected. No caller-provided billing usage is accepted. Prompts are client-supplied teaching content, so a purpose label does not prove that arbitrary text is educational; the allowlist and funding limits remain necessary.

The normalized result contains `requestID`, `text`, `sources`, `usage`, `costNanoUSD` and `rateVersion`. Usage has `inputTokens`, `cachedInputTokens`, `cacheWriteTokens`, `outputTokens` and `searchCalls`. Sources contain a title and an HTTPS URL; the gateway does not fetch them. Financial fields support internal accounting and need not appear in the learner's minute-based pricing interface.

During an active call, all configured purposes are eligible. After a confirmed close, only meaning, lookup and assessment remain available for the session's original post-conversation window, at most two minutes. A session's deadline also bounds that window. Topic creation before a funded voice session is not supported by this gateway.

## Provider contract

`HostedResponsesTransport.send(body, signal)` performs exactly one bounded Responses request and honors the abort signal. Its adapter must use the fixed OpenAI HTTPS endpoint, keep credentials server-side, limit the response to 1 MiB and disable automatic retries. Provider error bodies must not reach logs or clients.

The gateway fixes `gpt-5.6-luna`, standard service tier, low reasoning effort, `store: false`, background and streaming off, and a maximum of 1,400 output tokens or 2,200 for assessment. Explicit-only caching is selected with no cache breakpoints. Search uses only the built-in `web_search` tool, low context and `max_tool_calls: 1`. Output-token limits include reasoning tokens. [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create), [prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)

Only provider-returned usage can settle a reservation. Cost calculations include cache reads/writes, the published long-context multiplier and search calls. The reserved maximum prices every potential input token as a cache write, then adds output and search allowances. The configurable search reserve is at least 1,050,000 input tokens; a search can therefore need more than $0.53 of temporarily available helper funding even when its eventual cost is much lower. A modest session budget may intentionally reject search. This reserve is conservative engineering policy, not a provider guarantee about aggregate tool billing. [Luna model and prices](https://developers.openai.com/api/docs/models/gpt-5.6-luna)

## Funding and configuration

All configuration is explicit: account allowlist, aggregate funding cap, helper budget per reserved minute, requests per minute, searches per session, session/global concurrency, post-conversation window, input framing allowance, search input allowance and provider timeout. There is no launch-price default.

Migrations 012 and 014 store the session's budget, charging policy and limits as immutable snapshots. Per-call records contain IDs, purpose, limits, timestamps and trusted billing totals. They never contain instructions, input, output, schema, transcripts or hashes of that content. Helpers consume Mural's reserved funding; they do not debit extra learner minutes.

Voice and helper admission must share the `mural-hosted-funding-cap` transaction lock and include both voice exposure and `hostedHelperExposure(sql)` in their aggregate check. Helper admission alone cannot enforce a cap on a voice path that omits helper liabilities. Welcome-grant reserves, pricing, refunds, daily funding controls and provider invoice reconciliation still need a joint review before public activation.

`reserveSessionBudget(sql, account, sessionID)` reserves helper funding inside the voice-admission transaction, after its minute-funded session row exists and before provider creation. A transaction rollback removes that allocation too. The first `creating` → `active` session transition updates only the helper expiry to the actual voice deadline plus the original post-conversation window. Its budget and limits do not change. Later deadline edits cannot extend the helper window. The `available` getter and `allows(account)` support capability gating; they indicate configured availability, not provider reachability.

Admission reserves the full helper budget for the available conversation time. For new sessions, an individual request can use only the allowance earned by `min(reservedMilliseconds, max(15000, authoritativeObservedMilliseconds))`. Its accumulated settled costs and unknown/pending holds must fit `floor(earnedMilliseconds * rateNanoPerMinute / 60000)`. Request-count limits grow with that same time. A new gateway or short restart cannot authorize teaching costs against the full remaining ten-minute balance.

The request-count default is 24 per earned minute, shared across meanings, typed replies and other helper purposes. This changes the count ceiling only; the earned dollar allowance, search ceiling and concurrency limits remain unchanged. Deployments with an explicit `HOSTED_HELPER_REQUESTS_PER_MINUTE=6` must change that setting to use 24 for new sessions. Existing sessions retain their immutable count policy.

At trusted close, the final charged time replaces the active estimate. A database trigger immediately reduces future helper liability to that earned allowance while retaining all settled costs and unknown holds. This prevents overlapping post-conversation windows from reserving the same unconsumed minutes twice. `expireBudgets()` releases the unused earned allowance after the original window, preserving actual costs and unresolved holds. Configuration changes and restarts cannot extend the window or enlarge an earlier budget. Sessions created before migration 014 keep their original policy.

A final balance below 15 seconds earns helper funding only for that smaller balance. Very short conversations can therefore lack enough allowance for a large assessment or search request; the gateway returns a funding-limit error without calling the provider. A cancellation recorded before a durable provider-attempt marker releases the voice and helper reservations with zero charge. Once the marker exists, an uncertain creation retains its holds until trusted reconciliation.

## Failure and retry behavior

A duplicate ID returns `helper_request_already_attempted` (409), including when the first result was lost. Output is not persisted, so duplicate requests cannot replay it. A timeout or unknown provider response returns `helper_response_uncertain` (502) and keeps the maximum cost reserved. Neither server nor client may automatically retry with a new ID.

Uncertain requests occupy concurrency only until their recorded deadline: the configured timeout plus a five-second handoff margin, at most 65 seconds. Their financial holds remain. This avoids permanent concurrency exhaustion without assuming that an unknown provider bill is zero. Request-count limits and the remaining session budget still restrict later deliberate actions.

Session, concurrency and funding limits return distinct 429 codes. A closed window returns `helper_session_window_closed` (409). A known refusal or incomplete output settles valid returned usage before returning an error. Usage exceeding the reserved token/tool limits records the higher liability and blocks new helper admissions pending operator review. Runtime privileges cannot rewrite earlier attempts or enlarge session budgets; uncertain reconciliation requires separately reviewed operator evidence and tooling.

`helper_session_limit` includes `error.retryable`. When an active session can earn another request before its deadline, it also includes `error.retryAfterMilliseconds` (1,000–60,000) and a `Retry-After` header rounded up to whole seconds. The delay uses the next earned-count threshold minus actual observed voice time, with a one-second margin for observation lag. The initial 15-second allowance does not shorten that wait. Admission has made no provider attempt, request record or charge at this point, so the rejected request ID can be reused safely. A later attempt still checks authoritative time, dollar funding and concurrency.

Confirmed-close, search and final count ceilings return `retryable: false`, as does a remaining deadline too short for the advised wait. Other errors provide no permission to retry. Clients must require both the allowlisted `helper_session_limit` code and explicit `retryable: true`, then limit how often and how long they wait. Android Meaning retries only the latest subtitle revision, at most three times within 30 seconds; it cancels when the context changes. An uncertain provider response or an already-attempted ID must never enter this retry path.

The implementation has local PostgreSQL tests for ownership, immutable budgets, concurrent allocation, retries, deadlines, expiry, pricing and runtime permissions. No real provider calls, deployment or public pricing verification are represented by those tests.
