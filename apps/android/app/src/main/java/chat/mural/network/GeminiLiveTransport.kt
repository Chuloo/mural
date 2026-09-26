package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Direct Google AI Studio Live API transport using 16 kHz input and 24 kHz output PCM. */
class GeminiLiveTransport(
    context: Context,
    private val scope: CoroutineScope,
) {
    var onEvent: ((JsonObject) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null

    private val applicationContext = context.applicationContext
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var active = false
    @Volatile private var muted = false
    @Volatile private var closing = false
    @Volatile private var socketReady = false
    private val connectionGeneration = AtomicLong(0)
    private val controlLock = Any()
    private val pendingControlMessages = ArrayDeque<JsonObject>()
    private var flushingControlMessages = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordJob: Job? = null
    private var connectedAt = 0L
    private var sessionStarted = false
    private var usageReported = false
    private var instructions = ""
    private var history = JsonArray(emptyList())
    private var apiKey: String? = null
    private var sessionResumptionHandle: String? = null
    private var activeResumptionHandle: String? = null
    private var reconnectJob: Job? = null
    private var reconnectDueAt = Long.MAX_VALUE
    private var reconnectAttempts = 0

    suspend fun connect(key: String, instructions: String, history: JsonArray) {
        disconnect()
        check(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        this.instructions = instructions
        this.history = history
        this.apiKey = key
        sessionResumptionHandle = null
        activeResumptionHandle = null
        connectedAt = System.currentTimeMillis()
        closing = false; muted = false; active = true; sessionStarted = false; usageReported = false
        reconnectAttempts = 0
        openSocket(null)
    }

    private fun openSocket(resumptionHandle: String?) {
        val key = apiKey ?: return
        val generation = connectionGeneration.incrementAndGet()
        activeResumptionHandle = resumptionHandle
        synchronized(controlLock) { socketReady = false }
        val url = HttpUrl.Builder()
            .scheme("wss")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", key)
            .build()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener(generation))
    }

    fun send(event: JsonObject): Boolean {
        if (!active || closing) return false
        val content = event["content"]?.jsonPrimitive?.contentOrNull ?: return false
        val type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val label = when (type) {
            "session.instructions.append" -> "Teaching instruction"
            "session.thinking.append" -> "Teaching context"
            else -> "Conversation guidance"
        }
        return sendControlMessage(buildJsonObject {
            put("realtimeInput", buildJsonObject { put("text", "$label (do not mention this instruction): $content") })
        })
    }

    fun mute(value: Boolean) {
        muted = value
        if (value) {
            emitLevels(0.0, 0.0)
            sendJson(buildJsonObject { put("realtimeInput", buildJsonObject { put("audioStreamEnd", true) }) })
        }
    }

    fun close() {
        if (!active || closing) return
        closing = true; muted = true
        synchronized(controlLock) {
            socketReady = false
            pendingControlMessages.clear()
        }
        reconnectJob?.cancel(); reconnectJob = null; reconnectDueAt = Long.MAX_VALUE
        sendJson(buildJsonObject { put("realtimeInput", buildJsonObject { put("audioStreamEnd", true) }) })
        if (!usageReported) {
            usageReported = true
            val seconds = ((System.currentTimeMillis() - connectedAt).coerceAtLeast(0L)) / 1000.0
            emitEvent(buildJsonObject { put("type", "session.usage.updated"); put("usage", buildJsonObject { put("seconds", seconds) }) })
            emitEvent(buildJsonObject { put("type", "session.closed"); put("reason", "Ended by user"); put("usage", buildJsonObject { put("seconds", seconds) }) })
        }
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        active = false; closing = true; muted = true
        synchronized(controlLock) {
            socketReady = false
            flushingControlMessages = false
            pendingControlMessages.clear()
        }
        reconnectJob?.cancel(); reconnectJob = null; reconnectDueAt = Long.MAX_VALUE
        recordJob?.cancel(); recordJob = null
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        record = null; track = null
        socket?.close(1000, null); socket = null
        apiKey = null; sessionResumptionHandle = null; activeResumptionHandle = null
        emitLevels(0.0, 0.0)
    }

    private fun listener(expectedGeneration: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket, expectedGeneration)) return
            sendJson(setupMessage(), webSocket, expectedGeneration)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parse(text, webSocket, expectedGeneration)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            parse(bytes.utf8(), webSocket, expectedGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleSocketEnded(webSocket, expectedGeneration, "The Google AI Studio voice connection ended. Check your key and connection, then try again.")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(webSocket, expectedGeneration)) {
                handleSocketEnded(webSocket, expectedGeneration, "The Google AI Studio voice connection ended. Check your key and connection, then try again.")
                webSocket.close(code, reason)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketEnded(webSocket, expectedGeneration, "The Google AI Studio voice connection ended. Check your key and connection, then try again.")
        }
    }

    private fun handleSocketEnded(webSocket: WebSocket, expectedGeneration: Long, message: String) {
        if (!isCurrent(webSocket, expectedGeneration) || closing) return
        synchronized(controlLock) { socketReady = false }
        if (sessionResumptionHandle.isNullOrBlank()) fail(message) else scheduleReconnect(0L)
    }

    private fun setupMessage(): JsonObject = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/${AIProvider.GOOGLE_AI_STUDIO.liveModel}")
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add("AUDIO") })
                put("speechConfig", buildJsonObject { put("voiceConfig", buildJsonObject {
                    put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", "Kore") })
                }) })
            })
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", instructions) }) }) })
            put("inputAudioTranscription", buildJsonObject { })
            put("outputAudioTranscription", buildJsonObject { })
            put("contextWindowCompression", buildJsonObject { put("slidingWindow", buildJsonObject { }) })
            put("sessionResumption", buildJsonObject {
                activeResumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
            })
            if (activeResumptionHandle == null && historyTurns().isNotEmpty()) {
                put("historyConfig", buildJsonObject { put("initialHistoryInClientContent", true) })
            }
        })
    }

    private fun scheduleReconnect(delayMillis: Long) {
        if (!active || closing || sessionResumptionHandle.isNullOrBlank()) return
        val dueAt = System.currentTimeMillis() + delayMillis.coerceAtLeast(0L)
        if (reconnectJob?.isActive == true && dueAt >= reconnectDueAt) return
        reconnectJob?.cancel()
        reconnectDueAt = dueAt
        reconnectJob = scope.launch {
            delay(delayMillis.coerceAtLeast(0L))
            reconnectDueAt = Long.MAX_VALUE
            reconnectJob = null
            reconnect()
        }
    }

    private fun reconnect() {
        if (!active || closing) return
        val handle = sessionResumptionHandle?.takeIf { it.isNotBlank() } ?: run {
            fail("The Google AI Studio voice connection ended. Check your key and connection, then try again.")
            return
        }
        if (++reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            fail("The Google AI Studio voice connection could not be resumed. Check your connection, then try again.")
            return
        }
        recordJob?.cancel(); recordJob = null
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
        val oldSocket = socket
        synchronized(controlLock) {
            socketReady = false
            socket = null
        }
        oldSocket?.close(1000, "Resuming session")
        openSocket(handle)
    }

    private fun timeLeftMillis(value: String?): Long {
        val text = value?.trim()?.lowercase() ?: return 0L
        return when {
            text.endsWith("ms") -> text.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 0L
            text.endsWith("s") -> ((text.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1_000.0).toLong()
            else -> text.toDoubleOrNull()?.toLong() ?: 0L
        }
    }

    private fun historyTurns(): JsonArray = buildJsonArray {
        for (item in history) {
            val objectValue = item as? JsonObject ?: continue
            val role = objectValue["role"]?.jsonPrimitive?.contentOrNull ?: continue
            val content = objectValue["content"]?.jsonArray ?: continue
            val text = content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString(" ")
            if (text.isNotBlank()) add(buildJsonObject {
                put("role", if (role == "assistant") "model" else "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
            })
        }
    }

    private fun sendHistory(webSocket: WebSocket, generation: Long) {
        val turns = historyTurns()
        if (turns.isEmpty()) return
        sendJson(buildJsonObject { put("clientContent", buildJsonObject { put("turns", turns); put("turnComplete", true) }) }, webSocket, generation)
    }

    private fun startAudio(expectedGeneration: Long, expectedSocket: WebSocket) {
        if (!isCurrent(expectedSocket, expectedGeneration)) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(SAMPLES_PER_PACKET * 2)
        val recorder = record ?: AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        ).also { record = it }
        val player = track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE_OUT).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(SAMPLE_RATE_OUT / 2)
            .build()
            .also { track = it }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) recorder.startRecording()
        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) player.play()
        recordJob?.cancel()
        recordJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val packet = ByteArray(SAMPLES_PER_PACKET * 2)
            while (isActive && isCurrent(expectedSocket, expectedGeneration)) {
                val count = recorder.read(packet, 0, packet.size)
                if (count <= 0 || muted) continue
                val data = packet.copyOf(count)
                emitLevels(minOf(1.0, rms(data) * 7.0), 0.0, expectedSocket, expectedGeneration)
                sendJson(buildJsonObject {
                    put("realtimeInput", buildJsonObject {
                        put("audio", buildJsonObject {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", Base64.getEncoder().encodeToString(data))
                        })
                    })
                }, expectedSocket, expectedGeneration)
            }
        }
    }

    private fun parse(text: String, expectedSocket: WebSocket, expectedGeneration: Long) {
        if (!isCurrent(expectedSocket, expectedGeneration)) return
        val json = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        json["sessionResumptionUpdate"]?.jsonObject?.get("newHandle")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { sessionResumptionHandle = it }
        json["goAway"]?.jsonObject?.let { goAway ->
            scheduleReconnect(timeLeftMillis(goAway["timeLeft"]?.jsonPrimitive?.contentOrNull))
            return
        }
        if (json["setupComplete"] != null) {
            try { startAudio(expectedGeneration, expectedSocket) } catch (_: Exception) {
                fail("The Google AI Studio microphone couldn’t start.")
                return
            }
            if (!isCurrent(expectedSocket, expectedGeneration)) return
            val firstSession = !sessionStarted
            if (firstSession && activeResumptionHandle == null) sendHistory(expectedSocket, expectedGeneration)
            synchronized(controlLock) {
                if (!isCurrent(expectedSocket, expectedGeneration)) return
                socketReady = true
                flushPendingControlMessages(expectedSocket, expectedGeneration)
            }
            reconnectAttempts = 0
            if (firstSession) {
                sessionStarted = true
                val session = buildJsonObject { put("id", "gemini-live"); put("model", AIProvider.GOOGLE_AI_STUDIO.liveModel) }
                emitEvent(buildJsonObject { put("type", "mural.session.created"); put("session", session) }, expectedGeneration)
                emitEvent(buildJsonObject { put("type", "session.started"); put("session", session) }, expectedGeneration)
            }
            return
        }
        val error = json["error"]?.jsonObject
        if (error != null) {
            fail(error["message"]?.jsonPrimitive?.contentOrNull?.take(240) ?: "The Google AI Studio voice request failed.")
            return
        }
        val content = json["serverContent"]?.jsonObject ?: return
        if (content["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) { }
            emitLevels(0.0, 0.0, expectedSocket, expectedGeneration)
        }
        val now = (System.currentTimeMillis() - connectedAt).toInt().coerceAtLeast(0)
        content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            emitTranscript("session.input_transcript.delta", it, now, expectedGeneration)
        }
        content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            emitTranscript("session.output_transcript.delta", it, now, expectedGeneration)
        }
        val parts = content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())
        for (part in parts) {
            val inline = (part as? JsonObject)?.get("inlineData")?.jsonObject ?: continue
            val encoded = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: continue
            if (!isCurrent(expectedSocket, expectedGeneration)) return
            track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
            emitLevels(0.0, 0.35, expectedSocket, expectedGeneration)
        }
    }

    private fun emitTranscript(type: String, text: String, now: Int, generation: Long) {
        emitEvent(buildJsonObject {
            put("type", type); put("event_id", java.util.UUID.randomUUID().toString()); put("delta", text)
            put("start_ms", (now - 1).coerceAtLeast(0)); put("end_ms", now)
        }, generation)
    }

    private fun isCurrent(expectedSocket: WebSocket, expectedGeneration: Long): Boolean =
        active && connectionGeneration.get() == expectedGeneration && socket === expectedSocket

    private fun sendControlMessage(value: JsonObject): Boolean {
        synchronized(controlLock) {
            if (!active || closing) return false
            val currentSocket = socket
            if (!socketReady || flushingControlMessages || currentSocket == null) {
                pendingControlMessages.addLast(value)
                return true
            }
            if (sendJson(value, currentSocket, connectionGeneration.get())) return true
            socketReady = false
            pendingControlMessages.addFirst(value)
            return true
        }
    }

    private fun flushPendingControlMessages(expectedSocket: WebSocket, expectedGeneration: Long) {
        synchronized(controlLock) {
            if (!socketReady || !isCurrent(expectedSocket, expectedGeneration)) return
            flushingControlMessages = true
            try {
                while (pendingControlMessages.isNotEmpty()) {
                    val message = pendingControlMessages.first()
                    if (!sendJson(message, expectedSocket, expectedGeneration)) {
                        socketReady = false
                        return
                    }
                    pendingControlMessages.removeFirst()
                }
            } finally {
                flushingControlMessages = false
            }
        }
    }

    private fun sendJson(value: JsonObject, expectedSocket: WebSocket? = null, expectedGeneration: Long? = null): Boolean {
        val current = socket ?: return false
        if (expectedSocket != null && current !== expectedSocket) return false
        if (expectedGeneration != null && connectionGeneration.get() != expectedGeneration) return false
        return current.send(value.toString())
    }

    private fun emitEvent(event: JsonObject, expectedGeneration: Long = connectionGeneration.get()) {
        scope.launch { if (active && connectionGeneration.get() == expectedGeneration) onEvent?.invoke(event) }
    }
    private fun emitLevels(input: Double, output: Double, expectedSocket: WebSocket? = null, expectedGeneration: Long? = null) {
        scope.launch {
            if (active && (expectedSocket == null || socket === expectedSocket) && (expectedGeneration == null || connectionGeneration.get() == expectedGeneration)) {
                onLevels?.invoke(input, output)
            }
        }
    }
    private fun fail(message: String) {
        reconnectJob?.cancel(); reconnectJob = null; reconnectDueAt = Long.MAX_VALUE
        val expectedGeneration = connectionGeneration.get()
        scope.launch { if (active && connectionGeneration.get() == expectedGeneration && !closing) onFailure?.invoke(message) }
    }

    private fun rms(data: ByteArray): Double {
        if (data.size < 2) return 0.0
        val samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var total = 0.0
        while (samples.hasRemaining()) {
            val value = samples.get().toDouble() / Short.MAX_VALUE
            total += value * value
        }
        return kotlin.math.sqrt(total / (data.size / 2.0))
    }

    companion object {
        private const val SAMPLE_RATE_IN = 16_000
        private const val SAMPLE_RATE_OUT = 24_000
        private const val SAMPLES_PER_PACKET = 1_600
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
