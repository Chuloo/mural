package chat.mural

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

/** Offline screenshots and geometry checks of the actual UI, in its isolated test application. */
@RunWith(AndroidJUnit4::class)
class DesignReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var original = Preferences()
    private var preferencesCaptured = false
    @Before fun prepare() {
        compose.awaitHistoryLoaded()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
            original = vm.archive.preferences.copy()
            preferencesCaptured = true
            vm.updatePreferences(original.copy(learningLanguageID = "es", meaningLanguage = "English", hasOnboarded = false, aiConsentVersion = null))
        }
        compose.waitForIdle()
    }
    @After fun restore() {
        if (!preferencesCaptured) return
        compose.runOnIdle { ViewModelProvider(compose.activity)[MuralViewModel::class.java].updatePreferences(original) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        // Compose can be idle while Android is still fading a dialog window away.
        automation.waitForIdle(600, 5_000)
        val bitmap = automation.takeScreenshot()
            ?: error("Emulator screenshot unavailable")
        val directory = File(compose.activity.filesDir, "design-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun dropdownOnboardingAndCoreScreensKeepTheFloatingNavigationAndPrimaryActionVisible() {
        compose.onNodeWithTag("onboarding-language-picker").assertIsDisplayed()
        capture("01-onboarding")
        compose.onNodeWithTag("onboarding-language-picker").performClick()
        compose.onNodeWithTag("onboarding-language-es").performClick()
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("onboarding-meaning-picker").assertIsDisplayed()
        capture("02-meaning")
        compose.onNodeWithTag("onboarding-meaning-picker").performClick()
        compose.onNodeWithTag("meaning-English").performClick()
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("ai-consent-decline").performClick()
        compose.onNodeWithTag("start-conversation").assertIsDisplayed()
        compose.onNodeWithTag("meaning-caption").assertIsDisplayed()
        val nav = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(nav.width < root.width * .9f)
        assertTrue(nav.left > root.left && nav.right < root.right)
        capture("03-talk")
        compose.onNodeWithTag("tab-topics").performClick()
        compose.onNodeWithTag("topics-screen").assertIsDisplayed()
        capture("04-themes")
        compose.onNodeWithTag("tab-words").performClick()
        capture("05-words")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
    }
}
