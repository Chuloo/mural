package chat.mural.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test

class CaptionLinksTest {
    @Test fun everySpaceSeparatedWordLooksUpItselfAndTheCaptionIsUnchanged() {
        val tapped = mutableListOf<String>()
        val caption = " Hi! What did you cook?"
        val linked = captionLinks(caption) { tapped += it }
        assertEquals(caption, linked.text)
        val links = linked.getLinkAnnotations(0, linked.length)
        assertEquals(listOf("Hi!", "What", "did", "you", "cook?"), links.map { linked.text.substring(it.start, it.end) })
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("Hi!", "What", "did", "you", "cook?"), tapped)
    }

    @Test fun emptyCaptionHasNoLinks() {
        val linked = captionLinks("") { fail("no word should be tappable") }
        assertTrue(linked.getLinkAnnotations(0, linked.length).isEmpty())
    }
}
