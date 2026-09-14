package chat.mural.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ReportService {
    suspend fun available(): Boolean
    suspend fun submit(report: AIReportSubmission)
}

sealed class ReportFailure : Exception() {
    data object Unavailable : ReportFailure()
    data object Retry : ReportFailure()
    data object Invalid : ReportFailure()
}

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

data class ReportState(val selection: ReportSelection? = null, val available: Boolean = false,
    val delivery: ReportDelivery = ReportDelivery.IDLE, val generation: Long = 0)

/** UI-thread commands. Only submit() can send text, after the sheet's explicit consent action. */
class ReportController(private val scope: CoroutineScope, private val service: ReportService?) {
    private val mutable = MutableStateFlow(ReportState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun open(selection: ReportSelection) {
        job?.cancel()
        val token = ++generation
        mutable.value = ReportState(selection, false, if (service == null) ReportDelivery.UNAVAILABLE else ReportDelivery.CHECKING, token)
        if (service == null) return
        launchOperation(token) {
            val available = service.available()
            if (generation == token) mutable.value = mutable.value.copy(available = available,
                delivery = if (available) ReportDelivery.IDLE else ReportDelivery.UNAVAILABLE)
        }
    }

    fun submit(report: AIReportSubmission) {
        val current = mutable.value
        if (service == null || !current.available || current.delivery !in listOf(ReportDelivery.IDLE, ReportDelivery.FAILED) ||
            current.selection?.languageID != report.languageID || !report.isValid()) return
        val token = generation
        // Reserve synchronously so two taps before recomposition cannot issue two requests.
        mutable.value = current.copy(delivery = ReportDelivery.SENDING)
        launchOperation(token) {
            service.submit(report)
            if (generation == token) mutable.value = mutable.value.copy(delivery = ReportDelivery.SENT)
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
            catch (error: Exception) {
                if (generation == token) {
                    val unavailable = mutable.value.delivery == ReportDelivery.CHECKING || error is ReportFailure.Unavailable
                    mutable.value = mutable.value.copy(available = !unavailable,
                        delivery = if (unavailable) ReportDelivery.UNAVAILABLE else ReportDelivery.FAILED)
                }
            }
        }
        job = next
        next.start()
    }
}
