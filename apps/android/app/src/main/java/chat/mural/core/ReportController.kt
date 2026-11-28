package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable local selection; it is never serialized or added to learning archives. */
data class ReportSelection(val sessionID: String, val passageID: String, val languageID: String, val excerpt: String) {
    override fun toString() = "ReportSelection([redacted])"
    companion object {
        fun from(session: SessionRecord, passageID: String): ReportSelection? {
            val passage = session.passages.firstOrNull { it.id == passageID && it.speaker == Speaker.assistant }
                ?.takeIf { it.text.isNotBlank() } ?: return null
            return ReportSelection(session.id, passageID, session.languageID, passage.text)
        }
    }
}

internal fun AIReportSubmission.isValid(): Boolean {
    if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE).matches(reportID) ||
        !Regex("[a-z]{2,3}").matches(languageID) || ReportReason.entries.none { it.wireValue == reason } ||
        consentVersion != REPORT_CONSENT_VERSION || !validReportExcerpt(excerpt)) return false
    return true
}

internal fun validReportExcerpt(excerpt: String): Boolean {
    if (excerpt.isBlank() || excerpt.length > REPORT_EXCERPT_LIMIT || reportExcerptContainsCredential(excerpt) ||
        Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f\\u202a-\\u202e\\u2066-\\u2069]").containsMatchIn(excerpt)) return false
    var i = 0
    while (i < excerpt.length) {
        val char = excerpt[i++]
        if (char.isLowSurrogate()) return false
        if (char.isHighSurrogate() && (i >= excerpt.length || !excerpt[i++].isLowSurrogate())) return false
    }
    return true
}

data class ReportState(val selection: ReportSelection? = null,
    val delivery: ReportDelivery = ReportDelivery.IDLE, val generation: Long = 0)

/**
 * UI-thread commands. Only submit() stores text, after the sheet's explicit consent action, and it
 * stores it on this phone: [save] is on-device storage, so no report can reach a server, and the
 * sheet is ready at once because there is no capability to check.
 */
class ReportController(private val scope: CoroutineScope, private val save: suspend (ReportRecord) -> Unit,
    private val now: () -> Double = ::nowSeconds) {
    private val mutable = MutableStateFlow(ReportState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun open(selection: ReportSelection) {
        job?.cancel()
        mutable.value = ReportState(selection, ReportDelivery.IDLE, ++generation)
    }

    fun submit(report: AIReportSubmission) {
        val current = mutable.value
        if (current.delivery !in listOf(ReportDelivery.IDLE, ReportDelivery.FAILED) ||
            current.selection?.languageID != report.languageID || !report.isValid()) return
        val token = generation
        // Reserve synchronously so two taps before recomposition cannot save two reports.
        mutable.value = current.copy(delivery = ReportDelivery.SAVING)
        launchOperation(token) {
            save(ReportRecord(report.reportID, report.languageID, report.reason, report.excerpt,
                report.consentVersion, now()))
            if (generation == token) mutable.value = mutable.value.copy(delivery = ReportDelivery.SAVED)
        }
    }

    fun dismiss() {
        ++generation
        job?.cancel(); job = null
        mutable.value = ReportState(generation = generation)
    }

    private fun launchOperation(token: Long, action: suspend () -> Unit) {
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            // A failed write leaves the sheet retryable with the same report ID, so a report is
            // never stored twice and never reported as saved when this phone does not hold it.
            catch (_: Exception) {
                if (generation == token) mutable.value = mutable.value.copy(delivery = ReportDelivery.FAILED)
            }
        }
        job = next
        next.start()
    }
}
