package chat.mural.ondevice

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The microphone's pure turn decisions, one 0.1 s frame of energy at a time: a turn starts when the
 * level rises above [speechLevel], ends after [endSilenceSeconds] of quiet, and is cut at
 * [maxClipSeconds] so one clip never outgrows the model's audio input. A whole window of silence is
 * dropped, so a quiet room does not grow an unbounded clip.
 */
class SpeechTurnDetector(
    private val frameSamples: Int = FRAME_SAMPLES,
    private val sampleRate: Int = WavAudio.SAMPLE_RATE,
    private val speechLevel: Double = SPEECH_LEVEL,
    endSilenceSeconds: Double = END_SILENCE_SECONDS,
    maxClipSeconds: Double = MAX_CLIP_SECONDS,
    idleWindowSeconds: Double = IDLE_WINDOW_SECONDS,
) {
    sealed interface Step {
        /** Keep recording; [speaking] says whether this window already holds the learner's voice. */
        data class Listening(val speaking: Boolean) : Step

        /** The learner finished: the recorded clip is one complete turn. */
        data object TurnEnded : Step

        /** Only silence so far: drop the window and listen again. */
        data object Restart : Step
    }

    private val endSilenceFrames = frames(endSilenceSeconds)
    private val maxClipFrames = frames(maxClipSeconds)
    private val idleWindowFrames = frames(idleWindowSeconds)
    private var idleFrames = 0
    private var speechFrames = 0
    private var quietFrames = 0

    fun offer(level: Double): Step {
        if (speechFrames == 0) {
            if (level >= speechLevel) { speechFrames = 1; return Step.Listening(true) }
            return if (++idleFrames >= idleWindowFrames) { reset(); Step.Restart } else Step.Listening(false)
        }
        speechFrames++
        quietFrames = if (level >= speechLevel) 0 else quietFrames + 1
        return if (quietFrames >= endSilenceFrames || speechFrames >= maxClipFrames) {
            reset()
            Step.TurnEnded
        } else Step.Listening(true)
    }

    fun reset() { idleFrames = 0; speechFrames = 0; quietFrames = 0 }

    private fun frames(seconds: Double) = maxOf(1, (seconds * sampleRate / frameSamples).roundToInt())

    companion object {
        /** One 0.1 s frame of 16 kHz mono PCM. */
        const val FRAME_SAMPLES = 1_600

        // shortcut: one energy threshold for every phone and room; calibrate it against the live
        // device check if a quiet learner is missed or a noisy room never ends a turn.
        /** Frame energy, 0.0..1.0, above which the learner counts as speaking. */
        const val SPEECH_LEVEL = 0.02
        const val END_SILENCE_SECONDS = 0.8
        /** The reference app's cap for one audio clip handed to a model. */
        const val MAX_CLIP_SECONDS = 30.0
        const val IDLE_WINDOW_SECONDS = 5.0

        /** The root-mean-square energy of the [read] samples the recorder delivered, 0.0..1.0. */
        fun level(samples: ShortArray, read: Int): Double {
            if (read <= 0) return 0.0
            var sum = 0.0
            for (index in 0 until minOf(read, samples.size)) {
                val value = samples[index].toDouble() / Short.MAX_VALUE
                sum += value * value
            }
            return sqrt(sum / read)
        }
    }
}
