package chat.mural.ondevice

import kotlinx.coroutines.delay

/**
 * One scripted reply for [ScriptedModelRuntime]: streamed token deltas, an optional
 * failure message, and an optional delay that opens the cancellation test window.
 */
class ScriptedReply(vararg tokenParts: String, val failure: String? = null, val delayMillis: Long = 0) {
    val tokens: List<String> = tokenParts.toList()
}

/**
 * A Model Runtime fake for JVM tests: it pops one [ScriptedReply] per respond() call,
 * in order, and records every request together with the session's system prompt so
 * provider tests can assert exactly what a Helper sent.
 */
class ScriptedModelRuntime(vararg replies: ScriptedReply) : ModelRuntime {
    private val remaining = ArrayDeque(replies.toList())
    val requests = mutableListOf<RecordedRequest>()
    var closed = false

    data class RecordedRequest(val systemPrompt: String?, val request: GenerationRequest)

    override suspend fun load(modelPath: String, options: RuntimeOptions): RuntimeSession =
        ScriptedSession(modelPath, options)

    inner class ScriptedSession(val modelPath: String, val options: RuntimeOptions) : RuntimeSession {
        var systemPrompt: String? = null
        var sessionClosed = false

        override fun restart(systemPrompt: String?) {
            this.systemPrompt = systemPrompt
        }

        override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit): String {
            requests += RecordedRequest(systemPrompt, request)
            val reply = remaining.removeFirstOrNull()
                ?: throw ModelRuntimeException("No scripted reply left for request: ${request.prompt.take(50)}")
            if (reply.delayMillis > 0) delay(reply.delayMillis)
            reply.failure?.let { throw ModelRuntimeException(it) }
            for (token in reply.tokens) {
                onPartial(token)
                if (reply.delayMillis > 0) delay(reply.delayMillis)
            }
            return reply.tokens.joinToString("")
        }

        override fun close() {
            sessionClosed = true
            closed = true
        }
    }
}
