package chat.mural.core

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class MuralMinutesPresentation(
    val schemaVersion: Int, val asOf: String, val revision: String,
    val freeAvailableMilliseconds: Long, val paidEstimatedMilliseconds: Long?,
    val hasPurchasedRemainder: Boolean, val totalDisplayMilliseconds: Long?, val displayKind: String,
    val estimateRateVersion: String?, val availabilityReason: String, val settlementState: String, val paidSupported: Boolean,
) {
    init {
        require(schemaVersion == 1 && Regex("0|[1-9][0-9]{0,18}").matches(revision) && revision.toLongOrNull() != null)
        Instant.parse(asOf)
        require(freeAvailableMilliseconds >= 0)
        require(availabilityReason in listOf("ready", "insufficient_remaining_time", "active_conversation", "settling", "service_unavailable", "account_action_needed"))
        require(settlementState in listOf("settled", "in_use", "pending"))
        require(displayKind == if (hasPurchasedRemainder) "approximate" else "exactFree")
        require(!hasPurchasedRemainder || paidSupported)
        require(!paidSupported || !estimateRateVersion.isNullOrEmpty())
        if (paidEstimatedMilliseconds != null) {
            require(paidEstimatedMilliseconds >= 0 && totalDisplayMilliseconds == Math.addExact(freeAvailableMilliseconds, paidEstimatedMilliseconds))
            require(hasPurchasedRemainder || paidEstimatedMilliseconds == 0L)
        } else require(totalDisplayMilliseconds == null && !paidSupported)
        require((availabilityReason == "settling") == (settlementState == "pending"))
        require((availabilityReason == "active_conversation") == (settlementState == "in_use"))
    }
}
