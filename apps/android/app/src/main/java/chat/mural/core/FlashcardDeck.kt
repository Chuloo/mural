package chat.mural.core

/** Read-only snapshot: browsing never changes vocabulary or conversation recall. */
class FlashcardDeck(words: List<WordState>, languageID: String) {
    val words = words.filter { it.id.startsWith("$languageID|") }.distinctBy { it.id }
    fun destination(index: Int, direction: Int) = if (words.isEmpty()) 0 else (index + direction).coerceIn(0, words.lastIndex)
}
