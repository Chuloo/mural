# Run Mural's commercial backend foundation

This backend prepares accounts, a credit ledger, and **Stripe sandbox** payments. Public funded conversations and free trials remain unavailable; live Stripe keys are rejected. A disabled, operator-allowlisted voice experiment now has a real network adapter and durable accounting, tested entirely against a local fake provider. The existing iPhone BYOK build continues to operate independently.

## Run locally

Use Node.js 22.19 or later, npm, and a separate PostgreSQL database. PostgreSQL 14 was used for the integration tests; the deployment configuration uses PostgreSQL 17.

From this directory:

```sh
npm ci
cp .env.example .env
```

Set `DATABASE_URL` in `.env` to a database reserved for Mural. Keep `.env` private and excluded from Git. The example password is only for a local development database.

Apply the schema and start the development server:

```sh
npm run migrate
npm run dev
```

Open `http://localhost:8080/healthz`. A successful response identifies this as `commercial-foundation` with `hostedVoice: false` and `livePayments: false`. `/readyz` also checks the database.

For a compiled local run:

```sh
npm run build
npm start
```

## Run tests

Run the unit and HTTP boundary tests with:

```sh
npm test
```

For the full suite, set `TEST_DATABASE_URL` to an **isolated test database whose name ends in `_test`**, then run `npm test`. The integration suite applies migrations and adds synthetic test accounts, purchases, and usage. It never invokes OpenAI, Google, Apple, or Stripe servers. Ledger tests retain synthetic rows; voice tests create and remove isolated PostgreSQL schemas.

```sh
TEST_DATABASE_URL=postgresql://mural_test@127.0.0.1:55434/mural_billing_test npm test
```

The URL above is an example for an already-running local test database. Do not use a production database. Without `TEST_DATABASE_URL`, PostgreSQL tests are explicitly skipped.

## Prepare a Hetzner deployment

Create a private `.env` on the server containing `POSTGRES_PASSWORD` and `API_DOMAIN`. Use a unique URL-safe database password, such as random hexadecimal text; do not reuse the development example. Point the API hostname to the server and allow incoming TCP 80/443 and UDP 443. Do not expose PostgreSQL publicly.

Validate and start the containers:

```sh
docker compose config --quiet
docker compose up --build -d
```

Compose runs the schema migration before the API starts. Caddy obtains HTTPS certificates. Database contents live in `postgres_data`; keep that volume when updating. Configure encrypted off-server database backups and test restoration before accepting money. Do not run `docker compose down --volumes` against retained data.

This starts the foundation only. It does not enable hosted voice or real purchases. Do not direct paying users to it yet.

## Configure identity and sandbox checkout

Set `GOOGLE_CLIENT_ID` to the exact audience issued to the native app. Request a nonce from `/v1/auth/challenge`, include that exact nonce in the Google OIDC authorization request, and send the resulting ID token to `/v1/auth/exchange`. Tokens without the expected nonce are rejected. The server does not accept an email address or client-declared account ID as authentication.

Apple sign-in requires `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, and `APPLE_PRIVATE_KEY_PATH`, pointing to a private Sign in with Apple `.p8` file. The adapter exchanges a fresh native authorization code, verifies the returned ID token against the authenticated account’s Apple subject, and revokes its refresh/access token. Provider tokens stay in memory. Supplying `APPLE_CLIENT_ID` alone does not enable Apple sign-in. The adapter has cryptographic tests using fake responses; real Apple configuration and device verification remain pending. Mount the private key read-only through a deployment override; never add it to the image or repository.

For payment testing, supply the `STRIPE_TEST_*` variables in `.env`. Only `sk_test_` secrets are accepted. Create one-time USD sandbox Prices whose totals equal AI value + 15% Mural fee + the configured payment fee. Configure the exact `price_…` IDs and payment-fee amounts in cents. The server retrieves the Price and verifies its amount, currency, and test mode before creating Checkout. Taxes and live processor fees are not calculated by this foundation.

Point a Stripe sandbox webhook to `/v1/webhooks/stripe`, with the webhook signing secret, for `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `charge.refunded`, and `charge.dispute.created`. The handler verifies the signature on the original bytes. Credit comes from a confirmed payment mapped to a server-created order, never the return URL or client metadata. Retries retrieve the stored Checkout session, including after Stripe’s idempotency-key retention window. An expired Checkout needs a new order; an uncertain create older than 23 hours needs reconciliation. A session whose database mapping fails is expired and its URL is never returned. Resolved disputes still require operator reconciliation; do not activate real payments before that workflow exists. Account deletion returns `409 unresolved_billing` while any pending checkout, balance, or reservation remains. New purchases and reservations lock the account against deletion, so a payment cannot be stranded on a deleted account. This temporary restriction requires a supported refund/expiry/deletion workflow before commercial release.

