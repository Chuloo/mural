package chat.mural.ondevice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The speech recognition engine behind [ParakeetSpeechInput]: one turn's 16 kHz mono float PCM in,
 * the learner's words out. A thin seam, so the microphone and the turn flow are testable without a
 * native model on a JVM.
 */
interface SpeechTranscriber : AutoCloseable {
    /** Transcribes one complete turn. Runs on a worker thread; throws when the engine fails. */
    fun transcribe(samples: FloatArray): String
}

/**
 * The learner's microphone when a dedicated speech recognition model is the chosen alternate: the
 * same recording, the same turn boundaries and the same clip window as native audio input, with the
 * model's transcription handed to the conversation in place of the clip. Captions, session records
 * and assessments therefore see exactly what they see for native input, and a conversation model
 * with no native audio input of its own can still hear the learner.
 *
 * An alternate that cannot run never blocks the conversation: with [audioFallback] the clip goes to
 * the active model's own native audio input, and without it the failure is reported honestly instead
 * of feeding audio to a model that cannot hear. Either way the engine is loaded once per session and
 * is never retried turn by turn.
 *
 * shortcut: one clip is transcribed after the turn ends, not while the learner speaks, and a clip
 * the alternate drops mid-conversation reaches the model without a learner caption of its own;
 * upgrade to streamed chunks and a per-turn audio flag on the session if the device check shows
 * either cost.
 */
class ParakeetSpeechInput(
    private val microphone: OnDeviceSpeechInput,
    private val open: () -> SpeechTranscriber,
    private val audioFallback: Boolean = false,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OnDeviceSpeechInput {
    private var transcriber: SpeechTranscriber? = null
    private var broken: Throwable? = null

    override val level get() = microphone.level

    override suspend fun awaitTurn(): SpeechTurn? = withContext(dispatcher) {
        while (coroutineContext.isActive) {
            val turn = microphone.awaitTurn() ?: return@withContext null
            val clip = turn as? SpeechTurn.AudioWav ?: return@withContext turn
            val text = transcribe(clip)
            if (text == null) {
                if (audioFallback) return@withContext clip
                throw ModelRuntimeException("The speech recognition model failed", broken)
            }
            // A turn the alternate hears as silence is dropped, exactly as an empty clip is.
            if (text.isNotBlank()) return@withContext SpeechTurn.Transcript(clip.durationMS, text.trim())
        }
        null
    }

    override fun mute(muted: Boolean) = microphone.mute(muted)

    /** Releases the microphone and the speech recognition model with it. */
    override fun close() {
        microphone.close()
        runCatching { transcriber?.close() }
        transcriber = null
    }

    /** The learner's words, or null once the alternate has failed: it is not retried per turn. */
    private fun transcribe(clip: SpeechTurn.AudioWav): String? {
        if (broken != null) return null
        return try {
            val engine = transcriber ?: open().also { transcriber = it }
            engine.transcribe(WavAudio.floatSamples(clip.wavBytes))
        } catch (cancelled: CancellationException) { throw cancelled }
        // A native engine reports failure as an exception or as a linkage error alike.
        catch (error: Throwable) { broken = error; null }
    }
}
