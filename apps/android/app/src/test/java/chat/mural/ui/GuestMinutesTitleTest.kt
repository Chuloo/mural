package chat.mural.ui

import chat.mural.R
import chat.mural.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestMinutesTitleTest {
    @Test fun residualGuestAndMemberCreditOffersMoreMinutesWithoutRetryingAValidBalance() {
        for (remaining in listOf(0L, 1L, 14_999L, 15_000L)) {
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", remaining, 0, remaining)
            val state = GuestMinuteState(GuestMinuteStatus.READY, remainingMilliseconds = remaining)
            val expected = if (remaining >= 15_000L) R.string.guest_ready_title else R.string.guest_more_title
            assertEquals(expected, guestMinutesTitle(state, false, false, balance.canStartConversation, null))
            assertEquals(expected, guestMinutesTitle(state, true, false, balance.canStartConversation, remaining))
            assertEquals(remaining, balance.availableMilliseconds)
            assertEquals(remaining, state.remainingMilliseconds)
        }
    }

    @Test fun paidReadinessTakesPrecedenceOverAnIneligibleFreeRemainder() {
        val paid = PaidConversationBalance("USD", "actual-ai-usage", "1500000000", "0", "1500000000",
            900_000, "100000000", "30000000", true)
        for (remaining in listOf(0L, 1L, 14_999L)) {
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", remaining, 0, remaining, paid)
            assertEquals(R.string.guest_ready_title, guestMinutesTitle(
                GuestMinuteState(GuestMinuteStatus.READY, remainingMilliseconds = remaining), true, false,
                balance.canStartConversation, remaining))
        }
    }

    @Test fun pendingOrFailedChecksKeepTheirExistingRecoveryTitle() {
        val remaining = 1L
        assertEquals(R.string.guest_checking_title, guestMinutesTitle(
            GuestMinuteState(GuestMinuteStatus.READY, remainingMilliseconds = remaining), true, true, false, remaining))
        assertEquals(R.string.guest_retry_title, guestMinutesTitle(
            GuestMinuteState(GuestMinuteStatus.RETRY, remainingMilliseconds = remaining), true, false, false, remaining))
        assertEquals(R.string.guest_link_title, guestMinutesTitle(
            GuestMinuteState(GuestMinuteStatus.LINKING, remainingMilliseconds = remaining), true, false, false, remaining))
    }
}
