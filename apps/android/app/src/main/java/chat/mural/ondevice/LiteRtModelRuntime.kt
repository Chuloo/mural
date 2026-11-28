package chat.mural.ondevice

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The LiteRT-LM Model Runtime: the only place in Mural that knows the engine exists.
 * Cannot run on the JVM (it needs the device native library); tests run against the scripted
 * Model Runtime fake in `src/test` through the same [ModelRuntime] interface.
 */
class LiteRtModelRuntime : ModelRuntime {
    override suspend fun load(modelPath: String, options: RuntimeOptions): RuntimeSession =
        withContext(Dispatchers.IO) {
            try {
                // Without an audio backend the engine never initializes its audio executor, so a clip
                // handed to it would fail. Audio runs on the CPU, as in the reference app.
                val audioBackend = if (options.enableAudioInput || hearsAudio(modelPath)) Backend.CPU() else null
                val engine = openEngine(modelPath, Backend.GPU(), audioBackend, options) ?: run {
                    Log.w(TAG, "LiteRT-LM GPU backend unavailable for $modelPath; falling back to CPU")
                    createEngine(modelPath, Backend.CPU(), audioBackend, options)
                }
                LiteRtSession(engine, options)
            } catch (e: ModelRuntimeException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM could not load $modelPath", e)
                throw ModelRuntimeException("LiteRT-LM could not load the model: ${e.message}", e)
            }
        }

    private fun openEngine(
        modelPath: String,
        backend: Backend,
        audioBackend: Backend?,
        options: RuntimeOptions,
    ): Engine? = try {
        createEngine(modelPath, backend, audioBackend, options)
    } catch (e: Exception) {
        Log.w(TAG, "LiteRT-LM failed on $backend: ${e.message}", e)
        null
    }

    private fun createEngine(
        modelPath: String,
        backend: Backend,
        audioBackend: Backend?,
        options: RuntimeOptions,
    ): Engine {
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = audioBackend,
                maxNumTokens = options.maxTokens,
                cacheDir = options.cacheDir,
            )
        )
        try {
            engine.initialize()
            return engine
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw e
        }
    }

    /** The model file is the authority on its own input modalities; a probe that fails says no. */
    override suspend fun supportsAudioInput(modelPath: String): Boolean =
        withContext(Dispatchers.IO) { hearsAudio(modelPath) }

    private fun hearsAudio(modelPath: String): Boolean = runCatching {
        ModelInfo.from(modelPath).use { it.inputModalities().audio }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "MuralOnDevice"
    }
}

private class LiteRtSession(private val engine: Engine, private val options: RuntimeOptions) :
    RuntimeSession {
    private var conversation: Conversation? = null
    private var systemPrompt: String? = null
    private var conversationSupportsSchema = false
    private var needsReset = true

    override fun restart(systemPrompt: String?) {
        this.systemPrompt = systemPrompt
        this.needsReset = true
    }

    private fun ensureConversation(enableSchema: Boolean): Conversation {
        val existing = conversation
        if (!needsReset && existing != null && conversationSupportsSchema == enableSchema) return existing
        existing?.let { runCatching { it.close() } }
        conversation = null
        // Match Google AI Edge Gallery: keep enableResponseFormat = false on ordinary chat and voice
        // turns so on-GPU TopKOpenClSampler runs without LLGuidance constraints; opt in only when a
        // schema-constrained assessment request actually needs ResponseFormat.
        val created = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = options.topK,
                    topP = options.topP,
                    temperature = options.temperature,
                ),
                systemInstruction = systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    Contents.of(listOf(Content.Text(it)))
                },
                enableResponseFormat = enableSchema,
            )
        )
        conversation = created
        conversationSupportsSchema = enableSchema
        needsReset = false
        return created
    }

    override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val active = try {
                ensureConversation(enableSchema = request.jsonSchema != null)
            } catch (e: Exception) {
                Log.e("MuralOnDevice", "LiteRT-LM createConversation failed", e)
                throw ModelRuntimeException("LiteRT-LM could not open conversation: ${e.message}", e)
            }
            suspendCancellableCoroutine { continuation ->
                val reply = StringBuilder()
                // Cancel native generation when the caller's coroutine is cancelled.
                continuation.invokeOnCancellation { runCatching { active.cancelProcess() } }
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isNotEmpty()) {
                            reply.append(text)
                            onPartial(text)
                        }
                    }

                    override fun onDone() {
                        if (continuation.isActive) continuation.resume(reply.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e("MuralOnDevice", "LiteRT-LM generation error", throwable)
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ModelRuntimeException("Generation failed: ${throwable.message}", throwable)
                            )
                        }
                    }
                }
                try {
                    val audio = request.audioWavBytes
                    val maxOutputToken = request.maxOutputTokens
                    val responseFormat = request.jsonSchema?.let { ResponseFormat.json(it) }
                    val contents = buildList {
                        if (audio != null) add(Content.AudioBytes(audio))
                        if (request.prompt.isNotEmpty() || audio == null) add(Content.Text(request.prompt))
                    }
                    active.sendMessageAsync(
                        Contents.of(contents),
                        callback,
                        maxOutputToken = maxOutputToken,
                        responseFormat = responseFormat,
                    )
                } catch (e: Exception) {
                    Log.e("MuralOnDevice", "LiteRT-LM sendMessageAsync rejected", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ModelRuntimeException("LiteRT-LM rejected the request: ${e.message}", e)
                        )
                    }
                }
            }
        }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine.close() }
    }
}
