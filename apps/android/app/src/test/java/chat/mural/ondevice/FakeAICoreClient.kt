package chat.mural.ondevice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A fake AICore backend for JVM tests: it scripts the status the system service reports and the
 * deltas each generation streams, so the AICore Model Runtime is observed through its own seam with
 * no device, no Binder call and no system model.
 */
class FakeAICoreClient(
    var status: AICoreStatus = AICoreStatus.AVAILABLE,
    /** What the service reports once it has been asked to fetch the model. */
    var statusAfterDownload: AICoreStatus = AICoreStatus.AVAILABLE,
) : AICoreClient {
    /** Raised after a generation's deltas, as a failure that arrives mid-reply. */
    var failure: String? = null
    /** Raised by [checkStatus] instead of reporting a status: a phone that cannot reach the service. */
    var statusFailure: String? = null
    /** A delay before each delta: virtual time far enough ahead to cancel mid-reply. */
    var hangMillis: Long = 0

    data class Recorded(val prompt: String, val systemPrompt: String?, val maxOutputTokens: Int?)

    val requests = mutableListOf<Recorded>()
    var downloads = 0
    var closed = false
    private val scripted = ArrayDeque<List<String>>()

    /** Scripts one more generation: the deltas it streams, in order. */
    fun reply(vararg deltas: String) { scripted += deltas.toList() }

    override suspend fun checkStatus(): AICoreStatus {
        statusFailure?.let { throw IllegalStateException(it) }
        return if (downloads > 0) statusAfterDownload else status
    }

    override suspend fun download() { downloads++ }

    override fun generateContentStream(prompt: String, systemPrompt: String?, maxOutputTokens: Int?): Flow<String> =
        flow {
            requests += Recorded(prompt, systemPrompt, maxOutputTokens)
            for (delta in scripted.removeFirstOrNull().orEmpty()) {
                if (hangMillis > 0) delay(hangMillis)
                emit(delta)
            }
            // Not a ModelRuntimeException: the runtime is what wraps a backend failure.
            failure?.let { throw IllegalStateException(it) }
        }

    override fun close() { closed = true }
}
