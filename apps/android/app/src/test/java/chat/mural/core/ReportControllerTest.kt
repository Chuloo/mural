package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportControllerTest {
    private val selection = ReportSelection("local-session", "local-passage", "es", "Una frase.")
    private val report = AIReportSubmission("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect", "Una frase.", REPORT_CONSENT_VERSION)
    private class Fake : ReportService {
        var enabled = true
        var checks = 0
        val sent = mutableListOf<AIReportSubmission>()
        var submitAction: suspend () -> Unit = {}
        override suspend fun available(): Boolean { checks++; return enabled }
        override suspend fun submit(report: AIReportSubmission) { sent += report; submitAction() }
    }

    @Test fun openingChecksCapabilityOnlyAndDisabledOrUnconfiguredCannotSubmit() = runTest {
        val api = Fake().apply { enabled = false }; val controller = ReportController(this, api)
        runCurrent(); assertEquals(0, api.checks)
        controller.open(selection); assertEquals(ReportDelivery.CHECKING, controller.state.value.delivery)
        controller.submit(report); runCurrent()
        assertEquals(1, api.checks); assertTrue(api.sent.isEmpty())
        assertEquals(ReportDelivery.UNAVAILABLE, controller.state.value.delivery)
        controller.submit(report); runCurrent(); assertTrue(api.sent.isEmpty())
        val unconfigured = ReportController(this, null)
        unconfigured.open(selection); unconfigured.submit(report)
        assertEquals(ReportDelivery.UNAVAILABLE, unconfigured.state.value.delivery)
    }

    @Test fun duplicateTapCannotSubmitTwiceAndManualRetryKeepsID() = runTest {
        val api = Fake().apply { submitAction = { throw ReportFailure.Retry } }
        val controller = ReportController(this, api)
        controller.open(selection); runCurrent()
        controller.submit(report); controller.submit(report)
        assertEquals(ReportDelivery.SENDING, controller.state.value.delivery)
        runCurrent(); assertEquals(1, api.sent.size); assertEquals(ReportDelivery.FAILED, controller.state.value.delivery)
        api.submitAction = {}
        controller.submit(report); runCurrent()
        assertEquals(2, api.sent.size); assertEquals(api.sent[0].reportID, api.sent[1].reportID)
        assertEquals(ReportDelivery.SENT, controller.state.value.delivery)
        controller.submit(report); runCurrent(); assertEquals(2, api.sent.size)
    }

    @Test fun revokedCapabilityBecomesUnavailableWithoutAutomaticRetry() = runTest {
        val api = Fake().apply { submitAction = { throw ReportFailure.Unavailable } }
        val controller = ReportController(this, api)
        controller.open(selection); runCurrent(); controller.submit(report); advanceUntilIdle()
        assertEquals(ReportDelivery.UNAVAILABLE, controller.state.value.delivery)
        assertFalse(controller.state.value.available)
        controller.submit(report); advanceUntilIdle(); assertEquals(1, api.sent.size)
    }

    @Test fun rejectedConsentLanguageAndMalformedPayloadNeverReachNetwork() = runTest {
        val api = Fake(); val controller = ReportController(this, api)
        controller.open(selection); runCurrent()
        for (invalid in listOf(report.copy(consentVersion = ""), report.copy(languageID = "en"), report.copy(reason = "unknown"),
            report.copy(reportID = "not-uuid"), report.copy(excerpt = "\ud83d"), report.copy(excerpt = " "),
            report.copy(excerpt = "a".repeat(2001)), report.copy(excerpt = "sk-" + "example-not-a-key".repeat(3)))) {
            controller.submit(invalid); runCurrent()
        }
        assertTrue(api.sent.isEmpty()); assertEquals(ReportDelivery.IDLE, controller.state.value.delivery)
    }

    @Test fun dismissedCompletionCannotChangeNewReportEvenIfTransportIgnoresCancellation() = runTest {
        val completion = CompletableDeferred<Unit>()
        val api = Fake().apply { submitAction = { withContext(NonCancellable) { completion.await() } } }
        val controller = ReportController(this, api)
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
