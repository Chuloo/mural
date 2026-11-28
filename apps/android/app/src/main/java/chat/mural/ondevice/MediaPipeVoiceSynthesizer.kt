package chat.mural.ondevice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

/**
 * The MediaPipe `TextToSpeech` task behind [OpenWeightSpeechOutput]: a downloaded `.litertlm` voice
 * model that synthesizes one sentence into 24 kHz mono 16-bit PCM, which Mural plays on its own
 * [AudioTrack] so the teacher's energy reaches the orb. Kokoro tries the GPU first and falls back to
 * the CPU; PocketTTS runs on the CPU alone. Creation, synthesis and release all happen on the one
 * worker thread of [OpenWeightSpeechOutput], because a GPU context is bound to the thread that made
 * it; only [stop] can arrive from the session's own thread, as an interruption does. Everything
 * native lives here; the [VoiceSynthesizer] seam above it is what Mural tests.
 */
class MediaPipeVoiceSynthesizer(
    private val context: Context,
    private val modelFile: File,
    supportsGpu: Boolean,
) : VoiceSynthesizer {
    private var usingGpu = supportsGpu
    private var engine: TextToSpeech = (if (supportsGpu) open(Delegate.GPU) else null) ?: run {
        usingGpu = false
        open(Delegate.CPU)
    } ?: throw ModelRuntimeException("The voice model would not start: ${modelFile.name}")
    private val track = audioTrack()
    /** Frames handed to the track so far; its head position is what a sentence's drain waits for. */
    private var writtenFrames = 0L
    @Volatile private var stopped = false
    @Volatile private var energy = 0.0

    override val level get() = energy

    override fun synthesize(sentence: String, voice: TeacherVoice): Boolean {
        return try {
            runSynthesize(sentence, voice)
        } catch (error: Exception) {
            if (!usingGpu) throw error
            Log.w(TAG, "Voice model GPU synthesis failed; retrying on CPU", error)
            runCatching { engine.close() }
            usingGpu = false
            engine = open(Delegate.CPU)
                ?: throw ModelRuntimeException("The voice model would not start: ${modelFile.name}", error)
            runSynthesize(sentence, voice)
        }
    }

    private fun open(delegate: Delegate): TextToSpeech? = runCatching {
        TextToSpeech.create(options(context, modelFile, delegate))
    }.onFailure { Log.w(TAG, "Voice model $delegate init failed: ${it.message}") }.getOrNull()

    private fun runSynthesize(sentence: String, voice: TeacherVoice): Boolean {
        stopped = false
        energy = 0.0
        val spokenBefore = writtenFrames
        runCatching { track.play() }
        runBlocking {
            engine.synthesizeStream(sentence, SynthesisOptions(voice.voiceID, voice.locale)).collect { chunk ->
                if (stopped) return@collect
                val samples = chunk.samples
                if (samples.isEmpty()) return@collect
                val pcm = pcm16(samples)
                val written = track.write(pcm, 0, pcm.size)
                if (written > 0) writtenFrames += written / BYTES_PER_FRAME
                energy = energyOf(samples)
            }
        }
        // The PCM is still in the track's buffer: wait for the speaker to finish it, so the next
        // sentence never overlaps this one and the microphone stays off while the teacher talks.
        drain()
        energy = 0.0
        return writtenFrames > spokenBefore && !stopped
    }

    override fun stop() {
        stopped = true
        runCatching { engine.cancel() }
        // A flush drops the buffered sentence and rewinds the head position the drain waits for.
        runCatching { track.pause(); track.flush() }
        writtenFrames = 0
    }

    override fun close() {
        stop()
        runCatching { engine.close() }
        runCatching { track.release() }
    }

    private fun options(context: Context, modelFile: File, delegate: Delegate) = TextToSpeechOptions(
        baseOptions = BaseOptions.builder().setModelAssetPath(modelFile.absolutePath)
            .setDelegate(delegate).build(),
        // Each sentence carries its own packaged voice and locale, so the model needs no default.
        defaultVoiceId = null,
        defaultLocale = DEFAULT_LOCALE,
        cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }.absolutePath)

    private fun audioTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Otherwise a short sentence waits for the whole buffer to fill before the learner hears it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && track.state == AudioTrack.STATE_INITIALIZED)
            track.setStartThresholdInFrames(1)
        return track
    }

    /** Waits for the speaker to play the buffered PCM out, bounded by that audio's own duration. */
    private fun drain() {
        if (track.state != AudioTrack.STATE_INITIALIZED) return
        val head = track.playbackHeadPosition.toLong() and HEAD_MASK
        if (head >= writtenFrames) return
        val deadline = System.nanoTime() +
            (writtenFrames - head) * NANOS_PER_SECOND / SAMPLE_RATE + DRAIN_MARGIN_NANOS
        while (!stopped && System.nanoTime() < deadline) {
            if ((track.playbackHeadPosition.toLong() and HEAD_MASK) >= writtenFrames) return
            try { Thread.sleep(POLL_MILLIS) } catch (_: InterruptedException) { return }
        }
    }

    /** The 16-bit little-endian PCM the track writes, from the model's float samples. */
    private fun pcm16(samples: FloatArray): ByteArray {
        val pcm = ByteBuffer.allocate(samples.size * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) pcm.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return pcm.array()
    }

    /** The orb's energy: a chunk's RMS, which is what a level meter shows. */
    private fun energyOf(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / samples.size).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val TAG = "MuralOnDevice"
        /** Open-weight voices synthesize 24 kHz mono PCM. */
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
        const val BUFFER_BYTES = 96_000
        /** The tag the model is created with; every sentence passes its own voice and locale. */
        const val DEFAULT_LOCALE = "en-US"
        const val CACHE_DIRECTORY = "mural_voice_synthesis_cache"
        const val POLL_MILLIS = 20L
        const val DRAIN_MARGIN_NANOS = 500_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        /** The head position is an unsigned 32-bit counter that wraps on a long playback. */
        const val HEAD_MASK = 0xFFFFFFFFL
    }
}
