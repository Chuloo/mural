package chat.mural.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaidConversationBalanceTest {
    private val paid = PaidConversationBalance("USD", "actual-ai-usage", "2000000000", "500000000", "1500000000",
        900_000, "100000000", "30000000", true)

    @Test fun freeTimeAndPaidEstimateRemainSeparateAndSpendable() {
        val balance = MinuteBalance("milliseconds", "connected-conversation-time", 480_000, 0, 480_000, paid)
        assertEquals(480_000, balance.readinessMilliseconds)
        assertEquals(900_000, balance.paid!!.estimatedMilliseconds)
        assertTrue(balance.canStartConversation)
        assertEquals(900_000, balance.copy(balanceMilliseconds = 0, availableMilliseconds = 0).readinessMilliseconds)
    }

    @Test fun legacyGuestBalanceDecodesWithoutPaidFunds() {
        val result = Json.decodeFromString<MinuteBalance>("""{"unit":"milliseconds","billingBasis":"connected-conversation-time","balanceMilliseconds":600000,"reservedMilliseconds":0,"availableMilliseconds":600000}""")
        assertNull(result.paid)
        assertTrue(result.canStartConversation)
    }

    @Test fun refundDebtDoesNotBecomePositiveTime() {
        val debt = paid.copy(balanceNanoUSD = "-500000000", availableNanoUSD = "0", estimatedMilliseconds = 0, available = false)
        assertFalse(MinuteBalance("milliseconds", "connected-conversation-time", 0, 0, 0, debt).canStartConversation)
    }

    @Test fun valueUnderStartMinimumRemainsVisibleWithoutEnablingAConversation() {
        val small = paid.copy(balanceNanoUSD = "10000000", reservedNanoUSD = "0", availableNanoUSD = "10000000",
            estimatedMilliseconds = 6000, available = false)
        val balance = MinuteBalance("milliseconds", "connected-conversation-time", 0, 0, 0, small)
        assertFalse(balance.canStartConversation)
        assertEquals(6000, balance.paid!!.estimatedMilliseconds)
        assertEquals(0, balance.readinessMilliseconds)
        assertThrows(IllegalArgumentException::class.java) { small.copy(available = true) }
    }

    @Test fun inconsistentOrUntrustedMoneyAndEstimatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { paid.copy(availableNanoUSD = "2000000000") }
        assertThrows(IllegalArgumentException::class.java) { paid.copy(estimatedMilliseconds = 1_800_000) }
        assertThrows(IllegalArgumentException::class.java) { paid.copy(balanceNanoUSD = "2e9") }
        assertThrows(IllegalArgumentException::class.java) { paid.copy(reservedNanoUSD = "-1") }
        assertThrows(IllegalArgumentException::class.java) { paid.copy(estimatedNanoUSDPerMinute = "0") }
        assertThrows(IllegalArgumentException::class.java) { paid.copy(currency = "EUR") }
    }
}
