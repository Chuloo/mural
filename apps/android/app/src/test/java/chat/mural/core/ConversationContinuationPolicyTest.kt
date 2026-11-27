package chat.mural.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContinuationPolicyTest {
    @Test fun onlySettledInsufficientFundingOffersMoreMinutes() {
        assertTrue(ConversationContinuationPolicy.needsMoreMinutes("settled", "insufficient_remaining_time"))
        listOf(null, "pending", "in_use").forEach {
            assertFalse(ConversationContinuationPolicy.needsMoreMinutes(it, "insufficient_remaining_time"))
        }
        listOf(null, "ready", "settling", "active_conversation", "account_action_needed", "service_unavailable").forEach {
            assertFalse(ConversationContinuationPolicy.needsMoreMinutes("settled", it))
        }
    }

    @Test fun providerDeadlineBeforeLocalTimerPreservesFreeContinuation() {
        assertTrue(ConversationContinuationPolicy.reachedFreeBoundary(true, null, true))
        assertFalse(ConversationContinuationPolicy.reachedFreeBoundary(true, null, false))
        assertFalse(ConversationContinuationPolicy.reachedFreeBoundary(false, null, true))
        listOf("Ended by you", "App moved to background", "Inactivity").forEach {
            assertFalse(ConversationContinuationPolicy.reachedFreeBoundary(true, it, true))
        }
        assertTrue(ConversationContinuationPolicy.reachedFreeBoundary(true, "Time limit", true))
        assertTrue(ConversationContinuationPolicy.reachedFreeBoundary(true, "Reserved conversation time ended", true))
    }

    @Test fun aTimedOnDeviceConversationEndsOnTheSameLimitAndOffersTheSameChoice() {
        assertTrue(ConversationContinuationPolicy.reachedOnDeviceTimeLimit(true, "Time limit"))
        listOf("Inactivity", "Ended by you", "App moved to background", "Reserved conversation time ended", null)
            .forEach { assertFalse(ConversationContinuationPolicy.reachedOnDeviceTimeLimit(true, it)) }
        // A cloud conversation keeps its own funding-boundary continuation, never this one.
        assertFalse(ConversationContinuationPolicy.reachedOnDeviceTimeLimit(false, "Time limit"))
    }
}
