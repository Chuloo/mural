package chat.mural

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.AccountState
import chat.mural.core.AccountNotice
import chat.mural.core.ConversationProvider
import chat.mural.core.MinuteBalance
import chat.mural.ui.AccountSheet
import chat.mural.ui.MuralTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unavailableGoogleDoesNotLaunchAndExplainsGuestAccess() {
        var signIns = 0
        compose.setContent { MuralTheme {
            AccountSheet(AccountState(), {}, { signIns++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("account-google").assertIsNotEnabled()
        assertEquals(0, signIns)
        compose.onNodeWithText("Make yourself at home.").assertIsDisplayed()
        compose.onNodeWithText("You can keep using Mural without an account.", substring = true).assertExists()
    }
    @Test fun accountShowsMinutesAndDeletionRequiresConfirmation() {
        var deletions = 0
        val state = AccountState(googleAvailable = true, accountID = "synthetic-account", email = "preview@example.test",
            minutes = MinuteBalance("milliseconds", "connected-conversation-time", 1_800_000, 0, 1_800_000))
        compose.setContent { MuralTheme { AccountSheet(state, {}, {}, {}, { deletions++ }, {}) } }
        compose.onNodeWithTag("account-minute-balance").assertTextEquals("30 minutes")
        compose.onNodeWithText("Delete account").performScrollTo().performClick()
        assertEquals(0, deletions)
        compose.onNodeWithText("Delete your Mural account", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("account-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val member = AccountState(accountID = "12345678-1234-1234-1234-123456789012", googleAvailable = true,
        minutes = MinuteBalance("milliseconds", "connected-conversation-time", 5_000, 0, 5_000))

    @Test fun finalSecondsAreVisibleAndAnUnavailableStoreDoesNotOfferPurchases() {
        compose.setContent { MuralTheme { AccountSheet(member, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("account-minute-balance").assertTextEquals(context.getString(R.string.account_seconds_value, "5"))
        compose.onNodeWithTag("account-buy-minutes").assertDoesNotExist()
        compose.onNodeWithTag("account-conversation-source").assertDoesNotExist()
    }

    @Test fun minutesRequireAnExplicitChoiceAndCannotChangeDuringAConversation() {
        val running = mutableStateOf(false)
        val provider = mutableStateOf(ConversationProvider.PERSONAL_KEY)
        compose.setContent { MuralTheme {
            AccountSheet(member, {}, {}, {}, {}, {}, provider = provider.value, hostedAvailable = true,
                conversationRunning = running.value, onSelectProvider = { provider.value = it })
        } }
        compose.runOnIdle { assertEquals(ConversationProvider.PERSONAL_KEY, provider.value) }
        compose.onNodeWithTag("account-conversation-source").performScrollTo().performClick()
        compose.onNodeWithTag("account-conversation-source-HOSTED_MINUTES").performClick()
        compose.onNodeWithText(context.getString(R.string.hosted_minimum_charge_disclosure)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(ConversationProvider.HOSTED_MINUTES, provider.value); running.value = true }
        compose.onNodeWithTag("account-conversation-source").assertIsNotEnabled()
    }

    @Test fun accountTransitionDisablesAnotherSignInOrPurchase() {
        var purchases = 0
        compose.setContent { MuralTheme {
            AccountSheet(member, {}, {}, {}, {}, {}, transitionBusy = true, onBuyMinutes = { purchases++ })
        } }
        compose.onNodeWithTag("account-buy-minutes").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.account_sign_out)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, purchases) }
    }

    @Test fun blockedDeletionOpensOnlyAnExplicitSupportDraftWithoutAccountData() {
        var deletions = 0
        val opened = mutableListOf<String>()
        val uri = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uri) { MuralTheme {
            AccountSheet(member.copy(email = "private-member@example.test", notice = AccountNotice.BILLING_UNRESOLVED),
                {}, {}, {}, { deletions++ }, {})
        } } }
        compose.onNodeWithTag("account-deletion-support").performScrollTo().performClick()
        compose.onNodeWithText("hi@hackmamba.io").assertIsDisplayed()
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertEquals(0, deletions) }
        compose.onNodeWithTag("account-deletion-email").performClick()
        compose.runOnIdle {
            assertEquals(listOf("mailto:hi@hackmamba.io?subject=Mural%20account%20deletion%20request"), opened)
            assertEquals(0, deletions)
        }
        compose.onNodeWithTag("account-deletion-request").assertExists()
        compose.onNodeWithText(context.getString(R.string.account_deleted)).assertDoesNotExist()
        compose.onNodeWithTag("account-deletion-web").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://mural.chat/support/#delete-account", opened.last()) }
    }

    @Test fun noEmailOrBrowserAppKeepsACopyableContactAndDoesNotClaimSubmission() {
        val clipboard = object : ClipboardManager {
            var value: AnnotatedString? = null
            override fun getText(): AnnotatedString? = value
            override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
        }
        val uri = object : UriHandler {
            override fun openUri(uri: String) { throw IllegalArgumentException("No activity found") }
        }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uri, LocalClipboardManager provides clipboard) { MuralTheme {
            AccountSheet(member.copy(notice = AccountNotice.APPLE_DELETION), {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("account-deletion-support").performScrollTo().performClick()
        compose.onNodeWithTag("account-deletion-email").performClick()
        compose.onNodeWithTag("account-deletion-open-failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-deletion-web").performScrollTo().performClick()
        compose.onNodeWithTag("account-deletion-open-failed").assertExists()
        compose.onNodeWithTag("account-deletion-copy-email").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("hi@hackmamba.io", clipboard.value?.text) }
        compose.onNodeWithText(context.getString(R.string.account_deletion_email_copied)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_deleted)).assertDoesNotExist()
    }

    @Test fun deletionSupportCanBeRequestedBeforeAnAutomaticAttemptAndClosesOnAccountChange() {
        var deletions = 0
        val state = mutableStateOf(member)
        compose.setContent { MuralTheme { AccountSheet(state.value, {}, {}, {}, { deletions++ }, {}) } }
        compose.onNodeWithText(context.getString(R.string.account_delete)).performScrollTo().performClick()
        compose.onNodeWithTag("account-request-deletion").performClick()
        compose.onNodeWithTag("account-deletion-request").assertExists()
        compose.onNodeWithTag("account-confirm-delete").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletions); state.value = AccountState(googleAvailable = true) }
        compose.onNodeWithTag("account-deletion-request").assertDoesNotExist()
        compose.onNodeWithTag("account-deletion-support").assertDoesNotExist()
    }

    @Test fun anEmptyAccountStillUsesConfirmedAutomaticDeletion() {
        var deletions = 0
        compose.setContent { MuralTheme {
            AccountSheet(member.copy(minutes = MinuteBalance("milliseconds", "connected-conversation-time", 0, 0, 0)),
                {}, {}, {}, { deletions++ }, {})
        } }
        compose.onNodeWithText(context.getString(R.string.account_delete)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deletions) }
        compose.onNodeWithTag("account-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
        compose.onNodeWithTag("account-deletion-request").assertDoesNotExist()
    }
}
