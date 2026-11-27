package chat.mural.ondevice

import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.content
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What the system AICore service reports about the system model (ML Kit's `FeatureStatus`). */
enum class AICoreStatus { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/**
 * The AICore backend: every call into the system service goes through this seam, so JVM tests can
 * fake AICore behavior — streaming, a stateless history replay, unavailability — with no Binder.
 */
interface AICoreClient : AutoCloseable {
    suspend fun checkStatus(): AICoreStatus
    /** Asks the system service to fetch the model; returns once that has completed or failed. */
    suspend fun download()
    /** One streamed reply: the flow emits text deltas, and fails when AICore stops mid-reply. */
    fun generateContentStream(prompt: String, systemPrompt: String?, maxOutputTokens: Int?): Flow<String>
    override fun close()
}

/**
 * The AICore Model Runtime: the system-managed Gemini Nano behind the same [ModelRuntime] seam the
 * LiteRT-LM adapter uses, so no Conversation Provider or Helper can tell the two runtimes apart.
 * It is the zero-download On-Device Model — Mural stores no file and offers no delete, because the
 * system service manages the model. AICore takes no native audio input, so a voice turn never
 * reaches this runtime; cannot run on the JVM (every call is a Binder call into the system
 * service), so tests inject a fake [AICoreClient].
 */
class AICoreModelRuntime(
    /** Injected because a JVM test reads `Build.VERSION.SDK_INT` as 0. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val open: () -> AICoreClient = {
        SystemAICoreClient(Generation.getClient(generationConfig {
            modelConfig = modelConfig {
                releaseStage = ModelReleaseStage.STABLE
                preference = ModelPreference.FAST
            }
        }))
    },
) : ModelRuntime {
    /**
     * The device probe behind the picker: the zero-download option is offered only on Android 13
     * (API 33) or newer, and only when the system service reports the model — a phone below that
     * floor, or one AICore does not serve, never sees it. A probe that cannot reach the service
     * refuses rather than offering a model that could not start.
     */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return@withContext false
        val client = runCatching { open() }.getOrNull() ?: return@withContext false
        try { client.checkStatus() in OFFERED } catch (_: Exception) { false } finally { runCatching { client.close() } }
    }

    /**
     * Opens a session with the system model. [modelPath] is the catalogue id, not a file: AICore
     * serves the model itself. [options] stays with the LiteRT-LM sampler; the system model keeps
     * its own defaults. A phone that does not offer it fails here, and that failure reaches
     * the learner through the existing provider-issue messaging.
     */
    override suspend fun load(modelPath: String, options: RuntimeOptions): RuntimeSession =
        withContext(Dispatchers.IO) {
            if (sdkInt < Build.VERSION_CODES.TIRAMISU)
                throw ModelRuntimeException("AICore needs Android 13 (API 33); this phone reports $sdkInt.")
            val client = try { open() } catch (error: Exception) { throw couldNotStart(error) }
            try {
                when (client.checkStatus()) {
                    AICoreStatus.AVAILABLE -> AICoreSession(client)
                    // A model the service can still fetch: it downloads, Mural does not.
                    AICoreStatus.DOWNLOADABLE, AICoreStatus.DOWNLOADING -> {
                        client.download()
                        if (client.checkStatus() != AICoreStatus.AVAILABLE)
                            throw ModelRuntimeException("AICore did not make the system model available.")
                        AICoreSession(client)
                    }
                    AICoreStatus.UNAVAILABLE ->
                        throw ModelRuntimeException("AICore reports no system model on this phone.")
                }
            } catch (error: Exception) {
                runCatching { client.close() }
                throw couldNotStart(error)
            }
        }

    private fun couldNotStart(error: Exception) =
        if (error is CancellationException) error
        else if (error is ModelRuntimeException) error
        else ModelRuntimeException("AICore could not start the system model: ${error.message}", error)

    private companion object {
        /** A model the system service has, or can fetch. Anything else is not offered at all. */
        val OFFERED = setOf(AICoreStatus.AVAILABLE, AICoreStatus.DOWNLOADABLE)
    }
}

/**
 * One conversation with the system model. AICore's `GenerativeModel` is stateless across turns —
 * nothing persists between calls unless the app re-sends it — so the session keeps the system prompt
 * and the turn history, and replays both with every request. Only a completed turn joins that
 * history: a cancelled or failed reply is never replayed as though the model had answered it.
 */
private class AICoreSession(private val client: AICoreClient) : RuntimeSession {
    private data class Turn(val user: Boolean, val text: String)

    private val history = mutableListOf<Turn>()
    private var systemPrompt: String? = null

    override fun restart(systemPrompt: String?) {
        this.systemPrompt = systemPrompt
        history.clear()
    }

    override suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit): String {
        // AICore has no constrained decoding: the assessment Helper's unconstrained retry answers.
        if (request.jsonSchema != null)
            throw ModelRuntimeException("AICore cannot constrain a reply to a JSON Schema.")
        // AICore takes no native audio input: a voice turn is refused, never silently transcribed.
        if (request.audioWavBytes != null)
            throw ModelRuntimeException("AICore cannot hear the learner's speech as native audio input.")
        val reply = StringBuilder()
        try {
            client.generateContentStream(prompt(request.prompt), systemPrompt,
                request.maxOutputTokens?.coerceAtMost(MAX_OUTPUT_TOKENS))
                .collect { delta ->
                    reply.append(delta)
                    onPartial(delta)
                }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw ModelRuntimeException("AICore stopped the reply: ${error.message}", error) }
        history += Turn(user = true, text = request.prompt)
        history += Turn(user = false, text = reply.toString())
        return reply.toString()
    }

    /** The role-tagged replay of the conversation so far, ending with an open model turn. */
    private fun prompt(turn: String) = buildString {
        for (message in history) append(if (message.user) "user: " else "model: ").append(message.text).append('\n')
        append("user: ").append(turn).append("\nmodel: ")
    }

    override fun close() { runCatching { client.close() } }

    private companion object {
        /** AICore's native features enforce max_decode_steps = 4096 and reject a longer request. */
        const val MAX_OUTPUT_TOKENS = 4_096
    }
}

/** The real backend: ML Kit's GenAI Prompt API over the system AICore service. */
private class SystemAICoreClient(private val model: GenerativeModel) : AICoreClient {
    override suspend fun checkStatus(): AICoreStatus = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> AICoreStatus.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> AICoreStatus.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> AICoreStatus.DOWNLOADING
        else -> AICoreStatus.UNAVAILABLE
    }

    override suspend fun download() {
        when (val finished = model.download()
            .firstOrNull { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }) {
            is DownloadStatus.DownloadCompleted -> Unit
            is DownloadStatus.DownloadFailed ->
                throw ModelRuntimeException("AICore could not fetch the system model: ${finished.e.message}", finished.e)
            else -> throw ModelRuntimeException("AICore ended the system model fetch without completing it.")
        }
    }

    override fun generateContentStream(prompt: String, systemPrompt: String?, maxOutputTokens: Int?): Flow<String> {
        val builder = GenerateContentRequest.builder(content { text(prompt) })
        // The system prompt travels as the request's own instruction, which ML Kit turns into a
        // real SYSTEM turn instead of a "system:" line buried in the learner's message.
        if (!systemPrompt.isNullOrBlank()) builder.systemInstruction = SystemInstruction(systemPrompt)
        if (maxOutputTokens != null) builder.maxOutputTokens = maxOutputTokens
        return model.generateContentStream(builder.build())
            .map { response -> response.candidates.firstOrNull()?.text.orEmpty() }
            .filter { it.isNotEmpty() }
    }

    override fun close() = model.close()
}
