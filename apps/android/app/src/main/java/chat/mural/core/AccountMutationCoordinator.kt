package chat.mural.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Main-dispatcher confined. Committed changes belong to the retained owner, never its UI waiter. */
class AccountMutationCoordinator(private val scope: CoroutineScope) {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var generation = 0L

    /** Interactive sign-in can share the guard while its credential chooser remains Activity-bound. */
    fun begin(): Long? {
        if (mutableBusy.value) return null
        mutableBusy.value = true
        return ++generation
    }

    fun end(ticket: Long) {
        if (ticket == generation) mutableBusy.value = false
    }

    fun submit(prepare: suspend () -> Boolean, mutate: suspend () -> Unit, finished: () -> Unit = {}): Boolean {
        val ticket = begin() ?: return false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (prepare()) {
                    // A cancelled settlement cannot authorize a later account mutation.
                    currentCoroutineContext().ensureActive()
                    mutate()
                }
            } finally {
                end(ticket)
                finished()
            }
        }
        return true
    }
}
