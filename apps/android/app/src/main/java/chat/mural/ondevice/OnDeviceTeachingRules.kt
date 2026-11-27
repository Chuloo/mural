package chat.mural.ondevice

/**
 * The offline rules an On-Device Model session adds to a caller's Helper instructions. They live
 * here rather than in `TeachingPolicy` because they exist only for this Conversation Provider on
 * this platform and must not enter the cross-platform prompt contract.
 */
object OnDeviceTeachingRules {
    /** No internet exists offline: explain vocabulary and grammar directly, and refuse a live fact
     * honestly through the Language Module's existing unavailable reply instead of inventing it. */
    fun offline(unavailableReply: String) =
        "You are offline on the learner's phone: there is no internet, no web search, and nothing can be delegated to a client. " +
            "Explain vocabulary, grammar and meaning directly from what you know. If the learner asks for live news or current " +
            "facts that require checking the web, never invent them and never promise a search: reply honestly with this " +
            "sentence, then continue the conversation: \"$unavailableReply\""

    /** The strict rule of the single unconstrained assessment retry. */
    fun onlyJsonObject(schema: String) =
        "Return ONLY a single valid JSON object matching this schema, with no markdown or prose.\nSchema: $schema"

    /** The current-topic theme offline: the notes already in the conversation are the only sourcing. */
    val offlineCurrentTopic =
        "This conversation is offline: the reference notes it already contains are the only sourced material. Never promise a " +
            "source-backed lookup, a delegated search or new citations, and say honestly that a current fact cannot be checked offline."
}
