package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.mural.R
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode

class LiveTransport(
    context: Context,
    private val scope: CoroutineScope,
) {
    var onEvent: ((JsonObject) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null

    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val generation = AtomicLong(0)
    private val lock = Any()

    @Volatile private var activeAttempt: Attempt? = null
    @Volatile private var startedState = false
    @Volatile private var mutedState = false

    val started: Boolean get() = startedState
    val isMuted: Boolean get() = mutedState

    suspend fun connect(
        api: APIClient,
        instructions: String,
        history: JsonArray = JsonArray(emptyList()),
    ) {
        disconnect()
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw microphoneException()
        }

        val attempt = Attempt(
            id = generation.incrementAndGet(),
            previousAudioMode = audioManager.mode,
            previousSpeakerphone = if (Build.VERSION.SDK_INT < 31) legacySpeakerphoneState() else false,
            previousCommunicationDevice = if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice else null,
        )
        synchronized(lock) {
            activeAttempt = attempt
            startedState = false
            mutedState = false
        }

        try {
            configureAudio(attempt)
            ensureWebRtcInitialized()
            createPeer(attempt)
            requireCurrent(attempt)

            val offer = withTimeout(SDP_TIMEOUT_MILLISECONDS) { createOffer(attempt) }
            withTimeout(SDP_TIMEOUT_MILLISECONDS) { setDescription(attempt, local = true, offer) }
            if (attempt.peer?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                attempt.iceComplete.complete(Unit)
            }
            withTimeout(ICE_TIMEOUT_MILLISECONDS) { attempt.iceComplete.await() }
            requireCurrent(attempt)

            val sdp = attempt.peer?.localDescription?.description ?: throw connectionException()
            val result = api.post(
                "live/sessions",
                buildJsonObject {
                    put("session", buildJsonObject {
                        put("model", "gpt-live-1")
                        put("instructions", instructions)
                        put("input", history)
                        put("store", false)
                        put("delegation", buildJsonObject { put("type", "client") })
                        put("audio", buildJsonObject {
                            put("output", buildJsonObject { put("voice", "marin") })
                        })
                    })
                    put("transport", buildJsonObject {
                        put("type", "webrtc")
                        put("sdp", sdp)
                    })
                },
            )
            requireCurrent(attempt)

            val transport = result["transport"] as? JsonObject ?: throw connectionException()
            if ((transport["type"] as? JsonPrimitive)?.contentOrNull != "webrtc") {
                throw connectionException()
            }
            val answer = (transport["sdp"] as? JsonPrimitive)?.contentOrNull
                ?: throw connectionException()
            (result["session"] as? JsonObject)?.let { session ->
                emitEvent(attempt, buildJsonObject {
                    put("type", "mural.session.created")
                    put("session", session)
                })
            }
            withTimeout(SDP_TIMEOUT_MILLISECONDS) {
                setDescription(attempt, local = false, SessionDescription(SessionDescription.Type.ANSWER, answer))
            }
            withTimeout(READY_TIMEOUT_MILLISECONDS) { attempt.started.await() }
            requireCurrent(attempt)
            startMetering(attempt)
            attempt.scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion {
                cleanupIfCurrent(attempt)
            }
        } catch (_: TimeoutCancellationException) {
            cleanupIfCurrent(attempt)
            throw timeoutException()
        } catch (error: CancellationException) {
            cleanupIfCurrent(attempt)
            throw error
        } catch (error: Throwable) {
            cleanupIfCurrent(attempt)
            throw error
        }
    }

    fun send(event: JsonObject): Boolean {
        val attempt = activeAttempt ?: return false
        if (!isCurrent(attempt)) return false
        val channel = attempt.channel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return try {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false))
        } catch (_: Exception) {
            false
        }
    }

    fun mute(muted: Boolean) {
        mutedState = muted
        try { activeAttempt?.takeIf(::isCurrent)?.track?.setEnabled(!muted) } catch (_: Exception) { }
        send(buildJsonObject {
            put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
            put("event_id", UUID.randomUUID().toString())
        })
    }

    fun close() {
        val attempt = activeAttempt ?: return
        attempt.closing.set(true)
        mutedState = true
        try { attempt.track?.setEnabled(false) } catch (_: Exception) { }
        send(buildJsonObject {
            put("type", "session.close")
            put("event_id", UUID.randomUUID().toString())
        })
    }

    fun disconnect() {
        val attempt = synchronized(lock) {
            val nextGeneration = generation.incrementAndGet()
            activeAttempt.also {
                activeAttempt = null
                startedState = false
                mutedState = false
            } to nextGeneration
        }
        cleanup(attempt.first)
        emitZeroLevels(attempt.second)
    }

    private fun createPeer(attempt: Attempt) {
        val audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(voiceAudioAttributes())
            .setAudioRecordErrorCallback(object : AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message: String) = audioFailure(attempt)
                override fun onWebRtcAudioRecordStartError(
                    errorCode: AudioRecordStartErrorCode,
                    message: String,
                ) = audioFailure(attempt)
                override fun onWebRtcAudioRecordError(message: String) = audioFailure(attempt)
            })
            .setAudioTrackErrorCallback(object : AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String) = audioFailure(attempt)
                override fun onWebRtcAudioTrackStartError(
                    errorCode: AudioTrackStartErrorCode,
                    message: String,
                ) = audioFailure(attempt)
                override fun onWebRtcAudioTrackError(message: String) = audioFailure(attempt)
            })
            .createAudioDeviceModule()
        attempt.audioDeviceModule = audioDeviceModule
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        attempt.factory = factory

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = factory.createPeerConnection(configuration, peerObserver(attempt))
            ?: throw connectionException()
        attempt.peer = peer

        val source = factory.createAudioSource(MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        })
        attempt.source = source
        val track = factory.createAudioTrack("mural-microphone", source)
        attempt.track = track
        if (peer.addTrack(track, listOf("mural-audio")) == null) throw connectionException()

        val channel = peer.createDataChannel("oai-events", DataChannel.Init().apply { ordered = true })
            ?: throw connectionException()
        attempt.channel = channel
        channel.registerObserver(dataObserver(attempt))
    }

    private fun peerObserver(attempt: Attempt) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        override fun onRemoveTrack(receiver: RtpReceiver) = Unit
        override fun onTrack(transceiver: RtpTransceiver) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE && isCurrent(attempt)) {
                attempt.iceComplete.complete(Unit)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                fail(attempt, applicationContext.getString(R.string.error_transport_network_lost))
            }
        }

        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                fail(attempt, applicationContext.getString(R.string.error_transport_network_lost))
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED) {
                fail(attempt, applicationContext.getString(R.string.error_transport_network_lost))
            }
        }
    }

    private fun dataObserver(attempt: Attempt) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = try { attempt.channel?.state() } catch (_: Exception) { null }
            if (state == DataChannel.State.CLOSED && !attempt.closing.get()) {
                fail(attempt, applicationContext.getString(R.string.error_transport_channel_closed))
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary || !isCurrent(attempt)) return
            val byteCount = buffer.data.remaining()
            if (byteCount !in 1..MAX_EVENT_BYTES) {
                fail(attempt, applicationContext.getString(R.string.error_transport_invalid_event))
                return
            }
            val bytes = ByteArray(byteCount)
            buffer.data.duplicate().get(bytes)
            val event = try {
                JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                return
            }
            val type = event.string("type") ?: return
            if (!isCurrent(attempt) || !event.isSafeForCoordinator(type)) return
            if (type == "session.started") {
                startedState = true
                attempt.started.complete(Unit)
            }
            emitEvent(attempt, event)
        }
    }

    private suspend fun createOffer(attempt: Attempt): SessionDescription {
        val result = CompletableDeferred<SessionDescription>()
        val peer = attempt.peer ?: throw connectionException()
        peer.createOffer(sdpObserver(attempt, onCreate = { result.complete(it) }, onFailure = {
            result.completeExceptionally(connectionException())
        }), MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        })
        return result.await()
    }

    private suspend fun setDescription(attempt: Attempt, local: Boolean, description: SessionDescription) {
        val result = CompletableDeferred<Unit>()
        val peer = attempt.peer ?: throw connectionException()
        val observer = sdpObserver(attempt, onSet = { result.complete(Unit) }, onFailure = {
            result.completeExceptionally(connectionException())
        })
        if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
        result.await()
    }

    private fun sdpObserver(
        attempt: Attempt,
        onCreate: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
        onFailure: (String) -> Unit,
    ) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            if (isCurrent(attempt)) onCreate(description)
            else onFailure("stale")
        }

        override fun onSetSuccess() {
            if (isCurrent(attempt)) onSet() else onFailure("stale")
        }

        override fun onCreateFailure(message: String) = onFailure(message)
        override fun onSetFailure(message: String) = onFailure(message)
    }

    private fun startMetering(attempt: Attempt) {
        attempt.meterJob?.cancel()
        attempt.meterJob = scope.launch {
            var lastInput = 0.0
            var lastOutput = 0.0
            while (isActive && isCurrent(attempt)) {
                attempt.peer?.getStats { report ->
                    if (!isCurrent(attempt)) return@getStats
                    var input = 0.0
                    var output = 0.0
                    for (stat in report.statsMap.values) {
                        val level = (stat.members["audioLevel"] as? Number)?.toDouble() ?: 0.0
                        if (stat.type == "inbound-rtp") output = maxOf(output, level)
                        if (stat.type == "media-source") input = maxOf(input, level)
                    }
                    lastInput = lastInput * 0.35 + minOf(1.0, input * 4.0) * 0.65
                    lastOutput = lastOutput * 0.35 + minOf(1.0, output * 4.0) * 0.65
                    emitLevels(if (mutedState) 0.0 else lastInput, lastOutput, attempt)
                }
                delay(METER_INTERVAL_MILLISECONDS)
            }
        }
    }

    private fun configureAudio(attempt: Attempt) {
        val attributes = voiceAudioAttributes()
        lateinit var focusRequest: AudioFocusRequest
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                fail(attempt, applicationContext.getString(R.string.error_transport_audio_interrupted))
            }
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        attempt.focusRequest = focusRequest
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw audioFocusException()
        }
        attempt.ownsAudioFocus = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeCommunicationAudio()
    }

    private fun fail(attempt: Attempt, message: String) {
        if (!isCurrent(attempt) || attempt.closing.get() || !attempt.failureReported.compareAndSet(false, true)) return
        // WebRTC callbacks can run on its native signaling/audio threads. Disposing
        // a peer there can deadlock while joining the very thread delivering failure.
        scope.launch {
            val failureGeneration = cleanupIfCurrent(attempt) ?: return@launch
            if (generation.get() == failureGeneration && activeAttempt == null) onFailure?.invoke(message)
        }
    }

    private fun audioFailure(attempt: Attempt) {
        fail(attempt, applicationContext.getString(R.string.error_transport_audio_stopped))
    }

    private fun voiceAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun routeCommunicationAudio() {
        if (Build.VERSION.SDK_INT >= 31) {
            selectCommunicationDevice(audioManager.communicationDevice, audioManager.availableCommunicationDevices) { it.type }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            setLegacySpeakerphone(true)
        }
    }

    private fun restoreAudioRoute(attempt: Attempt) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
                attempt.previousCommunicationDevice?.let { audioManager.setCommunicationDevice(it) }
            } else {
                setLegacySpeakerphone(attempt.previousSpeakerphone)
            }
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphoneState(): Boolean = audioManager.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphone(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    private fun cleanupIfCurrent(attempt: Attempt): Long? {
        val cleanupGeneration = synchronized(lock) {
            if (activeAttempt !== attempt) null
            else {
                val nextGeneration = generation.incrementAndGet()
                activeAttempt = null
                startedState = false
                mutedState = false
                nextGeneration
            }
        }
        if (cleanupGeneration != null) {
            cleanup(attempt)
            emitZeroLevels(cleanupGeneration)
        }
        return cleanupGeneration
    }

    private fun cleanup(attempt: Attempt?) {
        if (attempt == null || !attempt.cleaned.compareAndSet(false, true)) return
        attempt.closing.set(true)
        attempt.scopeCompletion?.dispose()
        attempt.scopeCompletion = null
        attempt.meterJob?.cancel()
        attempt.meterJob = null
        attempt.iceComplete.cancel()
        attempt.started.cancel()
        try { attempt.track?.setEnabled(false) } catch (_: Exception) { }
        try { attempt.channel?.unregisterObserver() } catch (_: Exception) { }
        try { attempt.channel?.close() } catch (_: Exception) { }
        try { attempt.channel?.dispose() } catch (_: Exception) { }
        try { attempt.peer?.close() } catch (_: Exception) { }
        try { attempt.peer?.dispose() } catch (_: Exception) { }
        try { attempt.track?.dispose() } catch (_: Exception) { }
        try { attempt.source?.dispose() } catch (_: Exception) { }
        try { attempt.factory?.dispose() } catch (_: Exception) { }
        try { attempt.audioDeviceModule?.release() } catch (_: Exception) { }
        if (attempt.ownsAudioFocus) {
            try { attempt.focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } } catch (_: Exception) { }
            attempt.ownsAudioFocus = false
        }
        restoreAudioRoute(attempt)
        try { audioManager.mode = attempt.previousAudioMode } catch (_: Exception) { }
    }

    private fun emitEvent(attempt: Attempt, event: JsonObject) {
        val type = event.string("type") ?: return
        if (!event.isSafeForCoordinator(type)) return
        scope.launch { if (isCurrent(attempt)) onEvent?.invoke(event) }
    }

    private fun emitLevels(input: Double, output: Double, attempt: Attempt? = null) {
        scope.launch {
            if (attempt == null || isCurrent(attempt)) onLevels?.invoke(input, output)
        }
    }

    private fun emitZeroLevels(expectedGeneration: Long) {
        scope.launch {
            if (generation.get() == expectedGeneration) onLevels?.invoke(0.0, 0.0)
        }
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        activeAttempt === attempt && generation.get() == attempt.id && !attempt.cleaned.get()

    private fun requireCurrent(attempt: Attempt) {
        if (!isCurrent(attempt)) throw CancellationException("Voice connection superseded")
    }

    private fun ensureWebRtcInitialized() {
        synchronized(initializationLock) {
            if (webRtcInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            webRtcInitialized = true
        }
    }

    private class Attempt(
        val id: Long,
        val previousAudioMode: Int,
        val previousSpeakerphone: Boolean,
        val previousCommunicationDevice: AudioDeviceInfo?,
    ) {
        var focusRequest: AudioFocusRequest? = null
        var ownsAudioFocus = false
        var audioDeviceModule: AudioDeviceModule? = null
        var factory: PeerConnectionFactory? = null
        var peer: PeerConnection? = null
        var source: org.webrtc.AudioSource? = null
        var track: org.webrtc.AudioTrack? = null
        var channel: DataChannel? = null
        var meterJob: Job? = null
        var scopeCompletion: DisposableHandle? = null
        val iceComplete = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val closing = AtomicBoolean(false)
        val failureReported = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
    }

    sealed class TransportException(message: String) : Exception(message) {
        class Microphone(message: String) : TransportException(message)
        class Connection(message: String) : TransportException(message)
        class Timeout(message: String) : TransportException(message)
        class AudioFocus(message: String) : TransportException(message)
    }

    private fun microphoneException() = TransportException.Microphone(applicationContext.getString(R.string.error_transport_microphone))
    private fun connectionException() = TransportException.Connection(applicationContext.getString(R.string.error_transport_connection))
    private fun timeoutException() = TransportException.Timeout(applicationContext.getString(R.string.error_transport_timeout))
    private fun audioFocusException() = TransportException.AudioFocus(applicationContext.getString(R.string.error_transport_audio_focus))

    companion object {
        private const val ICE_TIMEOUT_MILLISECONDS = 10_000L
        private const val SDP_TIMEOUT_MILLISECONDS = 10_000L
        private const val READY_TIMEOUT_MILLISECONDS = 20_000L
        private const val METER_INTERVAL_MILLISECONDS = 100L
        private const val MAX_EVENT_BYTES = 524_288
        private val JSON = Json { ignoreUnknownKeys = true }
        private val initializationLock = Any()
        @Volatile private var webRtcInitialized = false
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Protects coordinator code from malformed provider fields before it uses jsonPrimitive. */
private fun JsonObject.isSafeForCoordinator(type: String): Boolean = when (type) {
    "mural.session.created", "session.started" ->
        (this["session"] as? JsonObject)?.get("id").isAbsentOrString()
    "session.input_transcript.delta", "session.output_transcript.delta" ->
        this["delta"].isAbsentOrPrimitive() &&
            this["start_ms"].isAbsentOrPrimitive() &&
            this["end_ms"].isAbsentOrPrimitive() &&
            this["event_id"].isAbsentOrPrimitive()
    "session.delegation.created" -> (this["delegation"] as? JsonObject)?.let {
        it["target"].isAbsentOrPrimitive() && it["id"].isAbsentOrPrimitive()
    } ?: true
    "session.usage.updated", "session.closed" -> (this["usage"] as? JsonObject)?.let {
        it["seconds"].isAbsentOrPrimitive()
    } ?: true
    else -> true
}

private fun kotlinx.serialization.json.JsonElement?.isAbsentOrPrimitive(): Boolean =
    this == null || this is JsonPrimitive

private fun kotlinx.serialization.json.JsonElement?.isAbsentOrString(): Boolean =
    this == null || (this as? JsonPrimitive)?.isString == true
