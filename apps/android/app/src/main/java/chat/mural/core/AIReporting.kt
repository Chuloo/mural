package chat.mural.core

import kotlinx.serialization.Serializable

const val REPORT_EXCERPT_LIMIT = 2_000
const val REPORT_CONSENT_VERSION = "ai-report-v1"

enum class ReportReason(val wireValue: String) {
    OFFENSIVE("offensive"), INCORRECT("incorrect"), WRONG_LANGUAGE("wrong_language"), OTHER("other")
}

enum class ReportDelivery { CHECKING, IDLE, SENDING, SENT, FAILED, UNAVAILABLE }

/** Explicit submission only. The owner supplies networking; this sheet cannot send or log data. */
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

