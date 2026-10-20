package chat.mural.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.ProductDetails
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayBillingOfferTest {
    private fun offer(token: String) = JSONObject().put("formattedPrice", "£6.49")
        .put("priceAmountMicros", 6_490_000L).put("priceCurrencyCode", "GBP")
        .put("offerIdToken", token).put("purchaseOptionId", "buy")

    // Build SDK objects from its response shape without connecting to Play or starting checkout.
    private fun product(offers: List<JSONObject>, legacy: Boolean = false): ProductDetails {
        val json = JSONObject().put("productId", "chat.mural.android.minutes.small.v1").put("type", "inapp")
        if (legacy) json.put("oneTimePurchaseOfferDetails", offers.single())
        else json.put("oneTimePurchaseOfferDetailsList", JSONArray(offers))
        return ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(json.toString())
    }

    @Test fun matchingPriceVariantsCannotHideOrReplaceTheSupportedBuyOption() {
        val sdk = product(listOf(offer("base"), offer("variant").put("offerId", "introductory"),
            offer("rental").put("rentalDetails", JSONObject().put("rentalPeriod", "P1D")),
            offer("preorder").put("preorderDetails", JSONObject().put("preorderReleaseTimeMillis", 1_000L).put("preorderPresaleEndTimeMillis", 500L))))
        assertEquals(4, sdk.oneTimePurchaseOfferDetailsList!!.size)
        val offered = PlayBillingAdapter.baseOffers(sdk)
        assertEquals(listOf("base"), offered.map { it.offerToken })
        assertEquals(6_490_000L, offered.single().priceAmountMicros)
    }

    @Test fun legacyBuyOptionsRemainAvailableButAVariantAloneCannotOpenCheckout() {
        assertEquals(1, PlayBillingAdapter.baseOffers(product(listOf(offer("legacy")), legacy = true)).size)
        assertTrue(PlayBillingAdapter.baseOffers(product(listOf(offer("variant").put("offerId", "introductory")))).isEmpty())
    }
}
