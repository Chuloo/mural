package chat.mural.ondevice

/**
 * The teacher's voice of an On-Device Model conversation. Sentences arrive as the model streams
 * them, so a sentence is queued behind the one before it. [speaking] and [level] drive MuralOrb and
 * keep the microphone off while the teacher is talking. Open-weight voices (Kokoro, PocketTTS)
 * implement this same interface, with the platform voice as their fallback.
 */
interface OnDeviceSpeechOutput {
    /** True while a queued sentence is still being spoken. */
    val speaking: Boolean

    /** Output energy, 0.0..1.0, for the orb. */
    val level: Double

    /** Speaks one sentence in [locale]; [queueAdd] appends it instead of replacing what is playing. */
    fun speak(sentence: String, locale: String, queueAdd: Boolean)

    /** Stops the current speech at once. */
    fun stop()

    /** Releases the engine. Nothing may be spoken afterwards. */
    fun close()
}
