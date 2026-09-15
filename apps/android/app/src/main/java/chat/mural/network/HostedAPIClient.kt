package chat.mural.network

import android.content.Context
import chat.mural.BuildConfig
import chat.mural.core.AccountSession
import chat.mural.core.SourceLink
import chat.mural.core.LanguageRegistry
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class HostedConfiguration private constructor(val origin: HttpUrl) {
    companion object {
        fun parse(value: String): HostedConfiguration? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 ||
                url.encodedPath != "/" || url.query != null || url.fragment != null ||
                url.host == "openai.com" || url.host.endsWith(".openai.com")) return null
            return HostedConfiguration(url)
        }
    }
}

sealed class HostedFailure : Exception() {
    data object SignInRequired : HostedFailure()
    data object InvalidRequest : HostedFailure()
    data object InvalidResponse : HostedFailure()
    data object Unavailable : HostedFailure()
    data object Unconfirmed : HostedFailure()
    class Http(val status: Int, val code: String?, val retryable: Boolean? = null,
        val retryAfterMilliseconds: Long? = null, val reference: String? = null) : HostedFailure()
}

data class HostedSessionStatus(val sessionID: String, val state: String, val deadlineMilliseconds: Long,
    val observedMilliseconds: Long, val reservedMilliseconds: Long, val chargedMilliseconds: Long?,
    val minimumChargeMilliseconds: Long? = null, val billingPolicy: String? = null,
    val billingBasis: String = "connected-conversation-time", val chargedNanoUSD: String? = null) {
    override fun toString() = "HostedSessionStatus([redacted])"
}

