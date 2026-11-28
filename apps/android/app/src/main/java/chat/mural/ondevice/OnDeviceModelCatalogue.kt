package chat.mural.ondevice

import androidx.annotation.StringRes
import chat.mural.R
import java.net.URI

/**
 * The Model Runtime that executes a catalogued model (GLOSSARY.md). AICore needs no download, and
 * MediaPipe Tasks Audio runs the speech recognition and voice models that are not conversation
 * models at all.
 */
enum class ModelRuntimeKind { LITERT_LM, AICORE, MEDIAPIPE_AUDIO }

/**
 * One curated On-Device Model in the catalogue. [downloadURL] and [fileName] are null for a
 * Model Runtime that downloads nothing itself (AICore); a model Mural has not curated yet
 * (speech input, voices) is another list on [OnDeviceModelCatalogue], not another shape.
 */
data class CataloguedModel(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val runtime: ModelRuntimeKind = ModelRuntimeKind.LITERT_LM,
    val fileName: String? = null,
    val downloadURL: String? = null,
    val sizeBytes: Long = 0,
    val supportsAudioInput: Boolean = false,
    val minDeviceMemoryGb: Int = 0,
    val recommended: Boolean = false,
    /** The BCP-47 coverage of a speech recognition or voice model; empty for a conversation model. */
    val languages: List<String> = emptyList(),
)

/**
 * The curated catalogue: un-gated `litert-community` files that download anonymously, so a
 * learner never meets a Hugging Face sign-in or a licence click. Sizes and commit hashes are
 * pinned; the app never probes a URL for its size, because a probe counts as a download.
 */
object OnDeviceModelCatalogue {
    val GEMMA_4_E4B = litertLM("gemma-4-e4b", R.string.settings_on_device_model_gemma_4_e4b,
        R.string.settings_on_device_model_gemma_4_e4b_description,
        "litert-community/gemma-4-E4B-it-litert-lm", "28299f30ee4d43294517a4ac93abd6163412f07f",
        "gemma-4-E4B-it.litertlm", 3_659_530_240L, supportsAudioInput = true, minDeviceMemoryGb = 12,
        recommended = true)

