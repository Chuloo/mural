package chat.mural.core

import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MinuteQuantityTest {
    @Test fun quantitiesPreserveUnitRoundingAndFloorOnlyTheCombinedEstimate() {
        val unit = AIValueEntitlement("3690000000", 2_214_000,
            AIValueQuote("usd", 2, 369, 1500, 56, 70, 5, 500, 1, "usd-v1", "estimate-v1"))
        for ((quantity, minutes) in listOf(1 to 36, 2 to 73, 10 to 369)) {
            val order = unit.multiplied(quantity)
            assertEquals(minutes, order.displayMinutes)
            assertEquals(500L * quantity, order.quote.totalMinor)
            assertEquals(56L * quantity, order.quote.serviceFeeMinor)
        }
        for (quantity in listOf(-1, 0, 11, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { unit.multiplied(quantity) }
        }
        assertThrows(IllegalArgumentException::class.java) { unit.multiplied(2).quote.copy(serviceFeeMinor = 111, totalMinor = 999) }
    }
    @Test fun sharedMinutesProjectionSupportsMixedHeldTinyAndRefundedBalances() {
        val fixtures = Json.parseToJsonElement(File("../../../shared/fixtures/cross-platform/minutes-presentation.json").readText()).jsonArray
        for (fixture in fixtures.map { it.jsonObject }) {
            val projection = Json.decodeFromJsonElement<MuralMinutesPresentation>(fixture.getValue("presentation"))
            val paid = fixture.getValue("paidNanoUSD").jsonPrimitive.content.toBigInteger()
            val held = fixture.getValue("reservedNanoUSD").jsonPrimitive.content.toBigInteger()
            val available = (paid - held).max(java.math.BigInteger.ZERO)
            val free = fixture.getValue("freeMilliseconds").jsonPrimitive.long
            val estimate = (available * 60_000.toBigInteger() / 100_000_000.toBigInteger()).toLong()
            val balance = MinuteBalance("milliseconds", "connected-conversation-time", free, 0, free,
                PaidConversationBalance("USD", "actual-ai-usage", paid.toString(), held.toString(), available.toString(),
                    estimate, "100000000", "30000000", available >= 30_000_000.toBigInteger()), projection)
            assertEquals(free + estimate, projection.totalDisplayMilliseconds)
            assertEquals(projection.availabilityReason == "ready", balance.canStartConversation)
            if (fixture.getValue("name").jsonPrimitive.content == "mixed") assertEquals(41L, projection.totalDisplayMilliseconds!! / 60_000)
        }
    }
}
