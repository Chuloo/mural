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

    private val record = ReportRecord("e3c1d862-2d0f-4bf0-a44f-404e9c559581", "es", "incorrect",
        "Selected private excerpt", REPORT_CONSENT_VERSION, 1_234.0)
    private fun archive(vararg reports: ReportRecord) = Archive(preferences = Preferences(learningLanguageID = "es"),
        reports = reports.toMutableList())

    @Test fun aSavedReportStaysOnThisPhoneAndTravelsWithTheLearningBackup() {
        assertFalse(record.toString().contains(record.excerpt))
        assertEquals(listOf(record), ArchiveCodec.decode(ArchiveCodec.encode(archive(record))).reports)
        // An imported backup brings its reports, and one report ID is stored once.
        val other = record.copy(reportID = "1b671a64-40d5-491e-99b0-da01ff1f3341")
        val merged = ArchiveCodec.merge(archive(record), archive(record, other))
        assertEquals(listOf(record, other), merged.reports)
    }

    @Test fun aReportThisPhoneWouldNotSaveMakesABackupInvalid() {
        for (broken in listOf(record.copy(reportID = "not-uuid"), record.copy(reason = "unknown"),
            record.copy(consentVersion = ""), record.copy(excerpt = " "),
            record.copy(excerpt = "sk-" + "example-not-a-key".repeat(3)))) {
            try {
                ArchiveCodec.decode(ArchiveCodec.encode(archive(broken)))
                fail("a backup holding $broken must be refused")
            } catch (expected: ArchiveError) { }
        }
    }

    @Test fun aReportLogIsBoundedSoABackupStaysExportable() {
        val reports = Array(MAXIMUM_SAVED_REPORTS + 1) { record.copy(reportID = java.util.UUID.randomUUID().toString()) }
        assertEquals(MAXIMUM_SAVED_REPORTS,
            ArchiveCodec.decode(ArchiveCodec.encode(archive(*reports.dropLast(1).toTypedArray()))).reports.size)
        try {
            ArchiveCodec.decode(ArchiveCodec.encode(archive(*reports)))
            fail("an over-full report log must be refused")
        } catch (expected: ArchiveError) { }
    }
}
