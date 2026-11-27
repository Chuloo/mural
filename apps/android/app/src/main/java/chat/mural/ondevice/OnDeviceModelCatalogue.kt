package chat.mural.ondevice

import androidx.annotation.StringRes
import chat.mural.R
import java.net.URI

/** The Model Runtime that executes a catalogued model (GLOSSARY.md). AICore needs no download. */
enum class ModelRuntimeKind { LITERT_LM, AICORE }

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

    /** The conversation models, best quality first. One of them is the active model globally. */
    val conversation: List<CataloguedModel> = listOf(GEMMA_4_E4B, GEMMA_4_E2B, QWEN2_5_1_5B)

    fun recommended(): CataloguedModel = conversation.first { it.recommended }
    fun byID(id: String?): CataloguedModel? = conversation.firstOrNull { it.id == id }

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
