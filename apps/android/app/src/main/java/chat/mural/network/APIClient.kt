package chat.mural.network

import chat.mural.core.SourceLink
import chat.mural.core.ProviderFailureKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class APIUsage(val input: Int = 0, val output: Int = 0, val searches: Int = 0)
data class APIResult(
    val text: String,
    val sources: List<SourceLink>,
    val usage: APIUsage,
    val searchEntryPointHTML: String? = null,
)

class APIClient private constructor(
    private val readCredential: (AIProvider) -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = API_BASE_URL,
    private val googleBaseUrl: HttpUrl = GOOGLE_API_BASE_URL,
) : TeachingClient, LiveSessionProvider {
    constructor(credentials: CredentialStore) : this({ provider -> credentials.read(provider) })

    var provider: AIProvider = AIProvider.OPENAI

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl) :
        this({ key }, client, baseUrl)

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl, googleBaseUrl: HttpUrl) :
        this({ key }, client, baseUrl, googleBaseUrl)

    override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
        val result = post("live/sessions", buildJsonObject {
            put("session", buildJsonObject {
                put("model", "gpt-live-1"); put("instructions", request.instructions); put("input", request.history)
                put("store", false)
                put("delegation", buildJsonObject { put("type", "client") })
                put("audio", buildJsonObject { put("output", buildJsonObject { put("voice", "marin") }) })
            })
            put("transport", buildJsonObject { put("type", "webrtc"); put("sdp", request.sdp) })
        })
        val transport = result["transport"] as? JsonObject ?: throw APIException.InvalidResponse
        val answer = (transport["sdp"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (transport["type"] != JsonPrimitive("webrtc") || answer.isNullOrBlank()) throw APIException.InvalidResponse
        val id = ((result["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return LiveSessionConnection(answer, id)
    }

    suspend fun post(path: String, body: JsonObject): JsonObject {
        if (!VALID_PATH.matches(path) || path.contains("..") || path.startsWith('/')) {
            throw APIException.InvalidResponse
        }
        val key = readCredential(AIProvider.OPENAI) ?: throw APIException.MissingKey
        return directPost(baseUrl.newBuilder().addPathSegments(path).build(), key, body, "OpenAI")
    }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
        if (provider == AIProvider.GOOGLE_AI_STUDIO) {
            return geminiRespond(instructions, input, schema, search)
        }
        val body = responseBody(instructions, input, schema, search)

        val response = post("responses", body)
        return decodeTeachingResponse(response)
    }

    private fun responseBody(instructions: String, input: String, schema: JsonObject?, search: Boolean): JsonObject {
        return buildJsonObject {
            put("model", "gpt-5.6-luna")
            put("store", false)
            put("instructions", instructions)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", input)
                })
            })
            put("max_output_tokens", if (schema == null) 1_400 else 2_200)
            put("reasoning", buildJsonObject { put("effort", "low") })
            if (schema != null) {
                put("text", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("name", "mural_result")
                        put("strict", true)
                        put("schema", schema)
                    })
                })
            }
            if (search) {
                put("tools", buildJsonArray { add(buildJsonObject { put("type", "web_search") }) })
                put("tool_choice", "auto")
                put("max_tool_calls", 1)
            }
        }
    }
    override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
        val key = readCredential(AIProvider.OPENAI) ?: throw APIException.MissingKey
        val body = buildJsonObject {
            responseBody(instructions, input, null, false).forEach { (key, value) -> put(key, value) }
            put("stream", true)
        }
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegments("responses").build())
            .header("Authorization", "Bearer $key").header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val callbacks = currentCoroutineContext().minusKey(Job)
        val result = streamingResponse(client, request) { response ->
            if (!response.isSuccessful) {
                val code = runCatching { JSON.parseToJsonElement(response.peekBody(16_384).string()).jsonObject["error"]
                    ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw APIException.Http(response.code, code, response.header("x-request-id"))
            }
            if (response.header("Content-Type")?.startsWith("text/event-stream", ignoreCase = true) != true) throw APIException.InvalidResponse
            var text = ""
            readTextEvents(response) { event ->
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "response.output_text.delta" -> {
                        text += event["delta"]?.jsonPrimitive?.contentOrNull ?: throw APIException.InvalidResponse
                        if (text.toByteArray(Charsets.UTF_8).size > 65_536) throw APIException.InvalidResponse
                        withContext(callbacks) { onText(text) }; null
                    }
                    "response.completed" -> event["response"] as? JsonObject ?: throw APIException.Incomplete
                    "response.refusal.delta", "response.refusal.done" -> throw APIException.Refused
                    "error", "response.failed", "response.incomplete" -> throw APIException.Incomplete
                    else -> null
                }
            }
        }
        return decodeTeachingResponse(result)
    }

    private suspend fun geminiRespond(instructions: String, input: String, schema: JsonObject?, search: Boolean): APIResult {
        val key = readCredential(AIProvider.GOOGLE_AI_STUDIO) ?: throw APIException.MissingKey
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", instructions) }) }) })
            put("contents", buildJsonArray { add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", input) }) })
            }) })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", if (schema == null) 1_400 else 2_200)
                if (schema != null) {
                    put("responseMimeType", "application/json")
                    put("responseJsonSchema", geminiCompatibleSchema(schema))
                }
            })
            if (search) put("tools", buildJsonArray { add(buildJsonObject { put("googleSearch", buildJsonObject { }) }) })
        }
        val path = "models/${AIProvider.GOOGLE_AI_STUDIO.helperModel}:generateContent"
        val response = directPost(googleBaseUrl.newBuilder().addPathSegments(path).build(), key, body, "Google AI Studio", google = true)
        val candidate = response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw APIException.InvalidResponse
        if (candidate["finishReason"]?.jsonPrimitive?.contentOrNull != "STOP") throw APIException.Incomplete
        val text = buildString {
            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())
            for (part in parts) append(part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        if (text.isEmpty()) throw APIException.Incomplete
        val grounding = candidate["groundingMetadata"]?.jsonObject
        val sources = linkedMapOf<String, SourceLink>()
        val chunks = grounding?.get("groundingChunks")?.jsonArray ?: JsonArray(emptyList())
        for (chunk in chunks) {
            val web = chunk.jsonObject["web"]?.jsonObject ?: continue
            val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: continue
            if (SourceLink("Source", url).safeUrl() != null) sources.putIfAbsent(url, SourceLink(web["title"]?.jsonPrimitive?.contentOrNull ?: "Source", url))
        }
        val searchQueries = grounding?.get("webSearchQueries")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            ?.size ?: 0
        val searchEntryPointHtml = grounding?.get("searchEntryPoint")?.jsonObject
            ?.get("renderedContent")?.jsonPrimitive?.contentOrNull?.take(32_000)
        val usage = response["usageMetadata"]?.jsonObject
        return APIResult(
            text = text,
            sources = sources.values.toList(),
            searchEntryPointHTML = searchEntryPointHtml,
            usage = APIUsage(
                input = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
                output = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
                searches = if (search) searchQueries.takeIf { it > 0 } ?: if (sources.isNotEmpty()) 1 else 0 else 0,
            ),
        )
    }

    private fun geminiCompatibleSchema(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value
            .filterKeys { it != "additionalProperties" }
            .mapValues { (_, nested) -> geminiCompatibleSchema(nested) })
        is JsonArray -> JsonArray(value.map(::geminiCompatibleSchema))
        else -> value
    }

    private suspend fun directPost(url: HttpUrl, key: String, body: JsonObject, providerName: String, google: Boolean = false): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (google) builder.header("x-goog-api-key", key) else builder.header("Authorization", "Bearer $key")
        val request = builder.build()
        // Parse on OkHttp's worker while the continuation remains cancellable.
        // Cancellation closes a response even if the peer stalls halfway through its body.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code !in 200..299) {
                                val errorCode = runCatching {
                                    val payload = it.peekBody(16_385).string()
                                    if (payload.toByteArray(Charsets.UTF_8).size > 16_384) null else
                                        (JSON.parseToJsonElement(payload).jsonObject["error"] as? JsonObject)
                                            ?.get("code")?.jsonPrimitive?.contentOrNull
                                }.getOrNull()
                                throw APIException.Http(it.code, errorCode, it.header("x-request-id"), providerName)
                            }
                            val payload = it.readBoundedBody()
                            try { JSON.parseToJsonElement(payload).jsonObject }
                            catch (_: Exception) { throw APIException.InvalidResponse }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private fun Response.readBoundedBody(): String {
        val responseBody = body ?: throw APIException.InvalidResponse
        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        val source = responseBody.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - total))
            if (count == -1L) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        }
        return buffer.readString(Charsets.UTF_8)
    }

    sealed class APIException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        data object MissingKey : APIException("Add your selected AI provider key in Settings to begin.")
        data object InvalidResponse : APIException("The AI provider returned an incomplete response. Please try again.")
        data object Incomplete : APIException("The AI provider returned an incomplete response. Please try again.")
        data object Refused : APIException("Mural couldn't complete that request. Try a different topic.")
        class Http(val status: Int, code: String? = null, reference: String? = null, providerName: String = "OpenAI") : APIException(messageFor(status, providerName)) {
            val code = ProviderFailureKind.safeCode(code)
            val reference = ProviderFailureKind.safeReference(reference)
            val kind get() = ProviderFailureKind.classify(status, code)
        }

        companion object {
            private fun messageFor(status: Int, providerName: String): String = when (status) {
                401 -> "Your $providerName key wasn't accepted. Check it in Settings."
                403, 404 -> "This API key may not have access to the requested model. Check your $providerName project."
                429 -> "$providerName's usage or rate limit was reached. Check your project billing and limits."
                else -> "$providerName couldn't complete the request (HTTP $status). Please try again."
            }
        }
    }

    companion object {
        private val API_BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegment("v1")
            .addPathSegment("")
            .build()
        private val GOOGLE_API_BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegment("v1beta")
            .addPathSegment("")
            .build()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VALID_PATH = Regex("[a-z0-9][a-z0-9_/-]*")
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()


    }
}
