package chat.mural.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class FinalAssessmentResult(
    val sessionID: String, val languageID: String, val assessment: Assessment,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val searchCalls: Int = 0,
) {
    /** Applies only to the original saved transcript, which may have changed or been deleted. */
    fun applying(current: SessionRecord?): SessionRecord? {
        if (current == null || current.id != sessionID || current.languageID != languageID || current.endedAt == null) return null
        val validated = LearningEngine.validate(assessment, current) ?: return null
        if (current.assessments.any { it.passageID == assessment.passageID && it.revisionKey == assessment.revisionKey }) return null
        val updated = current.copy(
            fragments = current.fragments.toMutableList(),
            assessments = current.assessments.filterNot { it.passageID == validated.passageID }.toMutableList(),
            translations = current.translations.toMutableMap(),
            topics = current.topics.toMutableList(),
        )
        updated.assessments += validated
        updated.inputTokens += inputTokens; updated.outputTokens += outputTokens; updated.searchCalls += searchCalls
        return updated
    }
}

/** Finishes the latest unassessed user passage without owning the visible conversation. */
class FinalAssessmentQueue(
    private val scope: CoroutineScope,
    timeoutMillis: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val assess: suspend (SessionRecord, Passage) -> FinalAssessmentResult,
) {
    var onResult: ((FinalAssessmentResult) -> Unit)? = null
    private val timeoutMillis = timeoutMillis.coerceIn(1, 15_000)
    private class Pending(val token: Any, val deadline: Long, val request: Job, val timer: Job)
    private val jobs = mutableMapOf<String, Pending>()

    fun submit(session: SessionRecord): Boolean {
        if (session.endedAt == null || jobs.containsKey(session.id)) return false
        val passage = session.passages.lastOrNull { it.speaker == Speaker.user } ?: return false
        if (passage.text.length < 3 || session.assessments.any { it.passageID == passage.id && it.revisionKey == passage.revisionKey }) return false
        val snapshot = session.copy(fragments = session.fragments.toMutableList(), assessments = session.assessments.toMutableList())
        val token = Any()
        val deadline = clock() + timeoutMillis
        val request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = assess(snapshot, passage)
                if (jobs[snapshot.id]?.token !== token) return@launch
                jobs.remove(snapshot.id)?.timer?.cancel()
                if (clock() > deadline || result.sessionID != snapshot.id || result.languageID != snapshot.languageID) return@launch
                onResult?.invoke(result)
            } catch (e: Exception) {
                if (jobs[snapshot.id]?.token === token) jobs.remove(snapshot.id)?.timer?.cancel()
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMillis)
            if (jobs[snapshot.id]?.token === token) cancel(snapshot.id)
        }
        jobs[snapshot.id] = Pending(token, deadline, request, timer)
        request.start(); timer.start()
        return true
    }

    fun isPending(id: String) = jobs.containsKey(id)

    fun cancel(id: String) {
        val job = jobs.remove(id) ?: return
        job.request.cancel(); job.timer.cancel()
    }

    fun cancelAll() = jobs.keys.toList().forEach(::cancel)
}
