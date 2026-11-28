package chat.mural.ondevice

import chat.mural.core.nowSeconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One imported On-Device Model file, copied into app storage that device backups skip. */
@Serializable
data class OnDeviceModel(val name: String, val sizeBytes: Long, val importedAt: Double = nowSeconds())

/**
 * Imported On-Device Model metadata, the one active model, the learner's speech-input engine choice
 * and the teacher voice of each Language Module, persisted as JSON beside the learning records: a
 * model choice is this phone's state and never exported learning content.
 */
class OnDeviceModelStore(initial: String? = null, private val save: (String) -> Unit = {}) {
    @Serializable private data class Stored(val models: List<OnDeviceModel> = emptyList(), val active: String? = null,
        val speechInput: String = SpeechInputEngine.NATIVE_AUDIO.name,
        val teacherVoices: Map<String, String> = emptyMap())

    private var stored = runCatching { json.decodeFromString<Stored>(initial.orEmpty()) }.getOrNull() ?: Stored()

    val models get() = stored.models
    fun active() = stored.models.firstOrNull { it.name == stored.active }

    /** The active model's identity: an imported file name, or the system model's catalogue id. */
    fun activeID(): String? = stored.active

    /**
     * The zero-download system model has no file to import, so its catalogue id alone becomes the
     * active model. Choosing it is as explicit as choosing a downloaded one.
     */
    fun selectSystem(id: String) {
        stored = stored.copy(active = id)
        persist()
    }

    /**
     * A newly imported model becomes the active one; re-importing a name replaces its metadata. A
     * speech recognition model is recorded with [makeActive] false: it lands on this phone beside
     * the active conversation model and never replaces it.
     */
    fun import(displayName: String?, sizeBytes: Long, makeActive: Boolean = true): OnDeviceModel {
        val model = OnDeviceModel(fileName(displayName), sizeBytes)
        stored = stored.copy(models = stored.models.filterNot { it.name == model.name } + model,
            active = if (makeActive) model.name else stored.active)
        persist()
        return model
    }

    /** Only an already imported model can become active; an unknown name changes nothing. */
    fun select(name: String): Boolean {
        if (stored.models.none { it.name == name }) return false
        stored = stored.copy(active = name)
        persist()
        return true
    }

    /**
     * Drops a model's metadata to reclaim its storage. Deleting the active model leaves no active
     * model: the learner picks the next one explicitly, as every provider choice is explicit.
     */
    fun delete(name: String): Boolean {
        if (stored.models.none { it.name == name }) return false
        stored = stored.copy(models = stored.models.filterNot { it.name == name },
            active = stored.active?.takeIf { it != name })
        persist()
        return true
    }

    /** The learner's speech-input engine. A record from before the choice existed keeps the default. */
    fun speechInput(): SpeechInputEngine =
        runCatching { SpeechInputEngine.valueOf(stored.speechInput) }.getOrDefault(SpeechInputEngine.NATIVE_AUDIO)

    /** Chooses the learner's speech input; the native audio input is the default and always stays available. */
    fun selectSpeechInput(engine: SpeechInputEngine) {
        stored = stored.copy(speechInput = engine.name)
        persist()
    }

    /**
     * The teacher voice of one Language Module. A record from before the choice existed, and a voice
     * engine this build does not know, both keep the phone's built-in voice.
     */
    fun teacherVoice(languageID: String): TeacherVoiceEngine = voiceEngine(stored.teacherVoices[languageID].orEmpty())

    /** Every Language Module the learner picked an open-weight voice for, by its language id. */
    fun teacherVoices(): Map<String, TeacherVoiceEngine> = stored.teacherVoices
        .mapValues { (_, name) -> voiceEngine(name) }
        .filterValues { it != TeacherVoiceEngine.PLATFORM }

    /** Chooses a Language Module's teacher voice; the platform voice is the default and always stays available. */
    fun selectTeacherVoice(languageID: String, engine: TeacherVoiceEngine) {
        stored = stored.copy(teacherVoices = stored.teacherVoices + (languageID to engine.name))
        persist()
    }

    /**
     * Drops every choice of a deleted voice model, so those languages speak with the phone's built-in
     * voice again instead of pointing at a file that is gone.
     */
    fun clearTeacherVoice(engine: TeacherVoiceEngine) {
        stored = stored.copy(teacherVoices = stored.teacherVoices.filterValues { it != engine.name })
        persist()
    }

    private fun voiceEngine(name: String) =
        runCatching { TeacherVoiceEngine.valueOf(name) }.getOrDefault(TeacherVoiceEngine.PLATFORM)

    private fun persist() = save(json.encodeToString(Stored.serializer(), stored))

    companion object {
        private const val EXTENSION = ".litertlm"
        private const val MAXIMUM_NAME_LENGTH = 104
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** A document-picker name is untrusted: one safe file name for the models directory, or a refusal. */
        fun fileName(displayName: String?): String {
            val name = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it != '\u007f' }.trim()
            if (!name.endsWith(EXTENSION, ignoreCase = true) || name.length <= EXTENSION.length)
                throw ModelRuntimeException("Not a $EXTENSION model file: ${displayName.orEmpty().take(80)}")
            return if (name.length <= MAXIMUM_NAME_LENGTH) name
            else name.take(MAXIMUM_NAME_LENGTH - EXTENSION.length) + EXTENSION
        }
    }
}
