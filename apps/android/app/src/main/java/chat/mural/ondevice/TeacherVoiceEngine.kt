package chat.mural.ondevice

/**
 * The teacher's side of an On-Device Model voice conversation: the phone's own text-to-speech, or an
 * open-weight voice model as its opt-in upgrade. The choice is per Language Module and is this
 * phone's state, kept beside the model metadata — it is never part of the learning records or a
 * backup.
 */
enum class TeacherVoiceEngine(val supportsGpu: Boolean) {
    /** The phone's built-in text-to-speech: the default, and nothing to download. */
    PLATFORM(false),

    /** Kokoro 82M: an open-weight voice that runs on the GPU when the phone has one, else the CPU. */
    KOKORO_82M(true),

    /** PocketTTS 100M: an open-weight voice the CPU alone runs. */
    POCKET_TTS_100M(false),
}

/**
 * One open-weight voice for one Language Module: the packaged voice id the model synthesizes with,
 * and the BCP-47 tag it routes the sentence by.
 */
data class TeacherVoice(val voiceID: String, val locale: String)

/**
 * Which voice an On-Device Model conversation's teacher speaks with. A voice whose model is not on
 * this phone, or that has no packaged voice for the active Language Module, resolves to the phone's
 * built-in text-to-speech, so an opt-in upgrade never blocks a conversation — and a sentence the
 * chosen voice cannot speak falls back mid-conversation inside [OpenWeightSpeechOutput].
 */
object TeacherVoiceSelector {
    /**
     * Kokoro's packaged voices by language letter: `af_heart` speaks `en-US`, `ef_dora` `es-ES`.
     * Both Portuguese modules get the Brazilian voice, because no open-weight voice speaks `pt-PT`.
     */
    private val KOKORO = mapOf(
        "en" to TeacherVoice("af_heart", "en-US"),
        "es" to TeacherVoice("ef_dora", "es-ES"),
        "fr" to TeacherVoice("ff_siwis", "fr-FR"),
        "it" to TeacherVoice("if_sara", "it-IT"),
        "pt" to TeacherVoice("pf_dora", "pt-BR"),
        "ja" to TeacherVoice("jf_alpha", "ja-JP"),
        "zh" to TeacherVoice("zf_xiaoxiao", "zh-CN"))

    /** PocketTTS's packaged voices: each one carries its own language's weights and voice cache. */
    private val POCKET_TTS = mapOf(
        "en" to TeacherVoice("alba", "en-US"),
        "fr" to TeacherVoice("estelle", "fr-FR"),
        "de" to TeacherVoice("juergen", "de-DE"),
        "es" to TeacherVoice("lola", "es-ES"),
        "it" to TeacherVoice("giovanni", "it-IT"),
        "pt" to TeacherVoice("rafael", "pt-BR"),
        "nl" to TeacherVoice("daan", "nl-NL"))

    /** The voice an engine speaks a Language Module's locale with, or null when it has none for it. */
    fun voice(engine: TeacherVoiceEngine, locale: String): TeacherVoice? = when (engine) {
        // The phone's own voice takes the module's locale itself, so Mural picks no voice id for it.
        TeacherVoiceEngine.PLATFORM -> null
        TeacherVoiceEngine.KOKORO_82M -> KOKORO[language(locale)]
        TeacherVoiceEngine.POCKET_TTS_100M -> POCKET_TTS[language(locale)]
    }

    /** The engine a conversation speaks with: the learner's choice, or the phone's built-in voice. */
    fun resolve(chosen: TeacherVoiceEngine, voiceModelOnPhone: Boolean, locale: String): TeacherVoiceEngine =
        if (voiceModelOnPhone && voice(chosen, locale) != null) chosen else TeacherVoiceEngine.PLATFORM

    /**
     * True when an open-weight voice speaks another variety of a Language Module's language than the
     * module does — today only Portuguese (Portugal), whose open-weight voices are Brazilian. The
     * picker says so honestly instead of promising a European voice that does not exist.
     */
    fun speaksAnotherVariety(moduleLocale: String): Boolean {
        val variety = region(moduleLocale)
        if (variety.length != REGION_LENGTH) return false
        return TeacherVoiceEngine.entries.any { engine ->
            val spoken = voice(engine, moduleLocale)?.let { region(it.locale) } ?: return@any false
            !variety.equals(spoken, ignoreCase = true)
        }
    }

    private fun language(locale: String) = locale.substringBefore('-')

    private fun region(locale: String) = locale.substringAfter('-', "")

    private const val REGION_LENGTH = 2
}