    val GEMMA_4_E2B = litertLM("gemma-4-e2b", R.string.settings_on_device_model_gemma_4_e2b,
        R.string.settings_on_device_model_gemma_4_e2b_description,
        "litert-community/gemma-4-E2B-it-litert-lm", "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        "gemma-4-E2B-it.litertlm", 2_588_147_712L, supportsAudioInput = true, minDeviceMemoryGb = 8)

    val QWEN2_5_1_5B = litertLM("qwen2.5-1.5b", R.string.settings_on_device_model_qwen2_5_1_5b,
        R.string.settings_on_device_model_qwen2_5_1_5b_description,
        "litert-community/Qwen2.5-1.5B-Instruct", "5e6e7a58d03777898b31330a15d2e0a5a9107de3",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", 1_597_922_856L,
        supportsAudioInput = false, minDeviceMemoryGb = 6)

    /**
     * The zero-download system model: Gemini Nano, served by the AICore Model Runtime. It has no
     * file, no size and no delete button — the system service manages it — and the picker offers it
     * only on a phone whose device probe reports AICore available (Android 13 / API 33 and up).
     */
    val GEMINI_NANO_AICORE = CataloguedModel("aicore-gemini-nano", R.string.settings_on_device_model_gemini_nano,
        R.string.settings_on_device_model_gemini_nano_description, ModelRuntimeKind.AICORE)

    /** The conversation models, best quality first. One of them is the active model globally. */
    val conversation: List<CataloguedModel> = listOf(GEMMA_4_E4B, GEMMA_4_E2B, QWEN2_5_1_5B)

    private const val PARAKEET_FILE = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"

    /** Parakeet TDT 0.6B's published coverage: 25 European languages, `pt-PT` and not `pt-BR`. */
    private val PARAKEET_LANGUAGES = listOf("bg-BG", "hr-HR", "cs-CZ", "da-DK", "nl-NL", "en-US",
        "et-EE", "fi-FI", "fr-FR", "de-DE", "el-GR", "hu-HU", "it-IT", "lv-LV", "lt-LT", "mt-MT",
        "pl-PL", "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sl-SI", "es-ES", "sv-SE", "uk-UA")

    /**
     * The dedicated speech recognition alternate, run by MediaPipe Tasks Audio instead of a
     * conversation Model Runtime: the escape hatch for a Language Module whose native audio input
     * disappoints. It covers European Portuguese, which no open-weight voice does on the output
     * side. Downloaded and deleted through the same catalogue experience, and never the active model.
     */
    val PARAKEET_TDT_0_6B = CataloguedModel("parakeet-tdt-0.6b",
        R.string.settings_on_device_model_parakeet, R.string.settings_on_device_model_parakeet_description,
        ModelRuntimeKind.MEDIAPIPE_AUDIO, PARAKEET_FILE,
        OnDeviceModelURLs.repository("litert-community/parakeet-tdt-0.6b-v3",
            "3ce205aa928f443f423214195cfe83ebe4802826", PARAKEET_FILE),
        614_588_752L, minDeviceMemoryGb = 4, languages = PARAKEET_LANGUAGES)

    /** The speech recognition alternates. */
    val speechRecognition: List<CataloguedModel> = listOf(PARAKEET_TDT_0_6B)

    private const val KOKORO_FILE = "kokoro_int8_gpu_fp16.litertlm"
    private const val POCKET_TTS_FILE = "pocket_tts_int8.litertlm"

    /** Kokoro 82M's packaged coverage: nine languages, `pt-BR` and not `pt-PT`. */
    private val KOKORO_LANGUAGES = listOf("en-US", "en-GB", "es-ES", "fr-FR", "hi-IN", "it-IT",
        "pt-BR", "ja-JP", "zh-CN")

    /** PocketTTS 100M's packaged coverage: seven languages, and the only German and Dutch voices. */
    private val POCKET_TTS_LANGUAGES = listOf("en-US", "fr-FR", "de-DE", "es-ES", "it-IT", "pt-BR", "nl-NL")

    /**
     * The opt-in open-weight teacher voices, run by MediaPipe Tasks Audio like the speech recognition
     * alternate: downloaded and deleted through the same catalogue experience, chosen per Language
     * Module, and never the active model. Both speak Brazilian Portuguese, so the picker says so to a
     * Portugal learner instead of promising a European voice that does not exist.
     */
    val KOKORO_82M = CataloguedModel("kokoro-82m", R.string.settings_on_device_model_kokoro,
        R.string.settings_on_device_model_kokoro_description, ModelRuntimeKind.MEDIAPIPE_AUDIO, KOKORO_FILE,
        OnDeviceModelURLs.repository("litert-community/Kokoro-82M",
            "81e43a025bccabcbbd487f08bc3ebd65b5fc7295", KOKORO_FILE),
        294_750_530L, minDeviceMemoryGb = 4, languages = KOKORO_LANGUAGES)

    val POCKET_TTS_100M = CataloguedModel("pocket-tts-100m", R.string.settings_on_device_model_pocket_tts,
        R.string.settings_on_device_model_pocket_tts_description, ModelRuntimeKind.MEDIAPIPE_AUDIO,
        POCKET_TTS_FILE,
        OnDeviceModelURLs.repository("litert-community/pocket-tts",
            "bca93d6756489f9403f94d66826cfa65f80023a1", POCKET_TTS_FILE),
        760_061_960L, minDeviceMemoryGb = 4, languages = POCKET_TTS_LANGUAGES)

    /** The teacher voices. The phone's built-in voice stays the default and downloads nothing. */
    val voiceSynthesis: List<CataloguedModel> = listOf(KOKORO_82M, POCKET_TTS_100M)

    /** Every curated model that is never the active conversation model: speech recognition and voices. */
    val support: List<CataloguedModel> = speechRecognition + voiceSynthesis

    /** The curated voice model a teacher voice engine runs on; the platform voice has none. */
    fun voiceModel(engine: TeacherVoiceEngine): CataloguedModel? = when (engine) {
        TeacherVoiceEngine.PLATFORM -> null
        TeacherVoiceEngine.KOKORO_82M -> KOKORO_82M
        TeacherVoiceEngine.POCKET_TTS_100M -> POCKET_TTS_100M
    }

    private val all: List<CataloguedModel> = conversation + GEMINI_NANO_AICORE + support

    fun recommended(): CataloguedModel = conversation.first { it.recommended }
    /** A catalogue id, whether it downloads a file or is the zero-download system model. */
    fun byID(id: String?): CataloguedModel? = all.firstOrNull { it.id == id }

    private fun litertLM(id: String, @StringRes title: Int, @StringRes description: Int, repositoryID: String,
        commitHash: String, fileName: String, sizeBytes: Long, supportsAudioInput: Boolean,
        minDeviceMemoryGb: Int, recommended: Boolean = false) = CataloguedModel(id, title, description,
        ModelRuntimeKind.LITERT_LM, fileName, OnDeviceModelURLs.repository(repositoryID, commitHash, fileName),
        sizeBytes, supportsAudioInput, minDeviceMemoryGb, recommended)
}

/** The download links of the catalogue and of a learner-pasted link, normalized to one shape. */
object OnDeviceModelURLs {
    private const val EXTENSION = ".litertlm"
    private const val HUGGING_FACE = "https://huggingface.co"

    /** The direct file URL of a repository revision: redirects to a short-lived signed CDN URL. */
    fun repository(repositoryID: String, commitHash: String, fileName: String) =
        "$HUGGING_FACE/$repositoryID/resolve/$commitHash/$fileName?download=true"

    /**
     * One direct HTTPS `.litertlm` URL from a pasted link, or a refusal: the manifest turns
     * cleartext off, so an `http://` link could never be fetched anyway. A Hugging Face `/blob/`
     * page is HTML, so it is rewritten to the `/resolve/` file. Credentials and fragments are dropped.
     */
    fun normalize(pasted: String?): String {
        val link = pasted.orEmpty().trim()
        val uri = runCatching { URI(link) }.getOrNull() ?: throw refused(link)
        if (!"https".equals(uri.scheme, ignoreCase = true)) throw refused(link)
        val host = uri.host?.takeIf { it.contains('.') } ?: throw refused(link)
        val path = uri.path.orEmpty().replace("/blob/", "/resolve/")
        // The same rule the document picker meets: a `.litertlm` file name, or a refusal.
        OnDeviceModelStore.fileName(path.substringAfterLast('/'))
        val query = uri.rawQuery.orEmpty()
        return "https://$host${if (uri.port > 0) ":${uri.port}" else ""}$path?" +
            when {
                query.isEmpty() || query.contains("download=true") -> query.ifEmpty { "download=true" }
                else -> "$query&download=true"
            }
    }

    /** The file name a URL is stored under, through the picker's one safe-name rule. */
    fun fileName(url: String): String =
        OnDeviceModelStore.fileName(runCatching { URI(url).path.orEmpty() }.getOrNull().orEmpty().substringAfterLast('/'))

    private fun refused(link: String) =
        ModelRuntimeException("Not a direct HTTPS $EXTENSION link: ${link.take(120)}")
}
