package chat.mural.network

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class EndpointProtocol { CHAT_COMPLETIONS, RESPONSES }

/** A learner-owned OpenAI-compatible server. Voice runs turn by turn through its audio endpoints. */
@Serializable
data class CustomEndpoint(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val protocol: EndpointProtocol = EndpointProtocol.CHAT_COMPLETIONS,
    val model: String = "",
    val transcriptionModel: String = "",
    val speechModel: String = "",
    val voice: String = "",
    /** Asks reasoning models (e.g. Qwen on vLLM) to answer directly; voice turns can otherwise take half a minute. */
    val skipThinking: Boolean = false,
) {
    val url: HttpUrl? get() = parseBaseUrl(baseUrl)
    val textReady get() = url != null && model.isNotBlank()
    val voiceReady get() = textReady && transcriptionModel.isNotBlank() && speechModel.isNotBlank() && voice.isNotBlank()
    /** A saved URL must be valid, an enabled endpoint must at least be able to chat, and a key needs a URL
     * (a key saved alone would be hidden from the Remove action). */
    fun canSave(keyEntered: Boolean) = (baseUrl.isEmpty() || url != null) && (!enabled || textReady) && (!keyEntered || baseUrl.isNotEmpty())

    fun trimmed() = copy(baseUrl = baseUrl.trim(), model = model.trim(), transcriptionModel = transcriptionModel.trim(),
        speechModel = speechModel.trim(), voice = voice.trim())

    fun target(key: String?) = EndpointTarget(url ?: throw APIClient.APIException.MissingKey, key, protocol, model.trim(),
        transcriptionModel.trim(), speechModel.trim(), voice.trim(), skipThinking, custom = true)

    companion object {
        /** HTTPS only, without credentials, query or fragment. Always ends in '/' so API paths resolve below it. */
        fun parseBaseUrl(value: String): HttpUrl? {
            val url = value.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        }
    }
}

/** Where one personal request goes. Never logged: it carries the key. */
data class EndpointTarget(
    val baseUrl: HttpUrl,
    val key: String?,
    val protocol: EndpointProtocol,
    val model: String,
    val transcriptionModel: String = "",
    val speechModel: String = "",
    val voice: String = "",
    val skipThinking: Boolean = false,
    val custom: Boolean = false,
) {
    override fun toString() = "EndpointTarget([redacted])"
}

/** Settings live in app-private preferences (excluded from backup); the key uses its own Keystore alias. */
class CustomEndpointStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mural_custom_endpoint", Context.MODE_PRIVATE)
    val credentials = CredentialStore(context, "mural_custom_endpoint_credentials", "chat.mural.custom-endpoint.aes") {
        it.isNotEmpty() && it.length <= 500 && it.none(Char::isWhitespace)
    }

    fun read(): CustomEndpoint = preferences.getString(CONFIG, null)
        ?.let { runCatching { JSON.decodeFromString<CustomEndpoint>(it) }.getOrNull() } ?: CustomEndpoint()

    fun save(endpoint: CustomEndpoint) = check(preferences.edit().putString(CONFIG, JSON.encodeToString(endpoint)).commit())

    fun delete() {
        credentials.delete()
        check(preferences.edit().clear().commit())
    }

    private companion object {
        const val CONFIG = "config"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
