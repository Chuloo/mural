package chat.mural

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.Preferences
import chat.mural.network.HostedFailure
import chat.mural.network.ManagedAccountConfiguration
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupErrorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var account: AccountViewModel
    private lateinit var preferences: Preferences
    private var configuration: ManagedAccountConfiguration? = null

    @Before fun prepare() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            assertEquals("chat.mural.android.uitest", compose.activity.packageName)
            account = ViewModelProvider(compose.activity)[AccountViewModel::class.java]
            configuration = account.configuration
            // Enable only the account UI. The isolated app has no account controller or credentials.
            AccountViewModel::class.java.getDeclaredField("configuration").apply { isAccessible = true }
                .set(account, requireNotNull(ManagedAccountConfiguration.parse("https://offline.invalid", "123-offline.apps.googleusercontent.com")))
            preferences = vm.archive.preferences
            vm.updatePreferences(preferences.copy(hasOnboarded = true, aiConsentVersion = 1))
        }
    }
    @After fun restore() {
        if (!::vm.isInitialized || !::preferences.isInitialized) return
        compose.runOnIdle {
            vm.dismissError()
            vm.updatePreferences(preferences)
            AccountViewModel::class.java.getDeclaredField("configuration").apply { isAccessible = true }.set(account, configuration)
        }
    }
    private fun fail(error: Throwable) = compose.runOnIdle {
        MuralViewModel::class.java.getDeclaredMethod("presentError", Throwable::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(vm, error, R.string.error_voice_connect_failed)
    }
    @Test fun rejectedSignInOpensExistingAccountSheetAndClearsRecoveryState() {
        fail(HostedFailure.Http(401, "sign_in_required", reference = "0123abcdef45"))
        compose.onNodeWithText(compose.activity.getString(R.string.hosted_sign_in_again), substring = true).assertIsDisplayed()
        compose.onNodeWithText("0123abcdef45", substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.error_go_to_settings)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.account_title)).performClick()
        compose.onNodeWithTag("account-google").assertExists()
        compose.runOnIdle { assertNull(vm.error); assertFalse(vm.errorNeedsAccountSignIn); assertFalse(vm.errorNeedsKeySetup) }
    }
    @Test fun ordinaryServerFailureKeepsSingleDismissActionAndSafeReference() {
        fail(HostedFailure.Http(500, "service_unavailable", reference = "0123abcdef45"))
        compose.onNodeWithText(compose.activity.getString(R.string.hosted_request_failed), substring = true).assertIsDisplayed()
        compose.onNodeWithText("0123abcdef45", substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.account_title)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.common_ok)).performClick()
        compose.runOnIdle { assertNull(vm.error); assertFalse(vm.errorNeedsAccountSignIn) }
    }
}
