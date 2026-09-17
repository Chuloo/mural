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
    private val connectionGeneration = AtomicLong(0)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordJob: Job? = null
    private var connectedAt = 0L
    private var sessionStarted = false
    private var usageReported = false
    private var instructions = ""
    private var history = JsonArray(emptyList())

    suspend fun connect(key: String, instructions: String, history: JsonArray) {
        disconnect()
        check(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        this.instructions = instructions
        this.history = history
        connectedAt = System.currentTimeMillis()
        closing = false; muted = false; active = true; sessionStarted = false; usageReported = false
        val generation = connectionGeneration.incrementAndGet()
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
        return sendJson(buildJsonObject { put("realtimeInput", buildJsonObject { put("text", "$label (do not mention this instruction): $content") }) })
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
        recordJob?.cancel(); recordJob = null
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        record = null; track = null
        socket?.close(1000, null); socket = null
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
            if (isCurrent(webSocket, expectedGeneration) && !closing) fail("The Google AI Studio voice connection ended. Check your key and connection, then try again.")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(webSocket, expectedGeneration)) webSocket.close(code, reason)
        }
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
            if (historyTurns().isNotEmpty()) put("historyConfig", buildJsonObject { put("initialHistoryInClientContent", true) })
        })
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
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        val outputFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_OUT)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(outputFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(SAMPLE_RATE_OUT / 2)
            .build()
        recorder.startRecording(); player.play()
        record = recorder; track = player
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
        if (json["setupComplete"] != null) {
            if (sessionStarted) return
            try { startAudio(expectedGeneration, expectedSocket) } catch (_: Exception) {
                fail("The Google AI Studio microphone couldn’t start.")
                return
            }
            sendHistory(expectedSocket, expectedGeneration)
            sessionStarted = true
            val session = buildJsonObject { put("id", "gemini-live"); put("model", AIProvider.GOOGLE_AI_STUDIO.liveModel) }
            emitEvent(buildJsonObject { put("type", "mural.session.created"); put("session", session) }, expectedGeneration)
            emitEvent(buildJsonObject { put("type", "session.started"); put("session", session) }, expectedGeneration)
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
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
