package chat.mural

import android.app.NotificationManager
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit live variant only: one brief hosted call per language, using synthetic typed input. */
@RunWith(AndroidJUnit4::class)
class LiveLanguageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: Preferences
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(body: () -> T): T = compose.runOnUiThread(body)

    @Before fun prepare() {
        assertEquals("chat.mural.android.verification", compose.activity.packageName)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("liveVerification"))
        assertEquals("https://api.mural.chat", BuildConfig.MANAGED_API_ORIGIN)
        val permissions = mutableListOf("android.permission.RECORD_AUDIO")
        if (Build.VERSION.SDK_INT >= 33) permissions += "android.permission.POST_NOTIFICATIONS"
        for (permission in permissions) {
            instrumentation.uiAutomation.executeShellCommand("pm grant chat.mural.android.verification $permission")
                .use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        }
        vm = compose.awaitHistoryLoaded()
        main {
            original = vm.archive.preferences.copy()
            vm.updatePreferences(original.copy(hasOnboarded = true, aiConsentVersion = 1,
                meaningLanguage = "English", meaningVisible = true, sessionMinutes = 2))
            vm.selectConversationProvider(ConversationProvider.HOSTED_MINUTES)
        }
        waitFor(40_000, "Hosted minutes must be ready") { vm.hostedReadiness.ready }
    }
    private fun waitFor(timeout: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (main(condition)) return
            Thread.sleep(50)
        }
        assertTrue(message, main(condition))
    }
    @After fun finish() {
        if (::vm.isInitialized) {
            main { vm.end("Live verification cleanup") }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
    private fun verify(id: String, reply: String, word: String) {
        val report = JSONObject().put("languageID", id).put("status", "running")
            .put("input", "synthetic typed turn").put("speakerQualityReview", "pending")
        val file = File(instrumentation.targetContext.filesDir, "language-verification-$id.json")
        fun write() { file.writeText(report.toString()) }
        write()
        try {
            main { vm.selectLanguage(id); vm.chooseTheme(vm.language.themes.first { it.id == "coffee" }); vm.start() }
            waitFor(45_000, "Voice must connect") { vm.state == "active" }
            main { vm.toggleMute() }
            var peak = 0.0
            waitFor(25_000, "Greeting audio and caption must arrive") {
                peak = maxOf(peak, vm.outputLevel)
                peak > 0.001 && vm.session?.fragments?.any { it.speaker == Speaker.assistant } == true
            }
            val sessionID = main { vm.session!!.id }
            val replies = main { vm.typedRepliesSent }
            main { vm.sendTyped(reply) }
            waitFor(30_000, "The target-language reply must be sent") { vm.typedRepliesSent > replies && !vm.working }
            waitFor(25_000, "Meaning subtitles must arrive") { vm.meaning.isNotBlank() && !vm.translating }
            main { vm.lookup(word, vm.session!!.passages.last { it.speaker == Speaker.assistant }.text) }
            waitFor(25_000, "Word lookup must complete") { !vm.lookupLoading && !vm.lookupResult.isNullOrBlank() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(2_000)
            main {
                assertEquals("active", vm.state); assertEquals(sessionID, vm.session?.id)
                assertTrue(VoiceConversationService.holds(sessionID))
                vm.lookup(vm.language.greetingWord, vm.session!!.passages.last { it.speaker == Speaker.assistant }.text)
            }
            waitFor(25_000, "A helper must complete while the voice app is backgrounded") { !vm.lookupLoading && !vm.lookupResult.isNullOrBlank() }
            val notifications = compose.activity.getSystemService(NotificationManager::class.java)
            notifications.activeNotifications.single { it.id == 139 }.notification.actions.single().actionIntent.send()
            val audio = compose.activity.getSystemService(AudioManager::class.java)
            waitFor(10_000, "End must release audio, the notification and foreground service") {
                !vm.isRunning && !VoiceConversationService.holds(sessionID) && audio.mode == AudioManager.MODE_NORMAL &&
                    notifications.activeNotifications.none { it.id == 139 }
            }
            assertFalse(notifications.activeNotifications.any { it.id == 139 })
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            main {
                assertEquals(sessionID, vm.session?.id)
                val restored = ArchiveCodec.decode(ArchiveCodec.encode(vm.archive))
                assertEquals(id, restored.sessions.single { it.id == sessionID }.languageID)
                assertTrue(restored.sessions.single { it.id == sessionID }.fragments.isNotEmpty())
                vm.selectLanguage("nb"); vm.selectLanguage(id)
                assertTrue(vm.archive.sessions.any { it.id == sessionID && it.languageID == id })
                vm.updatePreferences(original)
            }
            report.put("status", "complete").put("passed", true).put("peakAudioLevel", peak)
                .put("meaningAndLookup", true).put("backgroundHelper", true).put("sameSessionAfterResume", true)
                .put("notificationEndReleasedAudio", true).put("archiveRoundTrip", true)
            write()
        } catch (failure: Throwable) {
            report.put("status", "complete").put("passed", false).put("failure", failure.javaClass.simpleName)
            write(); throw failure
        }
    }
    @Test fun serbian() = verify("sr", "Kako da ljubazno naručim kafu?", "kafa")
    @Test fun greek() = verify("el", "Πώς μπορώ να παραγγείλω ευγενικά έναν καφέ;", "καφές")
    @Test fun tagalog() = verify("tl", "Paano po ako magalang na oorder ng kape?", "kape")
}
