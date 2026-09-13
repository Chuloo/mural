package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class SessionLimitsTest {
    @Test fun quietVoiceSessionEndsAfterTwoMinutes() {
        assertFalse(SessionLimits.endsForInactivity(voice = true, idleSeconds = 119.0))
        assertTrue(SessionLimits.endsForInactivity(voice = true, idleSeconds = 121.0))
    }

    @Test fun writtenConversationStaysOpenWhileTheLearnerTypes() {
        assertFalse(SessionLimits.endsForInactivity(voice = false, idleSeconds = 600.0))
    }
}
