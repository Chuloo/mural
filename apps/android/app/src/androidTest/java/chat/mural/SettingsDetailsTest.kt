package chat.mural

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.Preferences
import chat.mural.core.ConversationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDetailsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private var originalPreferences = Preferences()
    private var originalProvider: ConversationProvider? = null
    private var preferencesCaptured = false

    @Before fun skipOnboarding() {
        compose.awaitHistoryLoaded()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
            originalPreferences = vm.archive.preferences.copy()
            originalProvider = vm.conversationProvider
            preferencesCaptured = true
            vm.updatePreferences(originalPreferences.copy(hasOnboarded = true))
        }
        compose.waitForIdle()
    }

    @After fun restorePreferences() {
        if (!preferencesCaptured) return
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
            vm.updatePreferences(originalPreferences)
            originalProvider?.let(vm::selectConversationProvider)
        }
    }

    @Test fun unavailableMuralBalanceCannotChangePersonalKeySource() {
        val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
        compose.runOnIdle { vm.selectConversationProvider(ConversationProvider.PERSONAL_KEY) }
        compose.onNodeWithTag("tab-settings").performClick()
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasTestTag("settings-conversation-access"))
        compose.onNodeWithTag("settings-conversation-access").performClick()
        compose.onNodeWithTag("settings-conversation-access-HOSTED_MINUTES").performClick()
        compose.onNodeWithTag("settings-source-confirm").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(ConversationProvider.PERSONAL_KEY, vm.conversationProvider) }
    }

    @Test fun settingsMatchTheIphoneCorrectionsKeysAndVersionDetails() {
        val activity = compose.activity
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        assertEquals("0.1", version)
        compose.onNodeWithTag("tab-settings").performClick()
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_corrections_note)))
        settings.performScrollToNode(hasTestTag("settings-conversation-access"))
        compose.onNodeWithTag("settings-conversation-access").performClick()
        compose.onNodeWithTag("settings-conversation-access-PERSONAL_KEY").performClick()
        compose.onNodeWithTag("api-key-input").assertExists()
        compose.onNodeWithText(activity.getString(R.string.common_cancel)).performClick()
        settings.performScrollToNode(hasTestTag("settings-about"))
        compose.onNodeWithTag("settings-about").performClick()
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_app_version_footer, version)))
    }
}
