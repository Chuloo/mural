package chat.mural.core

object ConversationContinuationPolicy {
    /** A provider close can arrive before the next local timer tick. */
    fun reachedFreeBoundary(hasFreeFunding: Boolean, endReason: String?, deadlineReached: Boolean): Boolean =
        hasFreeFunding && (endReason == "Time limit" || endReason == "Reserved conversation time ended" ||
            (endReason == null && deadlineReached))
}
