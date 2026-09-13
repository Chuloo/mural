package chat.mural.network

import org.junit.Assert.*
import org.junit.Test

class LanguageDetectorTest {
    @Test fun norwegianMacrolanguageMatchesTheBokmalModule() {
        assertEquals("nb", LanguageDetector.normalizeLanguageID("no"))
        assertEquals("nb", LanguageDetector.normalizeLanguageID("NB"))
    }

    @Test fun onlyLongPassagesNotYetRedirectedAreChecked() {
        fun passage(id: String, text: String) = chat.mural.core.Passage(id, chat.mural.core.Speaker.assistant,
            listOf(chat.mural.core.Fragment(id = id, speaker = chat.mural.core.Speaker.assistant, text = text, startMS = 0, endMS = 1)))
        val long = "x".repeat(71)
        assertFalse(LanguageDetector.shouldCheck(null, null))
        assertFalse(LanguageDetector.shouldCheck(passage("a", "x".repeat(70)), null))
        assertTrue(LanguageDetector.shouldCheck(passage("a", long), null))
        assertFalse(LanguageDetector.shouldCheck(passage("a", long), lastRedirectedPassageID = "a"))
        assertTrue(LanguageDetector.shouldCheck(passage("b", long), lastRedirectedPassageID = "a"))
    }

    @Test fun otherLanguagesKeepTheirLowercaseCode() {
        assertEquals("es", LanguageDetector.normalizeLanguageID("es"))
        assertEquals("fr", LanguageDetector.normalizeLanguageID("FR"))
        assertEquals("und", LanguageDetector.normalizeLanguageID("und"))
    }
}
