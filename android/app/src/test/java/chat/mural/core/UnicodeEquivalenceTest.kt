package chat.mural.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnicodeEquivalenceTest {
    private fun proposal(lemma: String, meaning: String) =
        WordProposal(lemma = lemma, meaning = meaning, form = lemma, kind = EvidenceKind.independent, confidence = 0.9, sourceIDs = listOf("f1"), quote = lemma, language = "fr")

    @Test fun composedAndDecomposedLemmasShareOneVocabularyKey() {
        val composed = proposal("caf\u00e9", "coffee")
        val decomposed = proposal("cafe\u0301", "coffee")
        assertEquals(composed.key, decomposed.key)
        assertEquals("fr|caf\u00e9|coffee", decomposed.key)
    }

    @Test fun canonicalContainmentIgnoresCompositionAndCase() {
        assertTrue("Un CAF\u00c9 s'il vous pla\u00eet".containsCanonical("cafe\u0301"))
        assertTrue("cafe\u0301".containsCanonical("CAF\u00c9"))
    }
}
