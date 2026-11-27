package chat.mural.ondevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The learner's microphone: 16 kHz mono 16-bit PCM, one turn at a time, cut into a WAV clip the
 * model's native audio input hears. The voice-communication source keeps the platform's own echo
 * cancellation and noise suppression on the recording. The recorder is opened per turn and released
 * with it, so a closed microphone holds nothing and no stale buffer can carry the teacher's voice
 * into the learner's next turn. Nothing here touches a network.
 */
class NativeAudioSpeechInput(
    context: Context,
    private val sampleRate: Int = WavAudio.SAMPLE_RATE,
    private val detector: SpeechTurnDetector = SpeechTurnDetector(),
) : OnDeviceSpeechInput {
    private val applicationContext = context.applicationContext

    @Volatile private var measured = 0.0
    @Volatile private var muted = false
    @Volatile private var closed = false

    override val level get() = measured

    override suspend fun awaitTurn(): SpeechTurn? = withContext(Dispatchers.IO) {
        while (coroutineContext.isActive && !closed) {
            val recorder = open() ?: return@withContext null
            val clip = try { recordTurn(recorder) } finally { release(recorder) }
            // null means the microphone is unusable or was closed: there is no conversation left.
            if (clip == null) return@withContext null
            if (clip.isNotEmpty()) {
                return@withContext SpeechTurn.AudioWav(clip.size / BYTES_PER_MILLISECOND,
                    WavAudio.wrapPcm16Mono(clip, sampleRate))
            }
        }
        null
    }

    override fun mute(muted: Boolean) {
        this.muted = muted
        if (muted) measured = 0.0
    }

    /** The reading thread sees this within one frame and releases the recorder itself. */
    override fun close() {
        closed = true
        measured = 0.0
    }

    /** One clip, from just before the learner's first loud frame to just after their last one. */
    private fun recordTurn(recorder: AudioRecord): ByteArray? {
        val samples = ShortArray(SpeechTurnDetector.FRAME_SAMPLES)
        val frame = ByteArray(SpeechTurnDetector.FRAME_SAMPLES * BYTES_PER_SAMPLE)
        val window = ByteArrayOutputStream()
        detector.reset()
        var startBytes = -1
        var endBytes = 0
        while (!closed) {
            val read = recorder.read(samples, 0, samples.size)
            if (read < 0) return null
            if (read == 0) continue
            // A muted microphone reports silence, so its window is dropped before it can be a turn.
            val energy = if (muted) 0.0 else SpeechTurnDetector.level(samples, read)
            measured = energy
            val offset = window.size()
            val bytes = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until read) bytes.putShort(samples[index])
            window.write(frame, 0, read * BYTES_PER_SAMPLE)
            when (val step = detector.offer(energy)) {
                is SpeechTurnDetector.Step.Listening -> if (step.speaking) {
                    if (startBytes < 0) startBytes = (offset - PRE_ROLL_BYTES).coerceAtLeast(0)
                    if (energy >= SpeechTurnDetector.SPEECH_LEVEL) endBytes = window.size()
                }
                SpeechTurnDetector.Step.Restart -> {
                    window.reset(); startBytes = -1; endBytes = 0
                }
                SpeechTurnDetector.Step.TurnEnded -> {
                    val clip = window.toByteArray()
                    val from = startBytes.coerceIn(0, clip.size)
                    if (endBytes > from) return clip.copyOfRange(from, endBytes)
                    window.reset(); startBytes = -1; endBytes = 0; detector.reset()
                }
            }
        }
        return null
    }

    private fun open(): AudioRecord? {
        // A revoked permission is an honest "no microphone", never a SecurityException.
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return null
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, SpeechTurnDetector.FRAME_SAMPLES * BYTES_PER_SAMPLE * 4))
        }.getOrNull() ?: return null
        // startRecording() throws rather than returning a code; the state afterwards is the truth.
        runCatching { recorder.startRecording() }
        if (recorder.state != AudioRecord.STATE_INITIALIZED ||
            recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release(recorder)
            return null
        }
        return recorder
    }

    private fun release(recorder: AudioRecord?) {
        measured = 0.0
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_MILLISECOND = WavAudio.SAMPLE_RATE * BYTES_PER_SAMPLE / 1_000
        /** Kept before the first loud frame, so a turn never loses its first syllable. */
        const val PRE_ROLL_BYTES = 9_600
    }
}
