package chat.mural.core

import kotlinx.serialization.Serializable

const val REPORT_EXCERPT_LIMIT = 2_000
const val REPORT_CONSENT_VERSION = "ai-report-v1"
/** Saved reports live in the learning archive; the bound keeps an exported backup honest. */
const val MAXIMUM_SAVED_REPORTS = 1_000

enum class ReportReason(val wireValue: String) {
    OFFENSIVE("offensive"), INCORRECT("incorrect"), WRONG_LANGUAGE("wrong_language"), OTHER("other")
}

enum class ReportDelivery { IDLE, SAVING, SAVED, FAILED }

/** Explicit submission only. The owner supplies on-device storage; nothing here can reach a network. */
@Serializable
data class AIReportSubmission(
    val reportID: String,
    val languageID: String,
    val reason: String,
    val excerpt: String,
    val consentVersion: String,
) {
    override fun toString() = "AIReportSubmission([redacted])"
}

/**
 * A report as this phone keeps it: saved on the device, exported with the learner's backup, and
 * never transmitted. [savedAt] is the archive's epoch-seconds clock.
 */
@Serializable
data class ReportRecord(
    val reportID: String,
    val languageID: String,
    val reason: String,
    val excerpt: String,
    val consentVersion: String,
    val savedAt: Double,
) {
    override fun toString() = "ReportRecord([redacted])"
}

internal fun boundedReportExcerpt(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val end = minOf(normalized.length, REPORT_EXCERPT_LIMIT)
    val safeEnd = if (end > 0 && end < normalized.length && Character.isHighSurrogate(normalized[end - 1]) &&
        Character.isLowSurrogate(normalized[end])) end - 1 else end
    return normalized.substring(0, safeEnd)
}

internal fun reportExcerptContainsCredential(text: String): Boolean = listOf(
    Regex("\\bsk-[A-Za-z0-9_-]{16,}"),
    Regex("\\bBearer[ \\t]+[A-Za-z0-9._~+/-]{16,}={0,2}", RegexOption.IGNORE_CASE),
    Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
    Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])"),
).any { it.containsMatchIn(text) }

