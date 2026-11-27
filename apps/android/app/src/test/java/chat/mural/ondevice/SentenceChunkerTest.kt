package chat.mural.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentence chunker of streamed On-Device Model output: a finished sentence is handed to the
 * voice as soon as it arrives, and a half-received one is never spoken early.
 */
class SentenceChunkerTest {
    @Test fun anIncompleteSentenceIsHeldBackUntilItEnds() {
        assertEquals(emptyList<String>(), SentenceChunker.segment("Hola amigo", isComplete = false))
        assertEquals(listOf("Hola amigo"), SentenceChunker.segment("Hola amigo", isComplete = true))
        assertEquals(listOf("Hola amigo."), SentenceChunker.segment("Hola amigo.", isComplete = false))
        assertEquals(emptyList<String>(), SentenceChunker.segment("   ", isComplete = true))
        assertEquals(emptyList<String>(), SentenceChunker.segment("", isComplete = false))
    }

    @Test fun theFirstSentenceIsReleasedWhileTheSecondOneIsStillStreaming() {
        assertEquals(listOf("Buenos días."), SentenceChunker.segment("Buenos días. ¿Cómo", isComplete = false))
        assertEquals(listOf("Buenos días.", "¿Cómo"), SentenceChunker.segment("Buenos días. ¿Cómo", isComplete = true))
    }

    @Test fun commonAbbreviationsStayInsideOneSentence() {
        assertEquals(listOf("El Dr. García llegó."), SentenceChunker.segment("El Dr. García llegó.", isComplete = false))
        assertEquals(listOf("Usa etc. y nada más."), SentenceChunker.segment("Usa etc. y nada más.", isComplete = false))
        // A half-received abbreviation is not a sentence end either.
        assertEquals(emptyList<String>(), SentenceChunker.segment("El Dr.", isComplete = false))
        assertEquals(listOf("El Dr."), SentenceChunker.segment("El Dr.", isComplete = true))
    }

    @Test fun questionMarksExclamationMarksAndNewlinesEndASentence() {
        assertEquals(listOf("¡Hola!", "¿Cómo estás?"),
            SentenceChunker.segment("¡Hola! ¿Cómo estás?", isComplete = false))
        assertEquals(listOf("Primera línea"), SentenceChunker.segment("Primera línea\nSegunda", isComplete = false))
        assertEquals(listOf("Primera línea", "Segunda"),
            SentenceChunker.segment("Primera línea\nSegunda", isComplete = true))
    }

    @Test fun aClosingQuoteBelongsToTheSentenceItEnds() {
        assertEquals(listOf("Dijo \"hola.\"", "Luego se fue."),
            SentenceChunker.segment("Dijo \"hola.\" Luego se fue.", isComplete = false))
    }

    @Test fun everyStreamedPrefixKeepsTheSentencesItAlreadyReleased() {
        val reply = "Buenos días. Hoy vamos a practicar con la radio. ¿Qué hiciste ayer?"
        val released = SentenceChunker.segment(reply, isComplete = false)
        assertEquals(listOf("Buenos días.", "Hoy vamos a practicar con la radio.", "¿Qué hiciste ayer?"), released)
        for (length in 1..reply.length) {
            val prefix = reply.substring(0, length)
            val partial = SentenceChunker.segment(prefix, isComplete = false)
            assertEquals(prefix, released.take(partial.size), partial)
        }
    }
}
