package chat.mural.network

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationAudioRouteTest {
    @Test fun briefNetworkHandoffsRecoverWithoutEndingTheConversation() = runTest {
        var failures = 0
        val recovery = VoiceConnectionRecovery(backgroundScope, 8_000) { failures++ }
        recovery.disconnected(); runCurrent()
        advanceTimeBy(7_999); recovery.connected(); runCurrent()
        advanceTimeBy(8_001); runCurrent()
        assertEquals(0, failures)
        recovery.disconnected(); runCurrent(); advanceTimeBy(8_001); runCurrent()
        assertEquals(1, failures)
        recovery.connected()
    }

    @Test fun repeatedNetworkCallbacksCannotPostponeTheDisconnectDeadline() = runTest {
        var failures = 0
        val recovery = VoiceConnectionRecovery(backgroundScope, 8_000) { failures++ }
        recovery.disconnected(); runCurrent(); advanceTimeBy(5_000)
        recovery.disconnected(); runCurrent(); advanceTimeBy(3_001); runCurrent()
        assertEquals(1, failures)
        recovery.disconnected(); advanceTimeBy(8_001); runCurrent()
        assertEquals(1, failures)
        recovery.connected()
    }

    private data class Device(val id: String, val type: Int)
    private val speaker = Device("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val earpiece = Device("earpiece", AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val bluetooth = Device("headset", AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val usb = Device("usb", AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun select(current: Device?, vararg available: Device) =
        selectCommunicationDevice(current, available.toList()) { it.type }

    @Test fun connectedBluetoothWinsOverTheSpeakerWithoutAnActiveRoute() {
        assertEquals(bluetooth, select(null, speaker, bluetooth))
    }

    @Test fun connectedBluetoothReplacesABuiltInRoute() {
        assertEquals(bluetooth, select(earpiece, speaker, earpiece, bluetooth))
        assertEquals(bluetooth, select(speaker, speaker, bluetooth))
    }

    @Test fun bleHeadsetsAndHearingAidsAlsoWinOverTheSpeaker() {
        for (type in listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)) {
            val headset = Device("wireless-$type", type)
            assertEquals(headset, select(null, speaker, headset))
        }
    }

    @Test fun keepsTheExactActiveExternalDeviceWhenSeveralAreConnected() {
        val chosen = Device("second-headset", AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(chosen, select(chosen, speaker, bluetooth, chosen, usb))
        assertEquals(usb, select(usb, speaker, bluetooth, usb))
    }

    @Test fun newlySelectedWiredDevicesKeepPriorityOverWirelessDevices() {
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)) {
            val wired = Device("wired-$type", type)
            assertEquals(wired, select(null, speaker, bluetooth, wired))
        }
    }

    @Test fun unpluggedExternalRouteIsNeverSelectedAgain() {
        assertEquals(speaker, select(bluetooth, speaker))
        assertEquals(usb, select(bluetooth, speaker, usb))
        assertNull(select(bluetooth))
    }

    @Test fun freshDeviceDescriptorsRetainSelectionByStableDeviceId() {
        val stale = Device("headset", AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val refreshed = stale.copy(type = AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertEquals(refreshed, selectCommunicationDevice(stale, listOf(speaker, usb, refreshed),
            sameDevice = { left, right -> left.id == right.id }) { it.type })
    }

    @Test fun legacySpeakerFallbackRespectsWiredAndActiveBluetoothRoutes() {
        assertTrue(shouldUseLegacySpeakerphone(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), false))
        assertFalse(shouldUseLegacySpeakerphone(emptyList(), true))
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)) {
            assertFalse(shouldUseLegacySpeakerphone(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, type), false))
        }
    }

    @Test fun usesSpeakerOnlyWhenThereIsNoExternalCommunicationDevice() {
        assertEquals(speaker, select(earpiece, earpiece, speaker))
        assertNull(select(null))
        assertNull(select(earpiece, earpiece))
    }
}
