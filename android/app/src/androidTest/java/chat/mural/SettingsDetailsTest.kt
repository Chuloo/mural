package chat.mural

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.Preferences
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

    @Before fun skipOnboarding() {
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
            originalPreferences = vm.archive.preferences.copy()
            vm.updatePreferences(originalPreferences.copy(hasOnboarded = true))
        }
        compose.waitForIdle()
    }

    @After fun restorePreferences() {
        compose.runOnIdle { ViewModelProvider(compose.activity)[MuralViewModel::class.java].updatePreferences(originalPreferences) }
    }

    @Test fun settingsMatchTheIphoneCorrectionsKeysModelsAndVersionDetails() {
        val activity = compose.activity
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        assertEquals("0.1", version)
        compose.onNodeWithTag("tab-settings").performClick()
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_corrections_value)))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_open_api_keys), substring = true))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_models_footer)))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_app_version_footer, version)))
    }
}
