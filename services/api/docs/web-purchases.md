# Website purchases

The website `/buy-minutes/` sells the existing live Stripe packs to an existing Mural account. The buyer verifies the email shown in Mural’s Account screen. That address must identify exactly one active, non-guest account with a verified Google or Apple identity. Email matching does not create or merge accounts. Both Apple relay email domains are accepted.

## API contract

All routes start with `/v1/web-purchases`. Requests require `Origin: https://mural.chat` and the existing trusted account proxy headers. POST bodies use JSON. OPTIONS permits only the route’s methods and `Content-Type`, `Authorization`, `Idempotency-Key`; it permits no credential cookies. Preview and local website origins cannot write to production.

| Method and path | Request | Response |
| --- | --- | --- |
| POST `/challenges` | `{email}` | Generic 202 `{challengeID,expiresInSeconds:600,resendAfterSeconds:60}` |
| POST `/verify` | `{challengeID,code}` | `{token,email,expiresInSeconds:1800}` |
| GET `/session` | Purchase bearer | `{email}` |
| DELETE `/session` | Purchase bearer | `{signedOut:true}`; revokes this checkout access only |
| GET `/products` | Purchase bearer | Live Stripe offers, `maximumQuantity`, `billingBasis` |
| POST `/orders` | Purchase bearer, `Idempotency-Key`, `{sku,quantity}` | Immutable quoted order and `payment.checkoutURL` |
| GET `/orders/by-key/:key` | Purchase bearer | `{orderID}` for this account’s live Stripe order |
| GET `/orders/:id` | Purchase bearer | Fulfillment/refund status and the original `order` quote |

The original status quote contains `sku`, `quantity`, `currency`, `totalMinor`, `aiValueNanoUSD`. It supports review and recovery of an interrupted order after a catalog change. Existing native status responses are unchanged.

Purchase bearer tokens are random, hashed in `web_purchase_sessions`, and valid for 30 minutes. They cannot authenticate against `auth_sessions`, read a wallet or general account profile, start a conversation, link identities or delete an account. Access is rejected after expiry, revocation, deletion, an email change or a newly ambiguous email match. The browser stores checkout access and the original purchase attempt in session storage, never in a URL. A tab can reverify the same email and recover its pending attempt with the same key.

Stripe alone supplies the payable checkout. The server chooses the product, merchant, live environment, immutable price, allocation and quantity. The buyer cannot nominate an account identifier or payment amount. Returning through `/payment-return?status=success` does not credit value. The existing signed Stripe verifier and durable fulfillment worker grant once, verify fees/taxes and reverse confirmed refunds. Android/native return pages keep their existing behavior when the tab has no website purchase attempt. Old server code can still reconcile orders created through these routes after rollback; no order or receipt format changed.

## Verification and delivery limits

- Six-digit codes expire after 10 minutes, allow five guesses and work once. Code comparison uses a keyed hash bound to the challenge, with constant-time comparison.
- Each normalized email allows one request per 60 seconds and three requests in a rolling hour, including unknown addresses. A trusted network allows 10 challenge requests and 30 verification requests per UTC hour. Global allowances are 5,000 and 10,000 per hour respectively. Purchase reads/actions also use the existing durable account allowance and an early 120 requests/minute network limit.
- Unknown, deleted, guest, unverified and ambiguous accounts receive the same 202 response shape. Delivery happens outside that response through an encrypted durable queue.
- The queue leases up to four deliveries, uses a fixed Resend idempotency key per challenge, a 10 second request timeout and at most three attempts. Network errors, 429 and 5xx responses retry after five seconds. Other failures stop delivery. Expired or consumed challenges cannot generate a new send. A restart retains queued work.
- Pending recipient/code payloads use authenticated encryption with a domain-separated key derived from the protected HMAC secret. Payloads are cleared after sending, permanent failure, expiry or use. Hashed challenge records expire 24 hours after code expiry. Sessions and admission counters are pruned every 15 minutes. No email, code, bearer, provider key or raw response body reaches diagnostics.

Resend’s email API and idempotency behavior are documented in [Send Email](https://resend.com/docs/api-reference/emails/send-email) and [Idempotency Keys](https://resend.com/docs/dashboard/emails/idempotency-keys). [Usage limits](https://resend.com/docs/api-reference/rate-limit) apply across the team, including other senders. The application quota is an abuse bound, not a purchased Resend allowance.

## Deployment requirements

Deploy the API before publishing the website. Keep the existing live Stripe catalog, sales/quantity gates, restricted Stripe credentials, webhook URL, receipt encryption keys and public hosted funding policy. Website purchases require a live database, live Stripe sales and trusted account admission. They add no Apple sale, sandbox spending or AI provider permission.

1. Retain the current image/configuration, verify the server is quiet and take the existing encrypted backup.
2. Prepare the protected JSON file at `/run/mural-commerce/web-purchases.json` in the existing commerce mount. This is the only runtime path accepted. Set only `hmacKey` (an independent random 32 byte lowercase hex secret), `apiKey` (the approved Resend sending key), `from` (`Mural <hi@contact.hackmamba.io>`) and `replyTo` (`hi@hackmamba.io`). Keep the monitor’s configuration unchanged. The file must be regular, singly linked, root/runtime owned, private to its owner and at most 8 KiB. Register the sender with Apple’s email relay service and retain Resend’s verified domain.
3. Apply migration 034 and `operations/web-purchase-runtime-grants.sql` after the existing grants. Enable `WEB_PURCHASES_ENABLED=true` and point `WEB_PURCHASES_CREDENTIALS_FILE` at that file. Add only the documented web paths/methods to the proxy allowlist. No CORS origin wildcard is permitted.
4. Verify readiness, exact-origin preflights, generic challenge behavior, actual delivery to an authorized existing account, purchase-only token isolation and live catalog/price reads. Do not perform a billed purchase without separate authorization.
5. Publish the website and verify its CSP, mobile layout and existing native return page. Restore the previous image/configuration if any gate fails. Preserve the new HMAC file during rollback so encrypted queued deliveries remain recoverable; additive 034 tables and their grants can remain.

## Tests

Run `npm run check`, `npm run build` and the full API suite with the dedicated PostgreSQL test URL. `tests/web-purchases.test.ts` covers account ambiguity, both relay domains, code/session expiry and replay, durable quotas and delivery, real runtime privileges, browser-origin/proxy rejection, immutable ownership, fake signed Stripe fulfillment, duplicate credit and refunds. Tests use synthetic email and payment transports. Website unit/browser tests live in the separate website repository.
