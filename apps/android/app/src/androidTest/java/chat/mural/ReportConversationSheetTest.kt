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

@RunWith(AndroidJUnit4::class)
class ReportConversationSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sendingRequiresReasonAndExplicitConsentAndAnEditClearsConsent() {
        val delivery = mutableStateOf(ReportDelivery.IDLE)
        val submitted = mutableListOf<AIReportSubmission>()
        compose.setContent { MuralTheme {
            ReportConversationSheet("Una frase de prueba.", "es", true, delivery.value, {
                submitted += it; delivery.value = ReportDelivery.SENDING
            }, {})
        } }
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-reason-incorrect").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
        compose.onNodeWithTag("report-send").assertIsEnabled()
        compose.onNodeWithTag("report-excerpt").performScrollTo().performTextReplacement("Solo este fragmento.")
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-consent").performScrollTo().performClick()
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

    @Test fun unavailableAndCredentialStatesCannotSubmit() {
        var submissions = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Bearer " + "z".repeat(43), "es", false, ReportDelivery.UNAVAILABLE, { submissions++ }, {})
        } }
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-credential-warning").assertExists()
        compose.onNodeWithTag("report-status").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun receiptUsesSpanishCopyAndDoesNotShowTheSubmittedExcerpt() {
        var dismissals = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Private selected text.", "es", true, ReportDelivery.SENT, {}, { dismissals++ }, "es")
        } }
        compose.onNodeWithTag("report-success").assertTextEquals("Hemos recibido tu informe.")
        compose.onNodeWithTag("report-excerpt").assertDoesNotExist()
        compose.onNodeWithText("Listo").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun checkingAvailabilityDoesNotFlashUnavailableOrAllowSubmission() {
        var submissions = 0
        compose.setContent { MuralTheme {
            ReportConversationSheet("Una frase.", "es", false, ReportDelivery.CHECKING, { submissions++ }, {})
        } }
        compose.onNodeWithTag("report-send").assertIsNotEnabled()
        compose.onNodeWithTag("report-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Checking availability…").assertIsDisplayed()
        compose.onNodeWithTag("report-support").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions) }
    }
}
