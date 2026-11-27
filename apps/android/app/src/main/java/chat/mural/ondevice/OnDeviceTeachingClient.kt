package chat.mural.ondevice

import chat.mural.network.APIResult
import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import chat.mural.network.TeachingClient
import kotlinx.serialization.json.JsonObject

/**
 * The Helpers of an On-Device Model conversation, over the Model Runtime seam.
 * Strictly offline: `search` is never honoured, so `sources` stays empty and a web-dependent
 * request degrades through the caller's existing unavailable reply. Every Helper is one
 * single-turn request, so its instructions become the system prompt and the model's history
 * restarts — the same contract the cloud TeachingClient offers.
 */
class OnDeviceTeachingClient(private val session: suspend () -> RuntimeSession) : TeachingClient {
    override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
        purpose: HelperPurpose?): APIResult = generate(instructions, input, schema)

    override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult =
        generate(instructions, input, null, onText)

    private suspend fun generate(instructions: String, input: String, schema: JsonObject?,
        onText: ((String) -> Unit)? = null): APIResult {
        val runtime = session()
        runtime.restart(instructions)
        val text = if (onText == null) runtime.respond(GenerationRequest(input, schema?.toString()))
        else runtime.respond(GenerationRequest(input, schema?.toString()), onText)
        // The Model Runtime reports no token counts, so usage stays zero rather than invented.
        return APIResult(text, emptyList(), APIUsage())
    }
}
