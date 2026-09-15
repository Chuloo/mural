package chat.mural.ui

import androidx.compose.ui.text.LinkAnnotation
import chat.mural.core.HanReader
import chat.mural.core.MandarinPinyin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CaptionLinksTest {
    @After fun resetReader() { MandarinPinyin.reader = null }

    @Test fun everyWordLooksUpItselfWithoutPunctuationAndTheCaptionIsUnchanged() {
        val tapped = mutableListOf<String>()
        val caption = " Hi! What did you cook?"
        val linked = captionLinks(caption, "en") { tapped += it }
        assertEquals(caption, linked.text)
        val links = linked.getLinkAnnotations(0, linked.length)
        assertEquals(listOf("Hi!", "What", "did", "you", "cook?"), links.map { linked.text.substring(it.start, it.end) })
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("Hi", "What", "did", "you", "cook"), tapped)
    }

    @Test fun chineseCaptionsLinkDictionaryWordsNotSentences() {
        MandarinPinyin.reader = object : HanReader {
            override fun words(text: String) = listOf("我", "想", "去", "银行", "。")
            override fun reading(word: String): String? = null
        }
        val tapped = mutableListOf<String>()
        val linked = captionLinks("我想去银行。", "zh") { tapped += it }
        assertEquals("我想去银行。", linked.text)
        val links = linked.getLinkAnnotations(0, linked.length)
        assertEquals(listOf("我", "想", "去", "银行"), links.map { linked.text.substring(it.start, it.end) })
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("我", "想", "去", "银行"), tapped)
    }

    @Test fun emptyCaptionHasNoLinks() {
        val linked = captionLinks("", "en") { fail("no word should be tappable") }
        assertTrue(linked.getLinkAnnotations(0, linked.length).isEmpty())
    }

    @Test fun supplementaryLettersRemainTappableWithoutSplittingSurrogatePairs() {
        MandarinPinyin.reader = object : HanReader {
            override fun words(text: String) = listOf("𠮷", "，", "𠀀", "！", "😀")
            override fun reading(word: String): String? = null
        }
        for (language in listOf("zh", "en")) {
            val caption = if (language == "zh") "𠮷，𠀀！😀" else "𠮷 𠀀! 😀"
            val tapped = mutableListOf<String>()
            val linked = captionLinks(caption, language) { tapped += it }
            assertEquals(caption, linked.text)
            val links = linked.getLinkAnnotations(0, linked.length)
            assertEquals(2, links.size)
            links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
            assertEquals(listOf("𠮷", "𠀀"), tapped)
        }
    }
}
