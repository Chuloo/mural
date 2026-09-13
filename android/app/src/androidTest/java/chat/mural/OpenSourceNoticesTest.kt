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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenSourceNoticesTest {
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

    @Test fun settingsOpenEveryBundledNoticeIncludingWebRtc() {
        val label = compose.activity.getString(R.string.settings_open_source_notices)
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(label))
        compose.onNode(hasText(label)).performClick()
        for (file in listOf("THIRD-PARTY-NOTICES.txt", "Mural-LICENSE.txt", "Apache-2.0.txt", "WebRTC-SDK-LICENSE.txt", "WebRTC-THIRD-PARTY-NOTICES.md")) {
            compose.onNodeWithTag("notices-list").performScrollToNode(hasText(file))
        }
    }
}
