package chat.mural.core

import java.math.BigInteger
import kotlinx.serialization.Serializable

/** Provider-cost value is authoritative; time is only a display estimate. */
@Serializable
data class PaidConversationBalance(
    val currency: String,
    val billingBasis: String,
    val balanceNanoUSD: String,
    val reservedNanoUSD: String,
    val availableNanoUSD: String,
    val estimatedMilliseconds: Long,
    val estimatedNanoUSDPerMinute: String,
    val minimumSessionNanoUSD: String,
    val available: Boolean,
) {
    init {
        require(currency == "USD" && billingBasis == "actual-ai-usage")
        val balance = nanoAmount(balanceNanoUSD, signed = true)
        val reserved = nanoAmount(reservedNanoUSD)
        val spendable = nanoAmount(availableNanoUSD)
        val rate = nanoAmount(estimatedNanoUSDPerMinute)
        val minimum = nanoAmount(minimumSessionNanoUSD)
        require(rate.signum() > 0 && minimum.signum() > 0)
        require(spendable == (balance - reserved).max(BigInteger.ZERO))
        require(estimatedMilliseconds in 0..9_007_199_254_740_991L)
        require(BigInteger.valueOf(estimatedMilliseconds) == spendable * BigInteger.valueOf(60_000) / rate)
        require(!available || spendable >= minimum)
    }
}

internal fun nanoAmount(value: String, signed: Boolean = false): BigInteger {
    require((if (signed) Regex("-?(0|[1-9][0-9]{0,29})") else Regex("0|[1-9][0-9]{0,29}")).matches(value))
    return value.toBigInteger()
}