## Endpoint contracts

All monetary strings are integer **nanoUSD**: 1 USD = 1,000,000,000 nanoUSD. A display credit is $0.01 of AI usage. All responses disable caching. Authentication tokens expire after 24 hours; this foundation has no refresh-token endpoint. Sign in again after expiration.

| Endpoint | Request | Result |
| --- | --- | --- |
| `POST /v1/auth/challenge` | Empty JSON object | `challengeID`, `nonce`, `expiresInSeconds` |
| `POST /v1/auth/exchange` | `provider`, `idToken`, `challengeID` | `accountID`, `accessToken`, `expiresInSeconds` |
| `GET /v1/wallet` | Bearer token | `currency`, `balanceNanoUSD`, `reservedNanoUSD`, `availableNanoUSD` |
| `POST /v1/auth/sign-out` | Bearer token | Revokes this account's Mural sessions |
| `DELETE /v1/account` | Bearer token; Apple additionally needs a fresh authorization code | Removes identity/email/session data; retains required financial records under an opaque ID |
| `GET /v1/pricing` | None | Dated provider rates, money units, and separate-fee policy |
| `POST /v1/checkout` | Bearer token; `Idempotency-Key`; `product` = `ai-10-usd` or `ai-25-usd` | `checkoutURL`, `orderID`, `sandbox: true`, itemized `quote` |
| `POST /v1/webhooks/stripe` | Raw signed Stripe JSON | Atomic payment/refund journal update and duplicate receipt |
| `POST /v1/trial/eligibility` | Apple attestation proof | `503 trial_attestation_unavailable` until a verified adapter is configured |
| `POST /v1/live/sessions` | Bearer token; `Idempotency-Key`; `sdp`; `language` (`nb-NO`, `es-ES`, `en-US`, `fr-FR`, `zh-CN`) | Default `503`; allowlisted experiment returns `sessionID`, `providerSessionID`, `sdp`, `deadline`, `reservedNanoUSD`, `rateVersion`, `experimental` |
| `GET /v1/live/sessions/:id` | Bearer token belonging to the session owner | Minimal state, cumulative milliseconds, confirmed provider cost and customer debit |
| `POST /v1/live/sessions/:id/close` | Bearer token belonging to the session owner | Requests server closure; never accepts client-reported usage |

Error responses contain a safe `error.code` only. Request bodies, keys, ID tokens, bearer tokens, Stripe payloads, and conversations are not logged. Expired authentication records are pruned every 15 minutes. The basic in-memory rate limit does not trust forwarded client-IP headers; behind Caddy it applies conservatively to the proxy address. Configure and test a trusted-proxy policy before scaling it.

## Restricted voice experiment

The default remains `HOSTED_VOICE_EXPERIMENTAL=false`. The production Compose file deliberately does not forward hosted-provider credentials. To conduct a separately authorized engineering test, an operator must supply all of the following through a private deployment override:

- `HOSTED_VOICE_EXPERIMENTAL=true` and a dedicated `OPENAI_API_KEY`.
- Explicit existing account UUIDs in `HOSTED_VOICE_ACCOUNT_ALLOWLIST`; sandbox purchases never qualify a public user automatically.
- `HOSTED_VOICE_LIFETIME_CAP_NANO`, between $0.50 and $25 expressed in nanoUSD. It bounds admission against persisted lifetime exposure, including unresolved sessions. It does not reset on restart.

Each call reserves $0.50 of wallet value before one provider create, targets a 600-second maximum, and accepts only trusted sideband usage snapshots. It settles once after `session.closed`, including WebRTC’s 15-second initialization minimum, and releases the unused hold. Customer debit cannot exceed the reservation; observed provider overrun is recorded separately. Creation is never automatically retried. A duplicate offer key returns `409 live_request_already_created`; SDP is not retained for replay. Query the existing session’s status and close it before starting a new offer.

