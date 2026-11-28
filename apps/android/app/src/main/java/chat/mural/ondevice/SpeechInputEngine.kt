package chat.mural.ondevice

/**
 * The learner's side of an On-Device Model voice conversation: the active model's own native audio
 * input, or a dedicated speech recognition model as its alternate. The choice is this phone's state,
 * kept beside the model metadata — it is never part of the learning records or a backup.
 */
enum class SpeechInputEngine {
    /** The active model hears the recorded clip itself: the default, and nothing to download. */
    NATIVE_AUDIO,

    /** A downloaded speech recognition model transcribes the clip, and the model reads the text. */
    PARAKEET,
}

/**
 * Which microphone an On-Device Model voice conversation gets. An alternate that is not on this
 * phone, or whose published coverage does not hear the active Language Module's language, resolves
 * to the native audio input — so a missing, deleted or never-downloaded speech recognition model
 * never blocks a conversation the active model could otherwise hold, and a learner who picked
 * Parakeet for European Portuguese and then switches to Mandarin or Japanese is never fed to a
 * European-only transcriber. A conversation model with no native audio input of its own can still
 * hear the learner through the alternate. Null means this phone cannot hear the learner at all: the
 * caller owes them an honest notice and a typed conversation, never a silent voice session.
 */
object OnDeviceSpeechInputSelector {
    fun resolve(chosen: SpeechInputEngine, speechModelOnPhone: Boolean,
        activeModelHearsAudio: Boolean, locale: String): SpeechInputEngine? = when {
        chosen == SpeechInputEngine.PARAKEET && speechModelOnPhone &&
            hearsLanguage(OnDeviceModelCatalogue.PARAKEET_TDT_0_6B.languages, locale) -> SpeechInputEngine.PARAKEET
        activeModelHearsAudio -> SpeechInputEngine.NATIVE_AUDIO
        else -> null
    }

    /**
     * True when a speech recognition model's published BCP-47 coverage hears a Language Module's
     * locale: the exact tag, or — for a module with no variety of its own (`en`) — any tag of the
     * same language. Variety-sensitive, so European Portuguese (`pt-PT`) is covered and Brazilian
     * (`pt-BR`) is not, exactly as the catalogue publishes it; neither Mandarin (`zh-CN`) nor
     * Japanese (`ja-JP`) is covered by a European-only model.
     */
    private fun hearsLanguage(languages: List<String>, locale: String): Boolean =
        languages.contains(locale) ||
            (locale.indexOf('-') < 0 && languages.any { it.substringBefore('-') == locale })
}
