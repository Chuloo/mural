package chat.mural.ui

import chat.mural.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReportSubmissionTest {
    @Test fun excerptLimitPreservesUnicodeCharactersAndNormalizesLineBreaks() {
        assertEquals("One\nTwo\nThree", boundedReportExcerpt("One\r\nTwo\rThree"))
        assertEquals(2_000, boundedReportExcerpt("🙂".repeat(1_001)).length)
        val boundary = boundedReportExcerpt("a".repeat(1_999) + "🙂")
        assertEquals(1_999, boundary.length)
        assertFalse(boundary.last().isHighSurrogate())
    }

    @Test fun ordinaryTextPassesButCommonCredentialShapesAreBlocked() {
        assertFalse(reportExcerptContainsCredential("Una frase para revisar."))
        for (text in listOf("sk-" + "example-not-a-key".repeat(3), "Bearer " + "z".repeat(43), "z".repeat(43),
            "eyJ" + "a".repeat(15) + "." + "b".repeat(20) + "." + "c".repeat(20))) {
            assertTrue(reportExcerptContainsCredential(text))
        }
    }

    @Test fun submissionContainsOnlyOptedInFieldsAndRedactsDiagnostics() {
        val report = AIReportSubmission("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect", "Selected private excerpt", REPORT_CONSENT_VERSION)
        assertFalse(report.toString().contains(report.excerpt))
        val json = Json.encodeToString(report)
        assertTrue(json.contains("Selected private excerpt"))
        assertTrue(json.contains("ai-report-v1"))
        assertFalse(json.contains("accessToken"))
        assertFalse(json.contains("audio"))
        assertFalse(json.contains("accountID"))
        assertEquals("ai-report-v1", report.consentVersion)
    }
}
