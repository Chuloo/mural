package chat.mural.ondevice

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The LiteRT-LM Model Runtime: the only place in Mural that knows the engine exists.
 * Cannot run on the JVM (it needs the device native library); tests run against
 * [ScriptedModelRuntime] through the same [ModelRuntime] interface.
 */
class LiteRtModelRuntime : ModelRuntime {
    override suspend fun load(modelPath: String, options: RuntimeOptions): RuntimeSession =
        withContext(Dispatchers.IO) {
            try {
                val engine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        maxNumTokens = options.maxTokens,
                        cacheDir = options.cacheDir,
                    )
                )
                try {
                    engine.initialize()
                } catch (e: Exception) {
                    runCatching { engine.close() }
                    throw e
                }
                LiteRtSession(engine, options)
            } catch (e: ModelRuntimeException) {
                throw e
            } catch (e: Exception) {
                throw ModelRuntimeException("LiteRT-LM could not load the model: ${e.message}", e)
            }
        }
}

private class LiteRtSession(private val engine: Engine, private val options: RuntimeOptions) :
    RuntimeSession {
    private var conversation: Conversation? = null
    private var systemPrompt: String? = null

    override fun restart(systemPrompt: String?) {
        this.systemPrompt = systemPrompt
        conversation?.let { runCatching { it.close() } }
        conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = options.topK,
                    topP = options.topP,
                    temperature = options.temperature,
                ),
                systemInstruction = systemPrompt?.let { Contents.of(listOf(Content.Text(it))) },
                // Per-message ResponseFormat throws unless the conversation opted in.
                enableResponseFormat = true,
            )
        )
    }

    override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit): String {
        val conversation =
            conversation ?: restart(null).let { checkNotNull(this.conversation) }
        return suspendCancellableCoroutine { continuation ->
            val reply = StringBuilder()
            // Cancel native generation when the caller's coroutine is cancelled.
            continuation.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
            try {
                conversation.sendMessageAsync(
                    request.prompt,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val text = message.toString()
                            if (text.isNotEmpty()) {
                                reply.append(text)
                                onPartial(text)
                            }
                        }

                        override fun onDone() {
                            continuation.resume(reply.toString())
                        }

                        override fun onError(throwable: Throwable) {
                            continuation.resumeWithException(
                                ModelRuntimeException("Generation failed: ${throwable.message}", throwable)
                            )
                        }
                    },
                    maxOutputToken = request.maxOutputTokens,
                    responseFormat = request.jsonSchema?.let { ResponseFormat.json(it) },
                )
            } catch (e: Exception) {
                continuation.resumeWithException(
                    ModelRuntimeException("LiteRT-LM rejected the request: ${e.message}", e)
                )
            }
        }
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine.close() }
    }
}
