package chat.mural

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import chat.mural.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestMinutesSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unavailableGuestHasWarmExplanationAndGoogleButNoUnconfiguredPurchase() {
        var signIns = 0
        compose.setContent { MuralTheme {
            GuestMinutesSheet(GuestMinuteState(GuestMinuteStatus.UNAVAILABLE), false, false, false,
                onContinue = {}, onSignIn = { signIns++ }, onBuy = null, onRetry = {}, onSettings = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("guest-minutes-title").assertTextEquals("Free minutes currently unavailable")
        compose.onNodeWithText("Every conversation has an AI cost.", substring = true).assertExists()
        compose.onNodeWithTag("guest-buy-minutes").assertDoesNotExist()
        compose.onNodeWithTag("guest-sign-in").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }
    @Test fun readyGuestCanTalkWithoutSigningInAndSeesMinimumDisclosure() {
        var starts = 0
        compose.setContent { MuralTheme {
            GuestMinutesSheet(GuestMinuteState(GuestMinuteStatus.READY, remainingMilliseconds = 600_000), false, false, true,
                onContinue = { starts++ }, onSignIn = {}, onBuy = null, onRetry = {}, onSettings = {}, onDismiss = {})
        } }
        compose.onNodeWithText("15-second minimum", substring = true).assertExists()
        compose.onNodeWithTag("guest-continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }
    @Test fun accountTransitionCannotStartVoiceOrLaunchPurchaseAndRetryReflectsConnectionFailure() {
        val busy = mutableStateOf(true)
        compose.setContent { MuralTheme {
            GuestMinutesSheet(GuestMinuteState(GuestMinuteStatus.RETRY), true, busy.value, false,
                memberRemaining = 0, onContinue = {}, onSignIn = {}, onBuy = {}, onRetry = {}, onSettings = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("guest-buy-minutes").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("guest-sign-in").assertDoesNotExist()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithTag("guest-minutes-title").performScrollTo().assertTextEquals("Let’s try that again")
        compose.onNodeWithTag("guest-buy-minutes").performScrollTo().assertIsEnabled()
    }
    @Test fun deferredGuestDoesNotHideReadyMembersGiftedMinutesOrRequireGoogleAgain() {
        var starts = 0; var signIns = 0
        compose.setContent { MuralTheme {
            GuestMinutesSheet(GuestMinuteState(GuestMinuteStatus.DEFERRED), true, false, true,
                memberRemaining = 1_800_000, onContinue = { starts++ }, onSignIn = { signIns++ },
                onBuy = {}, onRetry = {}, onSettings = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("guest-sign-in").assertDoesNotExist()
        compose.onNodeWithTag("guest-continue").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, signIns) }
    }
    @Test fun consentIncludesAdultConfirmationWithoutDateOfBirthForm() {
        compose.setContent { MuralTheme { AIConsentDialog({}, {}) } }
        compose.onNodeWithTag("adult-confirmation").performScrollTo().assertTextEquals("By continuing, you confirm you’re 18 or older.")
        compose.onNodeWithTag("ai-consent-agree").performScrollTo().assertIsEnabled()
    }
}
