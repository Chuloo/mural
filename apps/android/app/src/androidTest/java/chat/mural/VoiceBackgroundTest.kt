package chat.mural

import android.app.NotificationManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Activity/service lifecycle with synthetic conversation data and no provider connection. */
@RunWith(AndroidJUnit4::class)
class VoiceBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", compose.activity.packageName)
        instrumentation.uiAutomation.executeShellCommand("pm grant chat.mural.android.uitest android.permission.RECORD_AUDIO")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }

        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive)
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = 1))
        }
    }
    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    private fun voice(value: Boolean) {
        MuralViewModel::class.java.getDeclaredField("voiceSession").apply { isAccessible = true }.setBoolean(vm, value)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(condition())
    }
    @After fun restore() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        instrumentation.runOnMainSync {
            vm.end("Offline test cleanup")
            VoiceConversationService.stop(compose.activity, vm.session?.id)
            state("state", "idle"); state("session", null); voice(false)
            if (::original.isInitialized) {
                val restored = ArchiveCodec.decode(original)
                state("archive", restored); vm.updatePreferences(restored.preferences)
            }
        }
    }

    @Test fun voiceSessionSurvivesStopAndResumeAndNotificationEndReleasesService() {
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        lateinit var saved: SessionRecord
        instrumentation.runOnMainSync {
            saved = SessionRecord(languageID = "el", voiceSeconds = 42.0, fragments = mutableListOf(
                Fragment(speaker = Speaker.assistant, text = "Πώς είσαι;", startMS = 0, endMS = 1000)))
            state("session", saved); state("state", "active"); voice(true)
            VoiceConversationService.start(compose.activity, saved.id) { vm.end() }
        }
        waitFor { notifications.activeNotifications.any { it.id == 139 } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertTrue(vm.isRunning); assertEquals(saved.id, vm.session?.id)
            assertEquals(42.0, vm.session!!.voiceSeconds, 0.0)
            assertEquals("Πώς είσαι;", vm.session!!.passages.single().text)
            assertNull(vm.session!!.endReason)
            assertTrue(VoiceConversationService.holds(saved.id))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        instrumentation.runOnMainSync { assertEquals(saved.id, vm.session?.id); assertEquals("active", vm.state) }
        notifications.activeNotifications.single { it.id == 139 }.notification.actions.single().actionIntent.send()
        waitFor { !VoiceConversationService.holds(saved.id) && notifications.activeNotifications.none { it.id == 139 } }
        instrumentation.runOnMainSync { assertEquals("ended", vm.state); assertEquals("Ended by you", vm.session?.endReason) }
    }

    @Test fun aSessionWithoutAudioOwnershipEndsAndDoesNotRestartInBackground() {
        instrumentation.runOnMainSync {
            state("session", SessionRecord(languageID = "tl")); state("state", "active"); voice(false)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertFalse(vm.isRunning)
            assertEquals("App moved to background", vm.session?.endReason)
            vm.start()
            assertFalse(vm.isRunning)
            assertFalse(VoiceConversationService.holds(vm.session?.id))
        }
    }
}
