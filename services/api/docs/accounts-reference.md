# Account API reference

Optional accounts use Google or Apple identity tokens to issue a Mural session. Account creation does not grant credits, a trial, or hosted conversations. Learning data and the user's OpenAI key remain on the device. See [Enable accounts](enable-accounts.md) for deployment steps.

## Routes and payloads

All responses use `Cache-Control: no-store`. Native requests use JSON and HTTPS. No account route enables browser CORS or uses cookies. The proxy supplies private admission headers; clients must not receive or send the proxy secret.

| Method and path | Request | Successful response |
| --- | --- | --- |
| `GET /v1/auth/providers` | No authorization | `{ "google": true, "googleAndroid": false, "apple": false }`; flags reflect server configuration, not OAuth dashboard or end-to-end verification. |
| `POST /v1/auth/challenge` | `{}`; 1,024-byte limit | `challengeID` UUID, `nonce` random hex string, `expiresInSeconds: 300`. |
| `POST /v1/auth/exchange` | `{ "provider": "google", "idToken": "…", "challengeID": "…" }`; 20,000-byte limit | `accountID` UUID, random `accessToken`, `expiresInSeconds: 86400`. Provider can also be `apple` when enabled. |
| `GET /v1/account` | `Authorization: Bearer <accessToken>` | `accountID`, nullable `email`, `providers` array, `createdAt` in UTC with milliseconds. |
| `POST /v1/auth/sign-out` | Bearer and `{}`; 1,024-byte limit | `{ "signedOut": true }`; revokes every Mural session for this account. |
| `DELETE /v1/account` | Bearer and `{}` for Google; Apple requires `{ "appleAuthorizationCode": "…" }`; 5,120-byte limit | `{ "deleted": true, "retained": null }` for an empty signup account. Resolved financial history instead returns a retention explanation. |
| `GET /v1/wallet` | Bearer | USD `balanceNanoUSD`, `reservedNanoUSD`, `availableNanoUSD` as integer strings. Account-only deployment can leave this route gated. |

`GET /v1/minutes` accepts a member or guest bearer and returns exact millisecond balances under the [shared contract](../../../shared/contracts/minute-balance.schema.json). Reading this route does not grant time.

`google` in provider discovery describes the iOS flow; `googleAndroid` describes the native Android flow.

Unknown body fields are rejected. ID tokens are limited to 16,384 characters; Apple deletion codes to 4,096. A profile's account ID comes from the authenticated session. The API never returns identity-provider subjects or tokens in the profile.

Send the challenge's **raw nonce** in the provider's authorization request. The backend compares SHA-256 of the signed token's nonce with the stored hash. Google installed-app authorization also uses PKCE and an independently checked state in the native client. Mural verifies RS256 signatures against each provider's fixed JWKS endpoint, issuer, audience, expiry, issued-at age (at most ten minutes), subject and nonce. For iOS, Google's authorized party, when present, must match the iOS client ID. Android tokens use `GOOGLE_ANDROID_SERVER_CLIENT_ID` as audience and must name an explicitly allowlisted Android client in `azp`. The server audience alone does not authorize an Android app. The provider's subject identifies the account; equal emails never merge accounts. Google recommends verifying these token claims and using the stable subject as the user identifier. [Google iOS backend authentication](https://developers.google.com/identity/sign-in/ios/backend-auth)

Apple deletion exchanges a fresh authorization code, checks the returned identity against the locked account subject, and revokes the returned refresh token. The code and returned tokens exist only in memory. A failed exchange or revocation leaves local account records intact. Apple requires an in-app deletion path and revocation when an app uses Sign in with Apple. [Apple account deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app/)

## Errors and limits

Errors have the form `{ "error": { "code": "…" } }`, with no reflected request bodies or database details.

| Status and code | Meaning |
| --- | --- |
| `400 invalid_request` / `invalid_json` | Malformed or unsupported input. |
| `401 invalid_challenge` / `invalid_identity_token` | Expired, replayed or invalid authorization. Start a new sign-in. |
| `401 sign_in_required` | Missing, expired, revoked or invalid Mural bearer. |
| `409 unresolved_billing` | Pending checkout, nonzero balance or reserved value prevents deletion. The account remains intact. |
| `429 rate_limit` | Hourly admission budget exhausted; `Retry-After: 3600`. |
| `503 accounts_unavailable` / `accounts_proxy_not_ready` | Disabled account service, missing trusted-proxy configuration, or admission database failure. |
| `503 apple_sign_in_not_ready` | Apple signup cannot proceed without configured revocation. |
| `503 account_capacity_reached` | 10,000 active accounts already exist; existing users can still sign in. |

Durable PostgreSQL counters allow 60 challenges, 120 exchanges and 600 account requests per network per UTC hour. Corresponding global limits are 2,000, 4,000 and 20,000. Requests rejected during parsing or authorization still consume admission allowance. Requests already over their network limit keep that network's counter but do not consume shared allowance. A private proxy token authenticates the forwarded address; raw `X-Forwarded-For` is ignored. IPv4-mapped addresses are normalized, and IPv6 addresses share a /64 allowance. Public metadata reads use a separate in-memory limit of 120 requests per network per minute, with HMAC identifiers. These caps bound ordinary abuse and database growth; they do not replace infrastructure protection against denial of service.

## Stored records and retention

| Record | Stored fields | Active-database retention |
| --- | --- | --- |
| Account | UUID, nullable verified email, creation time; deletion time only if financial history requires a retained record | Until account deletion. An empty wallet row accompanies signup. |
| Identity | Provider, provider subject, account UUID | Removed on account deletion. No automatic linking by email. |
| Session | UUID, account UUID, SHA-256 bearer hash, creation, expiry and optional revocation times | 24 hours; at most ten active sessions per account. Older sessions are removed when signing in again. |
| Challenge | UUID, SHA-256 nonce hash, expiry and optional use time | Five minutes. |
| Admission counter | Operation, scope, HMAC network identifier or global marker, hour, expiry and count | Expires two hours after its UTC hour starts. |

Pruning runs at startup and every 15 minutes, including when signup is disabled. Healthy operation therefore removes expired challenge/session records within 15 minutes and admission records within at most about two hours and 15 minutes of their bucket start. A stopped process or database outage delays physical cleanup; generic failure messages require operator attention.

The network HMAC includes the UTC date and a private key, so the same network gets a different identifier each day. Raw network addresses, user agents, provider ID/access/refresh tokens, authorization codes, names and avatars are not persisted or logged by this service. Verified email can be absent, including later Apple authorizations; an absent email does not erase an existing verified email. No account consent-version field is stored.

Deleting an account with no billing or usage history removes its account, identity, session and wallet rows. If settled financial history exists, the API removes email, identities and sessions and retains the opaque account reference needed by the immutable journal. It refuses deletion while paid value or a pending checkout is unresolved. Payments remain unavailable in the account-only release.

Encrypted backups have their own expiry, so live deletion does not immediately remove every backup copy. Exclude admission-counter data from logical backups and apply retention pruning and subsequent deletion requests before using restored data. Provider revocation outside Mural does not currently invalidate an existing Mural bearer immediately; the bearer expires within 24 hours or is revoked by Mural sign-out/deletion.
