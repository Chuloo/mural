package chat.mural.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

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
    var onResult: (suspend (FinalAssessmentResult) -> Unit)? = null
    /** Must commit the retry count and transcript before a provider request can start. */
    var beforeAssessment: (suspend (SessionRecord, Passage) -> Boolean)? = null
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
                if (beforeAssessment?.invoke(snapshot, passage) == false) {
                    if (jobs[snapshot.id]?.token === token) jobs.remove(snapshot.id)?.timer?.cancel()
                    return@launch
                }
                if (jobs[snapshot.id]?.token !== token || clock() > deadline) return@launch
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
        timer.start()
        request.start()
        return true
    }

    fun isPending(id: String) = jobs.containsKey(id)

    fun cancel(id: String) {
        val job = jobs.remove(id) ?: return
        job.request.cancel(); job.timer.cancel()
    }

    fun cancelAll() = jobs.keys.toList().forEach(::cancel)
}

/** Local retry metadata. It contains no transcript, credentials or provider response. */
@Serializable
data class FinalAssessmentTicket(
    val sessionID: String,
    val passageID: String,
    val revisionKey: String,
    val attempts: Int = 0,
    val lastAttemptAt: Double? = null,
) {
    fun matches(session: SessionRecord, passage: Passage): Boolean =
        sessionID == session.id && passageID == passage.id && revisionKey == passage.revisionKey
}

/** Retries only explicitly queued work; importing an archive does not trigger cloud requests. */
object FinalAssessmentRecovery {
    const val MAX_ATTEMPTS = 3
    const val MAX_RECOVERED_PER_LAUNCH = 5
    private const val MAX_AGE_SECONDS = 7 * 24 * 60 * 60.0

    fun passage(session: SessionRecord): Passage? {
        if (session.endedAt == null) return null
        val passage = session.passages.lastOrNull { it.speaker == Speaker.user } ?: return null
        return passage.takeIf { it.text.length >= 3 && session.assessments.none { assessment ->
            assessment.passageID == it.id && assessment.revisionKey == it.revisionKey
        } }
    }

    fun enqueue(session: SessionRecord, tickets: List<FinalAssessmentTicket>): List<FinalAssessmentTicket> {
        val passage = passage(session)
        val existing = tickets.firstOrNull { it.sessionID == session.id }
        if (passage != null && existing?.matches(session, passage) == true) return tickets
        return tickets.filterNot { it.sessionID == session.id } +
            listOfNotNull(passage?.let { FinalAssessmentTicket(session.id, it.id, it.revisionKey) })
    }

    fun canAttempt(ticket: FinalAssessmentTicket, session: SessionRecord, now: Double): Boolean {
        val passage = passage(session) ?: return false
        val endedAt = session.endedAt ?: return false
        if (!ticket.matches(session, passage) || ticket.attempts !in 0 until MAX_ATTEMPTS ||
            !now.isFinite() || now - endedAt !in 0.0..MAX_AGE_SECONDS) return false
        val last = ticket.lastAttemptAt ?: return ticket.attempts == 0
        if (!last.isFinite() || ticket.attempts == 0) return false
        val retryDelay = 60.0 * (1 shl (ticket.attempts - 1))
        return now - last >= retryDelay
    }

    fun recover(sessions: List<SessionRecord>, tickets: List<FinalAssessmentTicket>, now: Double): List<SessionRecord> {
        val byID = sessions.associateBy { it.id }
        return tickets.mapNotNull { ticket -> byID[ticket.sessionID]?.takeIf { canAttempt(ticket, it, now) } }
            .sortedByDescending { it.endedAt }
            .take(MAX_RECOVERED_PER_LAUNCH)
    }
}
