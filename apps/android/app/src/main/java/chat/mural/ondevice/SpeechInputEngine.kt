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
 * phone resolves to the native audio input, so a missing, deleted or never-downloaded speech
 * recognition model never blocks a conversation the active model could otherwise hold — and a
 * conversation model with no native audio input of its own can hear the learner through the
 * alternate. Null means this phone cannot hear the learner at all: the caller owes them an honest
 * notice and a typed conversation, never a silent voice session.
 */
object OnDeviceSpeechInputSelector {
    fun resolve(chosen: SpeechInputEngine, speechModelOnPhone: Boolean,
        activeModelHearsAudio: Boolean): SpeechInputEngine? = when {
        chosen == SpeechInputEngine.PARAKEET && speechModelOnPhone -> SpeechInputEngine.PARAKEET
        activeModelHearsAudio -> SpeechInputEngine.NATIVE_AUDIO
        else -> null
    }
}
