package chat.mural

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.AIReportSubmission
import chat.mural.ui.MuralTheme
import chat.mural.ui.ReportConversationSheet
import chat.mural.core.ReportDelivery
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A report is saved on this phone: the sheet has no capability to check and no server receipt. */
@RunWith(AndroidJUnit4::class)
class ReportConversationSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingRequiresReasonAndExplicitConsentAndAnEditClearsConsent() {
        val delivery = mutableStateOf(ReportDelivery.IDLE)
        val submitted = mutableListOf<AIReportSubmission>()
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        compose.setContent { MuralTheme {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            ReportConversationSheet("Una frase de prueba.", "es", delivery.value, {
                submitted += it; delivery.value = ReportDelivery.SAVING
            }, {})
        } }
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-reason-incorrect").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsEnabled()
        compose.onNodeWithTag("report-excerpt").performScrollTo().performTextReplacement("Solo este fragmento.")
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        // Finish editing before scrolling to consent; IME animation can otherwise move the tap target.
        compose.runOnIdle { focus.clearFocus(force = true) }
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-consent").assertIsOn()
        compose.onNodeWithTag("report-send").assertIsEnabled()
        compose.onNodeWithTag("report-send").performClick()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submitted.size)
            assertEquals("Solo este fragmento.", submitted.single().excerpt)
            assertEquals("incorrect", submitted.single().reason)
            assertEquals("ai-report-v1", submitted.single().consentVersion)
            delivery.value = ReportDelivery.FAILED
        }
        compose.onNodeWithTag("report-send").assertIsEnabled()
        compose.onNodeWithTag("report-send").performClick()
        compose.runOnIdle { assertEquals(submitted.first().reportID, submitted.last().reportID) }
    }

    /** Ready at once: nothing is checked on a server, so no unavailable or checking state exists. */
    @Test fun theSheetOffersSavingAtOnceAndAnnouncesNothingWhileIdle() {
        var submissions = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Una frase.", "es", ReportDelivery.IDLE, { submissions++ }, {})
        } }
        compose.onNodeWithTag("report-status").assertDoesNotExist()
        compose.onNodeWithTag("report-reason-incorrect").performScrollTo().performClick()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun aCredentialShapedExcerptCannotBeSaved() {
        var submissions = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Bearer " + "z".repeat(43), "es", ReportDelivery.IDLE, { submissions++ }, {})
        } }
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-credential-warning").assertExists()
        compose.onNodeWithTag("report-reason-incorrect").performScrollTo().performClick()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun receiptUsesSpanishCopyAndDoesNotShowTheSubmittedExcerpt() {
        var dismissals = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Private selected text.", "es", ReportDelivery.SAVED, {}, { dismissals++ }, "es")
        } }
        compose.onNodeWithTag("report-success").assertTextEquals("Tu informe está guardado en este teléfono.")
        compose.onNodeWithTag("report-excerpt").assertDoesNotExist()
        compose.onNodeWithText("Listo").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    /** The disclosure is the guarantee the learner reads: this phone keeps it, nothing is sent. */
    @Test fun theDisclosureSaysTheReportIsSavedOnThisPhoneAndNeverSent() {
        compose.setContent { MuralTheme {
            ReportConversationSheet("Una frase.", "es", ReportDelivery.IDLE, {}, {})
        } }
        compose.onNodeWithText("saved on this phone only", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-consent").performScrollTo().assertIsDisplayed()
    }
}
