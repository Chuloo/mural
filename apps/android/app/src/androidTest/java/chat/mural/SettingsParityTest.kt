package chat.mural

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.Preferences
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsParityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private var original: Preferences? = null

    @Before fun setup() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = vm.archive.preferences.copy()
            vm.updatePreferences(original!!.copy(hasOnboarded = true, learningLanguageID = "es", meaningVisible = true, meaningLanguage = "English"))
        }
        compose.onNodeWithTag("tab-settings").performClick()
    }
    @After fun restore() { original?.let { prefs -> compose.runOnIdle { vm.updatePreferences(prefs) } } }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(600, 5_000)
        val image = automation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(compose.activity.filesDir, "settings-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun groupedSettingsKeepKeyAdvancedAndRestoreSubtitleLanguageAndMinutePreferences() {
        compose.onNodeWithText(compose.activity.getString(R.string.settings_navigation_title)).assertIsDisplayed()
        compose.onNodeWithTag("settings-meaning-visible").assertIsOn()
        compose.onNodeWithTag("api-key-input").assertDoesNotExist()
        capture("01-overview")
        compose.onNodeWithTag("settings-meaning-visible").performClick().assertIsOff()
        compose.runOnIdle { assertFalse(vm.archive.preferences.meaningVisible) }
        compose.onNodeWithTag("settings-learning-language").performClick()
        capture("02-language-menu")
        compose.onNodeWithTag("settings-learning-language-fr").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("fr", vm.archive.preferences.learningLanguageID) }
        compose.onNodeWithTag("settings-meaning-language").performClick()
        compose.onNodeWithTag("settings-meaning-language-Spanish").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Spanish", vm.archive.preferences.meaningLanguage) }
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasTestTag("settings-conversation-limit"))
        compose.onNodeWithTag("settings-conversation-limit").performClick()
        compose.onNodeWithTag("settings-conversation-limit-30").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(30, vm.archive.preferences.sessionMinutes) }
        settings.performScrollToNode(hasTestTag("advanced-api-key"))
        compose.onNodeWithTag("advanced-api-key").performClick()
        settings.performScrollToNode(hasText(compose.activity.getString(R.string.settings_key_owner_footer)))
        capture("03-advanced")
        settings.performScrollToNode(hasTestTag("settings-history"))
        capture("04-data")
        compose.onNodeWithTag("settings-done").performClick()
        compose.onNodeWithTag("settings-screen").assertDoesNotExist()
        compose.onNodeWithTag("floating-navigation").assertIsDisplayed()
    }

    // Also exercised with emulator font_scale=1.6. System settings are restored after capture;
    // a LocalDensity override does not reach the separate native window used by the sheet.
    @Test fun settingsControlsRemainReachableAndInterestInputVisibleAboveKeyboard() {
        compose.onNodeWithTag("settings-done").assertIsDisplayed()
        capture("05-large-text")
        val settings = compose.onNodeWithTag("settings-screen")
        settings.performScrollToNode(hasTestTag("advanced-api-key"))
        compose.onNodeWithTag("advanced-api-key").performClick()
        settings.performScrollToNode(hasText(compose.activity.getString(R.string.settings_key_owner_footer)))
        capture("06-large-advanced")
        settings.performScrollToNode(hasTestTag("settings-interests"))
        compose.onNodeWithTag("settings-interests").performClick().performTextInput("Music, food, and a little travel.")
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        compose.onNodeWithTag("settings-interests").assertIsDisplayed()
        capture("07-large-keyboard")
        compose.runOnIdle { assertTrue(vm.archive.preferences.interests.contains("Music, food")) }
    }
}
