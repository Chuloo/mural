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

    /** The reason LearningRepository records for every session a process death left unfinished. */
    const val RECOVERED_ON_DEVICE_END_REASON = "App closed before finalization"

    /**
     * The On-Device Model conversation a low-memory kill interrupted, if this phone still holds one:
     * its transcript never left the device, so the Continue/New choice survives the kill and the
     * learner never loses the conversation. An empty transcript has nothing to continue, and
     * [declinedID] is a session the learner already continued or declined — its interruption reason
     * stays in the learning record forever, so without it the same choice would return every launch.
     */
    fun recoveredOnDeviceSession(sessions: List<SessionRecord>, onDeviceIDs: Set<String>,
        languageID: String, declinedID: String?): SessionRecord? = sessions.lastOrNull {
        it.id in onDeviceIDs && it.id != declinedID && it.languageID == languageID && it.endedAt != null &&
            it.fragments.isNotEmpty() && it.endReason == RECOVERED_ON_DEVICE_END_REASON
    }

    /**
     * Whether Continue resumes a voice conversation. It cannot when this phone has no voice to
     * resume: a Typed-only Module has none on any provider, an On-Device Model that cannot hear the
     * learner has none here, and a conversation that was typed stays typed. Asking for the
     * microphone in those cases would be a permission prompt that leads to a no-op.
     */
    fun resumesVoice(provider: ConversationProvider, typedOnlyModule: Boolean,
        canHearOnDeviceVoice: Boolean, continuationWasVoice: Boolean): Boolean =
        // Mural Minutes carry voice leases only, so their continuation is always the voice one.
        provider == ConversationProvider.HOSTED_MINUTES ||
            (!typedOnlyModule && continuationWasVoice &&
                (provider != ConversationProvider.ON_DEVICE || canHearOnDeviceVoice))
}
