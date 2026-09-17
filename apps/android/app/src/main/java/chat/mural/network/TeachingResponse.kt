package chat.mural.network

import chat.mural.core.SourceLink
import java.net.URI
import kotlinx.serialization.json.*

/** Decodes direct Responses output and retains only safe citations. */
internal fun decodeTeachingResponse(response: JsonObject): APIResult {
    if (response.string("status") != "completed") throw APIClient.APIException.Incomplete

    val text = StringBuilder()
    val sources = linkedMapOf<String, SourceLink>()
    var searches = 0
    for (item in response.array("output")) {
        val output = item as? JsonObject ?: continue
        if (output.string("type") == "web_search_call") searches += 1
        for (contentElement in output.array("content")) {
            val content = contentElement as? JsonObject ?: continue
            when (content.string("type")) {
                "refusal" -> throw APIClient.APIException.Refused
                "output_text" -> text.append(content.string("text").orEmpty())
            }
            for (annotationElement in content.array("annotations")) {
                val annotation = annotationElement as? JsonObject ?: continue
                if (annotation.string("type") != "url_citation") continue
                val url = annotation.string("url") ?: continue
                if (isSafeSourceUrl(url)) {
                    sources.putIfAbsent(url, SourceLink(annotation.string("title") ?: "Source", url))
                }
            }
        }
    }

    // A local reasoning model behind a Responses endpoint can inline its thinking too.
    val clean = text.toString().replace(THINKING, "").trim()
    if (clean.isEmpty()) throw APIClient.APIException.Incomplete
    val usage = response["usage"] as? JsonObject
    return APIResult(
        text = clean,
        sources = sources.values.toList(),
        usage = APIUsage(
            input = ((usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            output = ((usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            searches = searches,
        ),
    )
}

/** Decodes a Chat Completions reply from an OpenAI-compatible server. These carry no web citations. */
internal fun decodeChatCompletion(response: JsonObject): APIResult {
    val choice = response.array("choices").firstOrNull() as? JsonObject ?: throw APIClient.APIException.Incomplete
    val message = choice["message"] as? JsonObject ?: throw APIClient.APIException.Incomplete
    if (message.string("refusal") != null || choice.string("finish_reason") == "content_filter") throw APIClient.APIException.Refused
    if (choice.string("finish_reason") == "length") throw APIClient.APIException.Incomplete
    // Local reasoning models often inline their thinking; it must never be spoken or stored.
    val text = message.string("content").orEmpty().replace(THINKING, "").trim()
    if (text.isEmpty()) throw APIClient.APIException.Incomplete
    val usage = response["usage"] as? JsonObject
    return APIResult(text, emptyList(), APIUsage(
        input = ((usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
        output = ((usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
    ))
}

/** Inline reasoning, including a block the server cut off before its closing tag. */
private val THINKING = Regex("<think>[\\s\\S]*?(?:</think>|$)")

private fun isSafeSourceUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: Exception) {
    false
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.array(key: String): JsonArray =
    this[key] as? JsonArray ?: JsonArray(emptyList())
