package chat.mural.core

import java.text.Normalizer

data class MandarinPronunciationToken(val text: String, val pinyin: String?)

/** Word segmentation and readings for Han text, supplied by the platform. */
interface HanReader {
    /** Splits text into consecutive pieces whose concatenation is the original text. */
    fun words(text: String): List<String>

    /** A Latin transcription of one word, or null when the platform has none. */
    fun reading(word: String): String?
}

object MandarinPinyin {
    @Volatile var reader: HanReader? = null

    /** Punctuation, spacing and unrecognized characters remain exactly as supplied. */
    fun tokens(text: String, reader: HanReader? = this.reader): List<MandarinPronunciationToken> {
        if (text.isEmpty()) return emptyList()
        val words = reader?.words(text)?.takeIf { it.joinToString("") == text }
            ?: return listOf(MandarinPronunciationToken(text, null))
        return words.map { word ->
            val latin = if (containsHan(word)) reader.reading(word)?.let(::normalize) else null
            MandarinPronunciationToken(word, latin?.takeIf { it.isNotEmpty() && !containsHan(it) })
        }
    }

    /** A separate reading aid; source text and learning evidence are never replaced. */
    fun reading(text: String, reader: HanReader? = this.reader): String? {
        val parts = tokens(text, reader)
        if (parts.none { it.pinyin != null }) return null
        val result = StringBuilder()
        for (part in parts) {
            val value = part.pinyin ?: part.text
            if (result.isNotEmpty() && value.isNotEmpty() &&
                Character.isLetterOrDigit(result.codePointBefore(result.length)) && Character.isLetterOrDigit(value.codePointAt(0))) result.append(' ')
            result.append(value)
        }
        return result.toString()
    }

    fun containsHan(text: String): Boolean = text.codePoints().anyMatch { cp ->
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F || cp in 0x30000..0x3347F
    }

    /** Dictionary readings may spell ü as v (旅行); readings are lowercase and precomposed. */
    private fun normalize(latin: String): String =
        Normalizer.normalize(latin.lowercase().replace('v', 'ü'), Normalizer.Form.NFC).trim()
}

object HanWords {
    /** Joins adjacent segments that together form a known word, longest match first, up to four segments. */
    fun merge(segments: List<String>, words: Set<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < segments.size) {
            var taken = 1
            var chosen = segments[i]
            for (count in minOf(4, segments.size - i) downTo 2) {
                val joined = segments.subList(i, i + count).joinToString("")
                if (joined in words) { taken = count; chosen = joined; break }
            }
            result += chosen; i += taken
        }
        return result
    }
}

data class CaptionSegment(val text: String, val lookup: String?)

object CaptionWords {
    /** Keeps every source character, linking Chinese words instead of whole sentences. */
    fun segments(text: String, languageID: String, reader: HanReader? = MandarinPinyin.reader): List<CaptionSegment> {
        if (languageID == "zh") {
            return MandarinPinyin.tokens(text, reader).map { CaptionSegment(it.text, it.text.takeIf { t -> t.any(Char::isLetter) }) }
        }
        val result = mutableListOf<CaptionSegment>()
        val run = StringBuilder()
        for (character in text) {
            if (run.isNotEmpty() && run.last().isWhitespace() != character.isWhitespace()) {
                result += segment(run.toString()); run.clear()
            }
            run.append(character)
        }
        if (run.isNotEmpty()) result += segment(run.toString())
        return result
    }

    private fun segment(text: String): CaptionSegment {
        val word = text.trim { it.isWhitespace() || isPunctuation(it) }
        return CaptionSegment(text, word.takeIf { it.any(Char::isLetter) })
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}
