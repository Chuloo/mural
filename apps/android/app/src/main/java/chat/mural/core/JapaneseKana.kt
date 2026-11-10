package chat.mural.core

import com.atilika.kuromoji.ipadic.Tokenizer

data class JapaneseReadingToken(val text: String, val kana: String?)

object JapaneseKana {
    // Loading the bundled dictionary takes noticeable time; callers on the main thread check [ready] first.
    private val tokenizer: Lazy<Tokenizer> = lazy {
        val builder = Tokenizer.Builder()
        builder.userDictionary(EVERYDAY_WORDS.byteInputStream())
        builder.build()
    }
    val ready get() = tokenizer.isInitialized()
    fun warmUp() { tokenizer.value }

    /** Everyday words the dictionary would split and read character by character. */
    private const val EVERYDAY_WORDS = "一人,一人,ヒトリ,名詞\n二人,二人,フタリ,名詞\n"

    /** Everyday words whose dictionary reading is the formal variant. */
    private val everydayReadings = mapOf("私" to "わたし", "日本" to "にほん", "日本人" to "にほんじん")

    /**
     * Uses dictionary word readings so 今日 and 一人 read as words rather than character by character.
     * Kana, punctuation, spacing and words without a dictionary reading remain exactly as supplied.
     */
    fun tokens(text: String): List<JapaneseReadingToken> {
        val result = mutableListOf<JapaneseReadingToken>()
        var cursor = 0
        for (token in tokenizer.value.tokenize(text)) {
            val start = token.position
            val end = start + token.surface.length
            if (start < cursor || end > text.length || token.surface.isEmpty()) continue
            if (start > cursor) result += JapaneseReadingToken(text.substring(cursor, start), null)
            val kana = if (MandarinPinyin.containsHan(token.surface)) everydayReadings[token.surface] ?: hiragana(token.reading) else null
            result += JapaneseReadingToken(token.surface, kana?.takeIf { it.isNotEmpty() && it.all(::isHiragana) })
            cursor = end
        }
        if (cursor < text.length) result += JapaneseReadingToken(text.substring(cursor), null)
        return result
    }

    /** A separate reading aid; source text and learning evidence are never replaced. */
    fun reading(text: String): String? {
        val parts = tokens(text)
        if (parts.none { it.kana != null }) return null
        return parts.joinToString("") { it.kana ?: it.text }
    }

    private fun hiragana(katakana: String): String =
        katakana.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    private fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゟ' || c == 'ー'
}

/** The reading aid shown below text in languages written with Han characters. */
object ReadingAid {
    fun supports(languageID: String): Boolean = languageID == "zh" || languageID == "ja"

    fun reading(text: String, languageID: String): String? = when (languageID) {
        "zh" -> MandarinPinyin.reading(text)
        "ja" -> JapaneseKana.reading(text)
        else -> null
    }
}
