package chat.mural.ondevice

import chat.mural.network.APIResult
import chat.mural.network.APIUsage
import chat.mural.network.HelperPurpose
import chat.mural.network.TeachingClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The Helpers of an On-Device Model conversation, over the Model Runtime seam.
 * Strictly offline: `search` is refused before any model call, so `sources` stays empty and a
 * web-dependent request degrades through the Language Module's existing unavailable reply. Every
 * Helper is one single-turn request, so its instructions become the system prompt and the model's
 * history restarts — the same contract the cloud TeachingClient offers.
 *
 * [unavailableReply] is the active Language Module's `lookupUnavailableReply`, the honest answer
 * offline for a fact that would need the web.
 */
class OnDeviceTeachingClient(
    private val session: suspend () -> RuntimeSession,
    private val unavailableReply: () -> String,
) : TeachingClient {
    override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
        purpose: HelperPurpose?): APIResult {
        // A topic Helper lives on its sources; offline it is unavailable rather than invented.
        if (search) return offline(if (purpose == HelperPurpose.TOPIC) "" else unavailableReply())
        val system = if (purpose in offlineRulePurposes)
            instructions + "\n" + OnDeviceTeachingRules.offline(unavailableReply()) else instructions
        if (schema != null) return assess(system, input, schema)
        val runtime = session()
        runtime.restart(system)
        return offline(runtime.respond(GenerationRequest(input)))
    }

    override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
        val runtime = session()
        runtime.restart(instructions)
        var streamed = ""
        val text = runtime.respond(GenerationRequest(input)) { delta ->
            streamed += delta
            // Subtitles take the whole text so far, as the cloud clients send it, and never a blank.
            if (streamed.isNotBlank()) onText(streamed)
        }
        // A blank reply stays blank: the meaning controller already reports that as an empty meaning.
        return offline(text.trim())
    }

    /**
     * Two-pass assessment: constrained decoding first, then at most one unconstrained retry, because
     * a Model Runtime may not support `jsonSchema` and a constrained reply can still hold no usable
     * object. The same defensive parser judges both attempts and the caller records the result.
     */
    private suspend fun assess(instructions: String, input: String, schema: JsonObject): APIResult {
        val runtime = session()
        val constrained = attempt {
            runtime.restart(instructions)
            runtime.respond(GenerationRequest(input, schema.toString(), MAX_OUTPUT_TOKENS))
        }
        if (constrained != null && OnDeviceAssessmentExtractor.proposal(constrained) != null) return offline(constrained)
        runtime.restart(instructions + "\n" + OnDeviceTeachingRules.onlyJsonObject(schema.toString()))
        return offline(runtime.respond(GenerationRequest(input, null, MAX_OUTPUT_TOKENS)))
    }

    /** Only the constrained attempt is retryable; its failure must never swallow cancellation. */
    private suspend fun attempt(generate: suspend () -> String): String? = try { generate() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    // The Model Runtime reports no token counts and offline has no web, so nothing is invented.
    private fun offline(text: String) = APIResult(text, emptyList(), APIUsage())

    private companion object {
        val offlineRulePurposes = setOf(HelperPurpose.LOOKUP, HelperPurpose.TYPED_REPLY, HelperPurpose.HELP)
        /** Without a cap, a constrained reply whose schema allows whitespace decodes until the KV cache is full. */
        const val MAX_OUTPUT_TOKENS = 1_024
    }
}
