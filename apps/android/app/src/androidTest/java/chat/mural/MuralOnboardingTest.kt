package chat.mural

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.text.AnnotatedString
import chat.mural.core.Preferences
import chat.mural.core.CloudAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith

/**
 * These tests deliberately exercise the real Activity. A missing onboarding or consent gate
 * would allow the microphone action to reach a network-backed ViewModel method too early.
 */
@RunWith(AndroidJUnit4::class)
class MuralOnboardingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private var originalPreferences = Preferences()
    private var preferencesCaptured = false

    @Before fun preservePreferences() {
        compose.awaitHistoryLoaded()
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[MuralViewModel::class.java]
            originalPreferences = vm.archive.preferences.copy()
            preferencesCaptured = true
            vm.updatePreferences(originalPreferences.copy(hasOnboarded = false, aiConsentVersion = null))
        }
        compose.waitForIdle()
    }

    @After fun restorePreferences() {
        if (!preferencesCaptured) return
        compose.runOnIdle { ViewModelProvider(compose.activity)[MuralViewModel::class.java].updatePreferences(originalPreferences) }
    }

    @Test
    fun firstRun_requiresBothLanguageStepsAndExplicitAiConsent() {
        compose.onNodeWithTag("onboarding-language-title").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("onboarding-meaning-title").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("ai-consent-title").assertIsDisplayed()
        compose.onNodeWithTag("ai-consent-decline").performClick()
        compose.onNodeWithTag("talk-screen").assertIsDisplayed()
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("words-screen").assertIsDisplayed()
        compose.onNodeWithTag("tab-talk").performClick()
        compose.onNodeWithTag("start-conversation").performClick()
        compose.onNodeWithTag("ai-consent-title").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("ai-consent-title").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(CloudAction.StartVoice, ViewModelProvider(compose.activity)[MuralViewModel::class.java].pendingCloudAction)
        }
        compose.onNodeWithTag("ai-consent-decline").performClick()
        compose.runOnIdle { assertNull(ViewModelProvider(compose.activity)[MuralViewModel::class.java].pendingCloudAction) }

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-ai-permission"))
        compose.onNodeWithTag("settings-ai-permission").performClick()
        compose.onNodeWithTag("review-ai-consent").performClick()
        compose.onNodeWithTag("ai-consent-title").assertIsDisplayed()
        compose.onNodeWithTag("ai-consent-agree").performClick()
        compose.onNodeWithTag("settings-ai-permission").performClick()
        compose.onNodeWithTag("revoke-ai-consent").assertIsDisplayed()
        compose.onNode(hasText(compose.activity.getString(R.string.common_close))).performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("advanced-api-key"))
        compose.onNodeWithTag("advanced-api-key").performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyButton = hasText(context.getString(R.string.settings_save_key)) or hasText(context.getString(R.string.settings_replace_key))
        compose.onNodeWithTag("settings-screen").performScrollToNode(keyButton)
        compose.onNode(keyButton).performClick()
        compose.onNodeWithTag("api-key-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .performTextInput("sk-do-not-save")

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("api-key-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
