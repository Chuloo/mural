package chat.mural.core

object ConversationContinuationPolicy {
    fun needsMoreMinutes(settlementState: String?, availabilityReason: String?): Boolean =
        settlementState == "settled" && availabilityReason == "insufficient_remaining_time"

    /** A provider close can arrive before the next local timer tick. */
    fun reachedFreeBoundary(hasFreeFunding: Boolean, endReason: String?, deadlineReached: Boolean): Boolean =
        hasFreeFunding && (endReason == "Time limit" || endReason == "Reserved conversation time ended" ||
            (endReason == null && deadlineReached))
}
