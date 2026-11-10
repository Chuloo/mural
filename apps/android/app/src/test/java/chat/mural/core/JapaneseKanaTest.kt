package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class JapaneseKanaTest {
    @Test fun kanaReadingUsesWordReadingsAndKeepsKanaAndPunctuation() {
        for ((text, reading) in listOf("東京" to "とうきょう", "友達" to "ともだち", "一人" to "ひとり", "二人" to "ふたり", "大人" to "おとな", "銀行" to "ぎんこう")) {
            assertEquals(text, reading, JapaneseKana.reading(text))
        }
        assertEquals("きょうはてんきがいいですね。", JapaneseKana.reading("今日は天気がいいですね。"))
        assertEquals("ともだちとコーヒーをのみました。", JapaneseKana.reading("友達とコーヒーを飲みました。"))
        assertEquals("わたしはにほんごをべんきょうしています。", JapaneseKana.reading("私は日本語を勉強しています。"))
        assertEquals("わたしはコーヒーがすきです。", JapaneseKana.reading("私はコーヒーが好きです。"))
    }

    @Test fun textWithoutKanjiHasNoReading() {
        for (text in listOf("", "こんにちは！", "コーヒー", "Hello, Mural 2026! ☕️")) assertNull(text, JapaneseKana.reading(text))
    }

    @Test fun wordLinksPreserveMixedScriptPunctuationAndWhitespace() {
        for (text in listOf("", "こんにちは！", "  銀行に行ってから、旅行します。\n", "こんにちは、Mural 2026！☕️\nまたね", "𠀀とコーヒー", "本", "café e pão")) {
            assertEquals(text, JapaneseKana.tokens(text).joinToString("") { it.text })
            assertEquals(text, CaptionWords.segments(text, "ja").joinToString("") { it.text })
        }
        val links = CaptionWords.segments("銀行に行ってから、旅行します。", "ja").mapNotNull { it.lookup }
        assertTrue(links.contains("銀行")); assertTrue(links.contains("旅行")); assertTrue(links.size > 4)
        assertFalse(links.any { it.contains("、") || it.contains("。") })
    }

    @Test fun readingAidSelectsTheLanguageSpecificReading() {
        assertEquals("とうきょう", ReadingAid.reading("東京", "ja"))
        assertNull(ReadingAid.reading("東京", "ru"))
        assertTrue(ReadingAid.supports("zh")); assertTrue(ReadingAid.supports("ja")); assertFalse(ReadingAid.supports("ru"))
    }
}
