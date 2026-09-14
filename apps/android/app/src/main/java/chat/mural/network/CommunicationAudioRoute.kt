package chat.mural.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pure selection policy for AudioManager's communication devices (Android 12+). */
internal fun <T> selectCommunicationDevice(current: T?, available: List<T>, sameDevice: (T, T) -> Boolean = { left, right -> left == right }, typeOf: (T) -> Int): T? {
    val external = current?.let { selected -> available.firstOrNull { sameDevice(it, selected) } }?.takeIf {
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

/** Speaker fallback must not steal sound from a wired headset on Android 8–11. */
internal fun shouldUseLegacySpeakerphone(availableTypes: List<Int>, bluetoothScoOn: Boolean): Boolean =
    !bluetoothScoOn && availableTypes.none {
        it in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HEARING_AID)
    }

internal enum class LegacyScoConnection { Disconnected, Connecting, Connected }

internal data class LegacyAudioDevices(
    val outputTypes: List<Int>,
    val scoDeviceIDs: Set<Int>,
    val scoOn: Boolean,
    val canUseScoOffCall: Boolean,
)

internal interface LegacyScoAudio {
    fun devices(): LegacyAudioDevices
    fun speakerphone(): Boolean
    fun setSpeakerphone(enabled: Boolean)
    fun startSco()
    fun stopSco()
}

/** Called only on the audio worker. It releases only the SCO request it acquired. */
internal class LegacyScoController(
    private val audio: LegacyScoAudio,
    private val scope: CoroutineScope,
    private val previousSpeakerphone: Boolean,
    private val timeoutMillis: Long = 8_000,
    private val onFailure: () -> Unit = {},
) {
    private var connection = LegacyScoConnection.Disconnected
    private var ownedDeviceID: Int? = null
    private var observedConnectionProgress = false
    private val failedDeviceIDs = mutableSetOf<Int>()
    private var timer: Job? = null
    private var attempt = 0
    private var lastSpeakerphoneWrite: Boolean? = null
    private var closed = false

    fun start(initialConnection: LegacyScoConnection?) {
        if (closed) return
        connection = initialConnection ?: if (audio.devices().scoOn) LegacyScoConnection.Connected else LegacyScoConnection.Disconnected
        refresh()
    }

    fun refresh() {
        if (closed) return
        var devices = audio.devices()
        failedDeviceIDs.retainAll(devices.scoDeviceIDs)
        val wiredOrHearingAid = !shouldUseLegacySpeakerphone(devices.outputTypes, false)
        val selected = ownedDeviceID
        if (selected != null && (selected !in devices.scoDeviceIDs || wiredOrHearingAid)) {
            releaseOwnedRequest()
            devices = audio.devices()
        }
        if (ownedDeviceID != null) {
            setSpeakerphone(false)
            return
        }
        // A pre-existing connection belongs to its original caller; do not acquire or stop it.
        if (connection != LegacyScoConnection.Disconnected || devices.scoOn) {
            setSpeakerphone(false)
            return
        }
        val candidate = devices.scoDeviceIDs.firstOrNull { it !in failedDeviceIDs }
        if (!wiredOrHearingAid && devices.canUseScoOffCall && candidate != null) {
            begin(candidate)
        } else setSpeakerphone(shouldUseLegacySpeakerphone(devices.outputTypes, devices.scoOn))
    }

    fun stateChanged(state: LegacyScoConnection, previous: LegacyScoConnection?) {
        if (closed) return
        connection = state
        if (ownedDeviceID != null) {
            when (state) {
                LegacyScoConnection.Connecting -> observedConnectionProgress = true
                LegacyScoConnection.Connected -> {
                    observedConnectionProgress = true
                    timer?.cancel(); timer = null
                }
                LegacyScoConnection.Disconnected -> {
                    // Registration delivers a sticky DISCONNECTED state too. It is not a
                    // failed request unless this connection has actually made progress.
                    if (observedConnectionProgress || previous == LegacyScoConnection.Connecting || previous == LegacyScoConnection.Connected) {
                        failOwnedRequest()
                    }
                }
            }
        }
        refresh()
    }

    private fun begin(deviceID: Int) {
        setSpeakerphone(false)
        ownedDeviceID = deviceID
        observedConnectionProgress = false
        val token = ++attempt
        try {
            audio.startSco()
        } catch (_: Exception) {
            failOwnedRequest()
            setSpeakerphone(shouldUseLegacySpeakerphone(audio.devices().outputTypes, audio.devices().scoOn))
            return
        }
        timer = scope.launch {
            delay(timeoutMillis)
            if (!closed && attempt == token && ownedDeviceID != null) {
                try {
                    failOwnedRequest()
                    refresh()
                } catch (_: Exception) { onFailure() }
            }
        }
    }

    private fun failOwnedRequest() {
        ownedDeviceID?.let(failedDeviceIDs::add)
        releaseOwnedRequest()
    }

    private fun releaseOwnedRequest() {
        if (ownedDeviceID == null) return
        ownedDeviceID = null
        observedConnectionProgress = false
        connection = LegacyScoConnection.Disconnected
        attempt++
        timer?.cancel(); timer = null
        // stopBluetoothSco releases this app's request. Never set the global SCO flag.
        try { audio.stopSco() } catch (_: Exception) { }
    }

    private fun setSpeakerphone(enabled: Boolean) {
        if (audio.speakerphone() == enabled) return
        audio.setSpeakerphone(enabled)
        lastSpeakerphoneWrite = enabled
    }

    fun close(restoreSpeakerphone: Boolean) {
        if (closed) return
        closed = true
        releaseOwnedRequest()
        val lastWrite = lastSpeakerphoneWrite ?: return
        if (!restoreSpeakerphone || audio.speakerphone() != lastWrite) return
        val devices = audio.devices()
        // A new headset or another caller's SCO must not be replaced by the old speaker route.
        if (previousSpeakerphone && !shouldUseLegacySpeakerphone(devices.outputTypes,
                devices.scoOn || connection != LegacyScoConnection.Disconnected)) return
        audio.setSpeakerphone(previousSpeakerphone)
    }
}

/** Android 8–11 adapter; all effects and broadcasts are serialized by the supplied scope. */
@Suppress("DEPRECATION")
internal class LegacyCommunicationAudioRoute(
    private val context: Context,
    audioManager: AudioManager,
    private val scope: CoroutineScope,
    previousSpeakerphone: Boolean,
    private val onFailure: () -> Unit,
) {
    private val controller = LegacyScoController(object : LegacyScoAudio {
        override fun devices(): LegacyAudioDevices {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return LegacyAudioDevices(devices.map { it.type },
                devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }.map { it.id }.toSet(),
                audioManager.isBluetoothScoOn, audioManager.isBluetoothScoAvailableOffCall)
        }
        override fun speakerphone() = audioManager.isSpeakerphoneOn
        override fun setSpeakerphone(enabled: Boolean) { audioManager.isSpeakerphoneOn = enabled }
        override fun startSco() = audioManager.startBluetoothSco()
        override fun stopSco() = audioManager.stopBluetoothSco()
    }, scope, previousSpeakerphone, onFailure = onFailure)
    private var active = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = connection(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) ?: return
            val previous = connection(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1))
            scope.launch {
                if (active) {
                    try { controller.stateChanged(state, previous) }
                    catch (_: Exception) { onFailure() }
                }
            }
        }
    }

    fun start() {
        check(!active)
        // SCO state is a protected system broadcast. Exported receives the Bluetooth process's
        // updates on devices where that privileged process has a different UID from system.
        val sticky = ContextCompat.registerReceiver(context, receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED)
        active = true
        controller.start(sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)?.let(::connection))
    }

    fun devicesChanged() { if (active) controller.refresh() }

    fun close(restoreSpeakerphone: Boolean) {
        if (active) {
            active = false
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
        controller.close(restoreSpeakerphone)
    }

    companion object {
        fun hasExistingSco(context: Context, audioManager: AudioManager): Boolean {
            if (audioManager.isBluetoothScoOn) return true
            val sticky = context.registerReceiver(null,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            return sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) in listOf(
                AudioManager.SCO_AUDIO_STATE_CONNECTING,
                AudioManager.SCO_AUDIO_STATE_CONNECTED,
            )
        }
    }

    private fun connection(value: Int): LegacyScoConnection? = when (value) {
        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> LegacyScoConnection.Disconnected
        AudioManager.SCO_AUDIO_STATE_CONNECTING -> LegacyScoConnection.Connecting
        AudioManager.SCO_AUDIO_STATE_CONNECTED -> LegacyScoConnection.Connected
        else -> null
    }
}
