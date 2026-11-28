package chat.mural.ondevice

/**
 * Sentence chunking of a streamed On-Device Model reply: a finished sentence is released as soon as
 * it arrives, so the teacher starts speaking while the rest is still generating, and a half-received
 * sentence is never spoken early. Pure and stateless — the caller feeds the whole text accumulated
 * so far and releases the sentences it has not spoken yet, in order.
 */
object SentenceChunker {
    /**
     * A sentence ends at `.`, `!`, `?`, a newline or the CJK `。`, `！`, `？`, never inside a common
     * abbreviation, and a closing quote belongs to the sentence it ends. Japanese and Mandarin write
     * no space after a full-width ending, so that branch needs no following-whitespace lookahead.
     */
    private val BOUNDARY = Regex(
        "(?i)(?:(?<!\\b(?:e\\.g|i\\.e|dr|mr|mrs|ms|prof|vs|etc|fig|al|approx|inc|corp))\\.+|[!?]+)[\"')\\]\u2019\u201d]*(?=\\s|$)" +
            "|[\u3002\uFF01\uFF1F]+[\"')\\]\u2019\u201d\u300D\u300F\uFF09]*|\\n+")

    /**
     * The speakable sentences of [accumulatedText]. Text after the last sentence end is held back
     * until [isComplete], when the reply has finished streaming and the tail is all there is.
     */
    fun segment(accumulatedText: String, isComplete: Boolean): List<String> {
        val text = accumulatedText.trim()
        if (text.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        var start = 0
        for (boundary in BOUNDARY.findAll(text)) {
            val sentence = text.substring(start, boundary.range.last + 1).trim()
            if (sentence.isNotEmpty()) sentences += sentence
            start = boundary.range.last + 1
        }
        val tail = text.substring(start).trim()
        if (isComplete && tail.isNotEmpty()) sentences += tail
        return sentences
    }
}
