package chat.mural

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.*
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

    @Test fun purchasedTimeIsEstimatedAndAllFeesAreVisibleBeforeCheckout() {
        val quote = AIValueQuote("usd", 2, 200, 1500, 30, 39, 2, 271, 1, "synthetic-usd", "synthetic-estimate")
        val value = AIValueEntitlement("2000000000", 1_200_000, quote)
        val purchases = mutableListOf<String>()
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(packs = listOf(MinutePack("synthetic-value", 20, "$2.71", value))),
                true, { purchases += it }, {}, {}, {})
        } }
        compose.onNodeWithText("About 20 minutes", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        for (label in listOf("For AI usage", "Mural fee (15%)", "Estimated payment fee", "Payment cost buffer")) {
            compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("minute-purchase-pack-synthetic-value").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic-value"), purchases) }
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
    @Test fun spanishAndLargeTextKeepControlsReachableAndSmallBalanceHonest() {
        var refreshed = 0
        compose.setContent { MuralTheme {
            MinutePurchaseSheet(ready.copy(balance = MinuteBalance("milliseconds", "connected-conversation-time", 5_000, 0, 5_000)),
                true, {}, {}, { refreshed++ }, {})
        } }
        compose.onNodeWithText("Más tiempo para hablar").assertExists()
        capture("minute-packs-spanish.png")
        compose.onNodeWithTag("minute-purchase-balance").performScrollTo().assertTextEquals("Menos de un minuto disponible")
        compose.onNodeWithTag("minute-purchase-pack-test-30").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("minute-purchase-minimum").performScrollTo().assertTextContains("Mínimo de 15 segundos", substring = true)
        compose.onNodeWithTag("minute-purchase-refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }
}
