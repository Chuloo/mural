# Website access requests

This endpoint stores a request to hear when Mural is available. It sends no email, creates no account, and grants no trial or paid usage. Auth, billing, and hosted-voice gates remain separate.

## Website contract

Send `POST https://api.mural.chat/v1/access-requests` with `Content-Type: application/json`, no cookies or authorization, and this body:

```json
{
  "email": "person@example.com",
  "consentVersion": "waitlist-v1",
  "source": "website",
  "website": ""
}
```

`website` is an optional hidden honeypot. Leave it empty and exclude it from keyboard navigation and assistive technology. The other three fields are required. Unknown fields are rejected. Bodies are limited to 1,024 bytes. Email validation supports ordinary ASCII addresses, including plus addressing and punycode domains; it does not verify mailbox ownership or accept internationalized local parts. Addresses are trimmed and lowercased without removing dots or plus tags.

Use clear consent text near the submit button: “Email me about Mural access. No marketing. I can withdraw at hi@hackmamba.io.” Link the current privacy policy. Submitting `waitlist-v1` records acceptance of this purpose; changing that purpose requires a new consent flow.

| Response | Meaning |
| --- | --- |
| `202 {"accepted":true}` | Accepted, including a duplicate or filled honeypot. Show the same confirmation. |
| `400` | Invalid JSON, email, fields, source, or consent version. |
| `403` | Origin not allowed. |
| `413` / `415` | Body too large / wrong content type. |
| `429` | Rate limit; `Retry-After: 3600`. |
| `503` | Disabled, unavailable, proxy configuration missing, or admission capacity reached. Keep the form retryable. |

Only the exact origin `https://mural.chat` is allowed by default. `OPTIONS` preflight for POST with Content-Type returns 204 before parsing a body or contacting PostgreSQL. CORS does not grant permission to call other API routes or authenticate a visitor. It also does not stop non-browser bots from forging an Origin header.

## Private server configuration

Run migration `004_access_requests.sql` before starting the updated API. Keep the existing financial-table grants intact and grant the runtime role only these new table permissions:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON access_requests, access_request_limits TO mural_runtime;
```

The API requires these environment values:

```dotenv
ACCESS_REQUESTS_ENABLED=true
ACCESS_REQUEST_HMAC_KEY=<separate random 32-byte lowercase hex secret>
ACCESS_REQUEST_PROXY_TOKEN=<separate random 32-byte lowercase hex secret>
```

Generate secrets privately on the server and add them to its mode-600 environment file. The deployment override must pass all three variables into the API container and the proxy token into Caddy. Do not commit secrets, print the interpolated Compose configuration, or place these headers in browser code. Startup rejects missing, malformed, or identical secrets when enabled. Disabling access requests does not disable retention cleanup.

The origin currently connects directly to Caddy through a DNS-only API record. Expose only this additional method/path pair, retaining the existing health/pricing allowlist and 503 gate for everything else:

```caddyfile
@accessRequests {
  method POST OPTIONS
  path /v1/access-requests
}
handle @accessRequests {
  reverse_proxy api:8080 {
    header_up X-Mural-Client-IP {remote_host}
    header_up X-Mural-Proxy-Token {$ACCESS_REQUEST_PROXY_TOKEN}
  }
}
```

Caddy must overwrite both headers. The API accepts the forwarded address only with the private token and ignores untrusted X-Forwarded-For. With waitlist-only configuration, add these same headers to the foundation handler for `/readyz` and `/v1/pricing`, whose read limits also need a verified network address. Once accounts are configured, those reads instead use the account proxy token described in [Enable accounts](enable-accounts.md). Keep the API port unpublished and request access logging disabled. If the API moves behind another proxy, review the client-address boundary before enabling it; do not trust a client-supplied forwarding header.

For local development only, set `ACCESS_REQUEST_LOCAL_ORIGIN=http://localhost:5173` (or another exact HTTP loopback origin). Direct requests from a loopback socket with that explicit origin can use the socket address without proxy headers. Arbitrary preview domains, wildcard origins, and non-loopback direct clients are rejected. Production should leave this setting unset.

