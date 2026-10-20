package chat.mural

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.ui.MuralStartup
import chat.mural.ui.MuralTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MuralSplashTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localHistoryGatesContentAndCompletedStartupSurvivesRecreation() {
        val loading = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MuralTheme {
            MuralStartup(loading.value) {
                Text("Ready", Modifier.testTag("startup-content"))
            }
        } }
        compose.onNodeWithTag("startup-loading").assertIsDisplayed()
        compose.onNodeWithTag("mural-splash-orb", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("startup-content").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("startup-content").assertIsDisplayed()
        compose.onNodeWithTag("startup-loading").assertDoesNotExist()
        compose.runOnIdle { loading.value = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("startup-content").assertIsDisplayed()
        compose.onNodeWithTag("startup-loading").assertDoesNotExist()
    }

    @Test fun readyHistoryShowsContentWithoutATimedSplash() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MuralTheme {
            MuralStartup(loading = false) { Text("Ready", Modifier.testTag("startup-content")) }
        } }
        compose.onNodeWithTag("startup-content").assertIsDisplayed()
        compose.onNodeWithTag("startup-loading").assertDoesNotExist()
        compose.onNodeWithTag("mural-splash-orb", useUnmergedTree = true).assertDoesNotExist()
    }
}
