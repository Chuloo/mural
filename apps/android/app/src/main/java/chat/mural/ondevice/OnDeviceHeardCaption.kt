package chat.mural.ondevice

/**
 * The learner's caption of a native audio input turn. Nobody transcribes the clip: the model hears
 * it directly, so it writes what it heard in a `<heard>` block before its own reply. Mural lifts
 * that block out as the learner's transcript delta and speaks only the clean teacher text, so the
 * tags never reach the learner's ears or the conversation history. It lives here, not in
 * `TeachingPolicy`, because it exists only for this Conversation Provider on this platform.
 */
object OnDeviceHeardCaption {
    private const val OPEN = "<heard>"
    private const val CLOSE = "</heard>"

    /** Added to the system prompt of a voice conversation whose model hears audio. */
    val rule =
        "You hear the learner's recorded speech directly. Begin every reply with what the learner said, in the learner's own " +
            "words and language, inside <heard></heard>, and write your reply after the closing tag. Never mention the tags " +
            "and never repeat the learner's words outside them."

    /**
     * The heard words and the teacher's text of a reply streamed so far. The heard words are null
     * while the block is still open or absent, and nothing of an open block is treated as speech:
     * a half-received tag must never be spoken.
     */
    fun split(accumulated: String): Pair<String?, String> {
        val text = accumulated.trimStart()
        if (text.isEmpty()) return null to ""
        if (!text.startsWith(OPEN, ignoreCase = true)) {
            // Still deciding whether a block opens: "<he" is a tag so far, not teacher speech.
            return if (OPEN.startsWith(text, ignoreCase = true)) null to "" else null to accumulated
        }
        val close = text.indexOf(CLOSE, ignoreCase = true)
        if (close < 0) return null to ""
        return text.substring(OPEN.length, close).trim() to text.substring(close + CLOSE.length).trimStart()
    }
}
