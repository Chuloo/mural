package chat.mural

import androidx.compose.runtime.MutableState
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

/**
 * Uses the isolated UI-test app, whose reporting and network configuration is blank: a report is
 * saved on this phone, so there is no service to configure and nothing for it to reach.
 */
@RunWith(AndroidJUnit4::class)
class ReportHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private var originalPreferences: Preferences? = null
    private lateinit var originalArchive: String
    private val session = SessionRecord(id = UUID.randomUUID().toString(), languageID = "es", endedAt = nowSeconds(),
        title = "Reporting test conversation", fragments = mutableListOf(
            Fragment(id = UUID.randomUUID().toString(), speaker = Speaker.assistant, text = "Hola. ¿Cómo estás?", startMS = 0, endMS = 1000),
            Fragment(id = UUID.randomUUID().toString(), speaker = Speaker.user, text = "Private learner reply", startMS = 1100, endMS = 2000),
        ))

    @Before fun setup() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            originalPreferences = vm.archive.preferences.copy()
            originalArchive = ArchiveCodec.encode(vm.archive)
            assertTrue(vm.importData(ArchiveCodec.encode(Archive(sessions = mutableListOf(session)))))
            vm.updatePreferences(originalPreferences!!.copy(hasOnboarded = true))
        }
    }

    @After fun restore() {
        if (!::originalArchive.isInitialized) return
        compose.runOnIdle {
            vm.dismissReport()
            // A saved report lives in the archive, so the whole snapshot goes back, not only the session.
            val restored = ArchiveCodec.decode(originalArchive)
            archiveState().value = restored
            // Persisting the restored snapshot keeps the saved report out of the next test's history.
            vm.updatePreferences(restored.preferences)
        }
    }

    @Suppress("UNCHECKED_CAST") private fun archiveState(): MutableState<Archive> {
        val field = MuralViewModel::class.java.getDeclaredField("archive\$delegate").apply { isAccessible = true }
        return field.get(vm) as MutableState<Archive>
    }

    @Test fun savedAssistantReplyOpensExactPreviewAndSavingKeepsTheReportOnThisPhone() {
        compose.onNodeWithTag("report-current-utterance").assertDoesNotExist()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-data"))
        compose.onNodeWithTag("settings-data").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-history"))
        compose.onNodeWithTag("settings-history").performClick()
        compose.onNodeWithTag("settings-history-list").performScrollToNode(hasText(session.title))
        compose.onNodeWithText(session.title).performClick()
        compose.onNodeWithTag("report-history-${session.fragments[1].id}").assertDoesNotExist()
        compose.onNodeWithTag("report-history-${session.fragments[0].id}").assertHasClickAction().performClick()
        compose.onNodeWithTag("report-sheet").assertIsDisplayed()
        compose.onNodeWithTag("report-excerpt").assertTextContains("Hola. ¿Cómo estás?")
        compose.onNodeWithText("Private learner reply").assertDoesNotExist()
        // No capability is checked on a server: only the reason and the explicit consent gate the save.
        compose.onNodeWithTag("report-status").assertDoesNotExist()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-reason-incorrect").performScrollTo().performClick()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.reportState.value.delivery == ReportDelivery.SAVED }
        compose.onNodeWithTag("report-success").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("es", vm.reportState.value.selection?.languageID)
            val saved = vm.archive.reports.single()
            assertEquals("incorrect", saved.reason)
            assertEquals("Hola. ¿Cómo estás?", saved.excerpt)
            assertEquals(REPORT_CONSENT_VERSION, saved.consentVersion)
            // The report travels with the learner's own backup; it was never offered to a server.
            val exported = vm.exportData()
            assertTrue(exported.contains(REPORT_CONSENT_VERSION))
            assertTrue(exported.contains("Hola. ¿Cómo estás?"))
        }
    }
}
