package chat.mural.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import chat.mural.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Half-duplex voice for OpenAI-compatible servers: listen, transcribe, reply, speak.
 * Emits the coordinator events LiveTransport emits. Mural can't be interrupted while it speaks. */
class TurnTransport(context: Context, private val scope: CoroutineScope) : VoiceTransport {
    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null
    /** A request failed. Fatal failures mean the endpoint settings need attention. */
    var onError: ((Throwable, Boolean) -> Unit)? = null

    private sealed interface Work {
        class Heard(val wav: ByteArray, val startMS: Int, val endMS: Int) : Work
        data object Reply : Work
        class Say(val text: String) : Work
    }

    private val appContext = context.applicationContext
    private var session: Job? = null
    private var work: Channel<Work>? = null
    private val guidance = TurnGuidance()
    @Volatile private var startedAt = 0L
    @Volatile private var listening = false
    @Volatile private var muted = false
    @Volatile private var outputLevel = 0.0

    /** [language] is the learning language for transcription. [reply] receives app guidance for this turn and
     * returns the words to speak. Main-thread only. */
    @SuppressLint("MissingPermission") // Checked immediately before the recorder is created.
    fun connect(api: APIClient, language: String, reply: suspend (guidance: String) -> String) {
        disconnect()
        val microphone = LiveTransport.TransportException.Microphone(appContext.getString(R.string.error_transport_microphone))
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw microphone
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SAMPLES * 20)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (_: Exception) { throw microphone }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw microphone }

        val queue = Channel<Work>(Channel.UNLIMITED)
        work = queue; muted = false; listening = false
        startedAt = SystemClock.elapsedRealtime()
        // Assigned before starting: the coordinator may disconnect while handling session.started.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            launch(Dispatchers.IO) { listen(record, queue) }
            onEvent?.invoke(buildJsonObject { put("type", "session.started") })
            listening = true
            for (item in queue) process(item, api, language, reply)
        }
        session = job
        job.start()
    }

    override fun send(event: JsonObject, respond: Boolean): Boolean {
        val queue = work ?: return false
        val content = (event["content"] as? JsonPrimitive)?.contentOrNull ?: return false
        return when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "session.commentary.append" -> queue.trySend(Work.Say(content)).isSuccess
            "session.instructions.append", "session.thinking.append" -> {
                if (guidance.add(content, respond)) queue.trySend(Work.Reply)
                true
            }
            else -> false
        }
    }

    override fun mute(muted: Boolean) { this.muted = muted }

    /** There is no server session to settle, so closing reports itself closed. */
    override fun close() {
        if (work == null) return
        listening = false
        scope.launch(Dispatchers.Main) { onEvent?.invoke(buildJsonObject { put("type", "session.closed") }) }
    }

    override fun disconnect() {
        session?.cancel(); session = null
        work?.close(); work = null
        listening = false; outputLevel = 0.0
        guidance.clear()
        onLevels?.invoke(0.0, 0.0)
    }

    private suspend fun process(item: Work, api: APIClient, language: String, reply: suspend (String) -> String) {
        if (item == Work.Reply && !guidance.replyPending) return // A heard turn already answered this request.
        listening = false
        try {
            when (item) {
                is Work.Heard -> {
                    val text = api.transcribe(item.wav, language)
                    if (text.isNotBlank()) {
                        emitTranscript("session.input_transcript.delta", text, item.startMS, item.endMS)
                        speak(api, respond(reply))
                    }
                }
                Work.Reply -> speak(api, respond(reply))
                is Work.Say -> speak(api, item.text)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onError?.invoke(error, isFatal(error)) }
        delay(ECHO_TAIL_MS)
        listening = true
    }

    private suspend fun respond(reply: suspend (String) -> String): String {
        val used = guidance.begin()
        return reply(TurnGuidance.prompt(used)).also { guidance.consumed(used) }
    }

    private suspend fun speak(api: APIClient, text: String) {
        val clean = text.trim().take(MAX_SPOKEN_CHARS)
        if (clean.isEmpty()) return
        val pcm = Wav.decode(api.speak(clean)) ?: throw APIClient.APIException.InvalidResponse
        val start = elapsedMS()
        emitTranscript("session.output_transcript.delta", clean, start, start + pcm.durationMS)
        play(pcm)
    }

    private fun emitTranscript(type: String, text: String, start: Int, end: Int) = onEvent?.invoke(buildJsonObject {
        put("type", type); put("event_id", UUID.randomUUID().toString())
        put("delta", text); put("start_ms", start); put("end_ms", end)
    })

    private suspend fun listen(record: AudioRecord, queue: Channel<Work>) {
        val turn = TurnCollector(FRAME_MS)
        val frame = ShortArray(FRAME_SAMPLES)
        var frames = 0
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = record.read(frame, 0, frame.size)
                if (read < 0) break
                if (read == 0) continue
                val samples = frame.copyOf(read)
                val level = Wav.level(samples)
                if (!listening || muted) turn.reset()
                else turn.feed(samples, level)?.let { voiced -> listening = false; queue.trySend(heard(voiced, turn.trailingSilenceMS)) }
                if (++frames % LEVEL_FRAMES == 0) emitLevels(queue, if (turn.active) minOf(1.0, level * LEVEL_GAIN) else 0.0)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A recorder that stops working ends the conversation below instead of crashing the app. */ }
        finally {
            runCatching { record.stop() }
            record.release()
        }
        // Cancellation ends the loop silently; anything else means the microphone failed.
        if (currentCoroutineContext().isActive) scope.launch {
            if (work === queue) onFailure?.invoke(appContext.getString(R.string.error_transport_microphone))
        }
    }

    private fun heard(voiced: List<ShortArray>, trailingSilenceMS: Int): Work.Heard {
        val end = elapsedMS() - trailingSilenceMS
        return Work.Heard(Wav.encode(voiced, SAMPLE_RATE), maxOf(0, end - voiced.size * FRAME_MS), maxOf(0, end))
    }

    private fun emitLevels(queue: Channel<Work>, input: Double) {
        val output = outputLevel
        scope.launch { if (work === queue) onLevels?.invoke(input, output) }
    }

    private suspend fun play(pcm: Wav.Pcm) = withContext(Dispatchers.IO) {
        val mask = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val frameBytes = 2 * pcm.channels
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.sampleRate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), pcm.sampleRate / 5 * frameBytes))
            .build()
        try {
            track.play()
            val chunk = pcm.sampleRate / 20 * frameBytes
            var offset = 0
            while (offset < pcm.data.size) {
                ensureActive()
                val count = minOf(chunk, pcm.data.size - offset)
                outputLevel = minOf(1.0, Wav.level(pcm.data, offset, count) * LEVEL_GAIN)
                val written = track.write(pcm.data, offset, count)
                if (written <= 0) break
                offset += written
            }
            outputLevel = 0.0
            val frames = pcm.data.size / frameBytes
            val deadline = SystemClock.elapsedRealtime() + TAIL_TIMEOUT_MS
            while (track.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < deadline) delay(20)
        } finally {
            outputLevel = 0.0
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun elapsedMS() = (SystemClock.elapsedRealtime() - startedAt).toInt()

    private fun isFatal(error: Throwable) = error is APIClient.APIException.MissingKey ||
        (error is APIClient.APIException.Http && error.status in setOf(401, 403, 404))

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val LEVEL_FRAMES = 5
        private const val LEVEL_GAIN = 8.0
        // Lets the room's echo of Mural's voice fade before listening resumes.
        private const val ECHO_TAIL_MS = 300L
        private const val TAIL_TIMEOUT_MS = 2_000L
        private const val MAX_SPOKEN_CHARS = 1_200
    }
}

