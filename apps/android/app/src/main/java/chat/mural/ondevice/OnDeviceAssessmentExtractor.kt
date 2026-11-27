package chat.mural.ondevice

import chat.mural.core.EvidenceKind
import chat.mural.core.Outcome
import chat.mural.core.WordProposal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A clean assessment proposal: what `LearningEngine.validate` receives after defensive parsing. */
data class AssessmentProposal(
    val outcome: Outcome, val suggestedLevel: Int, val nextGoal: String, val capability: String,
    val words: List<WordProposal>,
)

/**
 * Defensive parsing of an On-Device Model assessment reply. A local model wraps its JSON in markdown
 * fences or prose, and one weak field must never discard a whole passage: values are coerced, clamped
 * or dropped here, and `LearningEngine.validate` stays the only gate on recorded evidence.
 */
object OnDeviceAssessmentExtractor {
    /** The assessment schema's `maxItems`; a longer word list would fail validation as a whole. */
    private const val MAX_WORDS = 12

    /** The proposal a raw reply supports, or null when it holds no usable assessment object. */
    fun proposal(raw: String): AssessmentProposal? {
        val reply = firstObject(raw) ?: return null
        // An object without an outcome is not an assessment: the caller retries or gives up.
        val outcomeName = reply.string("outcome") ?: return null
        return AssessmentProposal(
            Outcome.entries.firstOrNull { it.name.equals(outcomeName, true) } ?: Outcome.uncertain,
            reply.number("suggestedLevel")?.toInt()?.coerceIn(0, 5) ?: 0,
            reply.string("nextGoal") ?: "",
            reply.string("capability") ?: "",
            reply.words(),
        )
    }

    /** The first complete JSON object in a reply; fences and prose around it are ignored. */
    private fun firstObject(raw: String): JsonObject? {
        var start = raw.indexOf('{')
        while (start >= 0) {
            val end = closingBrace(raw, start)
            if (end > start) {
                val parsed = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
                if (parsed is JsonObject) return parsed
            }
            start = raw.indexOf('{', start + 1)
        }
        return null
    }

    /** The brace closing the object at [start], skipping braces inside string literals. */
    private fun closingBrace(raw: String, start: Int): Int {
        var depth = 0; var inString = false; var escaped = false
        for (index in start until raw.length) {
            val character = raw[index]
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = !inString
                !inString && character == '{' -> depth++
                !inString && character == '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun JsonObject.words(): List<WordProposal> {
        val items = runCatching { this["words"]?.jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val entry = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val kind = EvidenceKind.entries.firstOrNull { it.name.equals(entry.string("kind"), true) } ?: return@mapNotNull null
            val confidence = entry.number("confidence")?.coerceIn(0.0, 1.0) ?: return@mapNotNull null
            WordProposal(
                entry.string("lemma") ?: return@mapNotNull null,
                entry.string("meaning") ?: return@mapNotNull null,
                entry.string("form") ?: return@mapNotNull null,
                kind, confidence, entry.sourceIDs(),
                entry.string("quote") ?: return@mapNotNull null,
                entry.string("language") ?: return@mapNotNull null,
            )
        }.take(MAX_WORDS)
    }

    private fun JsonObject.sourceIDs() = runCatching { this["sourceIDs"]?.jsonArray }.getOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: emptyList()

    private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.number(key: String) = primitive(key)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.primitive(key: String) = runCatching { this[key]?.jsonPrimitive }.getOrNull()
}
