package chat.mural.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-dispatcher confined. A readiness result belongs to one account and one exact login session. */
class HostedReadinessController(
    private val scope: CoroutineScope,
    private val readSession: suspend () -> AccountSession?,
    private val fetch: suspend (AccountSession) -> HostedReadiness,
    private val changed: (HostedReadiness) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var value = HostedReadiness(); private set
    private var generation = 0L
    private var job: Job? = null
    private var selectionKnown = false
    private var selectedAccountID: String? = null
    private var transitioning = false

    fun selectAccount(accountID: String?, busy: Boolean) {
        if (selectionKnown && selectedAccountID == accountID && transitioning == busy) return
        selectionKnown = true
        selectedAccountID = accountID
        transitioning = busy
        invalidate()
        if (!busy && accountID != null) refresh()
    }

    private fun invalidate() {
        generation++
        job?.cancel(); job = null
        publish(HostedReadiness())
    }

    fun refresh() {
        // Every request supersedes the old read; a refresh during an in-flight check is never lost.
        invalidate()
        if (transitioning || (selectionKnown && selectedAccountID == null)) return
        val ticket = generation
        publish(HostedReadiness(checking = true))
        job = scope.launch {
            val result = try {
                val owner = readSession()?.takeIf { it.isValid(now()) }
                if (owner == null || (selectionKnown && owner.accountID != selectedAccountID)) HostedReadiness()
                else {
                    val fetched = fetch(owner)
                    if (readSession() != owner || !owner.isValid(now()) || fetched.accountID != owner.accountID)
                        HostedReadiness()
                    else fetched.copy(checking = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HostedReadiness() }
            if (generation == ticket) publish(result)
        }
    }

    private fun publish(readiness: HostedReadiness) { value = readiness; changed(readiness) }
}
