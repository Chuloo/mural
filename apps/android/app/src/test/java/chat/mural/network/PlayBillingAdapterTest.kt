package chat.mural.network

import chat.mural.core.MinuteStoreOutcome
import com.android.billingclient.api.BillingClient.BillingResponseCode
import org.junit.Assert.*
import org.junit.Test

class PlayBillingAdapterTest {
    @Test fun billingOutcomesDoNotTreatOpeningFlowAsCompletedPayment() {
        assertEquals(MinuteStoreOutcome.OPENED, PlayBillingAdapter.outcome(BillingResponseCode.OK))
        assertEquals(MinuteStoreOutcome.PURCHASES_UPDATED, PlayBillingAdapter.outcome(BillingResponseCode.OK, updated = true))
        assertEquals(MinuteStoreOutcome.CANCELED, PlayBillingAdapter.outcome(BillingResponseCode.USER_CANCELED))
        assertEquals(MinuteStoreOutcome.ALREADY_OWNED, PlayBillingAdapter.outcome(BillingResponseCode.ITEM_ALREADY_OWNED))
        for (code in listOf(BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingResponseCode.ITEM_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR))
            assertEquals(MinuteStoreOutcome.UNAVAILABLE, PlayBillingAdapter.outcome(code))
        assertEquals(MinuteStoreOutcome.FAILED, PlayBillingAdapter.outcome(BillingResponseCode.ERROR))
        assertEquals(MinuteStoreOutcome.FAILED, PlayBillingAdapter.outcome(9999))
    }
}
