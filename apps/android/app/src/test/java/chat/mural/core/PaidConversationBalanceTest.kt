package chat.mural.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaidConversationBalanceTest {
    private val paid = PaidConversationBalance("USD", "actual-ai-usage", "2000000000", "500000000", "1500000000",
        900_000, "100000000", "30000000", true)

    @Test fun freeRemaindersStayVisibleButNeedTheMinimumOrPaidFallback() {
        for (free in listOf(1L, 14_999L, 15_000L)) {
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", free, 0, free)
            val eligible = free >= 15_000L
            assertEquals(eligible, balance.hasFreeConversationTime)
            assertEquals(eligible, balance.canStartConversation)
            assertEquals(if (eligible) free else 0L, balance.readinessMilliseconds)
            assertEquals(free, balance.availableMilliseconds)
            val funded = balance.copy(paid = paid)
            assertTrue(funded.canStartConversation)
            assertEquals(if (eligible) free else paid.estimatedMilliseconds, funded.readinessMilliseconds)
            assertEquals(free, funded.availableMilliseconds)
        }
        // The generic legacy readiness policy still accepts a positive time value.
        assertTrue(HostedReadiness("owner", 1, true).ready)
    }

    @Test fun serverPresentationDeniesReadinessEvenWithFreeAndPaidTime() {
        for (reason in listOf("insufficient_remaining_time", "settling", "active_conversation", "account_action_needed", "service_unavailable")) {
            val free = 60_000L
            val projection = MuralMinutesPresentation(1, "2026-10-03T20:00:00Z", "1", free, paid.estimatedMilliseconds,
                true, free + paid.estimatedMilliseconds, "approximate", "nano-usd-per-minute-100000000", reason,
                when (reason) { "settling" -> "pending"; "active_conversation" -> "in_use"; else -> "settled" }, true)
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", free, 0, free, paid, projection)
            assertFalse(balance.canStartConversation)
            assertEquals(0L, balance.readinessMilliseconds)
            assertEquals(free, balance.availableMilliseconds)
        }
    }

    @Test fun authoritativeLegacyReadinessKeepsSmallFreeTimeAndPrefersAvailablePaidTime() {
        for (free in listOf(1L, 14_999L)) for (cash in listOf(null, paid)) {
            val estimate = cash?.estimatedMilliseconds ?: 0L
            val projection = MuralMinutesPresentation(1, "2026-10-03T20:00:00Z", "1", free, estimate,
                cash != null, free + estimate, if (cash == null) "exactFree" else "approximate",
                if (cash == null) null else "nano-usd-per-minute-100000000", "ready", "settled", cash != null)
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", free, 0, free, cash, projection)
            assertTrue(balance.canStartConversation)
            assertFalse(balance.hasFreeConversationTime)
            assertEquals(cash?.estimatedMilliseconds ?: free, balance.readinessMilliseconds)
            assertEquals(free, balance.availableMilliseconds)
        }
    }

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
