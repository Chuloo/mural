package chat.mural.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** Adding a verified member identity does not revoke a guest lease or transfer its funds.
 * Destructive account changes still require the separate settlement gate. */
class AccountSignInPreparation(
    private val finishLocalWork: suspend () -> Boolean,
    private val pendingOwner: () -> String?,
    private val guestOwns: suspend (String) -> Boolean,
    private val prepareAccountChange: suspend () -> Boolean,
) {
    suspend fun prepare(): Boolean {
        if (!finishLocalWork()) return false
        val owner = pendingOwner()
        // The guest bearer and durable owner remain available after member OAuth completes.
        if (owner != null && guestOwns(owner)) return true
        return prepareAccountChange()
    }
}

/** A timeout stops sign-in but must explain why; external cancellation remains cancellation. */
suspend fun awaitLocalSignInStartup(job: Job?, onTimeout: () -> Unit): Boolean {
    if (withTimeoutOrNull(10_000) { job?.join(); true } == true) return true
    onTimeout()
    return false
}
