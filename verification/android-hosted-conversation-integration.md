# Android hosted conversation integration

The Android conversation flow can use a signed-in member’s Mural minutes through the experimental hosted service. Personal API keys remain the default. This implementation does not enable public trials, purchases, or guest funding.

## Native wiring

`MuralViewModel.conversationProvider` selects `PERSONAL_KEY` or `HOSTED_MINUTES`. The account/minutes UI must call `selectConversationProvider` after an explicit choice and `refreshHostedReadiness` after login or balance changes. Hosted readiness requires a valid member session, an enabled server capability, and a positive available balance. Creation checks these again. A failure never selects another provider.

Before account switching, sign-out, account deletion, or revocation, call the suspending `prepareForAccountChange()` and continue only when it returns `true`. Call it even when `accountChangeBlocked` is false so any remaining post-session helpers are canceled. The guard remains set until known leases are closed, current-session reconciliation confirms closure, and the server reports zero reserved milliseconds. The member token must remain available until then. `pendingHostedOwnerAccountID` supplies the expected account for restricted reauthentication; the account controller must reject a different account before replacing storage. An expired token may require renewal for the same account before reconciliation can finish.

`HostedAPIClient.currentSession()` reads `/v1/live/sessions/current` to recover an interrupted creation. It never creates a replacement. The private local provider index records hosted session IDs and the unresolved owner before any hosted create. It contains no credentials or conversation content. This prevents unfinished hosted conversations from entering the separate seven-day personal-key assessment recovery queue after restart or history edits.

## Conversation behavior

The existing teaching policy and language locale feed either voice provider. Shared history uses at most 40 standard user/assistant messages and 6,000 UTF-8 bytes. Hosted helper context is bounded without cutting through the target assessment’s evidence. An oversized target is skipped rather than assigned an unsupported learning result.

Meaning, assessment, lookup, delegation, typed replies, topic research, and help use the lease that created the local conversation. Automatic meaning/assessment/delegation requests retain one in-memory deferred result per logical request. Canceling a screen waiter or receiving an uncertain response cannot create another bill for that request. Successful usage is counted once locally; the server remains authoritative for billing.

Meaning, lookup, and final assessment can run for up to two minutes after local end, after server closure is confirmed and while its budget allows. Saved meanings remain local. Hosted finalization can await the original request for the remaining two-minute window without using the personal-key queue’s 15-second wait. Other new helper requests stop at end. Hosted typed replies require an active voice lease; starting a standalone typed conversation or researching a topic before a funded call still requires the explicitly selected personal-key mode.

The client ends microphone capture at the reservation deadline as well as the user’s time/inactivity limits. Server cutoff and settlement remain authoritative. New hosted conversations have the approved 15-second minimum, capped by the remaining reserved time; longer calls use connected time. The client validates the versioned minimum metadata while preserving absent/zero metadata for older sessions. It displays no independently computed final charge: `chargedMilliseconds` remains authoritative. Closing audio and changing visible screens do not discard the lease needed for cutoff or the short final-assessment window.

## Verification and remaining release gates

The combined integration run passed all 176 JVM tests with no skips, including 12 conversation-provider tests and 16 hosted-client tests. Coverage includes provider selection, ownership, cancellation, uncertain requests, the post-end window, bounded context, current-session recovery, provenance after restart/edit, local reservation expiry and versioned billing-minimum metadata. The same run passed the commerce and account tests. Android lint completed with zero errors (42 warnings and two hints across the app). These tests use synthetic accounts and a mock HTTP server; no provider call or purchase was made.

Still required before enabling hosted use: wire the account controls and guards; add the separately approved guest identity namespace; run the full hosted flow with an allowlisted account on the emulator and a physical Android phone; verify minute settlement, interruptions, account renewal and free-budget limits against the deployed server; review the aggregate voice/helper budget and restart economics. Research can remain unavailable when the conservative search liability exceeds the session helper budget. Public capability flags must remain disabled until those checks and cost parameters are approved.