## Storage, abuse limits, and retention

`access_requests` contains only email, `requested_at`, `consent_version`, and source. A repeat request updates `requested_at`; the response never reveals whether the email was already present. Consent remains `waitlist-v1`. There is no user profile, invitation history, email delivery tracking, or public listing endpoint.

Admission limits are transactional and shared by every API instance: five valid submissions per network address per UTC hour, 100,000 total per hour, 200,000 total per UTC day, 100,000 new email addresses per day, and 100,000 retained addresses. The public launch exhausted the original 500-address daily allowance; the current limits allow the requested 100,000-address list without that earlier cutoff. These are admission ceilings, not a claim of tested throughput. Duplicates and honeypots consume request limits. Attempts rejected by a network's limit do not consume global allowance; an exhausted hour does not consume the following hours' daily allowance. Invalid bodies are rejected before persistence. Once storage or daily signup capacity is full, both new and existing addresses receive the same unavailable response. The website distinguishes this capacity response from a connection failure and offers the support address. These limits do not provide protection against a large denial-of-service attack.

Rate buckets store HMAC-SHA256 identifiers derived from the network address and UTC date, using the private key. IPv4-mapped addresses are normalized; IPv6 privacy addresses share a /64 bucket. Raw addresses and user agents are never stored. Date changes produce different hashes. IP-derived buckets expire no later than two hours after their hour begins. Cleanup runs at startup and every 15 minutes, so healthy operation retains them for at most about two hours and 15 minutes. Global counters have no visitor identifier and expire after at most 48 hours. Rotating the HMAC key changes current identifiers and can reset per-address allowances; global caps remain effective.

Email requests are deleted after 12 months without another request, or earlier through the private deletion command. Cleanup runs even with admission disabled. Monitor the generic retention-cleanup failure message. An outage or stopped process can delay physical deletion; run pruning before resuming service. No automated invitation cleanup or marketing campaign is implemented.

Exclude abuse-bucket contents from logical backups with `pg_dump --exclude-table-data=public.access_request_limits`. Encrypted backups may retain removed email records until their scheduled expiry. The current logical backup schedule retains 14 days of local encrypted dumps; provider VM backups can also contain old database pages and have a separate rotation. Keep any exports private and delete them when their purpose ends. A restore must reapply withdrawals received since the backup before contacts are used. Do not promise immediate removal from every backup copy.

## Private operator commands

The compiled CLI is `dist/src/access-requests-admin.js`. Run it through an SSH session with a private `DATABASE_URL`; never expose it through Caddy. It prints only generic status or aggregate cleanup counts.

```sh
node dist/src/access-requests-admin.js export /absolute/private/new-file.json
node dist/src/access-requests-admin.js delete < /absolute/private/email-to-delete.txt
node dist/src/access-requests-admin.js prune
```

Export writes a new JSON file with mode 600 and refuses to overwrite an existing path. Choose a private directory outside the source checkout and web roots. It excludes already-expired requests and includes no abuse identifiers. For deletion, provide only the email on stdin through a mode-600 temporary file or private pipe; avoid command-line arguments, shell history, and logs. Delete that temporary file after use. Deletion is idempotent and does not disclose membership. The API container is read-only, so an operator export needs an explicitly mounted private writable directory or a separate administrative process.

## Release checks

Run the PostgreSQL integration tests before deployment. After deploying, confirm the three public health/pricing reads still work; test OPTIONS and a malformed POST without storing a real address; verify payment, trial, and voice routes still return the existing public gate. Accounts have a separate [enablement procedure](enable-accounts.md) and remain gated unless that procedure is applied. A full form test should use an operator-controlled address and remove its request afterward. No message is sent by submitting the form.

Stripe remains a sandbox-only implementation. Dashboard catalog setup does not make billing operational: the webhook and purchase routes remain gated, and refunds/deletion support, taxes, storefront purchase rules, and hosted funding verification still require work. This endpoint does not change payment handling.
