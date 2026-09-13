package chat.mural.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class InlineMarkdownTest {
    @Test fun citationLinkShowsLabelAndOpensHttpsUrl() {
        val url = "https://www.gob.pe/institucion/produce/noticias/1255305?utm_source=openai"
        val rendered = inlineMarkdown("Sales grew 37.1%. ([gob.pe]($url)) More soon.")
        assertEquals("Sales grew 37.1%. (gob.pe) More soon.", rendered.text)
        val links = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(1, links.size)
        assertEquals(url, (links[0].item as LinkAnnotation.Url).url)
        assertEquals("gob.pe", rendered.text.substring(links[0].start, links[0].end))
    }

    @Test fun boldMarkersBecomeBoldText() {
        val rendered = inlineMarkdown("**Discussion question:** Would you buy one?")
        assertEquals("Discussion question: Would you buy one?", rendered.text)
        val bold = rendered.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Discussion question:", rendered.text.substring(bold.start, bold.end))
    }

    @Test fun unsafeLinkKeepsLabelWithoutOpeningIt() {
        val rendered = inlineMarkdown("Try [this](file:///sdcard/notes.txt) or [that](http://example.com).")
        assertEquals("Try this or that.", rendered.text)
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
    }
}
