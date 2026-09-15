package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class MandarinPhraseReadingsTest {
    @Test fun bundledDictionaryResolvesEverydayPolyphones() {
        for ((word, expected) in mapOf("重复" to "chóngfù", "重要" to "zhòngyào", "行长" to "hángzhǎng",
            "还给" to "huángěi", "还钱" to "huánqián", "教书" to "jiāoshū", "长大" to "zhǎngdà",
            "旅行" to "lǚxíng", "重庆" to "chóngqìng", "银行" to "yínháng")) {
            assertEquals(word, expected, MandarinPhraseReadings.reading(word))
        }
    }
    @Test fun pronunciationUsesAdjacentWordsWithoutChangingSourceCharacters() {
        val segments = listOf("我", "还", "钱", "，", "然后", "去", "银行", "。")
        val result = MandarinPhraseReadings.tokens(segments, emptyMap()) { null }
        assertEquals(segments.joinToString(""), result.joinToString("") { it.text })
        assertTrue(result.contains(MandarinPronunciationToken("还钱", "huánqián")))
        assertEquals("我", result.first().text)
    }
    @Test fun readingsNeverMergeAcrossPunctuationOrSpaces() {
        val result = MandarinPhraseReadings.tokens(listOf("还", " ", "钱", "，", "教", "。", "书"), emptyMap()) { null }
        assertEquals("还 钱，教。书", result.joinToString("") { it.text })
        assertFalse(result.any { it.text == "还钱" || it.text == "教书" })
    }
}
