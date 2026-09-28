package chat.mural.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContinuationPolicyTest {
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
}
