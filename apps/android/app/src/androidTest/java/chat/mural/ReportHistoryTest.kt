package chat.mural

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses the isolated UI-test app with reporting/network configuration blank. */
@RunWith(AndroidJUnit4::class)
class ReportHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private var originalPreferences: Preferences? = null
    private val session = SessionRecord(id = UUID.randomUUID().toString(), languageID = "es", endedAt = nowSeconds(),
        title = "Reporting test conversation", fragments = mutableListOf(
            Fragment(id = UUID.randomUUID().toString(), speaker = Speaker.assistant, text = "Hola. ¿Cómo estás?", startMS = 0, endMS = 1000),
            Fragment(id = UUID.randomUUID().toString(), speaker = Speaker.user, text = "Private learner reply", startMS = 1100, endMS = 2000),
        ))

    @Before fun setup() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            originalPreferences = vm.archive.preferences.copy()
            assertTrue(vm.importData(ArchiveCodec.encode(Archive(sessions = mutableListOf(session)))))
            vm.updatePreferences(originalPreferences!!.copy(hasOnboarded = true))
        }
    }

    @After fun restore() {
        originalPreferences?.let { prefs -> compose.runOnIdle {
            vm.dismissReport(); vm.deleteSession(session.id); vm.updatePreferences(prefs)
        } }
    }

    @Test fun savedAssistantReplyOpensExactPreviewAndUnavailableServiceCannotSubmit() {
        compose.onNodeWithTag("report-current-utterance").assertDoesNotExist()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-history"))
        compose.onNodeWithTag("settings-history").performClick()
        compose.onNodeWithTag("settings-history-list").performScrollToNode(hasText(session.title))
        compose.onNodeWithText(session.title).performClick()
        compose.onNodeWithTag("report-history-${session.fragments[1].id}").assertDoesNotExist()
        compose.onNodeWithTag("report-history-${session.fragments[0].id}").assertHasClickAction().performClick()
        compose.onNodeWithTag("report-sheet").assertIsDisplayed()
        compose.onNodeWithTag("report-excerpt").assertTextContains("Hola. ¿Cómo estás?")
        compose.onNodeWithText("Private learner reply").assertDoesNotExist()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-support").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(ReportDelivery.UNAVAILABLE, vm.reportState.value.delivery)
            assertEquals("es", vm.reportState.value.selection?.languageID)
            assertFalse(vm.exportData().contains("ai-report-v1"))
        }
    }
}
