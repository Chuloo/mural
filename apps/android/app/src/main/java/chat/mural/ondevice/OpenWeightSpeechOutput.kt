package chat.mural.ondevice

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The engine behind an open-weight teacher voice: one sentence and its packaged [TeacherVoice] in,
 * the learner hearing it out. A thin seam, so the sentence queue and the platform fallback are
 * testable without a native model on a JVM.
 */
interface VoiceSynthesizer : AutoCloseable {
    /** Output energy, 0.0..1.0, of the sentence being spoken, for the orb. */
    val level: Double

    /**
     * Synthesizes one sentence and plays it, returning once the learner has heard it. True when audio
     * reached the speaker; false when the engine produced none, or was stopped before it finished.
     * Runs on the voice's own worker thread; throws when the engine fails.
     */
    fun synthesize(sentence: String, voice: TeacherVoice): Boolean

    /** Stops the sentence being spoken at once. */
    fun stop()
}

/**
 * An opt-in open-weight teacher voice: the sentences of a conversation synthesized on this phone by a
 * downloaded voice model, queued one behind another on a single worker thread, so the microphone
 * stays off from the first sentence to the last exactly as it does for the platform voice.
 *
 * The phone's built-in voice is always the fallback, and the learner never meets the difference as an
 * error or as silence: a model that will not start, a sentence whose synthesis fails, and a sentence
 * the model produces no audio for are spoken by [platform] instead. A model that threw is released
 * and never retried sentence by sentence, exactly as a failed speech recognition model is never
 * retried turn by turn.
 *
 * shortcut: the model loads on the first sentence instead of warming up when the session opens, so an
 * open-weight greeting starts a beat later than the platform voice; upgrade to a warm-up at session
 * start if the device check shows the pause.
 */
class OpenWeightSpeechOutput(
    private val platform: OnDeviceSpeechOutput,
    private val voice: TeacherVoice,
    private val open: () -> VoiceSynthesizer,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, VOICE_THREAD).apply { isDaemon = true } },
) : OnDeviceSpeechOutput {
    private val lock = Any()
    private val queue = ArrayDeque<Sentence>()
    /** True while the worker holds a sentence: synthesis and playback both count as speaking. */
    private var playing = false
    /** Bumped by [stop], so an interrupted sentence is never handed to the platform voice as well. */
    private var generation = 0
    @Volatile private var engine: VoiceSynthesizer? = null
    @Volatile private var broken = false
    @Volatile private var closed = false

    private data class Sentence(val text: String, val locale: String)

    override val speaking get() = synchronized(lock) { playing || queue.isNotEmpty() } || platform.speaking

    override val level get() = if (platform.speaking) platform.level
        else synchronized(lock) { if (playing) engine?.level ?: 0.0 else 0.0 }

    override fun speak(sentence: String, locale: String, queueAdd: Boolean) {
        if (sentence.isBlank() || closed) return
        // A voice already known to be broken is not asked again: the platform voice takes the sentence.
        if (broken) { platform.speak(sentence, locale, queueAdd); return }
        val idle = synchronized(lock) {
            // A flush replaces what is waiting, exactly as the platform voice's own flush does.
            if (!queueAdd) { queue.clear(); generation++ }
            val idle = !playing && queue.isEmpty()
            queue += Sentence(sentence, locale)
            idle
        }
        if (idle) worker.execute { drain() }
    }

    override fun stop() {
        synchronized(lock) { queue.clear(); playing = false; generation++ }
        runCatching { engine?.stop() }
        platform.stop()
    }

    override fun close() {
        closed = true
        stop()
        // The native engine is released on the thread that created it, as its GPU context requires,
        // and that same thread then stops the worker: shutting the worker down first would drop the
        // queued release and leak both the GPU context and the voice thread for the app's lifetime.
        worker.execute {
            release()
            worker.shutdownNow()
            // shutdownNow() interrupts the worker that calls it. This one is about to exit anyway;
            // clearing the flag keeps an inline test executor from carrying it into the test thread.
            Thread.interrupted()
        }
        platform.close()
    }

    /** The one worker thread speaks the queue in order, so two sentences never overlap. */
    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (queue.isEmpty()) { playing = false; return }
                playing = true
                queue.removeFirst()
            }
            speakNow(next)
        }
    }

    private fun speakNow(sentence: Sentence) {
        val started = synchronized(lock) { generation }
        if (!broken) {
            try {
                val opened = engine ?: open().also { engine = it }
                if (opened.synthesize(sentence.text, voice)) return
            } catch (_: Throwable) {
                // A native engine reports failure as an exception or as a linkage error alike.
                broken = true
                release()
            }
        }
        // A sentence the learner just stopped is not spoken again by the fallback voice.
        if (synchronized(lock) { generation } != started) return
        platform.speak(sentence.text, sentence.locale, queueAdd = true)
    }

    private fun release() {
        runCatching { engine?.close() }
        engine = null
    }

    private companion object {
        const val VOICE_THREAD = "mural-teacher-voice"
    }
}
