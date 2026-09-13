package chat.mural.network

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunicationAudioRouteTest {
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

    @Test fun usesSpeakerOnlyWhenThereIsNoExternalCommunicationDevice() {
        assertEquals(speaker, select(earpiece, earpiece, speaker))
        assertNull(select(null))
        assertNull(select(earpiece, earpiece))
    }
}
