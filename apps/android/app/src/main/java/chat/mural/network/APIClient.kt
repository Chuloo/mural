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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class APIUsage(val input: Int = 0, val output: Int = 0, val searches: Int = 0)
data class APIResult(val text: String, val sources: List<SourceLink>, val usage: APIUsage)

class APIClient internal constructor(
    private val target: () -> EndpointTarget,
    private val client: OkHttpClient,
) : TeachingClient, LiveSessionProvider {
    /** Resolves OpenAI or the learner's custom endpoint for each request. */
    constructor(target: () -> EndpointTarget) : this(target, defaultClient())

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl) :
        this({ openAI(key, baseUrl) }, client)

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

    suspend fun post(path: String, body: JsonObject): JsonObject = postJson(target(), path, body)

    private suspend fun postJson(endpoint: EndpointTarget, path: String, body: JsonObject): JsonObject =
        execute(endpoint, path, body.toString().toRequestBody(JSON_MEDIA_TYPE), MAX_RESPONSE_BYTES) { payload ->
            try { JSON.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject }
            catch (_: Exception) { throw APIException.InvalidResponse }
        }

    private suspend fun <T> execute(endpoint: EndpointTarget, path: String, body: RequestBody, limit: Long, parse: (ByteArray) -> T): T {
        if (!VALID_PATH.matches(path) || path.contains("..") || path.startsWith('/')) {
            throw APIException.InvalidResponse
        }
        // A custom server may need no key; OpenAI always does.
        val key = endpoint.key ?: if (endpoint.custom) null else throw APIException.MissingKey
        val request = Request.Builder()
            .url(endpoint.baseUrl.newBuilder().addPathSegments(path).build())
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .post(body)
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
                            parse(it.readBoundedBody(limit))
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
        val endpoint = target()
        return if (endpoint.protocol == EndpointProtocol.CHAT_COMPLETIONS)
            decodeChatCompletion(postJson(endpoint, "chat/completions", chatBody(endpoint, instructions, input, schema)))
        // Web search is OpenAI's hosted tool; a compatible Responses server may reject it.
        else decodeTeachingResponse(postJson(endpoint, "responses", responsesBody(endpoint.model, instructions, input, schema, search && !endpoint.custom)))
    }

    // ponytail: Chat Completions has no standard web search, so `search` is ignored and topics stay unsourced.
    private fun chatBody(endpoint: EndpointTarget, instructions: String, input: String, schema: JsonObject?) = buildJsonObject {
        put("model", endpoint.model)
        // vLLM's chat-template switch; OpenAI and some other servers reject unknown fields, so it's opt-in.
        if (endpoint.skipThinking) put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", instructions) })
            add(buildJsonObject { put("role", "user"); put("content", input) })
        })
        if (schema != null) {
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", "mural_result"); put("strict", true); put("schema", schema) })
            })
        }
    }

    private fun responsesBody(model: String, instructions: String, input: String, schema: JsonObject?, search: Boolean) =
        buildJsonObject {
            put("model", model)
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

    /** Streams OpenAI meanings as they arrive. A custom endpoint answers in one piece, since compatible servers
     * don't reliably emit OpenAI's Responses stream events. */
    override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
        val endpoint = target()
        if (endpoint.custom) return respond(instructions, input, purpose = HelperPurpose.MEANING).also { onText(it.text) }
        val key = endpoint.key ?: throw APIException.MissingKey
        val body = buildJsonObject {
            responsesBody(endpoint.model, instructions, input, null, false).forEach { (key, value) -> put(key, value) }
            put("stream", true)
        }
        val request = Request.Builder().url(endpoint.baseUrl.newBuilder().addPathSegments("responses").build())
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

    /** [language] is the learning language. Left to auto-detection, Whisper can turn accented Spanish into an
     * English translation; the cost is that a reply in another language may transcribe poorly. */
    suspend fun transcribe(wav: ByteArray, language: String): String {
        val endpoint = target()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", endpoint.transcriptionModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("language", if (language == "nb") "no" else language) // Whisper names Norwegian "no".
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV_MEDIA_TYPE))
            .build()
        return execute(endpoint, "audio/transcriptions", body, MAX_RESPONSE_BYTES) { payload ->
            val json = try { JSON.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject }
                catch (_: Exception) { throw APIException.InvalidResponse }
            (json["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw APIException.InvalidResponse
        }
    }

    /** Returns WAV bytes; 16-bit PCM WAV is the one format every compatible speech server offers. */
    suspend fun speak(text: String): ByteArray {
        val endpoint = target()
        val body = buildJsonObject {
            put("model", endpoint.speechModel); put("voice", endpoint.voice); put("input", text); put("response_format", "wav")
        }
        return execute(endpoint, "audio/speech", body.toString().toRequestBody(JSON_MEDIA_TYPE), MAX_AUDIO_BYTES) { it }
    }

    private fun Response.readBoundedBody(limit: Long): ByteArray {
        val responseBody = body ?: throw APIException.InvalidResponse
        if (responseBody.contentLength() > limit) throw APIException.InvalidResponse
        val source = responseBody.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val count = source.read(buffer, minOf(8_192L, limit + 1L - total))
            if (count == -1L) break
            total += count
            if (total > limit) throw APIException.InvalidResponse
        }
        return buffer.readByteArray()
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
        const val TEACHER_MODEL = "gpt-5.6-luna"
        private val API_BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegment("v1")
            .addPathSegment("")
            .build()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        private val VALID_PATH = Regex("[a-z0-9][a-z0-9_/-]*")
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private const val MAX_AUDIO_BYTES = 16_777_216L
        private val JSON = Json { ignoreUnknownKeys = true }

        fun openAI(key: String?, baseUrl: HttpUrl = API_BASE_URL) =
            EndpointTarget(baseUrl, key, EndpointProtocol.RESPONSES, TEACHER_MODEL)

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
