package chat.mural.core

import org.junit.Test
import org.junit.Assert.*

class CaptionAndFlashcardTest {
    @Test fun interruptionSurvivesStreamingAndOnlyNewPassageResumes() {
        var follow = CaptionFollowing()
        assertEquals(0.0, follow.nextOffset(0.0, 100.0, .05), .001)
        follow = follow.receive("first")
        assertEquals(1.2, follow.nextOffset(0.0, 100.0, .05), .001)
        follow = follow.interrupt().receive("first")
        assertEquals(20.0, follow.nextOffset(20.0, 200.0, .05), .001)
        follow = follow.receive("next")
        assertFalse(follow.interrupted)
        assertEquals(21.2, follow.nextOffset(20.0, 200.0, .05), .001)
        assertEquals(100.0, follow.nextOffset(99.0, 100.0, 10.0), .001)
        assertEquals(0.0, follow.nextOffset(0.0, -1.0, .05), .001)
        assertEquals(200.0, follow.nextOffset(20.0, 200.0, .05, true), .001)
        assertEquals(20.0, follow.interrupt().nextOffset(20.0, 200.0, .05, true), .001)
    }
    @Test fun deckIsLanguageScopedReadOnlyAndBounded() {
        val first = WordState("ru|книга|book", "книга", "book", "книгу", "Я читаю книгу.", 2, 1, 2, 0.0, 0.0)
        val second = first.copy(id = "ru|кофе|coffee", lemma = "кофе")
        val input = listOf(first, first.copy(id = "nl|boek|book"), first, second)
        val deck = FlashcardDeck(input, "ru")
        assertEquals(listOf(first, second), deck.words)
        assertEquals(0, deck.destination(0, -1)); assertEquals(1, deck.destination(0, 1))
        assertEquals(1, deck.destination(1, 1)); assertEquals(0, deck.destination(1, -1))
        assertEquals(4, input.size)
        assertEquals(0, FlashcardDeck(emptyList(), "ru").destination(0, 1))
    }
    @Test fun nativeWordLinksAndStreamingPreserveDutchAndCyrillic() {
        for ((id, text, links) in listOf(
            Triple("nl", "Ik houd van koffie. Jij ook?", listOf("Ik", "houd", "van", "koffie", "Jij", "ook")),
            Triple("ru", "Я читаю книгу. А ты?", listOf("Я", "читаю", "книгу", "А", "ты")))) {
            val segments = CaptionWords.segments(text, id, null)
            assertEquals(text, segments.joinToString("") { it.text })
            assertEquals(links, segments.mapNotNull { it.lookup })
            assertEquals(text, Passage.join(listOf(text.take(8), text.drop(8))))
        }
    }
}