/** Experimental transport only: callers must explicitly select it after readiness and consent checks. */
class HostedAPIClient internal constructor(
    private val origin: HttpUrl,
    private val readSession: suspend () -> AccountSession?,
    transport: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val cleanupScope: CoroutineScope = HOSTED_CLEANUP,
) : LiveSessionProvider {
    constructor(context: Context, config: HostedConfiguration) : this(config.origin,
        AccountSessionStore(context, config.origin.toString())::read, OkHttpClient())

    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun available(): Boolean {
        val account = authorization()
        return request("GET", "capabilities", account) { body ->
            when (body["hostedMinutes"]) {
                JsonPrimitive(false) -> false
                JsonPrimitive(true) -> if (body["experimental"] == JsonPrimitive(true)) true else throw HostedFailure.InvalidResponse
                else -> throw HostedFailure.InvalidResponse
            }
        }
    }

    /** Reconciles an interrupted create without creating a second funded conversation. */
    suspend fun currentSession(): HostedLease? {
        val owner = authorization()
        return request("GET", "sessions/current", owner) { body ->
            if (body["session"] == JsonNull) null else {
                val item = body["session"] as? JsonObject ?: throw HostedFailure.InvalidResponse
                val id = item.string("sessionID") ?: throw HostedFailure.InvalidResponse
                if (!UUID_PATTERN.matches(id)) throw HostedFailure.InvalidResponse
                val status = parseStatus(item, id)
                HostedLease(id, owner).also {
                    it.deadlineMilliseconds = status.deadlineMilliseconds
                    it.reservedMilliseconds = status.reservedMilliseconds
                    it.minimumChargeMilliseconds = status.minimumChargeMilliseconds
                    it.billingPolicy = status.billingPolicy
                }
            }
        }
    }

    override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
        validateCreate(request)
        val account = authorization()
        val body = buildJsonObject {
            put("sdp", request.sdp); put("language", request.language!!); put("instructions", request.instructions)
            put("history", request.history)
            request.requestedMilliseconds?.let { put("requestedMilliseconds", it) }
        }
        return request("POST", "sessions", account, body, request.requestID,
            onUndelivered = { it.lease?.let(::closeLater) }) { result ->
            val id = result.string("sessionID")?.takeIf(UUID_PATTERN::matches) ?: throw HostedFailure.Unconfirmed
            val lease = HostedLease(id, account)
            try {
                val deadline = result.instant("deadline")
                val paid = isPaid(result)
                val reserved = result.count(if (paid) "limitMilliseconds" else "reservedMilliseconds", 3_600_000)
                val providerID = result.string("providerSessionID")?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
                    ?: throw HostedFailure.InvalidResponse
                val answer = result.string("sdp")?.takeIf { it.startsWith("v=0") && it.utf8Size() <= 65_536 }
                    ?: throw HostedFailure.InvalidResponse
                if ((!paid && result["billingBasis"] != JsonPrimitive(BILLING_BASIS)) || result["experimental"] != JsonPrimitive(true) ||
                    reserved <= 0 || deadline <= now() || deadline - now() > 86_400_000) throw HostedFailure.InvalidResponse
                lease.deadlineMilliseconds = deadline
                lease.reservedMilliseconds = reserved
                val minimum = billingMinimum(result)
                lease.minimumChargeMilliseconds = minimum.first
                lease.billingPolicy = minimum.second
                LiveSessionConnection(answer, providerID, lease)
            } catch (_: Exception) { closeLater(lease); throw HostedFailure.Unconfirmed }
        }
    }

    /** The lease captures its creating account; another signed-in account cannot read or use it. */
    inner class HostedLease internal constructor(override val sessionID: String, private val owner: AccountSession) : LiveSessionLease {
        init { if (!UUID_PATTERN.matches(sessionID)) throw HostedFailure.InvalidRequest }
        var deadlineMilliseconds: Long = 0; internal set
        var reservedMilliseconds: Long = 0; internal set
        var minimumChargeMilliseconds: Long? = null; internal set
        var billingPolicy: String? = null; internal set
        private val cutoffRequested = AtomicBoolean(false)
        val teaching: TeachingClient = object : TeachingClient {
            override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
                purpose: HelperPurpose?): APIResult = helper(owner.accountID, sessionID, instructions, input, schema, search,
                    purpose ?: throw HostedFailure.InvalidRequest)
        }
        suspend fun status(): HostedSessionStatus = request("GET", "sessions/$sessionID", authorization(owner.accountID)) {
            parseStatus(it, sessionID)
        }
        override suspend fun requestClose() {
            if (!cutoffRequested.compareAndSet(false, true)) return
            // Use the creating identity even if local sign-out races audio disposal. Server revocation is authoritative.
            request("POST", "sessions/$sessionID/close", owner, buildJsonObject {}) { parseStatus(it, sessionID) }
        }
        override fun toString() = "HostedLease([redacted])"
    }

    private suspend fun helper(accountID: String, sessionID: String, instructions: String, input: String,
        schema: JsonObject?, search: Boolean, purpose: HelperPurpose): APIResult = try {
        performHelper(accountID, sessionID, instructions, input, schema, search, purpose)
    } catch (failure: Exception) {
        if (BuildConfig.DEBUG) HostedHelperDiagnostics.report(purpose, failure)
        throw failure
    }

    private suspend fun performHelper(accountID: String, sessionID: String, instructions: String, input: String,
        schema: JsonObject?, search: Boolean, purpose: HelperPurpose): APIResult {
        if (!validText(instructions, 16_384) || !validText(input, 24_576) ||
            (search && purpose !in listOf(HelperPurpose.DELEGATION, HelperPurpose.TOPIC)) ||
            ((schema != null) != (purpose == HelperPurpose.ASSESSMENT)) || (schema?.toString()?.utf8Size() ?: 0) > 12_288)
            throw HostedFailure.InvalidRequest
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("requestID", id); put("purpose", purpose.wireValue); put("instructions", instructions); put("input", input)
            schema?.let { put("schema", it) }; put("search", search)
        }
        if (body.toString().utf8Size() > 65_536) throw HostedFailure.InvalidRequest
        return request("POST", "sessions/$sessionID/helpers", authorization(accountID), body) { result ->
            if (result["requestID"] != JsonPrimitive(id)) throw HostedFailure.InvalidResponse
            val text = result.string("text")?.takeIf { it.isNotBlank() } ?: throw HostedFailure.InvalidResponse
            val usage = result["usage"] as? JsonObject ?: throw HostedFailure.InvalidResponse
            val inputTokens = usage.count("inputTokens", 100_000_000).toInt()
            val outputTokens = usage.count("outputTokens", 100_000_000).toInt()
            val cached = usage.count("cachedInputTokens", 100_000_000)
            val written = usage.count("cacheWriteTokens", 100_000_000)
            if (cached + written > inputTokens) throw HostedFailure.InvalidResponse
            val searches = usage.count("searchCalls", 1).toInt()
            if (!search && searches > 0) throw HostedFailure.InvalidResponse
            val sourceList = result["sources"] as? JsonArray ?: throw HostedFailure.InvalidResponse
            val sources = sourceList.map { source ->
                val item = source as? JsonObject ?: throw HostedFailure.InvalidResponse
                SourceLink(item.string("title") ?: throw HostedFailure.InvalidResponse,
                    item.string("url") ?: throw HostedFailure.InvalidResponse)
            }.filter { it.safeUrl() != null }.distinctBy { it.url }
            if (result.string("costNanoUSD")?.matches(Regex("[0-9]{1,30}")) != true || result.string("rateVersion").isNullOrBlank())
                throw HostedFailure.InvalidResponse
            APIResult(text, sources, APIUsage(inputTokens, outputTokens, searches))
        }
    }

    private suspend fun authorization(expectedAccount: String? = null): AccountSession {
        val current = try { readSession() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw HostedFailure.SignInRequired }
        if (current == null || !current.isValid(now()) || (expectedAccount != null && current.accountID != expectedAccount))
            throw HostedFailure.SignInRequired
        return current
    }

    private fun closeLater(lease: LiveSessionLease) { cleanupScope.launch { try { lease.requestClose() } catch (_: Exception) { } } }

    private suspend fun <T> request(method: String, path: String, account: AccountSession, body: JsonObject? = null,
        idempotencyKey: String? = null, onUndelivered: (T) -> Unit = {}, transform: (JsonObject) -> T): T {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/live/$path").build())
            .header("Authorization", "Bearer ${account.accessToken}").header("Accept", "application/json")
            .header("Cache-Control", "no-store").apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(HostedFailure.Unconfirmed)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code != 200) {
                                val error = runCatching { json.parseToJsonElement(readBounded(it)).jsonObject["error"] as? JsonObject }.getOrNull()
                                val code = safeErrorCode(error?.string("code"))
                                val retryable = (error?.get("retryable") as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
                                val wait = (error?.get("retryAfterMilliseconds") as? JsonPrimitive)?.takeUnless { value -> value.isString }
                                    ?.longOrNull?.takeIf { value -> value in 1000..60_000 }
                                throw HostedFailure.Http(it.code, code, retryable, wait,
                                    safeRequestErrorReference(it.header("X-Mural-Error-Reference")))
                            }
                            transform(json.parseToJsonElement(readBounded(it)).jsonObject)
                        }
                        if (continuation.isActive) continuation.resume(result) { _, value, _ -> onUndelivered(value) }
                        else onUndelivered(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? HostedFailure ?: HostedFailure.InvalidResponse)
                    }
                }
            })
        }
    }

    private fun readBounded(response: Response): String {
        val body = response.body ?: throw HostedFailure.InvalidResponse
        if (body.contentLength() > 1_048_576) throw HostedFailure.InvalidResponse
        val buffer = Buffer(); val source = body.source()
        while (buffer.size <= 1_048_576) {
            if (source.read(buffer, minOf(8192, 1_048_577 - buffer.size)) == -1L) return buffer.readUtf8()
        }
        throw HostedFailure.InvalidResponse
    }

    private fun parseStatus(body: JsonObject, id: String): HostedSessionStatus {
        val paid = isPaid(body)
        if (body["sessionID"] != JsonPrimitive(id) || (!paid && body["billingBasis"] != JsonPrimitive(BILLING_BASIS))) throw HostedFailure.InvalidResponse
        val state = body.string("state")?.takeIf { it in listOf("creating", "active", "closing", "incomplete", "closed") }
            ?: throw HostedFailure.InvalidResponse
        val reserved = body.count(if (paid) "limitMilliseconds" else "reservedMilliseconds", 3_600_000)
        val charged = if (paid || body["chargedMilliseconds"] == JsonNull) null else body.count("chargedMilliseconds", reserved)
        val minimum = billingMinimum(body)
        return HostedSessionStatus(id, state, body.instant("deadline"), body.count("observedMilliseconds"), reserved, charged,
            minimum.first, minimum.second, if (paid) "actual-ai-usage" else BILLING_BASIS,
            if (paid && body["chargedNanoUSD"] != JsonNull) body.nano("chargedNanoUSD") else null)
    }

    private fun isPaid(body: JsonObject): Boolean {
        if (body["billingBasis"] != JsonPrimitive("actual-ai-usage")) return false
        if (body["fundingMode"] != JsonPrimitive("ai-value")) throw HostedFailure.InvalidResponse
        body.nano("reservedNanoUSD")
        return true
    }

    private fun billingMinimum(body: JsonObject): Pair<Long?, String?> {
        if ("minimumChargeMilliseconds" !in body && "billingPolicy" !in body) {
            if (isPaid(body)) throw HostedFailure.InvalidResponse
            return null to null
        }
        val minimum = body.count("minimumChargeMilliseconds", 15_000)
        val policy = body.string("billingPolicy") ?: throw HostedFailure.InvalidResponse
        if ((minimum == 0L && policy == "connected-time-only-v1" && !isPaid(body)) ||
            (minimum == 15_000L && policy == "connected-time-15s-minimum-v1" && !isPaid(body)) ||
            (minimum == 15_000L && policy == "actual-ai-usage-15s-minimum-v1" && isPaid(body))) return minimum to policy
        throw HostedFailure.InvalidResponse
    }

    private fun validateCreate(value: LiveSessionRequest) {
        if ((value.requestedMilliseconds != null && value.requestedMilliseconds !in 60_000L..3_600_000L) ||
            !UUID_PATTERN.matches(value.requestID) || LanguageRegistry.all.none { it.locale == value.language } ||
            !value.sdp.startsWith("v=0") || value.sdp.utf8Size() > 65_536 || !validText(value.instructions, 12_000) ||
            value.history.size > 40 || value.history.toString().utf8Size() > 6_000) throw HostedFailure.InvalidRequest
        for (entry in value.history) {
            val item = entry as? JsonObject ?: throw HostedFailure.InvalidRequest
            val role = item.string("role")
            val content = item["content"] as? JsonArray ?: throw HostedFailure.InvalidRequest
            if (item.keys != setOf("type", "role", "content") || item["type"] != JsonPrimitive("message") ||
                role !in listOf("user", "assistant") || content.size != 1) throw HostedFailure.InvalidRequest
            val part = content[0] as? JsonObject ?: throw HostedFailure.InvalidRequest
            if (part.keys != setOf("type", "text") || part["type"] != JsonPrimitive(if (role == "user") "input_text" else "output_text") ||
                !validText(part.string("text"), 6_000)) throw HostedFailure.InvalidRequest
        }
    }

    companion object {
        internal fun safeErrorCode(value: String?): String? = value?.takeIf { it in SAFE_ERROR_CODES }
        private const val BILLING_BASIS = "connected-conversation-time"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        private val SAFE_ERROR_CODES = setOf("sign_in_required", "hosted_voice_not_ready", "hosted_helpers_not_ready",
            "sign_in_to_continue", "provider_create_rejected", "rate_limit", "service_unavailable",
            "insufficient_minutes", "insufficient_credit", "hosted_funding_cap_reached", "live_request_already_created",
            "live_session_unresolved", "live_session_not_found", "provider_session_unconfirmed", "provider_connection_lost",
            "helper_request_already_attempted", "helper_response_uncertain", "helper_session_limit", "helper_concurrency_limit",
            "helper_budget_exhausted", "helper_session_window_closed", "helper_output_refused", "helper_output_incomplete",
            "helper_session_funding_unavailable", "helper_minute_session_required", "helper_provider_reconciliation_required")
        private val HOSTED_CLEANUP = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.nano(key: String): String = string(key)?.takeIf { Regex("0|[1-9][0-9]{0,29}").matches(it) }
    ?: throw HostedFailure.InvalidResponse
private fun JsonObject.count(key: String, max: Long = 9_007_199_254_740_991): Long =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..max } ?: throw HostedFailure.InvalidResponse
private fun JsonObject.instant(key: String): Long = try { Instant.parse(string(key)).toEpochMilli().also { if (it < 0) throw HostedFailure.InvalidResponse } }
    catch (_: Exception) { throw HostedFailure.InvalidResponse }
private fun validText(value: String?, maxBytes: Int): Boolean {
    if (value.isNullOrBlank() || value.utf8Size() > maxBytes || value.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }) return false
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isLowSurrogate() || (char.isHighSurrogate() && (index == value.length || !value[index++].isLowSurrogate()))) return false
    }
    return true
}
