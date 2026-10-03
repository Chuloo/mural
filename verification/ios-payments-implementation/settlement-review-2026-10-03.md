# Settlement investigation, October 3, 2026

Reference status checked at 09:56 UTC. Read-only provider recovery was attempted at 10:06 UTC. The code changes below are locally tested; this record does not establish their deployment.

## Production evidence

Production has three incomplete minute-funded conversations and no active conversations at the snapshot. There are 1,261 closed conversations created since September 17. The three open reservations total **19 minutes 17 seconds**. Final voice usage, provider cost and customer charge remain unconfirmed. All 39 associated helper requests are settled.

| Opaque monitor reference | Created, UTC | Latest observed voice | Open reservation | Settled helper requests |
| --- | --- | --- | --- | --- |
| `b9f95ca8e5c7` | October 1, 11:37:50 | 27 seconds | 9 minutes 31 seconds | 9 |
| `98122fdb250c` | October 1, 23:21:00 | 0 seconds | 1 second | 0 |
| `c78c03c4ed18` | October 2, 18:10:11 | 223 seconds | 9 minutes 45 seconds | 30 |

All three have `sideband_lost` and a saved provider-session binding. Observed durations are partial evidence, not final customer bills. The September 30 support adjustment covered 21 older records; it did not cover these three.

The retained application-log tail shows repeated recovery failures: roughly 10,500 connection-loss events and 21,000 failed HTTP hangups per affected session. The initial failures are absent from that tail, so the precise cause of each original disconnection cannot be established from these logs.

## Recovery result

A read-only sideband attach to each saved provider session returned HTTP 404. No final usage arrived. This attempt created no new provider session, issued no close or hangup command, and changed no database record.

OpenAI's [usage and graceful-close guidance](https://developers.openai.com/api/docs/guides/live-conversations#usage-and-graceful-close) treats `session.closed` as the confirmation of final voice usage. Its [session endpoint reference](https://developers.openai.com/api/reference/typescript/resources/live/subresources/sessions) documents a recording download, but no per-session historical usage retrieval endpoint. Mural starts these sessions with recording storage disabled. No supported automatic path to their final usage was identified in the current documentation.

These records therefore remain unresolved. A customer support adjustment needs owner review and a retained audit record; final provider cost must stay unconfirmed unless new provider evidence supplies it.

## Watchdog fix

The watchdog previously tried to reattach every incomplete session on each one-second tick. A failed attach called the loss handler, which requested HTTP hangup, and the same tick could request a second hangup. Neither request supplied final usage, so each expired provider session continued generating errors indefinitely.

The local fix delays failed recovery attempts by 10, 20, 40, 80 and 160 seconds, then five minutes between subsequent attempts. Duplicate loss callbacks share the pending retry. The same tick no longer sends a second hangup after a failed attach. Unknown-cost holds remain intact. A recovered connection can still settle from trusted final usage; creation is never retried.

The delay is in memory. A worker restart immediately attempts durable-session recovery once, then applies the delay again. This preserves the existing restart behavior.

## Monitoring coverage

The production Play void-history cursor was updated at 09:44 UTC, within the monitor's one-hour limit. The deployed protected monitor configuration has no expected providers, so a stalled Play history would not alert. The example now expects Play; the operations guide explains adding Apple when its history worker is enabled. These repository edits do not change the deployed configuration.

## Verification

- Hosted voice suite: **43 passed, zero failures or skips**, using an isolated local PostgreSQL test database and fake HTTP/WebSocket provider.
- New recovery regression: exact retry intervals through the five-minute ceiling, one hangup per rejected reconnect, unchanged balance and hold during unknown finalization, no second provider creation, and exactly one ledger settlement when valid final usage arrives.
- Payment monitor suite: **13 passed**. Apple and Play cursor freshness are checked independently, including one missing while the other is healthy.
- No production settlement, customer balance, monitor configuration or email delivery was changed by this investigation.

Sanitized evidence: [database and diagnostics snapshot](settlement-audit-2026-10-03.json), [provider recovery result](settlement-recovery-2026-10-03.json).
