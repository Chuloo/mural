package chat.mural.network

import android.media.AudioDeviceInfo

/** Pure selection policy for AudioManager's communication devices (Android 12+). */
internal fun <T> selectCommunicationDevice(current: T?, available: List<T>, typeOf: (T) -> Int): T? {
    val external = current?.takeIf {
        typeOf(it) !in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }
    val wired = available.firstOrNull {
        typeOf(it) in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
    val wireless = available.firstOrNull {
        typeOf(it) in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
    return external ?: wired ?: wireless ?: available.firstOrNull { typeOf(it) == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}
