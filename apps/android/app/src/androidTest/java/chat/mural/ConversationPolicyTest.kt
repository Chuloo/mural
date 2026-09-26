package chat.mural

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.*
import chat.mural.network.APIClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationPolicyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", compose.activity.packageName)
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive)
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = AI_CONSENT_VERSION))
        }
    }
    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    @After fun restore() {
        if (!::original.isInitialized) return
        compose.runOnIdle {
            state("session", null); state("state", "idle"); state("inactivitySeconds", null); vm.dismissError()
            val restored = ArchiveCodec.decode(original); state("archive", restored); vm.updatePreferences(restored.preferences)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val auto = InstrumentationRegistry.getInstrumentation().uiAutomation
        auto.waitForIdle(500, 5000)
        val dir = File(compose.activity.filesDir, "conversation-policy").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { auto.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun countdownIsVisibleAndTypingClearsTheWarning() {
        compose.runOnIdle {
            state("session", SessionRecord(languageID = vm.language.id, fragments = mutableListOf(
                Fragment(speaker = Speaker.assistant, text = "Hei! Hvordan går det?", startMS = 0, endMS = 1000))))
            MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, true)
            state("state", "active"); state("inactivitySeconds", 5)
        }
        compose.onNodeWithTag("conversation-status").assertTextEquals(compose.activity.getString(R.string.talk_inactivity_warning, 5)).assertIsDisplayed()
        val warningBounds = compose.onNodeWithTag("conversation-status").fetchSemanticsNode().boundsInRoot
        val reportBounds = compose.onNodeWithTag("report-current-utterance").fetchSemanticsNode().boundsInRoot
        assertTrue("Countdown must leave room for the report control", warningBounds.right <= reportBounds.left + 1)
        capture("countdown")
        compose.onNodeWithText(compose.activity.getString(R.string.talk_type_button)).performClick()
        compose.onNodeWithTag("typed-reply-input").performTextInput("Hola")
        compose.runOnIdle { assertNull(vm.inactivitySeconds) }
        capture("typing-grace")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_close)).performClick()
    }
    @Test fun quotaErrorUsesBillingAdviceAndSafeProviderReference() {
        compose.runOnIdle {
            MuralViewModel::class.java.getDeclaredMethod("presentError", Throwable::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(vm, APIClient.APIException.Http(429, "insufficient_quota", "req_ui_fixture"), R.string.error_voice_connect_failed)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.error_provider_quota), substring = true).assertIsDisplayed()
        compose.onNodeWithText("req_ui_fixture", substring = true).assertIsDisplayed()
        capture("quota-error")
        compose.onNodeWithText(compose.activity.getString(R.string.common_ok)).performClick()
        compose.runOnIdle { assertNull(vm.error) }
    }
}
