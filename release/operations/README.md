# Payment operations

William owns payment alerts sent to **hi@hackmamba.io**. Customer replies are due within **one business day**, as approved on 28 September 2026. Investigate provider deadlines when an alert arrives; the customer response target does not extend a store deadline.

## Checks

Once configured and enabled, the monitor runs every five minutes. It reads one consistent, read-only database snapshot per environment. It never changes orders, receipts, grants, refunds, reservations or sessions.

| Alert | Trigger | First action |
| --- | --- | --- |
| `delivery_failed` | Provider delivery has failed for an order older than 15 minutes | Inspect verified provider status, receipt scope and worker errors; retry the existing order |
| `delivery_worker_stalled` | A queued or leased job is at least 15 minutes overdue | Check the API worker, provider connectivity and database readiness |
| `purchase_pending_48h` | Provider still reports pending after 48 hours | Check whether the provider has expired or completed checkout; do not grant pending payments |
| `play_acknowledgment_24h` | A verified purchased Play order remains unfinished for 24 hours | Restore durable delivery and consume/acknowledge before the three-day deadline |
| `play_purchase_timestamp_missing` | A purchased Play order has no verified purchase event timestamp | Inspect the purchase event journal before estimating any deadline |
| `settlement_overdue` | A conversation is unresolved 15 minutes after its deadline | Inspect final provider usage and the existing closeout tools; keep unresolved holds intact |
| `provider_history_stalled` | A required provider cursor is missing or unchanged for an hour, or an exact environment/merchant scope lacks a valid completion from the last hour | Check credentials, provider responses and cursor retention; replay verified history |
| `inspection_failed` | The monitor cannot read or validate its snapshot | Check database availability, migrations and the monitor service |

Ordinary unpaid checkout polls do not trigger delivery-failure emails. Changes are limited to one notice per environment every 15 minutes; unresolved incidents get a daily reminder. A recovery notice is sent once. Failed email delivery retains the previous successful-send state and retries on the next run. Messages contain only alert categories and opaque support references. They contain no identity, card, receipt or conversation data.

To find an alert's record, hash the order/session UUID with SHA-256 and compare its first 12 hexadecimal characters with the reference. Use protected server access. Do not paste raw receipts or private provider responses into tickets.

## Installation and delivery verification

1. Install `scripts/payment_health_monitor.py` as `/opt/mural/operations/payment_health_monitor.py` and copy the two systemd files into `/etc/systemd/system/`.
2. Use the approved Resend service and a sending-only key restricted to the verified Hackmamba subdomain. Set `smtp.resend.com`, port `587`, `starttls`, and username `resend`; the deployment host can reach port 587, while port 465 timed out during review. Use an address on the verified subdomain as sender and keep `hi@hackmamba.io` as recipient. [Resend SMTP settings](https://resend.com/changelog/smtp-service).
3. Store the configuration at `/opt/mural/operations/payment-monitor.json`, owned by root with mode `0600`. Use a verified sender and the existing mail provider's restricted SMTP credentials. The example contains placeholders, not working credentials. TLS with certificate validation is mandatory: `starttls` or `implicit`.
4. Configure the public target with `expectedProviders: ["play"]` and two `expectedHistoryScopes` for Apple merchant `chat.mural.ios`: require `test` immediately and set `pendingUntilFirstCompletion: true` on `live` while the app awaits its first production release. The [example configuration](payment-monitor.example.json) matches this setup. The retained isolated sandbox keeps `expectedProviders: ["apple"]`. Confirm each deployed adapter and completed cursor before changing these expectations.
5. Run the monitor with `--dry-run` and review the sanitized output. This mode sends no email and changes no monitor state.
6. Run with `--test-email`. SMTP acceptance is not inbox delivery: confirm receipt at hi@hackmamba.io before marking delivery verified.
7. Enable the timer only after the test succeeds. Inspect `systemctl status mural-payment-monitor.timer` and the service journal. Alert content is deliberately absent from the journal.
8. Verify host-health monitoring separately. This process cannot email when the host or its outbound mail service is unavailable.

Configuration and mail credentials stay outside Git. Installing this separate inspector does not restart the API or alter its deployment configuration. To roll back, disable the timer and remove its two unit files; preserve monitor state for incident review.

The monitor persists each pending scope's first valid completion in `activatedHistoryScopes`. After activation, a missing, null, future or stale completion alerts even after a restart or failed email delivery. Preserve this state during upgrades and rollback. A dry run reads existing activation state without saving a newly observed completion. See the [Apple scope reference](../../services/api/docs/apple-funding-scope.md) for checkout readiness and monitor behavior.

## Current operating findings

The 21 September 14–16 unresolved conversations were closed on September 30 with an approved Mural-funded adjustment. No customer minutes were deducted. Provider usage remains unconfirmed in the retained audit evidence.

Read-only inspection on October 3 found three newer production conversations marked `incomplete` with `sideband_lost`. Their open minute reservations total **19 minutes 17 seconds**. Final voice cost and customer charge remain unconfirmed; all 39 associated helper requests are settled. See [the settlement investigation](../../verification/ios-payments-implementation/settlement-review-2026-10-03.md) for sanitized record references and recovery requirements.

The public API deployed revision `4ef01a07` on October 3. Read-only checks confirmed healthy API/database responses, a completed public Apple sandbox history cursor and the monitor expectations described above. Apple live history remains pending before the app's first production release. Live checkout opens automatically after a real completed history pass; checkout readiness requires a completion from the last hour. Balance reads, delivery, recovery and refunds remain available while a scope's checkout is closed.

The retained isolated sandbox has its own orders and history cursor. Its older provider rejects verified sandbox orders owned by the public database, so a public sandbox purchase can stall that legacy history page. A focused backport of the public provider's verified unknown-order acknowledgement is prepared separately; it has not been applied. Keep its existing data, receipt reconciliation and monitoring until that transition is reviewed and verified.

Keep unresolved reservations and provider evidence until a trusted final usage event or an explicitly approved support adjustment closes them. A retry or monitoring fix does not establish final usage and does not authorize customer charges or balance changes.
