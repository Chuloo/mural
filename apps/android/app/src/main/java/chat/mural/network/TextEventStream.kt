package chat.mural.network

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Closing the call unblocks a stalled body reader when its owning coroutine is cancelled. */
internal suspend fun <T> streamingResponse(client: OkHttpClient, request: Request, read: suspend (Response) -> T): T = coroutineScope {
    val call = client.newCall(request)
    val reader = async(Dispatchers.IO) {
        try { call.execute().use { read(it) } }
        catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
    }
    try { reader.await() } finally { if (!isActive) call.cancel() }
}

/** SSE has blank-line event boundaries and may contain multiple data lines. */
internal suspend fun readTextEvents(response: Response, receive: suspend (JsonObject) -> JsonObject?): JsonObject {
    val source = response.body?.source() ?: throw IOException("Missing stream")
    val data = StringBuilder()
    var total = 0L
    while (!source.exhausted()) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8LineStrict(65_536)
        total += line.toByteArray(Charsets.UTF_8).size + 1
        if (total > 2_097_152) throw IOException("Stream exceeds limit")
        if (line.isEmpty()) {
            if (data.isEmpty()) continue
            val payload = data.toString(); data.clear()
            if (payload == "[DONE]") throw IOException("Stream ended without a result")
            val event = Json.parseToJsonElement(payload) as? JsonObject ?: throw IOException("Invalid stream event")
            receive(event)?.let { return it }
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
            if (data.toString().toByteArray(Charsets.UTF_8).size > 65_536) throw IOException("Event exceeds limit")
        }
    }
    throw IOException("Stream ended without a result")
}
