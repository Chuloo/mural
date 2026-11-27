package chat.mural.ondevice

/**
 * One finished learner turn of an On-Device Model conversation. Native audio input hands the model
 * the clip it hears; a dedicated speech recognition model hands over its transcript instead. Both
 * are the same seam, so the live conversation never knows which one produced a turn.
 */
sealed interface SpeechTurn {
    /** How long the learner spoke, in milliseconds: the caption's window on the session timeline. */
    val durationMS: Int

    /** The learner's speech as 16 kHz mono 16-bit PCM in a WAV wrapper, for the model to hear. */
    class AudioWav(override val durationMS: Int, val wavBytes: ByteArray) : SpeechTurn

    /** The learner's speech as text, from a speech recognition model. */
    data class Transcript(override val durationMS: Int, val text: String) : SpeechTurn
}

/**
 * The learner's side of an On-Device Model conversation: a microphone that yields one turn at a
 * time. [level] drives MuralOrb, and [mute] drops what the microphone hears — nothing is kept while
 * the learner is muted or the teacher is speaking.
 */
interface OnDeviceSpeechInput {
    /** Microphone energy, 0.0..1.0, for the orb. */
    val level: Double

    /** Waits for the learner's next turn, or returns null when the input is closed or unusable. */
    suspend fun awaitTurn(): SpeechTurn?

    /** Discards what the microphone hears while muted. */
    fun mute(muted: Boolean)

    /** Releases the microphone. Nothing may be recorded afterwards. */
    fun close()
}
