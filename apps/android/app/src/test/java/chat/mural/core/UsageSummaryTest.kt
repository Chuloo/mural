package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class UsageSummaryTest {
    @Test fun addsVoiceTimeEstimateAndSearchesAcrossSessions() {
        val first = SessionRecord(languageID = "en").apply { voiceSeconds = 90.0; searchCalls = 2 }
        val second = SessionRecord(languageID = "en").apply { voiceSeconds = 545.5; searchCalls = 1 }
        val summary = UsageSummary.of(listOf(first, second))
        assertEquals("10 min 35 s", summary.voiceTime)
        assertEquals("$0.53 USD", summary.voiceEstimate)
        assertEquals(3, summary.searchCalls)
    }

    @Test fun emptyHistoryShowsZeroUsage() {
        val summary = UsageSummary.of(emptyList())
        assertEquals("0 min 0 s", summary.voiceTime)
        assertEquals("$0.00 USD", summary.voiceEstimate)
        assertEquals(0, summary.searchCalls)
    }
}
