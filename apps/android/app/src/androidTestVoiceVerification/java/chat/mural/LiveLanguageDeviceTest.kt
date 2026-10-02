package chat.mural

import android.app.NotificationManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.PowerManager
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
        val personalKey = InstrumentationRegistry.getArguments().getString("personalKey") == "true"
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
                meaningLanguage = "English", meaningVisible = true,
                sessionMinutes = if (InstrumentationRegistry.getArguments().getString("screenOff") == "true") 3 else 2))
            vm.selectConversationProvider(if (personalKey) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
        }
        if (personalKey) {
            // The owner enters the key directly in this isolated app's Settings.
            // No credential is passed through runner arguments, logs or test reports.
            waitFor(600_000, "Enter the existing key directly in the verification app") { vm.hasKey }
            return
        }
        try {
            waitFor(40_000, "Hosted minutes must be ready") { vm.hostedReadiness.ready }
        } catch (failure: AssertionError) {
            // Only status flags, never account identifiers or installation credentials.
            val diagnostics = main { JSONObject()
                .put("guestStatus", vm.guestState.status.name)
                .put("guestMilliseconds", vm.guestState.remainingMilliseconds)
                .put("readinessChecking", vm.hostedReadiness.checking)
                .put("readinessVerified", vm.hostedReadiness.verified)
                .put("hasHostedOwner", vm.hostedReadiness.accountID != null)
                .put("provider", vm.conversationProvider.name)
                .put("accountTransitionBlocked", vm.accountChangeBlocked)
            }
            try {
                val request = okhttp3.Request.Builder().url("https://api.mural.chat/healthz").build()
                okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { diagnostics.put("healthStatus", it.code) }
            } catch (error: Exception) { diagnostics.put("healthFailure", error.javaClass.simpleName) }
            File(instrumentation.targetContext.filesDir, "language-verification-setup.json").writeText(diagnostics.toString())
            throw AssertionError("Hosted setup failed: $diagnostics", failure)
        }
    }
    private fun waitFor(timeout: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            // Manual key entry uses the real UI while this runner is waiting.
            // runOnUiThread alone does not advance Compose's test frame clock.
            compose.mainClock.advanceTimeBy(50)
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
        val screenOff = InstrumentationRegistry.getArguments().getString("screenOff") == "true"
        val interruptAudio = InstrumentationRegistry.getArguments().getString("interruptAudio") == "true"
        val report = JSONObject().put("languageID", id).put("status", "running")
            .put("input", "synthetic typed turn").put("speakerQualityReview", "pending")
            .put("screenOffRequested", screenOff).put("audioInterruptionRequested", interruptAudio)
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
            if (screenOff) {
                val power = compose.activity.getSystemService(PowerManager::class.java)
                report.put("status", "ready-for-lock"); write()
                waitFor(25_000, "Lock the physical Samsung for the screen-off check") { !power.isInteractive }
                val began = System.currentTimeMillis()
                var backgroundPeak = 0.0
                for (line in listOf("Ask me a short question about coffee.", "Give one short example of a polite order.")) {
                    var replyPeak = 0.0
                    val before = main { vm.session!!.fragments.count { it.speaker == Speaker.assistant } }
                    main { vm.sendTyped(line) }
                    waitFor(30_000, "A new spoken reply must arrive with the screen off") {
                        replyPeak = maxOf(replyPeak, vm.outputLevel)
                        backgroundPeak = maxOf(backgroundPeak, replyPeak)
                        !power.isInteractive && replyPeak > 0.001 &&
                            vm.session!!.fragments.count { it.speaker == Speaker.assistant } > before && !vm.working
                    }
                    Thread.sleep(5_000)
                }
                while (System.currentTimeMillis() - began < 30_000) {
                    assertFalse("The screen must remain off", power.isInteractive)
                    Thread.sleep(100)
                }
                main { assertEquals("active", vm.state); assertEquals(sessionID, vm.session?.id) }
                report.put("screenOffSeconds", (System.currentTimeMillis() - began) / 1000.0)
                    .put("screenOffAudioPeak", backgroundPeak).put("screenOffReplies", 2)
            }
            main {
                assertEquals("active", vm.state); assertEquals(sessionID, vm.session?.id)
                assertTrue(VoiceConversationService.holds(sessionID))
                vm.lookup(vm.language.greetingWord, vm.session!!.passages.last { it.speaker == Speaker.assistant }.text)
            }
            waitFor(25_000, "A helper must complete while the voice app is backgrounded") { !vm.lookupLoading && !vm.lookupResult.isNullOrBlank() }
            val notifications = compose.activity.getSystemService(NotificationManager::class.java)
            val audio = compose.activity.getSystemService(AudioManager::class.java)
            var interruption: AudioFocusRequest? = null
            try {
                if (interruptAudio) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setOnAudioFocusChangeListener { }.build()
                    interruption = request
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, main { audio.requestAudioFocus(request) })
                } else {
                    notifications.activeNotifications.single { it.id == 139 }.notification.actions.single().actionIntent.send()
                }
                waitFor(10_000, "End must release audio, the notification and foreground service") {
                    !vm.isRunning && !VoiceConversationService.holds(sessionID) && audio.mode == AudioManager.MODE_NORMAL &&
                        notifications.activeNotifications.none { it.id == 139 }
                }
            } finally {
                interruption?.let { main { audio.abandonAudioFocusRequest(it) } }
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
                .put("notificationEndReleasedAudio", !interruptAudio).put("interruptionReleasedAudio", interruptAudio)
                .put("archiveRoundTrip", true)
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
