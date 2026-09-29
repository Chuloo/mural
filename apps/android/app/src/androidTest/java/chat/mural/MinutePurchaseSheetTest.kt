package chat.mural

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import chat.mural.ui.MinutePurchaseSheet
import chat.mural.ui.MuralTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class MinutePurchaseSheetTest {
    private val compose = createComposeRule()
    // The sheet has its own native window. Set the isolated app's real locale before its Activity starts.
    private val spanishLocale = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            if (!description.methodName.startsWith("spanish")) { base.evaluate(); return }
            assumeTrue(Build.VERSION.SDK_INT >= 33)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val manager = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
            val previous = manager.applicationLocales
            try {
                instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags("es") }
                instrumentation.waitForIdleSync()
                base.evaluate()
            } finally {
                instrumentation.runOnMainSync { manager.applicationLocales = previous }
                instrumentation.waitForIdleSync()
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(spanishLocale).around(compose)
    private val pack = MinutePack("test-30", 30, "5,99 €")
    private val ready = MinutePurchaseState(available = true, packs = listOf(pack))

    @Test fun selectionShowsMinutesAndLocalPriceWithoutInternalFees() {
        val quote = AIValueQuote("usd", 2, 200, 1500, 30, 39, 2, 271, 1, "synthetic-usd", "synthetic-estimate")
        val value = AIValueEntitlement("2000000000", 1_200_000, quote)
        val purchases = mutableListOf<String>()
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(packs = listOf(MinutePack("synthetic-value", 20, "$2.71", value))),
                true, { purchases += it }, {}, {}, {})
        } }
        compose.onNodeWithTag("minute-purchase-total").assertTextEquals("About 20 minutes")
        for (label in listOf("For AI usage", "Mural fee (15%)", "Estimated payment fee", "Payment cost buffer")) {
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("minute-purchase-pack-synthetic-value").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(purchases.isEmpty()) }
        compose.onNodeWithTag("minute-purchase-continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-value"), purchases) }
    }

    @Test fun quantityAggregatesBeforeFlooringAndRequiresExplicitCheckout() {
        val quote = AIValueQuote("usd", 2, 369, 1500, 56, 70, 5, 500, 1, "synthetic-usd", "synthetic-estimate")
        val value = AIValueEntitlement("3690000000", 2_214_000, quote)
        val purchases = mutableListOf<Pair<String, Int>>()
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(channel = PurchaseChannel.STRIPE, maximumQuantity = 10,
                packs = listOf(MinutePack("starter", 36, "$5.00", value))), true,
                { error("Quantity lost") }, {}, {}, {}, onBuyQuantity = { sku, quantity -> purchases += sku to quantity })
        } }
        compose.onNodeWithTag("minute-purchase-total").assertTextEquals("About 36 minutes")
        compose.onNodeWithTag("minute-quantity-increase").performClick()
        compose.onNodeWithTag("minute-purchase-total").assertTextEquals("About 73 minutes")
        compose.onNodeWithTag("minute-purchase-continue").assertTextContains("$10.00", substring = true)
        compose.runOnIdle { assertTrue(purchases.isEmpty()) }
        repeat(8) { compose.onNodeWithTag("minute-quantity-increase").performClick() }
        compose.onNodeWithTag("minute-quantity-increase").assertIsNotEnabled()
        compose.onNodeWithTag("minute-purchase-total").assertTextEquals("About 369 minutes")
        capture("minute-packs-quantity-ten.png")
        compose.onNodeWithTag("minute-purchase-continue").performClick()
        compose.runOnIdle { assertEquals(listOf("starter" to 10), purchases) }
    }

    @Test fun compactHeaderKeepsThreePackChoicesVisibleBeforeCheckout() {
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(channel = PurchaseChannel.STRIPE, maximumQuantity = 10,
                packs = listOf(MinutePack("small", 36, "$5.00"), MinutePack("medium", 79, "$10.00"),
                    MinutePack("large", 123, "$15.00"))), true, {}, {}, {}, {}, onBuyQuantity = { _, _ -> })
        } }
        for (sku in listOf("small", "medium", "large")) {
            compose.onNodeWithTag("minute-purchase-pack-$sku").assertIsDisplayed()
        }
        compose.onNodeWithTag("minute-purchase-pack-medium").performClick()
        compose.onNodeWithTag("minute-purchase-continue").assertIsDisplayed().assertTextContains("$10.00", substring = true)
        capture("minute-packs-compact-three.png")
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("minute-purchase-sheet").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun localizedQuoteAndMinimumAreVisibleAndTapSendsOnlySku() {
        val purchases = mutableListOf<String>()
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready, true, { purchases += it }, {}, {}, {})
        } }
        compose.onNodeWithText("5,99 €", useUnmergedTree = true).assertExists()
        capture("minute-packs-english.png")
        compose.onNodeWithTag("minute-purchase-pack-test-30").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(purchases.isEmpty()) }
        compose.onNodeWithTag("minute-purchase-continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-30"), purchases) }
        compose.onNodeWithTag("minute-purchase-minimum").performScrollTo().assertTextContains("15-second minimum", substring = true)
    }
    @Test fun guestsCannotBuyAndCanOpenAccountFlowOrDismiss() {
        var signIns = 0; var dismissals = 0
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready, false, { error("guest bought") }, { signIns++ }, {}, { dismissals++ })
        } }
        compose.onNodeWithTag("minute-purchase-pack-test-30").assertIsNotEnabled()
        compose.onNodeWithTag("minute-purchase-sign-in").performScrollTo().performClick()
        compose.onNodeWithTag("minute-purchase-close").performClick()
        compose.runOnIdle { assertEquals(1, signIns); assertEquals(1, dismissals) }
    }
    @Test fun pendingAndVerificationBlockPurchasesButCanceledCanRetry() {
        val current = mutableStateOf(ready.copy(purchaseInProgress = true, notice = MinutePurchaseNotice.PENDING))
        compose.setContent { MuralTheme { MinutePurchaseSheet(current.value, true, {}, {}, {}, {}) } }
        compose.onNodeWithTag("minute-purchase-pack-test-30").assertIsNotEnabled()
        compose.onNodeWithTag("minute-purchase-status").performScrollTo().assertExists()
        capture("minute-packs-pending.png")
        compose.runOnIdle { current.value = ready.copy(notice = MinutePurchaseNotice.VERIFYING) }
        compose.onNodeWithTag("minute-purchase-pack-test-30").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = ready.copy(notice = MinutePurchaseNotice.CANCELED) }
        compose.onNodeWithTag("minute-purchase-pack-test-30").assertIsEnabled()
    }
    @Test fun unsupportedPlayCountryUsesSpecificCopyAndCannotOpenCheckout() {
        val current = mutableStateOf(MinutePurchaseState(regionUnavailable = true))
        compose.setContent { MuralTheme { MinutePurchaseSheet(current.value, true, { error("unavailable checkout") }, {}, {}, {}) } }
        compose.onNodeWithTag("minute-purchase-empty").assertTextEquals("Minute packs aren’t available in your Google Play country.")
        compose.onNodeWithTag("minute-purchase-continue").assertDoesNotExist()
        capture("minute-packs-country-unavailable.png")
        compose.runOnIdle { current.value = MinutePurchaseState() }
        compose.onNodeWithTag("minute-purchase-empty").assertTextEquals("Minute packs aren’t available right now. Please check again later.")
    }
    @Test fun regionalPlayPackShowsTheLocalStorePriceWithItsUsdBackedMinuteEstimate() {
        val quote = AIValueQuote("usd", 2, 369, 1500, 56, 0, 0, 425, 1, "usd-v1", "estimate-v1",
            play = PlayPriceSnapshot("gbp", 2, 599, "play-global-v1", "GB", "fixed-usd-allocation"))
        val value = AIValueEntitlement("3690000000", 2_214_000, quote)
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(packs = listOf(MinutePack("small-gb", 36, "£5.99", value))), true, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("minute-purchase-total").assertTextEquals("About 36 minutes")
        compose.onNodeWithTag("minute-purchase-continue").assertIsDisplayed().assertTextContains("£5.99", substring = true)
        compose.onNodeWithText("$4.25", useUnmergedTree = true).assertDoesNotExist()
        capture("minute-packs-global-gbp.png")
    }
    @Test fun spanishAndLargeTextKeepControlsReachableAndSmallBalanceHonest() {
        var refreshed = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { MuralTheme {
            MinutePurchaseSheet(ready.copy(balance = MinuteBalance("milliseconds", "connected-conversation-time", 5_000, 0, 5_000)),
                true, {}, {}, { refreshed++ }, {})
        } } }
        compose.onNodeWithText("Añadir minutos de Mural").assertExists()
        capture("minute-packs-spanish.png")
        compose.onNodeWithTag("paid-minute-estimate").performScrollTo().assertTextEquals("0 min 5 s")
        compose.onNodeWithTag("minute-purchase-pack-test-30").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("minute-purchase-minimum").performScrollTo().assertTextContains("Mínimo de 15 segundos", substring = true)
        compose.onNodeWithTag("minute-purchase-refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }
}
