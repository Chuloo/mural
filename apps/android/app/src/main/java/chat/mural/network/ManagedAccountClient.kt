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

class ManagedAccountConfiguration private constructor(val origin: HttpUrl, val googleServerClientID: String) {
    companion object {
        fun parse(origin: String, clientID: String): ManagedAccountConfiguration? {
            val url = origin.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedPath != "/" || url.query != null || url.fragment != null || url.port != 443 ||
                !Regex("[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com").matches(clientID)) return null
            return ManagedAccountConfiguration(url, clientID)
        }
    }
}

/** Account tokens can only be sent to this client's fixed origin and fixed account endpoints. */
class ManagedAccountClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient) : AccountService {
    constructor(config: ManagedAccountConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun providers(): AccountProviders = decode(request("GET", "auth/providers"))
    override suspend fun challenge(): AccountChallenge = decode(request("POST", "auth/challenge"))
    override suspend fun exchange(challenge: AccountChallenge, idToken: String, expectedAccountID: String?): AccountExchange {
        if (idToken.isBlank() || idToken.length > 16_384) throw AccountFailure.Google
        return decode(request("POST", "auth/exchange", body = buildJsonObject {
            put("provider", "google"); put("challengeID", challenge.challengeID); put("idToken", idToken)
            expectedAccountID?.let { put("expectedAccountID", it) }
        }))
    }
    override suspend fun profile(session: AccountSession): AccountProfile = decode<AccountProfile>(request("GET", "account", session)).also {
        if (it.accountID != session.accountID) throw AccountFailure.InvalidResponse
    }
    override suspend fun minutes(session: AccountSession): MinuteBalance = decode(request("GET", "minutes", session))
    override suspend fun signOut(session: AccountSession) {
        if (request("POST", "auth/sign-out", session)["signedOut"] != JsonPrimitive(true)) throw AccountFailure.InvalidResponse
    }
    override suspend fun delete(session: AccountSession) {
        if (request("DELETE", "account", session)["deleted"] != JsonPrimitive(true)) throw AccountFailure.InvalidResponse
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
                                throw AccountFailure.Http(it.code, code, safeRequestErrorReference(it.header("X-Mural-Error-Reference")))
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
