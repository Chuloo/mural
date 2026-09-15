# Hosted voice startup recovery

A rejected startup must not leave a learner permanently waiting for a previous conversation. The provider adapter treats an explicit HTTP 4xx response, except 408, as a rejected create. The controller settles the reservation at zero, releases the helper allowance, and keeps rejection status and a sanitized request ID as evidence. Reusing the same idempotency key never creates another provider session; a new attempt requires a new key.

Transport failures, 408, 5xx, malformed successful responses and attachment failures remain uncertain. Those holds are not released automatically. Startup diagnostics contain only a failure stage, HTTP status and sanitized request ID. They contain no credentials, SDP, prompts, transcripts or provider error bodies.

## Operator-funded support adjustment

`recoverHostedStartup` is an operator-only function, with no HTTP route. It restores access after an old, unconfirmed minute-funded startup when there is no provider ID, observed usage or helper request. The operator must supply the account and session IDs, expected reserved milliseconds, actor and reason. The attempt must have a close request and an expired deadline. Expiry is an eligibility guard for manual review; it is not evidence of zero provider usage.

The adjustment releases the customer's reservation without changing their minute balance. It closes the customer-side session, leaves provider cost null, retains the original funding exposure, and writes an immutable `hosted_startup_recoveries` audit row with provider cost marked `unconfirmed`. It does not claim that the provider sent a final usage event. Repeated application is idempotent. Operator reconciliation of that provider liability remains outstanding.

The function must run through an operator database connection. Apply `operations/hosted-startup-recovery-runtime-grants.sql` after baseline grants; the API role must have no access to the audit table. Do not expose this operation through an app, public API, or client-supplied recovery flag.

## Deployment and verification

Apply migrations 019 and 020, then the updated hosted-helper grants and recovery audit restrictions. Deploy the matching API code before applying support recovery so a stale create result or worker snapshot cannot reopen a recovered session. Preserve current grant policy and payment gates.

PostgreSQL tests cover explicit rejections, unknown outcomes, all three funding modes, helper settlement, idempotency, database rollback, late provider callbacks and runtime permissions. Recovery tests also verify unchanged customer balance, retained unknown cost, immutable audit records and refusal of active or observed sessions.
