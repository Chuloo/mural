package chat.mural.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import chat.mural.core.CaptionWords

fun captionLinks(caption: String, languageID: String, onWord: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    CaptionWords.segments(caption, languageID).forEach { segment ->
        val word = segment.lookup
        if (word == null) append(segment.text)
        else withLink(LinkAnnotation.Clickable(word) { onWord(word) }) { append(segment.text) }
    }
}
