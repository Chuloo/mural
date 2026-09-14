package chat.mural.network

import chat.mural.core.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class ReportConfiguration private constructor(val origin: HttpUrl) {
    companion object {
        fun parse(origin: String): ReportConfiguration? {
            val url = origin.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedPath != "/" || url.query != null || url.fragment != null || url.port != 443) return null
            return ReportConfiguration(url)
        }
    }
}

/** Independent, credential-free client. Fixed paths, no redirects, cookies, cache or automatic retries. */
class ReportClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient) : ReportService {
    constructor(config: ReportConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun available(): Boolean {
        val result = request("capabilities", null, 200)["aiReports"]
        return when (result) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> throw ReportFailure.Invalid
        }
    }

    override suspend fun submit(report: AIReportSubmission) {
        if (!report.isValid()) throw ReportFailure.Invalid
        val result = request("ai", json.encodeToString(report), 202)
        if (result["accepted"] != JsonPrimitive(true) || result["reportID"] != JsonPrimitive(report.reportID))
            throw ReportFailure.Invalid
    }

    private suspend fun request(path: String, body: String?, status: Int): JsonObject {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/feedback/$path").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ReportFailure.Retry)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            when {
                                it.code == 503 || it.code == 404 -> throw ReportFailure.Unavailable
                                it.code == 429 || it.code >= 500 -> throw ReportFailure.Retry
                                it.code != status -> throw ReportFailure.Invalid
                            }
                            json.parseToJsonElement(readBounded(it)).jsonObject
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? ReportFailure ?: ReportFailure.Invalid)
                    }
                }
            })
        }
    }

    private fun readBounded(response: Response): String {
        val body = response.body ?: throw ReportFailure.Invalid
        if (body.contentLength() > 16_384) throw ReportFailure.Invalid
        val buffer = Buffer(); val source = body.source()
        while (buffer.size <= 16_384) {
            if (source.read(buffer, minOf(8192, 16_385 - buffer.size)) == -1L) return buffer.readUtf8()
        }
        throw ReportFailure.Invalid
    }
}
