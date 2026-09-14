package chat.mural.ui

import chat.mural.R
import chat.mural.core.WordState
import org.junit.Assert.assertEquals
import org.junit.Test

class WordExplanationTest {
    private fun word(bars: Int, independent: Int) = WordState("en|cook|to cook", "cook", "to cook", "cooked", "I cooked", bars, 0, independent, 0.0, 0.0)

    @Test fun explanationResourceFollowsTheCoreRecallRules() {
        assertEquals(R.string.words_explanation_supported, wordExplanationRes(word(bars = 0, independent = 0)))
        assertEquals(R.string.words_explanation_recalled_once, wordExplanationRes(word(bars = 1, independent = 1)))
        assertEquals(R.string.words_explanation_recalled_days, wordExplanationRes(word(bars = 2, independent = 2)))
        assertEquals(R.string.words_explanation_steady, wordExplanationRes(word(bars = 3, independent = 4)))
    }
}
