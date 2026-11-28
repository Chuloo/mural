package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportControllerTest {
    private val selection = ReportSelection("local-session", "local-passage", "es", "Una frase.")
    private val report = AIReportSubmission("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect", "Una frase.", REPORT_CONSENT_VERSION)
    private class Storage {
        val saved = mutableListOf<ReportRecord>()
        var action: suspend () -> Unit = {}
        suspend fun save(record: ReportRecord) { saved += record; action() }
    }

    @Test fun openingProbesNothingAndSubmittingSavesTheReportOnThisPhone() = runTest {
        val storage = Storage()
        val controller = ReportController(this, storage::save, now = { 1_234.0 })
        controller.open(selection)
        // No capability check exists: the sheet is ready at once and nothing reached a network.
        assertEquals(ReportDelivery.IDLE, controller.state.value.delivery)
        assertTrue(storage.saved.isEmpty())
        controller.submit(report); runCurrent()
        assertEquals(ReportDelivery.SAVED, controller.state.value.delivery)
        assertEquals(listOf(ReportRecord(report.reportID, "es", "incorrect", "Una frase.", REPORT_CONSENT_VERSION, 1_234.0)),
            storage.saved)
        controller.submit(report); runCurrent(); assertEquals(1, storage.saved.size)
    }

    @Test fun duplicateTapCannotSaveTwiceAndManualRetryKeepsID() = runTest {
        val storage = Storage().apply { action = { throw IllegalStateException("storage busy") } }
        val controller = ReportController(this, storage::save)
        controller.open(selection); runCurrent()
        controller.submit(report); controller.submit(report)
        assertEquals(ReportDelivery.SAVING, controller.state.value.delivery)
        runCurrent(); assertEquals(1, storage.saved.size); assertEquals(ReportDelivery.FAILED, controller.state.value.delivery)
        storage.action = {}
        controller.submit(report); runCurrent()
        assertEquals(2, storage.saved.size); assertEquals(storage.saved[0].reportID, storage.saved[1].reportID)
        assertEquals(ReportDelivery.SAVED, controller.state.value.delivery)
        controller.submit(report); runCurrent(); assertEquals(2, storage.saved.size)
    }

    @Test fun rejectedConsentLanguageAndMalformedPayloadNeverReachStorage() = runTest {
        val storage = Storage()
        val controller = ReportController(this, storage::save)
        controller.open(selection); runCurrent()
        for (invalid in listOf(report.copy(consentVersion = ""), report.copy(languageID = "en"), report.copy(reason = "unknown"),
            report.copy(reportID = "not-uuid"), report.copy(excerpt = "\ud83d"), report.copy(excerpt = " "),
            report.copy(excerpt = "a".repeat(2001)), report.copy(excerpt = "sk-" + "example-not-a-key".repeat(3)))) {
            controller.submit(invalid); runCurrent()
        }
        assertTrue(storage.saved.isEmpty()); assertEquals(ReportDelivery.IDLE, controller.state.value.delivery)
    }

    @Test fun anotherLanguageSelectionCannotBeSavedUnderTheOpenOne() = runTest {
        val storage = Storage()
        val controller = ReportController(this, storage::save)
        controller.open(selection); runCurrent()
        controller.submit(report.copy(languageID = "it")); runCurrent()
        assertTrue(storage.saved.isEmpty()); assertEquals(ReportDelivery.IDLE, controller.state.value.delivery)
    }

    @Test fun dismissedCompletionCannotChangeNewReportEvenIfStorageIgnoresCancellation() = runTest {
        val completion = CompletableDeferred<Unit>()
        val storage = Storage().apply { action = { withContext(NonCancellable) { completion.await() } } }
        val controller = ReportController(this, storage::save)
        controller.open(selection); runCurrent(); controller.submit(report); runCurrent()
        controller.dismiss(); controller.open(selection.copy(passageID = "new", excerpt = "Otra frase.")); runCurrent()
        completion.complete(Unit); runCurrent()
        assertEquals("Otra frase.", controller.state.value.selection?.excerpt)
        assertEquals(ReportDelivery.IDLE, controller.state.value.delivery)
    }

    @Test fun selectedAssistantTextIsFrozenAndUserPassagesAreNeverReportSources() {
        val assistant = Fragment(id = "reply", speaker = Speaker.assistant, text = "Una frase.", startMS = 0, endMS = 1)
        val user = Fragment(id = "learner", speaker = Speaker.user, text = "Private reply", startMS = 2, endMS = 3)
        val session = SessionRecord(languageID = "es", fragments = mutableListOf(assistant, user), endedAt = nowSeconds())
        val selected = ReportSelection.from(session, "reply")!!
        assistant.text = "The next streamed delta"
        assertEquals("Una frase.", selected.excerpt)
        assertNull(ReportSelection.from(session, "learner")); assertNull(ReportSelection.from(session, "missing"))
        assertFalse(selected.toString().contains(selected.excerpt))
    }
}
