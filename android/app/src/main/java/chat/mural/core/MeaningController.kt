package chat.mural.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MeaningRequest(
    val sessionID: String, val passageID: String, val revisionKey: String, val text: String,
    val learningLanguageID: String, val meaningLanguage: String,
) {
    constructor(sessionID: String, passage: Passage, learningLanguageID: String, meaningLanguage: String) :
        this(sessionID, passage.id, passage.revisionKey, passage.text, learningLanguageID, meaningLanguage)

    val cacheKey get() = cacheKey(revisionKey, meaningLanguage)

    fun sharesContext(other: MeaningRequest) = sessionID == other.sessionID && passageID == other.passageID &&
        learningLanguageID == other.learningLanguageID && meaningLanguage == other.meaningLanguage

    companion object {
        fun cacheKey(revisionKey: String, language: String) = "$language::$revisionKey"
    }
}

data class MeaningResult(val text: String, val inputTokens: Int = 0, val outputTokens: Int = 0)

class EmptyMeaningException : Exception("The translation came back empty.")

/** Keeps one translation in flight while coalescing growing transcript fragments. */
class MeaningController(
    private val scope: CoroutineScope,
    private val delayMillis: Long = 450,
    private val translate: suspend (MeaningRequest) -> MeaningResult,
) {
    var text = ""; private set
    var isLoading = false; private set
    var error: Throwable? = null; private set
    var onResult: ((MeaningRequest, MeaningResult) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private var desired: MeaningRequest? = null
    private var rendered: MeaningRequest? = null
    private var worker: Job? = null
    private var generation = 0

    fun update(request: MeaningRequest, cached: String? = null) {
        if (desired?.sharesContext(request) != true) reset()
        desired = request
        if (!cached.isNullOrEmpty()) {
            cancelWorker(); text = cached; rendered = request; error = null; onChange?.invoke(); return
        }
        if (rendered == request) return
        rendered?.let { if (!request.text.startsWith(it.text)) { text = ""; rendered = null } }
        if (worker == null && error == null) begin()
        onChange?.invoke()
    }

    fun reset() {
        cancelWorker(); desired = null; rendered = null; text = ""; error = null
        onChange?.invoke()
    }

    fun retry() {
        if (desired == null) return
        cancelWorker(); error = null; begin()
        onChange?.invoke()
    }

    private fun cancelWorker() {
        generation++; worker?.cancel(); worker = null; isLoading = false
    }

    private fun begin() {
        if (desired == null || worker != null) return
        isLoading = true
        val token = generation
        worker = scope.launch {
            try {
                delay(delayMillis)
                val request = desired
                if (token != generation || request == null) return@launch
                val result = translate(request)
                val latest = desired
                if (token != generation || latest == null) return@launch
                if (result.text.isBlank()) throw EmptyMeaningException()
                onResult?.invoke(request, result)
                if (latest.sharesContext(request) && latest.text.startsWith(request.text)) {
                    text = result.text; rendered = request
                }
                worker = null; isLoading = false
                if (latest != request) begin()
                onChange?.invoke()
            } catch (e: CancellationException) {
                if (token == generation) throw e
            } catch (e: Exception) {
                if (token != generation) return@launch
                worker = null; isLoading = false; error = e
                onChange?.invoke()
            }
        }
    }
}
