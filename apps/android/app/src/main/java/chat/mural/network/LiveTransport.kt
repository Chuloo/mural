package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.mural.R
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
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
) : VoiceTransport {
    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null

    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val generation = AtomicLong(0)
    private val lock = Any()
    private val retiredAttempts = ArrayDeque<Attempt>()
    private val audioScope = CoroutineScope(SupervisorJob() + AUDIO_DISPATCHER)

    @Volatile private var activeAttempt: Attempt? = null
    @Volatile private var startedState = false
    @Volatile private var mutedState = false

    val started: Boolean get() = startedState
    val isMuted: Boolean get() = mutedState

    suspend fun connect(
        api: LiveSessionProvider,
        instructions: String,
        history: JsonArray = JsonArray(emptyList()),
        language: String? = null,
    ) = withContext(AUDIO_DISPATCHER) {
        val attemptGeneration = detachAttempt()
        drainRetiredAttempts()
        emitZeroLevels(attemptGeneration)
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw microphoneException()
        }

        val attempt = Attempt(
            id = attemptGeneration,
            ownership = LiveSessionOwnership(audioScope),
            previousAudioMode = audioManager.mode,
            previousSpeakerphone = if (Build.VERSION.SDK_INT < 31) legacySpeakerphoneState() else false,
        )
        synchronized(lock) {
            if (generation.get() != attemptGeneration) throw CancellationException("Voice connection superseded")
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
            val result = api.createLiveSession(LiveSessionRequest(sdp, instructions, history, language))
            attempt.ownership.adopt(result.lease)
            requireCurrent(attempt)
            val answer = result.sdp
            result.providerSessionID?.let { id ->
                emitEvent(attempt, buildJsonObject {
                    put("type", "mural.session.created")
                    put("session", buildJsonObject { put("id", id) })
                })
            }
            withTimeout(SDP_TIMEOUT_MILLISECONDS) {
                setDescription(attempt, local = false, SessionDescription(SessionDescription.Type.ANSWER, answer))
            }
            withTimeout(READY_TIMEOUT_MILLISECONDS) { attempt.started.await() }
            requireCurrent(attempt)
            startMetering(attempt)
            attempt.scopeCompletion = scope.coroutineContext[Job]?.invokeOnCompletion {
                audioScope.launch { cleanupIfCurrent(attempt) }
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

    /** True means accepted for delivery; every native operation runs on the audio worker. */
    override fun send(event: JsonObject, respond: Boolean): Boolean {
        val attempt = activeAttempt ?: return false
        if (!isCurrent(attempt) || !attempt.channelOpen.get()) return false
        audioScope.launch {
            if (isCurrent(attempt) && !sendNow(attempt, event) && !attempt.closing.get()) {
                fail(attempt, applicationContext.getString(R.string.error_transport_channel_closed))
            }
        }
        return true
    }

    private fun sendNow(attempt: Attempt, event: JsonObject): Boolean {
        if (!isCurrent(attempt)) return false
        val channel = attempt.channel ?: return false
        return try {
            channel.state() == DataChannel.State.OPEN &&
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false))
        } catch (_: Exception) { false }
    }

    override fun mute(muted: Boolean) {
        val attempt = activeAttempt ?: return
        mutedState = muted
        audioScope.launch {
            if (!isCurrent(attempt)) return@launch
            try { attempt.track?.setEnabled(!muted) } catch (_: Exception) { }
            sendNow(attempt, buildJsonObject {
                put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
                put("event_id", UUID.randomUUID().toString())
            })
        }
    }

    override fun close() {
        val attempt = activeAttempt ?: return
        attempt.closing.set(true)
        attempt.ownership.close()
        mutedState = true
        audioScope.launch {
            if (!isCurrent(attempt)) return@launch
            try { attempt.track?.setEnabled(false) } catch (_: Exception) { }
            sendNow(attempt, buildJsonObject {
                put("type", "session.close")
                put("event_id", UUID.randomUUID().toString())
            })
        }
    }

    override fun disconnect() {
        val detached = detachAttempt()
        // This scope outlives the ViewModel so clearing the screen cannot cancel native cleanup.
        audioScope.launch { drainRetiredAttempts() }
        emitZeroLevels(detached)
    }

    private fun detachAttempt(): Long = synchronized(lock) {
        activeAttempt?.let(retiredAttempts::addLast)
        activeAttempt = null
        startedState = false
        mutedState = false
        generation.incrementAndGet()
    }

    private fun drainRetiredAttempts() {
        // A restart may reach the worker before a previously posted cleanup task. Drain all
        // retired attempts before the replacement reads or changes any process audio state.
        while (true) {
            val retired = synchronized(lock) { retiredAttempts.removeFirstOrNull() } ?: break
            cleanup(retired)
        }
    }

    private fun createPeer(attempt: Attempt) {
        attempt.networkRecovery = VoiceConnectionRecovery(audioScope) {
            fail(attempt, applicationContext.getString(R.string.error_transport_network_lost))
        }
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
            audioScope.launch {
                if (!isCurrent(attempt)) return@launch
                when (state) {
                    PeerConnection.PeerConnectionState.DISCONNECTED -> attempt.networkRecovery?.disconnected()
                    PeerConnection.PeerConnectionState.CONNECTED -> attempt.networkRecovery?.connected()
                    PeerConnection.PeerConnectionState.FAILED ->
                        fail(attempt, applicationContext.getString(R.string.error_transport_network_lost))
                    else -> Unit
                }
            }
        }
    }

    private fun dataObserver(attempt: Attempt) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            audioScope.launch {
                if (!isCurrent(attempt)) return@launch
                val state = try { attempt.channel?.state() } catch (_: Exception) { null }
                attempt.channelOpen.set(state == DataChannel.State.OPEN)
                if (state == DataChannel.State.CLOSED && !attempt.closing.get()) {
                    fail(attempt, applicationContext.getString(R.string.error_transport_channel_closed))
                }
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
                // The first event can reach the UI before the queued OPEN callback runs.
                attempt.channelOpen.set(true)
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
        attempt.meterJob = audioScope.launch {
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
        // Do not claim an in-progress call or a legacy SCO connection owned by another app.
        if (attempt.previousAudioMode != AudioManager.MODE_NORMAL || audioManager.mode != AudioManager.MODE_NORMAL ||
            (Build.VERSION.SDK_INT < 31 && LegacyCommunicationAudioRoute.hasExistingSco(applicationContext, audioManager))) {
            throw audioFocusException()
        }
        val attributes = voiceAudioAttributes()
        lateinit var focusRequest: AudioFocusRequest
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                attempt.focusLost.set(true)
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
        attempt.ownsAudioMode = true
        if (Build.VERSION.SDK_INT < 31) {
            val legacyRoute = LegacyCommunicationAudioRoute(applicationContext, audioManager, audioScope,
                attempt.previousSpeakerphone, onFailure = { audioFailure(attempt) })
            attempt.legacyAudioRoute = legacyRoute
            legacyRoute.start()
        }
        routeCommunicationAudio(attempt)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
            private fun reroute() {
                audioScope.launch {
                    if (isCurrent(attempt)) {
                        try { routeCommunicationAudio(attempt) }
                        catch (_: Exception) { audioFailure(attempt) }
                    }
                }
            }
        }
        attempt.deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun fail(attempt: Attempt, message: String) {
        if (!isCurrent(attempt) || attempt.closing.get() || !attempt.failureReported.compareAndSet(false, true)) return
        // WebRTC callbacks can run on its native signaling/audio threads. Disposing
        // a peer there can deadlock while joining the very thread delivering failure.
        audioScope.launch {
            val failureGeneration = cleanupIfCurrent(attempt) ?: return@launch
            scope.launch {
                if (generation.get() == failureGeneration && activeAttempt == null) onFailure?.invoke(message)
            }
        }
    }

    private fun audioFailure(attempt: Attempt) {
        fail(attempt, applicationContext.getString(R.string.error_transport_audio_stopped))
    }

    private fun voiceAudioAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun routeCommunicationAudio(attempt: Attempt) {
        if (Build.VERSION.SDK_INT >= 31) {
            val current = audioManager.communicationDevice
            val selected = selectCommunicationDevice(current, audioManager.availableCommunicationDevices,
                sameDevice = { left, right -> left.id == right.id }) { it.type }
            if (selected != null && current?.id != selected.id && audioManager.setCommunicationDevice(selected)) {
                attempt.ownsCommunicationRoute = true
            }
        } else attempt.legacyAudioRoute?.devicesChanged()
    }

    private fun releaseAudioRoute(attempt: Attempt) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // clearCommunicationDevice releases this caller's selection. Re-selecting a
                // previously observed global device would create a new, lingering request.
                if (attempt.ownsCommunicationRoute) audioManager.clearCommunicationDevice()
            } else attempt.legacyAudioRoute?.close(restoreSpeakerphone = !attempt.focusLost.get())
        } catch (_: Exception) { }
        attempt.ownsCommunicationRoute = false
        attempt.legacyAudioRoute = null
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphoneState(): Boolean = audioManager.isSpeakerphoneOn

    private fun cleanupIfCurrent(attempt: Attempt): Long? {
        val cleanupGeneration = synchronized(lock) {
            if (activeAttempt !== attempt) null
            else {
                val nextGeneration = generation.incrementAndGet()
                retiredAttempts.addLast(attempt)
                activeAttempt = null
                startedState = false
                mutedState = false
                nextGeneration
            }
        }
        if (cleanupGeneration != null) {
            drainRetiredAttempts()
            emitZeroLevels(cleanupGeneration)
        }
        return cleanupGeneration
    }

    private fun cleanup(attempt: Attempt?) {
        if (attempt == null || !attempt.cleaned.compareAndSet(false, true)) return
        attempt.ownership.close()
        attempt.closing.set(true)
        attempt.channelOpen.set(false)
        attempt.networkRecovery?.connected()
        attempt.networkRecovery = null
        try { attempt.deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) } } catch (_: Exception) { }
        attempt.deviceCallback = null
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
        releaseAudioRoute(attempt)
        if (attempt.ownsAudioMode) {
            // MODE_NORMAL removes this process's mode request; Android restores another
            // caller's request itself. Reapplying its observed mode would claim ownership.
            try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) { }
            attempt.ownsAudioMode = false
        }
        if (attempt.ownsAudioFocus) {
            try { attempt.focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } } catch (_: Exception) { }
            attempt.ownsAudioFocus = false
        }
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
        val ownership: LiveSessionOwnership,
        val previousAudioMode: Int,
        val previousSpeakerphone: Boolean,
    ) {
        var networkRecovery: VoiceConnectionRecovery? = null
        var deviceCallback: AudioDeviceCallback? = null
        var focusRequest: AudioFocusRequest? = null
        var ownsAudioFocus = false
        var ownsAudioMode = false
        var ownsCommunicationRoute = false
        var legacyAudioRoute: LegacyCommunicationAudioRoute? = null
        val focusLost = AtomicBoolean(false)
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
        val channelOpen = AtomicBoolean(false)
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
        // WebRTC creation, route changes and disposal can block while joining native threads.
        // A single worker also prevents disposal racing a send, mute or stats request.
        private val AUDIO_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mural-audio-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
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

/** Allows a brief Wi-Fi/mobile handoff; an unrecovered connection cannot stay active forever. */
internal class VoiceConnectionRecovery(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 8_000,
    private val onLost: () -> Unit,
) {
    private var timer: Job? = null
    fun disconnected() {
        if (timer != null) return
        timer = scope.launch {
            delay(timeoutMillis)
            onLost()
        }
    }
    fun connected() {
        timer?.cancel()
        timer = null
    }
}