A PostgreSQL advisory lock permits one controller. On restart it reattaches saved provider IDs and requests closure. The watchdog checks deadlines and reversed funding, attempts graceful closure, and retries HTTP hangup. An uncertain creation, lost sideband, regressing final usage, or missing final event keeps the reservation unresolved and blocks new funding. No provider success is inferred from an HTTP hangup alone. There is no automated operator override that invents final usage.

The sideband adapter discards audio, transcripts, and session snapshots before the accounting callback. Only identifiers, duration, money, state, and fixed reason codes reach PostgreSQL. Live uses fixed client delegation; no Responses model or search tool is funded by this experiment. Hosted teacher analysis, subtitles, and research tools still need separate server budgets and implementation.

A 600-second wall-clock closure request is **not an absolute provider spending guarantee during a network partition**. OpenAI’s general guide describes hangup for Live sessions, while the fetched endpoint reference calls it a SIP operation. WebRTC hangup and terminal usage recovery must be confirmed with the provider and a real bounded test. No such paid call was made here. Keep public hosted voice off until those limits and reconciliation are verified.

## Container verification

An isolated smoke stack uses PostgreSQL 17, no Caddy, and an ephemeral localhost-only API port:

```sh
docker compose -p mural-foundation-smoke -f tests/compose.smoke.yaml up --build -d api
docker compose -p mural-foundation-smoke -f tests/compose.smoke.yaml run --rm tests
docker compose -p mural-foundation-smoke -f tests/compose.smoke.yaml port api 8080
```

Only this disposable test project may be removed with:

```sh
docker compose -p mural-foundation-smoke -f tests/compose.smoke.yaml down --volumes
```

## Complete before commercial activation

- Verify the implemented GPT-Live transport against a real bounded call, including WebRTC hangup support, recovery after provider finalization, unknown creation outcomes, process/database failure, and provider-level spending protection. Build operator reconciliation for incomplete sessions; fake-provider tests cannot establish the provider’s failure behavior.
- Implement App Attest and DeviceCheck verification and trial-claim persistence against Apple. `TrialAttestor` defaults to rejection. No environment switch grants free usage.
- Apply a global trial budget, a 600-second per-device allowance, output/search limits, and failure exposure limits. Confirm cutoff behavior with an untrusted client and failed control connections.
- Verify Apple authorization revocation on a real device; complete account deletion with unresolved purchases/balances, token refresh, identity linking, and device-loss recovery.
- Add StoreKit verified transactions, purchase refunds, and storefront routing before in-app sales. Stripe support alone is not an App Store payment implementation.
- Confirm the operator's merchant country, tax treatment, channel fees, retention periods, and live receipt details. Then implement live payment activation, taxes, dispute resolution, support tooling, and operator reconciliation.
- Separate migration and runtime database privileges; add financial backup/restore verification, operational alerts without content, and tested provider-level spending protection.

Local verification on 12 September 2026: TypeScript build and checks passed. **42 tests passed with no skips** on local PostgreSQL 14 and inside the built Node 22 container against PostgreSQL 17. They cover ledger races, Checkout retry/mapping/deletion races, signed raw webhooks, JWTs, Apple revocation, and HTTP/WebSocket voice metering and recovery. The container started with a read-only filesystem, dropped capabilities, and a localhost-only port; its database-readiness endpoint passed. Dependency audit reported zero known vulnerabilities. No paid provider calls or real payments were made.


## Provider contracts

The adapter follows [OpenAI WebRTC creation](https://developers.openai.com/api/docs/guides/voice-webrtc?api=live), [authenticated sideband controls](https://developers.openai.com/api/docs/guides/voice-server-controls?api=live), [cumulative and final usage](https://developers.openai.com/api/docs/guides/live-conversations#usage-and-graceful-close), and the [hangup endpoint](https://developers.openai.com/api/reference/typescript/resources/live/subresources/sessions/methods/hangup). The WebRTC/SIP documentation discrepancy above remains an activation blocker.

Apple revocation follows [authorization-code validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens), [token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens), and the [client-secret contract linked from Apple's Sign in with Apple documentation](https://developer.apple.com/documentation/accountorganizationaldatasharing/creating-a-client-secret). Checkout retry handling follows [Stripe's idempotency retention contract](https://docs.stripe.com/api/idempotent_requests).
