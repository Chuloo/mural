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

    if (text.isEmpty()) throw APIClient.APIException.Incomplete
    val usage = response["usage"] as? JsonObject
    return APIResult(
        text = text.toString(),
        sources = sources.values.toList(),
        usage = APIUsage(
            input = ((usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            output = ((usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            searches = searches,
        ),
    )
}

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