/** Coordinator guidance waiting for the next spoken reply. A reply request made while one is pending is dropped. */
internal class TurnGuidance(private val limit: Int = 12) {
    private val notes = mutableListOf<String>()
    var replyPending = false
        private set

    /** Returns true when the caller should queue a reply. */
    fun add(note: String, respond: Boolean): Boolean {
        notes += note
        if (notes.size > limit) notes.removeAt(0)
        if (!respond || replyPending) return false
        replyPending = true
        return true
    }

    /** Starts a reply. Notes stay until [consumed], so a failed reply keeps its guidance for the next turn. */
    fun begin(): List<String> { replyPending = false; return notes.toList() }
    /** Removes one occurrence per used note, so an identical note added during the reply survives. */
    fun consumed(used: List<String>) { used.forEach { notes.remove(it) } }
    fun clear() { notes.clear(); replyPending = false }

    companion object {
        fun prompt(used: List<String>) =
            if (used.isEmpty()) "" else "\nApp guidance for this reply, never to be read aloud:\n" + used.joinToString("\n")
    }
}

/** Collects one learner turn from microphone frames, keeping a little audio from before speech began. */
internal class TurnCollector(private val frameMillis: Int) {
    private val detector = SpeechDetector(frameMillis)
    private val preroll = ArrayDeque<ShortArray>()
    private val speech = mutableListOf<ShortArray>()
    val active get() = detector.active
    /** Silence trimmed from the last finished turn, for its timing. */
    val trailingSilenceMS get() = detector.silence

