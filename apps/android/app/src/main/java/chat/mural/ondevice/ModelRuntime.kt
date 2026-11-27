package chat.mural.ondevice

/**
 * A Model Runtime: the single engine boundary that executes an On-Device Model.
 * Helpers and voice sessions call this interface; they never touch a concrete engine,
 * so LiteRT-LM and AICore stay interchangeable behind it (see GLOSSARY.md).
 */
interface ModelRuntime {
    /**
     * Loads a model file and opens a session. Throws [ModelRuntimeException] when the
     * model cannot be loaded; callers surface that through provider-issue messaging.
     */
    suspend fun load(modelPath: String, options: RuntimeOptions = RuntimeOptions()): RuntimeSession
}

class ModelRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class RuntimeOptions(
    val maxTokens: Int = 4096,
    val temperature: Double = 1.0,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val cacheDir: String? = null,
)

data class GenerationRequest(
    val prompt: String,
    /** When non-null the whole reply must match this JSON Schema (constrained decoding). */
    val jsonSchema: String? = null,
    val maxOutputTokens: Int? = null,
)

/**
 * One conversation with the loaded model. The session keeps the turn history;
 * a new system prompt restarts the conversation with fresh history.
 */
interface RuntimeSession : AutoCloseable {
    /** Sets the system prompt and restarts the conversation. Null clears it. */
    fun restart(systemPrompt: String?)

    /**
     * Generates one reply. [onPartial] receives streamed deltas; the return value is
     * the complete reply text. Cancelling the coroutine cancels generation.
     * Throws [ModelRuntimeException] when the engine fails.
     */
    suspend fun respond(request: GenerationRequest, onPartial: (String) -> Unit = {}): String
}
