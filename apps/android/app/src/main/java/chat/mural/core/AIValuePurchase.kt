package chat.mural.core

import kotlinx.serialization.Serializable

@Serializable
data class AIValueQuote(
    val currency: String, val currencyExponent: Int, val aiValueMinor: Long,
    val serviceFeeBasisPoints: Int, val serviceFeeMinor: Long,
    val processingEstimateMinor: Long, val processingBufferMinor: Long, val totalMinor: Long,
    val policyVersion: Int, val exchangeRateVersion: String, val estimateRateVersion: String,
) {
    init {
        require(Regex("[a-z]{3}").matches(currency) && currencyExponent in 0..3)
        require(java.util.Currency.getInstance(currency.uppercase(java.util.Locale.ROOT)).defaultFractionDigits == currencyExponent)
        require(aiValueMinor in 1..100_000_000 && totalMinor in 1..100_000_000)
        require(serviceFeeBasisPoints in 0..10_000 && policyVersion > 0)
        require(listOf(serviceFeeMinor, processingEstimateMinor, processingBufferMinor).all { it in 0..100_000_000 })
        require(serviceFeeMinor == (aiValueMinor * serviceFeeBasisPoints + 9_999) / 10_000)
        require(totalMinor == aiValueMinor + serviceFeeMinor + processingEstimateMinor + processingBufferMinor)
        require(listOf(exchangeRateVersion, estimateRateVersion).all { it.length in 1..128 && it.none(Char::isISOControl) })
    }
}

internal const val MAX_AI_ESTIMATE_MS = Int.MAX_VALUE.toLong() * 60_000L

data class AIValueEntitlement(val aiValueNanoUSD: String, val estimatedMilliseconds: Long, val quote: AIValueQuote) {
    init {
        require(nanoAmount(aiValueNanoUSD).signum() > 0)
        require(estimatedMilliseconds in 1..MAX_AI_ESTIMATE_MS)
    }
    val displayMinutes get() = ((estimatedMilliseconds + 59_999) / 60_000).toInt()
}

data class AIValueFulfillment(val grantedNanoUSD: String, val reversedNanoUSD: String, val reversalOutstandingNanoUSD: String) {
    init {
        val granted = nanoAmount(grantedNanoUSD)
        val reversed = nanoAmount(reversedNanoUSD)
        val outstanding = nanoAmount(reversalOutstandingNanoUSD)
        require(reversed + outstanding <= granted)
    }
    val recorded get() = nanoAmount(grantedNanoUSD).signum() > 0
    val reversed get() = nanoAmount(reversedNanoUSD).signum() > 0 || nanoAmount(reversalOutstandingNanoUSD).signum() > 0
}
