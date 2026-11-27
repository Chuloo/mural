package chat.mural.core

object ConversationContinuationPolicy {
    fun needsMoreMinutes(settlementState: String?, availabilityReason: String?): Boolean =
        settlementState == "settled" && availabilityReason == "insufficient_remaining_time"

    /** A provider close can arrive before the next local timer tick. */
    fun reachedFreeBoundary(hasFreeFunding: Boolean, endReason: String?, deadlineReached: Boolean): Boolean =
        hasFreeFunding && (endReason == "Time limit" || endReason == "Reserved conversation time ended" ||
            (endReason == null && deadlineReached))

    /** A timed On-Device Model conversation ends on the same limit and offers the same Continue/New
     * choice, with no balance to settle: it never consumed minutes. */
    fun reachedOnDeviceTimeLimit(onDevice: Boolean, endReason: String?): Boolean =
        onDevice && endReason == "Time limit"
}
