package chat.mural.ondevice

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.MainActivity
import chat.mural.MuralViewModel
import chat.mural.awaitHistoryLoaded
import chat.mural.core.*
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The On-Device Model guarantee on a real phone: with On-Device Model chosen, a conversation turn,
 * a filed report and the automatic probes a foreground return triggers transmit nothing. Bytes are
 * measured for this app's own UID, so any egress at all — a provider endpoint, a stray download —
 * fails the check. The human counterpart with a configured service origin is the Airplane Mode run
 * in `apps/android/on-device-verification.md` §2; the interface build's origin is blank.
 * shortcut: measured per UID, not per socket; add a per-host check if a second origin ever appears.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceZeroNetworkTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var originalArchive: String
    private var originalProvider = ConversationProvider.PERSONAL_KEY
    private val reply = "¡Hola! Muy bien, ¿y tú?"
    private val model = FakeRuntimeSession(reply)

    /** Stands in for the loaded model, so a conversation runs with no download and no engine. */
    private class FakeRuntimeSession(private val reply: String) : RuntimeSession {
        val prompts = mutableListOf<String?>()
        override fun restart(systemPrompt: String?) { prompts += systemPrompt }
        override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit): String {
            onPartial(reply)
            return reply
        }
        override fun close() { }
    }

    @Before fun prepare() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            originalArchive = ArchiveCodec.encode(vm.archive)
            originalProvider = vm.conversationProvider
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = 1,
                learningLanguageID = "es"))
        }
        // The durable choice, which every automatic probe reads before it may reach a server.
        providerPreferences().edit().putString("selection", ConversationProvider.ON_DEVICE.name).commit()
        compose.runOnIdle {
            state("conversationProvider", ConversationProvider.ON_DEVICE)
            state("onDeviceReadiness", OnDeviceReadiness(modelID = "fake-model.litertlm", initialized = true))
            setField(controller(), "session", model)
            // A foreground return runs the automatic probes; with On-Device Model chosen they must not.
            vm.refreshHostedReadiness()
        }
    }

    @After fun restore() {
        if (!::originalArchive.isInitialized) return
        providerPreferences().edit().putString("selection", originalProvider.name).commit()
        compose.runOnIdle {
            vm.end("Ended by the network check")
            vm.dismissReport()
            // A saved report lives in the archive, so the whole snapshot goes back and is persisted.
            val restored = ArchiveCodec.decode(originalArchive)
            state("archive", restored)
            state("conversationProvider", originalProvider)
            state("onDeviceReadiness", OnDeviceReadiness())
            setField(controller(), "session", null)
            vm.updatePreferences(restored.preferences)
        }
    }

    private fun providerPreferences() =
        compose.activity.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)

    private fun controller(): Any = getField(vm, "onDeviceModels")!!

    private fun getField(owner: Any, name: String): Any? =
        owner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun setField(owner: Any, name: String, value: Any?) {
        owner::class.java.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        (getField(vm, name + "\$delegate") as MutableState<Any?>).value = value
    }

    /** -1 when this device keeps no per-UID counters, which makes the byte check unavailable. */
    private fun transmittedBytes(): Long {
        val bytes = TrafficStats.getUidTxBytes(Process.myUid())
        return if (bytes == TrafficStats.UNSUPPORTED.toLong()) -1 else bytes
    }

    @Test fun aConversationTurnAndItsReportTransmitNothingAndNoProbeRunsOnItsOwn() {
        val before = transmittedBytes()
        compose.runOnIdle { vm.sendTyped("Hola, ¿cómo estás?") }
        compose.waitUntil(timeoutMillis = 15_000) { compose.runOnUiThread { (vm.session?.passages?.size ?: 0) >= 2 } }
        compose.runOnIdle {
            assertEquals(ConversationProvider.ON_DEVICE, vm.conversationProvider)
            assertNull(vm.error)
            val session = vm.session!!
            assertEquals(ON_DEVICE_PROVIDER_ID, session.providerID)
            assertEquals(Speaker.assistant, session.passages.last().speaker)
            assertTrue(session.passages.last().text, session.passages.last().text.contains("Muy bien"))
            // The whole turn ran on the phone's own Model Runtime seam.
            assertTrue(model.prompts.isNotEmpty())
        }

        // Filing a report on that reply saves it on this phone and offers it to no server.
        compose.runOnIdle {
            val session = vm.session!!
            vm.reportUtterance(session.id, session.passages.last().id)
            vm.submitReport(AIReportSubmission(UUID.randomUUID().toString(), session.languageID, "incorrect",
                session.passages.last().text, REPORT_CONSENT_VERSION))
        }
        compose.waitUntil(timeoutMillis = 10_000) { vm.reportState.value.delivery == ReportDelivery.SAVED }
        compose.runOnIdle {
            assertEquals("incorrect", vm.archive.reports.single().reason)
            assertTrue(vm.exportData().contains(REPORT_CONSENT_VERSION))
            vm.dismissReport()
        }

        compose.runOnIdle { vm.end("Ended by the network check") }
        compose.waitUntil(timeoutMillis = 10_000) { compose.runOnUiThread { vm.state == "ended" } }
        compose.runOnIdle {
            // No readiness probe ran on its own for a provider that does not consume minutes.
            assertEquals(HostedReadiness(), vm.hostedReadiness)
        }

        val after = transmittedBytes()
        if (before >= 0 && after >= 0)
            assertEquals("an On-Device Model conversation transmitted bytes", before, after)
    }
}
