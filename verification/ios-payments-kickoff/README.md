# iOS payments implementation kickoff

26 September 2026 · Branch `codex/ios-payments-implementation`

The iOS client now reads the server's paid balance alongside free minutes. Account shows an approximate whole-minute total when spendable paid value contributes, and keeps the existing exact free-time display otherwise. A fully reserved paid balance shows “Updating your minutes…” rather than a spendable amount. A paid-only member can pass the conversation and personal-key switch checks. The client accepts the server's paid lease schema, retains its funding and limit, and ends the call by the earlier of the selected limit and server deadline. If a successful create response is malformed but identifies a session, the client requests its closure.

Native iPhone 17 Pro simulator checks passed for the existing free-only balance, mixed and paid-only Account states, reserved paid value, and paid-only switching from a personal key. The existing fresh-confirmation switch test also passed. A Release configuration simulator build passed. The [mixed balance](account-mixed-minutes.png) and [paid-only balance](account-paid-minutes.png) screenshots were reviewed for layout and copy.

This work does not offer a purchase. StoreKit checkout, server-side Apple verification, quantity orders, settlement-aware balance refresh, account linking, and sandbox purchase/refund tests remain required before a payment-enabled build. No server change was made or deployed, and no App Store product or live purchase was created.
