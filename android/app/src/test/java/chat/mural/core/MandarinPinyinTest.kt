package chat.mural.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MandarinPinyinTest {
    /** Splits on a fixed word list and reads words the way a platform dictionary would. */
    private class DictionaryReader : HanReader {
        private val readings = mapOf(
            "银行" to "yínháng", "旅行" to "lv\u030Cxíng", "音乐" to "yīnyuè", "快乐" to "kuàilè", "重新" to "chóngxīn",
            "重庆" to "chóngqìng", "女儿" to "nǚér", "我" to "wǒ", "想" to "xiǎng", "去" to "qù", "然后" to "ránhòu",
            "你好" to "nǐhǎo", "再见" to "zàijiàn", "和" to "hé", "咖啡" to "kāfēi", "咖" to "kā", "銀行" to "yínháng",
            "與" to "yǔ", "音樂" to "yīnyuè",
        )
        private val words = readings.keys.sortedByDescending { it.length }

        override fun words(text: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            val other = StringBuilder()
            while (i < text.length) {
                val word = words.firstOrNull { text.startsWith(it, i) }
                if (word != null) {
                    if (other.isNotEmpty()) { result += other.toString(); other.clear() }
                    result += word; i += word.length
                } else {
                    val cp = text.codePointAt(i); val chars = Character.charCount(cp)
                    if (MandarinPinyin.containsHan(text.substring(i, i + chars))) {
                        if (other.isNotEmpty()) { result += other.toString(); other.clear() }
                        result += text.substring(i, i + chars)
                    } else other.append(text, i, i + chars)
                    i += chars
                }
            }
            if (other.isNotEmpty()) result += other.toString()
            return result
        }

        override fun reading(word: String): String? = readings[word]
    }

    private val reader = DictionaryReader()

    @Test fun readingsUseWordEntriesAndNormalizeUmlautVowels() {
        for ((text, expected) in listOf("银行" to "yínháng", "旅行" to "lǚxíng", "音乐" to "yīnyuè", "快乐" to "kuàilè",
            "重新" to "chóngxīn", "重庆" to "chóngqìng", "女儿" to "nǚér")) {
            assertEquals(text, expected, MandarinPinyin.reading(text, reader))
        }
        assertEquals("wǒ xiǎng qù yínháng，ránhòu qù lǚxíng。", MandarinPinyin.reading("我想去银行，然后去旅行。", reader))
    }

    @Test fun textWithoutReadingsHasNoReadingAid() {
        assertNull(MandarinPinyin.reading("Hello, Mural 2026! ☕️", reader))
        assertNull(MandarinPinyin.reading("", reader))
        assertNull(MandarinPinyin.reading("你好", null))
    }

    @Test fun unrecognizedHanCharactersStayInTheReading() {
        assertEquals("𠀀 hé kāfēi", MandarinPinyin.reading("𠀀和咖啡", reader))
    }

    @Test fun tokensAndChineseSegmentsPreserveEveryCharacter() {
        for (text in listOf("", "你好！", "  我想去银行，然后去旅行。\n", "你好，Mural 2026！☕️\n再见", "𠀀和咖啡", "銀行與音樂", "咖", "café e pão")) {
            assertEquals(text, MandarinPinyin.tokens(text, reader).joinToString("") { it.text })
            assertEquals(text, CaptionWords.segments(text, "zh", reader).joinToString("") { it.text })
        }
        val links = CaptionWords.segments("我想去银行，然后去旅行。", "zh", reader).mapNotNull { it.lookup }
        assertTrue(links.contains("银行"))
        assertTrue(links.contains("旅行"))
        assertFalse(links.any { it.contains("，") || it.contains("。") })
    }

    @Test fun aReaderThatDropsCharactersIsIgnored() {
        val broken = object : HanReader {
            override fun words(text: String) = listOf(text.drop(1))
            override fun reading(word: String) = "x"
        }
        assertEquals(listOf(MandarinPronunciationToken("你好", null)), MandarinPinyin.tokens("你好", broken))
    }

    @Test fun adjacentSegmentsThatFormAKnownWordAreMergedLongestFirst() {
        val words = setOf("一会儿", "有点儿", "东西", "东西南北", "银行")
        assertEquals(listOf("一会儿"), HanWords.merge(listOf("一", "会", "儿"), words))
        assertEquals(listOf("有点儿", "。"), HanWords.merge(listOf("有", "点儿", "。"), words))
        assertEquals(listOf("东西南北"), HanWords.merge(listOf("东西", "南北"), words))
        assertEquals(listOf("我", "的", "东西"), HanWords.merge(listOf("我", "的", "东西"), words))
        assertEquals(listOf("我想去", "银行", "，"), HanWords.merge(listOf("我想去", "银行", "，"), words))
        assertEquals(emptyList<String>(), HanWords.merge(emptyList(), words))
    }

    @Test fun spacedLanguageSegmentsKeepAccentsApostrophesAndLineBreaks() {
        for ((text, expected) in listOf(
            "  J'ai déjà\nvisité ce marché.  " to listOf("J'ai", "déjà", "visité", "ce", "marché"),
            "Olá!\n pão  e maçã." to listOf("Olá", "pão", "e", "maçã"),
        )) {
            val segments = CaptionWords.segments(text, "pt", reader)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(expected, segments.mapNotNull { it.lookup })
        }
        assertEquals(listOf<String>(), CaptionWords.segments("2026 — ☕️", "en", reader).mapNotNull { it.lookup })
    }
}
