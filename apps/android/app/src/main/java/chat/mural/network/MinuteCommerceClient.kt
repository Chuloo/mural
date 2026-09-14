package chat.mural.network

import chat.mural.core.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class MinuteCommerceConfiguration private constructor(val origin: HttpUrl) {
    companion object {
        fun parse(value: String): MinuteCommerceConfiguration? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 ||
                url.encodedPath != "/" || url.query != null || url.fragment != null || url.host == "openai.com" || url.host.endsWith(".openai.com")) return null
            return MinuteCommerceConfiguration(url)
        }
    }
}

/** Fixed backend endpoints only; client price, quantity, account identity and payment state are never sent. */
class MinuteCommerceClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis) : MinuteCommerceService {
    constructor(config: MinuteCommerceConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun catalog(): MinuteCatalog = decoded {
        val body = request("GET", "minutes/products", providerQuery = true)
        val basis = body.text("billingBasis")
        if (basis !in listOf("connected-conversation-time", "actual-ai-usage")) throw MinuteCommerceFailure.InvalidResponse
        val products = body["products"] as? JsonArray ?: throw MinuteCommerceFailure.InvalidResponse
        if (products.size > 100) throw MinuteCommerceFailure.InvalidResponse
        MinuteCatalog(body.boolean("available"), products.map { value ->
            val item = value as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
            val ai = if (basis == "actual-ai-usage") parseAIValue(item) else null
            MinuteProduct(item.text("sku"), item.text("providerProduct"), ai?.displayMinutes ?: item.count("minutes", 1440).toInt(), item.text("currency"),
                item.count("totalMinor", 100_000_000), item.text("environment"), ai)
        })
    }
    override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder = decoded {
        if (sku.length > 128 || !minuteIdentifier.matches(sku) || !Regex("[A-Za-z0-9._:-]{8,128}").matches(idempotencyKey))
            throw MinuteCommerceFailure.InvalidResponse
        val body = request("POST", "minutes/orders", session, buildJsonObject { put("provider", "play"); put("sku", sku) }, idempotencyKey)
        val payment = body["payment"] as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
        val ai = if (body["entitlementKind"] == JsonPrimitive("ai_value")) parseAIValue(body) else null
        MinuteOrder(body.text("orderID"), ai?.displayMinutes ?: body.count("minutes", 1440).toInt(), body.text("currency"), body.count("totalMinor", 100_000_000),
            PlayOrderBinding(payment.text("orderID"), payment.text("obfuscatedAccountID"), payment.text("obfuscatedProfileID")), ai)
    }
    override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus = decoded {
        validateID(orderID); parseStatus(request("GET", "minutes/orders/$orderID", session)).also {
            if (it.orderID != orderID) throw MinuteCommerceFailure.InvalidResponse
        }
    }
    override suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus = decoded {
        validateID(orderID); validateToken(token)
        parseStatus(request("POST", "minutes/orders/$orderID/play", session, buildJsonObject { put("purchaseToken", token) })).also {
            if (it.orderID != orderID) throw MinuteCommerceFailure.InvalidResponse
        }
    }
    override suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus = decoded {
        validateToken(token)
        parseStatus(request("POST", "minutes/play/recover", session, buildJsonObject { put("purchaseToken", token) }))
    }
    override suspend fun balance(session: AccountSession): MinuteBalance = decoded {
        val body = request("GET", "minutes", session)
        json.decodeFromJsonElement<MinuteBalance>(body)
    }
    private fun parseStatus(body: JsonObject): MinutePurchaseStatus {
        val ai = if (body["entitlementKind"] == JsonPrimitive("ai_value")) AIValueFulfillment(body.text("grantedNanoUSD"),
            body.text("reversedNanoUSD"), body.text("reversalOutstandingNanoUSD")) else null
        return MinutePurchaseStatus(body.text("orderID"), body.text("state"),
            if (ai == null) body.count("grantedMilliseconds", 86_400_000) else 0,
            if (ai == null) body.count("reversedMilliseconds", 86_400_000) else 0,
            if (ai == null) body.count("reversalOutstandingMilliseconds", 86_400_000) else 0,
            body.boolean("fulfillmentRecorded"), ai)
    }
    private fun parseAIValue(body: JsonObject): AIValueEntitlement {
        if (body.text("entitlementKind") != "ai_value" || body.text("billingBasis") != "actual-ai-usage" ||
            !body.boolean("estimate")) throw MinuteCommerceFailure.InvalidResponse
        val quote = body["quote"] as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
        return AIValueEntitlement(body.text("aiValueNanoUSD"), body.count("estimatedMilliseconds", MAX_AI_ESTIMATE_MS),
            json.decodeFromJsonElement<AIValueQuote>(quote))
    }
    private fun validateID(value: String) { if (!minuteUUID.matches(value)) throw MinuteCommerceFailure.InvalidResponse }
    private fun validateToken(value: String) { if (!validPurchaseToken(value)) throw MinuteCommerceFailure.InvalidResponse }
    private suspend fun <T> decoded(block: suspend () -> T): T = try { block() }
        catch (error: IllegalArgumentException) { throw MinuteCommerceFailure.InvalidResponse }

    private suspend fun request(method: String, path: String, session: AccountSession? = null, body: JsonObject? = null,
        idempotencyKey: String? = null, providerQuery: Boolean = false): JsonObject {
        if (session != null && !session.isValid(now())) throw MinuteCommerceFailure.SignInRequired
        val url = origin.newBuilder().addPathSegments("v1/$path").apply { if (providerQuery) addQueryParameter("provider", "play") }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
            .apply { session?.let { header("Authorization", "Bearer ${it.accessToken}") }; idempotencyKey?.let { header("Idempotency-Key", it) } }
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(MinuteCommerceFailure.Unavailable)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val buffer = Buffer(); val source = it.body?.source() ?: throw MinuteCommerceFailure.InvalidResponse
                            if ((it.body?.contentLength() ?: 0) > 131_072) throw MinuteCommerceFailure.InvalidResponse
                            while (buffer.size <= 131_072) {
                                if (source.read(buffer, minOf(8192, 131_073 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > 131_072) throw MinuteCommerceFailure.InvalidResponse
                            val parsed = try { json.parseToJsonElement(buffer.readUtf8()) as? JsonObject } catch (_: Exception) { null }
                            if (!it.isSuccessful) {
                                val code = ((parsed?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
                                    ?.takeIf { value -> value.length <= 80 && Regex("[a-z_]+").matches(value) }
                                throw MinuteCommerceFailure.Http(it.code, code)
                            }
                            parsed ?: throw MinuteCommerceFailure.InvalidResponse
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? MinuteCommerceFailure ?: MinuteCommerceFailure.InvalidResponse)
                    }
                }
            })
        }
    }
}
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw MinuteCommerceFailure.InvalidResponse
private fun JsonObject.count(key: String, max: Long = 9_007_199_254_740_991) =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..max } ?: throw MinuteCommerceFailure.InvalidResponse
private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    ?: throw MinuteCommerceFailure.InvalidResponse
