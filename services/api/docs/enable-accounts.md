# Enable optional accounts

This procedure enables native Google sign-in independently of payments, free trials and hosted voice. The current release keeps Apple disabled until Apple Developer enrollment and revocation credentials are ready. Account storage and HTTP contracts are in the [account reference](accounts-reference.md).

## Configure the identity provider

Create a Google **iOS** OAuth client for the app's bundle ID and use that same client ID as `GOOGLE_CLIENT_ID` on the server and as the native authorization audience. The current Mural app uses bundle ID `no.william.mural`; its public client ID is `1034240936303-gernpir75rp4uvc8m5imrhrs18npma5n.apps.googleusercontent.com`, in project `mural-508413`. This installed-app PKCE flow needs no Google client secret on the backend. [Google iOS backend authentication](https://developers.google.com/identity/sign-in/ios/backend-auth)

Mural's Google project is External/In production as of September 12, 2026, with verified and published branding. It requests only `openid` and `email`. For a separate development project, note that Testing is not an invitation-only gate for these basic identity scopes. Workspace policies can still block authorization. [Google OAuth app states](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview)

For Apple later, configure the native client/bundle ID and Sign in with Apple capability, then supply `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID` and `APPLE_PRIVATE_KEY_PATH`. Mount the `.p8` key read-only in the API container. Startup imports it as an ES256 key before offering Apple signup. A successful local key import does not verify portal configuration; complete a real sign-in and deletion test before enabling the app button. The backend must be able to exchange a fresh Apple code and revoke the returned token. [Apple token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens)

## Apply the migration and runtime grants

Build the reviewed source, take the normal encrypted backup, and run migrations with the migration role. Migration `005_account_readiness.sql` adds session creation times, expiry indexes and durable account-admission counters. It is required even when `ACCOUNTS_ENABLED` is false because startup prunes expired authentication records.

Preserve the runtime role's existing table permissions and grant:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON auth_rate_limits TO mural_runtime;
GRANT DELETE ON accounts, wallets TO mural_runtime;
```

The runtime role also needs its existing SELECT/INSERT/UPDATE privileges on accounts, wallets, identities, auth_challenges and auth_sessions; DELETE on identities, auth_challenges and auth_sessions; and SELECT on ledger, reservations, checkout_orders, usage_records and hosted_sessions for safe deletion checks. Do not grant modification or deletion of the ledger. The migration role retains schema ownership.

Add `--exclude-table-data=public.auth_rate_limits` to the logical `pg_dump` command alongside the existing `access_request_limits` exclusion. Existing encrypted backups retain their configured expiry; keep restoration and deletion handling in the operational runbook.

## Pass the account configuration

Generate the two secrets privately and write them into the mode-600 deployment environment file. Pass these values into the API container through the deployment override:

```dotenv
ACCOUNTS_ENABLED=true
ACCOUNTS_HMAC_KEY=<random 32-byte lowercase hex secret>
ACCOUNTS_PROXY_TOKEN=<different random 32-byte lowercase hex secret>
GOOGLE_CLIENT_ID=<the iOS OAuth client ID>
```

The HMAC and proxy secrets must differ. The proxy token can share the existing trusted-Caddy token if the deployment already manages one; never reuse the HMAC key as a token. Leave `ACCOUNTS_ALLOW_LOCAL_LOOPBACK` unset in production. Explicit `true` permits direct loopback requests for local tests only. Leave Apple variables unset for the Google-only release.

Keep `HOSTED_VOICE_EXPERIMENTAL=false`, no hosted account allowlist or spending allowance, and no OpenAI or Stripe credentials in this account-only configuration. No code in this release grants a free trial. Do not print the interpolated Compose environment or place private headers in native/browser code.

## Expose only the account routes

The API hostname currently reaches Caddy directly through a DNS-only A record. Keep PostgreSQL and the API container ports private, disable request access logs, and retain the existing waitlist route. Update the existing foundation handler and add the account handlers before the catch-all public gate:

```caddyfile
@foundation {
  method GET HEAD
  path /healthz /readyz /v1/pricing
}
handle @foundation {
  reverse_proxy api:8080 {
    header_up X-Mural-Client-IP {remote_host}
    header_up X-Mural-Proxy-Token {$ACCOUNTS_PROXY_TOKEN}
  }
}
@accountRead {
  method GET
  path /v1/auth/providers /v1/account
}
@accountPost {
  method POST
  path /v1/auth/challenge /v1/auth/exchange /v1/auth/sign-out
}
@accountDelete {
  method DELETE
  path /v1/account
}
handle @accountRead {
  reverse_proxy api:8080 {
    header_up X-Mural-Client-IP {remote_host}
    header_up X-Mural-Proxy-Token {$ACCOUNTS_PROXY_TOKEN}
  }
}
handle @accountPost {
  reverse_proxy api:8080 {
    header_up X-Mural-Client-IP {remote_host}
    header_up X-Mural-Proxy-Token {$ACCOUNTS_PROXY_TOKEN}
  }
}
handle @accountDelete {
  reverse_proxy api:8080 {
    header_up X-Mural-Client-IP {remote_host}
    header_up X-Mural-Proxy-Token {$ACCOUNTS_PROXY_TOKEN}
  }
}
```

Pass `ACCOUNTS_PROXY_TOKEN` into the Caddy container too. Caddy must overwrite both headers; app code ignores untrusted forwarding headers. Public read throttling also uses this authenticated network identity, so `/readyz`, `/v1/pricing` and `/v1/auth/providers` require the headers whenever accounts are configured. `/healthz` remains available without them. Keep `/v1/wallet`, `/v1/checkout`, `/v1/webhooks/stripe`, `/v1/trial/*` and `/v1/live/*` behind the existing gate. Account routes do not need browser CORS. Revisit the trusted-address boundary if another proxy is inserted in front of Caddy.

## Verify the deployment

1. Run `npm run check` and the isolated PostgreSQL integration suite. The signed-token HTTP fixtures verify signature handling and durable state without calling Google or Apple.
2. Start the API under the restricted runtime role. Confirm `/healthz` and `/readyz`, then `/v1/auth/providers` returns Google true and Apple false. Check that startup retention cleanup succeeds.
3. POST an empty JSON object to `/v1/auth/challenge`. Validate its response privately without recording the nonce. A malformed exchange must fail without adding account or identity records. Confirm disallowed commercial routes retain their public gate.
4. On a configured test user's iPhone, complete real Google authorization and fetch `/v1/account`. Privately verify that the account ID matches the native session and that exactly one account, identity and empty wallet exist. Keep tokens and email out of logs or shared evidence.
5. Sign out, verify the old bearer is rejected, and sign in again to the same account. Delete a disposable account through the app and verify its account, identity, session and empty wallet rows are gone. A separate app installation can confirm on-device learning remains independent.
6. Check that the waitlist still accepts its documented preflight and invalid-input cases, and that no payment, trial or hosted service was enabled by the account rollout.

Server configuration flags and local JWT fixtures cannot establish that a real Google authorization succeeds. Record that outcome separately after the device test. Apple remains pending until its own sign-in, fresh-code revocation and deletion flow passes.
