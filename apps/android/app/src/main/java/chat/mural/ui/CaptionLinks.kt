package chat.mural.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink

fun captionLinks(caption: String, onWord: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    caption.split(' ').forEachIndexed { index, word ->
        if (index > 0) append(' ')
        if (word.isEmpty()) return@forEachIndexed
        withLink(LinkAnnotation.Clickable(word) { onWord(word) }) { append(word) }
    }
}
