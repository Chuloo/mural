package chat.mural.core

/** Offline word readings; caption boundaries and source evidence stay unchanged. */
object MandarinPhraseReadings {
    private val phrases: Map<String, String?> by lazy {
        val result = mutableMapOf<String, String?>()
        checkNotNull(javaClass.getResourceAsStream("/mandarin/phrases.txt")).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val fields = line.substringBefore('#').trim().split(":", limit = 2)
                if (fields.size == 2) {
                    val word = fields[0].trim()
                    val syllables = fields[1].trim().split(Regex("\\s+"))
                    val reading = syllables.joinToString("").canonical()
                    // Multiple dictionary readings require context we do not have.
                    result[word] = if (result.containsKey(word) && result[word] != reading) null else reading
                }
            }
        }
        result
    }

    fun reading(word: String): String? = phrases[word]
    fun isAmbiguous(word: String): Boolean = phrases.containsKey(word) && phrases[word] == null

    fun tokens(segments: List<String>, overrides: Map<String, String>, fallback: (String) -> String?): List<MandarinPronunciationToken> {
        val result = mutableListOf<MandarinPronunciationToken>()
        var index = 0
        while (index < segments.size) {
            var count = 1
            var text = segments[index]
            var reading: String? = null
            var known = false
            // Bound work for streaming captions. Dictionary phrases spanning words are read together,
            // while CaptionWords continues using the platform's original word boundaries.
            for (length in minOf(16, segments.size - index) downTo 1) {
                val candidate = segments.subList(index, index + length).joinToString("")
                val value = overrides[candidate] ?: phrases[candidate]
                if (overrides.containsKey(candidate) || phrases.containsKey(candidate)) {
                    count = length; text = candidate; reading = value; known = true; break
                }
            }
            result += MandarinPronunciationToken(text, if (known) reading else if (MandarinPinyin.containsHan(text)) fallback(text) else null)
            index += count
        }
        return result
    }
}
