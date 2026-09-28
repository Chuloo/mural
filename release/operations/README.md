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
| `provider_history_stalled` | An enabled provider has no reconciliation cursor or it has not advanced for an hour | Check credentials, provider responses and cursor retention; replay verified history |
| `inspection_failed` | The monitor cannot read or validate its snapshot | Check database availability, migrations and the monitor service |

Ordinary unpaid checkout polls do not trigger delivery-failure emails. Changes are limited to one notice per environment every 15 minutes; unresolved incidents get a daily reminder. A recovery notice is sent once. Failed email delivery retains the previous successful-send state and retries on the next run. Messages contain only alert categories and opaque support references. They contain no identity, card, receipt or conversation data.

To find an alert's record, hash the order/session UUID with SHA-256 and compare its first 12 hexadecimal characters with the reference. Use protected server access. Do not paste raw receipts or private provider responses into tickets.

## Installation and delivery verification

1. Install `scripts/payment_health_monitor.py` as `/opt/mural/operations/payment_health_monitor.py` and copy the two systemd files into `/etc/systemd/system/`.
2. Store the configuration at `/opt/mural/operations/payment-monitor.json`, owned by root with mode `0600`. Use a verified sender and the existing mail provider's restricted SMTP credentials. The example contains placeholders, not working credentials. TLS with certificate validation is mandatory: `starttls` or `implicit`.
3. Include only configured provider histories in `expectedProviders`. Production Apple and Play are currently disabled, so neither is expected there. Sandbox Apple is enabled.
4. Run the monitor with `--dry-run` and review the sanitized output. This mode sends no email and changes no monitor state.
5. Run with `--test-email`. SMTP acceptance is not inbox delivery: confirm receipt at hi@hackmamba.io before marking delivery verified.
6. Enable the timer only after the test succeeds. Inspect `systemctl status mural-payment-monitor.timer` and the service journal. Alert content is deliberately absent from the journal.
7. Verify host-health monitoring separately. This process cannot email when the host or its outbound mail service is unavailable.

Configuration and mail credentials stay outside Git. Installing this separate inspector does not restart the API or alter its deployment configuration. To roll back, disable the timer and remove its two unit files; preserve monitor state for incident review.

## Known records at installation

Read-only inspection on 28 September found 21 unresolved production minute-funded conversations created September 14–16. Their final usage remains unverified. Preserve these records and reservations until provider evidence supports settlement. One production Stripe checkout from September 27 is still pending with zero grant; it has not failed payment delivery. The current sandbox has no unfinished delivery jobs or unsettled conversations.

These are operating findings, not authorization to fabricate usage, issue credits or refund live purchases.
