package chat.mural.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalAssessmentQueueTest {
    private class Provider {
        val pending = ArrayDeque<Triple<SessionRecord, Passage, CompletableDeferred<FinalAssessmentResult>>>()
        suspend fun assess(session: SessionRecord, passage: Passage): FinalAssessmentResult {
            val response = CompletableDeferred<FinalAssessmentResult>()
            pending.addLast(Triple(session, passage, response))
            // Ignores cancellation, like a response already in flight.
            return withContext(NonCancellable) { response.await() }
        }
        fun finish() {
            val (session, passage, response) = pending.removeFirst()
            val assessment = Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Una pregunta más.", "Names an object",
                listOf(WordProposal("la radio", "radio", "radio", EvidenceKind.independent, 0.95, passage.fragments.map { it.id }, passage.text, session.languageID)))
            response.complete(FinalAssessmentResult(session.id, session.languageID, assessment, inputTokens = 100, outputTokens = 20))
        }
    }

    private fun ended(languageID: String = "es") = SessionRecord(languageID = languageID).apply {
        append(Fragment(speaker = Speaker.user, text = "radio", startMS = 0, endMS = 1000))
        endedAt = nowSeconds()
    }

    private fun TestScope.queue(provider: Provider, timeoutMillis: Long = 15_000) =
        FinalAssessmentQueue(backgroundScope, timeoutMillis, assess = provider::assess)

    @Test fun resetAndNewLanguageDoNotRedirectResultsToTheNewSession() = runTest {
        val old = ended(); val new = ended("fr"); val provider = Provider()
        val records = mutableMapOf(old.id to old, new.id to new)
        var visible: SessionRecord? = old
        val queue = queue(provider)
        queue.onResult = { result ->
            result.applying(records[result.sessionID])?.let { records[it.id] = it }
            if (visible?.id == result.sessionID) visible = records[result.sessionID]
        }
        assertTrue(queue.submit(old)); runCurrent()
        assertEquals(1, provider.pending.size)
        visible = null
        visible = new
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(old.id))
        assertEquals(1, records.getValue(old.id).assessments.size)
        assertEquals(100, records.getValue(old.id).inputTokens)
        assertTrue(records.getValue(new.id).assessments.isEmpty())
        assertEquals(new.id, visible?.id)
        assertTrue(visible!!.assessments.isEmpty())
    }

    @Test fun deletedSessionIsNeverRecreatedByALateResult() = runTest {
        val session = ended(); val provider = Provider()
        val records = mutableMapOf(session.id to session)
        val queue = queue(provider)
        queue.onResult = { result -> result.applying(records[result.sessionID])?.let { records[it.id] = it } }
        queue.submit(session); runCurrent()
        records.remove(session.id)
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertTrue(records.isEmpty())
    }

    @Test fun cancellationAndDeadlineRejectResponsesThatIgnoreCancellation() = runTest {
        for (explicitlyCancel in listOf(true, false)) {
            val session = ended(); val provider = Provider()
            var received = 0
            val queue = queue(provider, timeoutMillis = 30)
            queue.onResult = { received++ }
            queue.submit(session); runCurrent()
            assertEquals(1, provider.pending.size)
            if (explicitlyCancel) queue.cancel(session.id) else { advanceTimeBy(31); runCurrent() }
            assertFalse(queue.isPending(session.id))
            provider.finish(); runCurrent()
            assertEquals(0, received)
        }
    }

    @Test fun wallClockDeadlineRejectsAResponseAfterTheDeviceSlept() = runTest {
        val session = ended(); val provider = Provider()
        var now = 1_000_000L
        var received = 0
        val queue = FinalAssessmentQueue(backgroundScope, 15_000, clock = { now }, assess = provider::assess)
        queue.onResult = { received++ }
        queue.submit(session); runCurrent()
        now += 20_000
        provider.finish(); runCurrent()
        assertEquals(0, received)
        assertFalse(queue.isPending(session.id))
    }

    @Test fun cancelAllRejectsEveryPendingResult() = runTest {
        val first = ended(); val second = ended("fr"); val provider = Provider()
        var received = 0
        val queue = queue(provider)
        queue.onResult = { received++ }
        queue.submit(first); queue.submit(second); runCurrent()
        queue.cancelAll()
        assertFalse(queue.isPending(first.id) || queue.isPending(second.id))
        provider.finish(); provider.finish(); runCurrent()
        assertEquals(0, received)
    }

    @Test fun correctedTranscriptRejectsTheOriginalAssessment() = runTest {
        val session = ended(); val provider = Provider(); val queue = queue(provider)
        var applied = false
        queue.onResult = { result -> applied = result.applying(session) != null }
        queue.submit(session); runCurrent()
        session.correctFragment(session.fragments[0].id, "televisión")
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertFalse(applied)
    }

    @Test fun alreadyAssessedAndPendingPassagesDoNotStartDuplicateRequests() = runTest {
        var session = ended(); val provider = Provider(); val queue = queue(provider)
        queue.onResult = { result -> session = result.applying(session)!! }
        assertTrue(queue.submit(session))
        assertFalse(queue.submit(session))
        runCurrent()
        assertEquals(1, provider.pending.size)
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertFalse(queue.submit(session))
        assertEquals(1, session.assessments.size)
        assertEquals(20, session.outputTokens)
    }
}