    /** Returns a finished turn without its trailing silence, or null while listening continues. */
    fun feed(frame: ShortArray, level: Double): List<ShortArray>? {
        when (detector.feed(level)) {
            SpeechDetector.Event.NONE -> if (detector.active) speech += frame else remember(frame)
            SpeechDetector.Event.START -> { speech.clear(); speech.addAll(preroll); speech += frame; preroll.clear() }
            SpeechDetector.Event.END -> {
                speech += frame
                return speech.dropLast(detector.silence / frameMillis).also { speech.clear() }
            }
            SpeechDetector.Event.DISCARD -> speech.clear()
        }
        return null
    }

    fun reset() { detector.reset(); speech.clear(); preroll.clear() }

    private fun remember(frame: ShortArray) {
        preroll.addLast(frame)
        if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
    }

    companion object {
        const val PREROLL_FRAMES = 15
    }
}

/** Energy endpointing for one speaker at a time. The thresholds are calibration knobs: tune them on real devices. */
internal class SpeechDetector(private val frameMillis: Int) {
    enum class Event { NONE, START, END, DISCARD }

    var active = false
        private set
    private var floor = 0.003
    private var onset = 0
    private var length = 0
    private var voiced = 0
    /** Quiet time at the end of the current turn; zero when a turn is cut off at the length limit mid-speech. */
    var silence = 0
        private set

    fun feed(level: Double): Event {
        val loud = level >= maxOf(MIN_LEVEL, floor * FLOOR_RATIO)
        if (!active) {
            if (!loud) { onset = 0; floor += (level - floor) * FLOOR_ADAPTATION; return Event.NONE }
            if (++onset * frameMillis < ONSET_MS) return Event.NONE
            // The onset frames are speech too; without them a short "sí" would be discarded as a click.
            val onsetMillis = onset * frameMillis
            active = true; onset = 0; length = onsetMillis; voiced = onsetMillis; silence = 0
            return Event.START
        }
        length += frameMillis
        if (loud) { voiced += frameMillis; silence = 0 } else silence += frameMillis
        if (silence < END_SILENCE_MS && length < MAX_UTTERANCE_MS) return Event.NONE
        active = false
        return if (voiced >= MIN_VOICED_MS) Event.END else Event.DISCARD
    }

    fun reset() { active = false; onset = 0 }

    companion object {
        const val MIN_LEVEL = 0.01
        const val FLOOR_RATIO = 3.0
        const val FLOOR_ADAPTATION = 0.05
        const val ONSET_MS = 60
        // Learners pause to find words; a short gap must not end their turn.
        const val END_SILENCE_MS = 1_200
        const val MIN_VOICED_MS = 250
        const val MAX_UTTERANCE_MS = 30_000
    }
}

internal object Wav {
    class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {
        val durationMS get() = (data.size.toLong() / (2 * channels) * 1000 / sampleRate).toInt()
    }

    fun encode(chunks: List<ShortArray>, sampleRate: Int): ByteArray {
        val bytes = chunks.sumOf { it.size } * 2
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ascii("RIFF")).putInt(36 + bytes).put(ascii("WAVE"))
            .put(ascii("fmt ")).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            .put(ascii("data")).putInt(bytes)
        chunks.forEach { chunk -> chunk.forEach { buffer.putShort(it) } }
        return buffer.array()
    }

    /** Reads 16-bit PCM WAV and skips extra chunks. Streaming servers may leave the data length unknown. */
    fun decode(bytes: ByteArray): Pcm? {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = tag(bytes, offset)
            val size = buffer.getInt(offset + 4)
            val start = offset + 8
            if (id == "data") {
                if ((format != 1 && format != 0xFFFE) || bits != 16 || channels !in 1..2 || rate !in 8_000..96_000) return null
                val end = if (size <= 0 || size > bytes.size - start) bytes.size else start + size
                val frame = 2 * channels
                return Pcm(rate, channels, bytes.copyOfRange(start, start + (end - start) / frame * frame))
            }
            if (size < 0 || size > bytes.size - start) return null
            if (id == "fmt " && size >= 16) {
                format = buffer.getShort(start).toInt() and 0xFFFF
                channels = buffer.getShort(start + 2).toInt()
                rate = buffer.getInt(start + 4)
                bits = buffer.getShort(start + 14).toInt()
            }
            offset = start + size + (size and 1)
        }
        return null
    }

    fun level(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return sqrt(sum / samples.size) / 32_768
    }

    fun level(data: ByteArray, offset: Int, count: Int): Double {
        var sum = 0.0
        var index = offset
        while (index + 1 < offset + count) {
            val sample = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xFF)).toShort().toDouble()
            sum += sample * sample; index += 2
        }
        return sqrt(sum / maxOf(1, count / 2)) / 32_768
    }

    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
