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

/** Installation identity and guest bearers stay on the configured Mural origin. No identity in URLs. */
class GuestMinuteClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis) : GuestMinuteService {
    constructor(config: HostedConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(installationToken: String): GuestGrant {
        if (!Regex("[A-Za-z0-9_-]{43}").matches(installationToken)) throw AccountFailure.InvalidResponse
        val value = request("POST", "guest/minutes", body = buildJsonObject { put("installationToken", installationToken) })
        return try {
            when (value["available"]) {
                JsonPrimitive(true) -> {
                    val exchange = decode<AccountExchange>(buildJsonObject {
                        put("accountID", value.getValue("guestID")); put("accessToken", value.getValue("accessToken"))
                        put("expiresInSeconds", value.getValue("expiresInSeconds"))
                    })
                    GuestGrant.Available(AccountSession(exchange.accountID, exchange.accessToken,
                        now() + exchange.expiresInSeconds * 1000L),
                        value.getValue("remainingMilliseconds").jsonPrimitive.long,
                        value.getValue("resumed").jsonPrimitive.boolean)
                }
                JsonPrimitive(false) -> {
                    require(value.getValue("remainingMilliseconds").jsonPrimitive.long == 0L)
                    when (value.getValue("reason").jsonPrimitive.content) {
                        "temporarily_unavailable" -> GuestGrant.TemporarilyUnavailable
                        "sign_in_required" -> GuestGrant.SignInRequired
                        else -> throw AccountFailure.InvalidResponse
                    }
                }
                else -> throw AccountFailure.InvalidResponse
            }
        } catch (_: Exception) { throw AccountFailure.InvalidResponse }
    }
    override suspend fun balance(session: AccountSession): MinuteBalance = decode(request("GET", "minutes", session))
    override suspend fun link(member: AccountSession, guestAccessToken: String): GuestLinkResult =
        linkRequest(member, guestAccessToken, false)
    override suspend fun deferLink(member: AccountSession, guestAccessToken: String?, guestAccountID: String): GuestLinkResult =
        linkRequest(member, guestAccessToken, true, guestAccountID)
    private suspend fun linkRequest(member: AccountSession, guestAccessToken: String?, deferred: Boolean, guestAccountID: String? = null): GuestLinkResult {
        if ((deferred && (guestAccountID == null || !Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(guestAccountID))) || (guestAccessToken != null && !Regex("[A-Za-z0-9_-]{43}").matches(guestAccessToken)) ||
            (!deferred && guestAccessToken == null) || !member.isValid(now())) throw AccountFailure.InvalidResponse
        val value = request("POST", "minutes/link-guest", member, buildJsonObject {
            guestAccessToken?.let { put("guestAccessToken", it) }
            if (deferred) { put("deferPending", true); put("guestAccountID", guestAccountID!!) }
        })
        return try {
            GuestLinkResult(value.getValue("transferredMilliseconds").jsonPrimitive.long,
                value.getValue("alreadyLinked").jsonPrimitive.boolean, value.getValue("outcome").jsonPrimitive.content,
                value["pending"]?.jsonPrimitive?.boolean ?: false).also { require(deferred || !it.pending) }
        } catch (_: Exception) { throw AccountFailure.InvalidResponse }
    }
    private inline fun <reified T> decode(value: JsonObject): T = try { json.decodeFromJsonElement<T>(value) }
        catch (_: Exception) { throw AccountFailure.InvalidResponse }

    private suspend fun request(method: String, path: String, session: AccountSession? = null, body: JsonObject = buildJsonObject {}): JsonObject {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/$path").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .apply { session?.let { header("Authorization", "Bearer ${it.accessToken}") } }
            .method(method, if (method == "GET") null else body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(AccountFailure.Unavailable)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val payload = readBounded(it)
                            val parsed = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { null }
                            if (!it.isSuccessful) {
                                val code = ((parsed?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
                                    ?.takeIf { value -> value.length <= 80 && Regex("[a-z_]+").matches(value) }
                                throw AccountFailure.Http(it.code, code)
                            }
                            parsed ?: throw AccountFailure.InvalidResponse
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            error as? AccountFailure ?: AccountFailure.InvalidResponse)
                    }
                }
            })
        }
    }
    private fun readBounded(response: Response): String {
        val body = response.body ?: throw AccountFailure.InvalidResponse
        if (body.contentLength() > 65_536) throw AccountFailure.InvalidResponse
        val buffer = Buffer(); val source = body.source()
        while (buffer.size <= 65_536) {
            if (source.read(buffer, minOf(8192, 65_537 - buffer.size)) == -1L) return buffer.readUtf8()
        }
        throw AccountFailure.InvalidResponse
    }
}
