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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
data class APIResult(val text: String, val sources: List<SourceLink>, val usage: APIUsage)

class APIClient private constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = API_BASE_URL,
) : TeachingClient, LiveSessionProvider {
    constructor(credentials: CredentialStore) : this(credentials::read)

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl) :
        this({ key }, client, baseUrl)

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
        val key = readCredential() ?: throw APIException.MissingKey
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

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
                                throw APIException.Http(it.code, errorCode, it.header("x-request-id"))
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

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
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
        val key = readCredential() ?: throw APIException.MissingKey
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
        data object MissingKey : APIException("Add your OpenAI key in Settings to begin.")
        data object InvalidResponse : APIException("OpenAI returned an incomplete response. Please try again.")
        data object Incomplete : APIException("OpenAI returned an incomplete response. Please try again.")
        data object Refused : APIException("Mural couldn't complete that request. Try a different topic.")
        class Http(val status: Int, code: String? = null, reference: String? = null) : APIException(messageFor(status)) {
            val code = ProviderFailureKind.safeCode(code)
            val reference = ProviderFailureKind.safeReference(reference)
            val kind get() = ProviderFailureKind.classify(status, code)
        }

        companion object {
            private fun messageFor(status: Int): String = when (status) {
                401 -> "Your OpenAI key wasn't accepted. Check it in Settings."
                403, 404 -> "This API key may not have access to the requested model. Check your OpenAI project."
                429 -> "OpenAI's usage or rate limit was reached. Check your project billing and limits."
                else -> "OpenAI couldn't complete the request (HTTP $status). Please try again."
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
