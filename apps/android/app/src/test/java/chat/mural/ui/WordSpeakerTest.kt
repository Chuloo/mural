package chat.mural.ui

import chat.mural.core.LanguageRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class WordSpeakerTest {

    @Test
    fun allRegisteredLanguagesHaveCandidateLocales() {
        for (language in LanguageRegistry.all) {
            val candidates = WordSpeakerLocales.candidateLocales(language.id)
            assertFalse("Expected candidates for language ${language.id}", candidates.isEmpty())
            assertEquals("Expected candidate locales to be distinct", candidates.size, candidates.distinct().size)
        }
    }

    @Test
    fun dutchAndRussianLocalesAreAccuratelyMapped() {
        val dutch = WordSpeakerLocales.candidateLocales("nl")
        assertTrue(dutch.any { it.language == "nl" })
        assertTrue(dutch.contains(Locale.forLanguageTag("nl-NL")))

        val russian = WordSpeakerLocales.candidateLocales("ru")
        assertTrue(russian.any { it.language == "ru" })
        assertTrue(russian.contains(Locale.forLanguageTag("ru-RU")))
    }

    @Test
    fun norwegianAndTagalogProvideRegionalFallbacks() {
        val norwegian = WordSpeakerLocales.candidateLocales("nb")
        assertTrue(norwegian.any { it.language == "no" || it.language == "nb" })

        val tagalog = WordSpeakerLocales.candidateLocales("tl")
        assertTrue(tagalog.any { it.language == "fil" || it.language == "tl" })
    }

    @Test
    fun portuguesePrioritizesBrazilianLocale() {
        val candidates = WordSpeakerLocales.candidateLocales("pt")
        assertEquals(Locale.forLanguageTag("pt-BR"), candidates.first())
        assertFalse("Should not include European Portuguese fallback", candidates.contains(Locale.forLanguageTag("pt-PT")))
    }

    @Test
    fun unknownLanguageFallsBackGracefully() {
        val candidates = WordSpeakerLocales.candidateLocales("xyz")
        assertEquals(listOf(Locale.forLanguageTag("xyz")), candidates)
    }

    @Test
    fun playbackStateIntegrity() {
        val idle = WordPlaybackState.Idle
        val loading = WordPlaybackState.Loading("en|travel")
        val playing = WordPlaybackState.Playing("en|travel")
        val unavailable = WordPlaybackState.Unavailable("tl|salamat", "tl")
        val error = WordPlaybackState.Error("ru|книга")

        assertEquals("en|travel", loading.wordId)
        assertEquals("en|travel", playing.wordId)
        assertEquals("tl|salamat", unavailable.wordId)
        assertEquals("tl", unavailable.languageID)
        assertEquals("ru|книга", error.wordId)
        assertNotEquals(idle, playing)
    }
}
