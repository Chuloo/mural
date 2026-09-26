package chat.mural.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    /** Caption text sent to the translation helper. Must match what the learner sees for this revision. */
    val translationInput get() = translationInput(text)

    fun sharesContext(other: MeaningRequest) = sessionID == other.sessionID && passageID == other.passageID &&
        learningLanguageID == other.learningLanguageID && meaningLanguage == other.meaningLanguage

    companion object {
        fun cacheKey(revisionKey: String, language: String) = "caption2/$language::$revisionKey"
        fun translationInput(text: String): String = text
    }
}

data class MeaningResult(val text: String, val inputTokens: Int = 0, val outputTokens: Int = 0)

class MeaningInputLimitException : Exception("This caption is too long to translate in one request.")

class EmptyMeaningException : Exception("The translation came back empty.")

/** Waits for a sentence or quiet transcript, then keeps one translation in flight. */
class MeaningController(
    private val scope: CoroutineScope,
    private val delayMillis: Long = 450,
    private val incompleteDelayMillis: Long = 1800,
    private val canRetryFailure: (Throwable) -> Boolean = { false },
    private val retryDelay: (Throwable) -> Long? = { null },
    private val minimumSpacingMillis: Long = 2500,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val stream: (suspend (MeaningRequest, (String) -> Unit) -> MeaningResult)? = null,
    private val translate: suspend (MeaningRequest) -> MeaningResult,
) {
    var text = ""; private set
    var isLoading = false; private set
    var error: Throwable? = null; private set
    var onResult: ((MeaningRequest, MeaningResult) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private var desired: MeaningRequest? = null
    private var rendered: MeaningRequest? = null
    private var displayed: MeaningRequest? = null
    private var activeTranslation: Long? = null
    private var nextTranslation = 0L
    private var worker: Job? = null
    private var generation = 0
    private var translating = false
    private var finalRequested = false
    private var finalRetryAvailable = false
    private var sessionEnded = false
    private var desiredUpdatedAt = 0L
    private var lastDispatchedAt: Long? = null
    private var automaticRetries = 0
    private var retryWindowStartedAt: Long? = null
    private var retryNotBefore = 0L
    private var pendingFailure: Throwable? = null

    fun update(request: MeaningRequest, cached: String? = null, utteranceComplete: Boolean = false, conversationEnded: Boolean = false) {
        if (desired?.sharesContext(request) != true) {
            val sameConversation = desired?.let { it.sessionID == request.sessionID &&
                it.learningLanguageID == request.learningLanguageID && it.meaningLanguage == request.meaningLanguage } == true
            if (translating && sameConversation) {
                // A new display passage waits for the current request instead of starting a second helper.
                rendered = null; displayed = null; text = ""; error = null; finalRequested = false; finalRetryAvailable = false; sessionEnded = false
                automaticRetries = 0; retryWindowStartedAt = null; retryNotBefore = 0; pendingFailure = null
            } else reset()
        }
        val changed = desired != request
        desired = request
        if (changed) desiredUpdatedAt = now()
        val newBoundary = (utteranceComplete && (!finalRequested || changed)) || (conversationEnded && !sessionEnded)
        if (newBoundary) finalRetryAvailable = true
        finalRequested = utteranceComplete || conversationEnded
        sessionEnded = sessionEnded || conversationEnded
        // Only a conclusive pre-provider rejection may be reopened automatically at a turn boundary.
        if (newBoundary && error?.let(canRetryFailure) == true) {
            val failure = error!!
            prepareAutomaticRetry(failure, retryDelay(failure) ?: 0)
            finalRetryAvailable = false
        }
        if (!cached.isNullOrEmpty()) {
            if (!translating) cancelWorker()
            text = cached; rendered = request; displayed = request; error = null; isLoading = false
            pendingFailure = null; retryNotBefore = 0
            onChange?.invoke(); return
        }
        if (rendered == request) return
        displayed?.let { if (!request.text.startsWith(it.text)) { text = ""; rendered = null; displayed = null } }
        // A waiting timer follows the latest fragment; an admitted request is never cancelled by speech.
        if (!translating && (changed || utteranceComplete || conversationEnded)) cancelWorker()
        if (worker == null && error == null) begin()
        onChange?.invoke()
    }

    fun reset() {
        cancelWorker(); desired = null; rendered = null; displayed = null; text = ""; error = null
        finalRequested = false; finalRetryAvailable = false; sessionEnded = false
        automaticRetries = 0; retryWindowStartedAt = null; retryNotBefore = 0; pendingFailure = null
        onChange?.invoke()
    }

    fun retry() {
        if (desired == null) return
        if (translating) return
        cancelWorker(); error = null; finalRequested = true; finalRetryAvailable = false
        automaticRetries = 0; retryWindowStartedAt = null; retryNotBefore = 0; pendingFailure = null
        begin()
        onChange?.invoke()
    }

    private fun cancelWorker() {
        generation++; activeTranslation = null; worker?.cancel(); worker = null; isLoading = false; translating = false
    }

    private fun endsSentence(value: String): Boolean =
        value.trimEnd().trimEnd('"', '\'', '”', '’', '»', ')').lastOrNull() in setOf('.', '!', '?', '。', '！', '？', '…')

    private fun prepareAutomaticRetry(failure: Throwable, wait: Long): Boolean {
        val current = now()
        val started = retryWindowStartedAt ?: current
        if (wait !in 0..30_000 || automaticRetries >= 3 || current - started + wait >= 30_000) return false
        retryWindowStartedAt = started; automaticRetries++
        retryNotBefore = current + wait; pendingFailure = failure; error = null
        return true
    }

    private fun begin() {
        if (desired == null || rendered == desired || worker != null) return
        isLoading = true
        val token = generation
        worker = scope.launch(start = CoroutineStart.LAZY) {
            var dispatched: MeaningRequest? = null
            try {
                val initial = desired ?: return@launch
                val current = now()
                val quietUntil = desiredUpdatedAt + if (finalRequested) 0 else if (endsSentence(initial.text)) delayMillis else incompleteDelayMillis
                val pacedUntil = lastDispatchedAt?.let { it + minimumSpacingMillis } ?: current
                delay((maxOf(quietUntil, pacedUntil, retryNotBefore) - current).coerceAtLeast(0))
                val request = desired
                if (token != generation || request == null) return@launch
                pendingFailure?.let { failure ->
                    if (now() - (retryWindowStartedAt ?: now()) >= 30_000) throw failure
                }
                pendingFailure = null; retryNotBefore = 0
                translating = true; lastDispatchedAt = now(); dispatched = request
                val translation = ++nextTranslation; activeTranslation = translation
                // Retain the readable prefix until the next stream has caught up.
                val minimumPartialLength = text.length
                val result = if (stream == null) translate(request) else stream.invoke(request) { partial ->
                    val latest = desired
                    if (token == generation && activeTranslation == translation && latest != null && rendered != latest &&
                        latest.sharesContext(request) && latest.text.startsWith(request.text) && partial.isNotEmpty() && partial.length >= minimumPartialLength) {
                        text = partial; displayed = request; onChange?.invoke()
                    }
                }
                val latest = desired
                if (token != generation || latest == null) return@launch
                activeTranslation = null
                if (result.text.isBlank()) throw EmptyMeaningException()
                onResult?.invoke(request, result)
                if (rendered != latest && latest.sharesContext(request) && latest.text.startsWith(request.text)) {
                    text = result.text; rendered = request; displayed = request
                }
                worker = null; isLoading = false; translating = false
                if (latest != request) begin()
                onChange?.invoke()
            } catch (e: CancellationException) {
                if (token == generation) throw e
            } catch (e: Exception) {
                if (token != generation) return@launch
                activeTranslation = null; worker = null; isLoading = false; translating = false
                if (rendered == desired) { error = null; onChange?.invoke(); return@launch }
                if (dispatched != null && desired?.sharesContext(dispatched) == false) {
                    // Do not retry or render the old passage; continue with the separately requested new one.
                    begin(); onChange?.invoke(); return@launch
                }
                error = e
                // A prefix translation is not the meaning of a completed, longer utterance.
                if (rendered != desired) text = ""
                // End may arrive while a previously admitted request is completing or being rejected.
                val wait = retryDelay(e) ?: if (finalRequested && finalRetryAvailable && canRetryFailure(e)) 0L else null
                finalRetryAvailable = false
                if (wait != null && prepareAutomaticRetry(e, wait)) begin()
                onChange?.invoke()
            }
        }
        worker?.start()
    }
}
