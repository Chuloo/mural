package chat.mural.network

import android.icu.text.BreakIterator
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.os.Build
import chat.mural.core.HanReader
import chat.mural.core.HanWords
import chat.mural.core.MandarinPronunciationToken
import chat.mural.core.MandarinPhraseReadings

/**
 * Segments caption links with ICU. On Android 10+, offline phrase readings resolve common polyphones
 * before the character transform is considered. Unresolved common polyphones keep their Han text.
 */
class IcuHanReader : HanReader {
    // Each background reader owns its ICU instance; Transliterator is mutable.
    private val transliterator = ThreadLocal.withInitial {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Transliterator.getInstance("Han-Latin") else null
    }

    override fun pronunciationTokens(text: String): List<MandarinPronunciationToken> {
        val segments = words(text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return segments.map { MandarinPronunciationToken(it, null) }
        return MandarinPhraseReadings.tokens(segments, WORD_READINGS, ::reading)
    }

    override fun words(text: String): List<String> {
        val iterator = BreakIterator.getWordInstance(ULocale.CHINA)
        iterator.setText(text)
        val pieces = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            pieces += text.substring(start, end)
            start = end; end = iterator.next()
        }
        return HanWords.merge(pieces, CAPTION_WORDS)
    }

    /** Prefer a word reading; use ICU only when a known ambiguous character does not need context. */
    override fun reading(word: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        WORD_READINGS[word]?.let { return it }
        MandarinPhraseReadings.reading(word)?.let { return it }
        if (MandarinPhraseReadings.isAmbiguous(word)) return null
        // These everyday polyphones need a word/phrase entry. A character transform cannot choose their sense.
        if (word.any { it in "行重长長还還教着著得乐樂便藏朝传傳数數调調相为為参參薄降难難处處只隻空少好干乾" }) return null
        val latin = transliterator.get()?.transliterate(word)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (word.codePointCount(0, word.length) <= 2) latin.replace(" ", "") else latin
    }

    private companion object {
        /** Everyday words the character transform reads wrongly, plus erhua forms whose 儿 is not a syllable. */
        val WORD_READINGS = mapOf(
            "银行" to "yínháng", "音乐" to "yīnyuè", "音乐会" to "yīnyuèhuì", "重新" to "chóngxīn", "长城" to "chángchéng",
            "觉得" to "juéde", "睡觉" to "shuìjiào", "便宜" to "piányi", "差不多" to "chàbuduō", "头发" to "tóufa",
            "认为" to "rènwéi", "以为" to "yǐwéi", "成为" to "chéngwéi", "作为" to "zuòwéi", "干净" to "gānjìng",
            "地方" to "dìfang", "一只" to "yīzhī", "少年" to "shàonián", "爱好" to "àihào", "大夫" to "dàifu",
            "首都" to "shǒudū", "了解" to "liǎojiě", "妈妈" to "māma", "爸爸" to "bàba", "东西" to "dōngxi",
            "漂亮" to "piàoliang", "喜欢" to "xǐhuan", "时候" to "shíhou", "学生" to "xuésheng", "先生" to "xiānsheng",
            "谢谢" to "xièxie", "对不起" to "duìbuqǐ", "没关系" to "méiguānxi", "不客气" to "búkèqi", "困难" to "kùnnan",
            "早上好" to "zǎoshanghǎo", "晚上好" to "wǎnshanghǎo", "普通话" to "pǔtōnghuà", "咖啡馆" to "kāfēiguǎn",
            "看见" to "kànjiàn", "知识" to "zhīshi", "明白" to "míngbai", "认识" to "rènshi", "意思" to "yìsi",
            "打扮" to "dǎban", "眼睛" to "yǎnjing", "衣服" to "yīfu", "朋友" to "péngyou", "休息" to "xiūxi",
            "一点儿" to "yīdiǎnr", "有点儿" to "yǒudiǎnr", "一会儿" to "yīhuìr", "一块儿" to "yīkuàir", "差点儿" to "chàdiǎnr",
            "哪儿" to "nǎr", "那儿" to "nàr", "这儿" to "zhèr", "玩儿" to "wánr", "花儿" to "huār", "事儿" to "shìr",
            "没事儿" to "méishìr", "鸟儿" to "niǎor", "小孩儿" to "xiǎoháir", "女孩儿" to "nǚháir", "男孩儿" to "nánháir",
            "聊天儿" to "liáotiānr", "好玩儿" to "hǎowánr", "画儿" to "huàr", "味儿" to "wèir", "空儿" to "kòngr",
            "东西南北" to "dōngxīnánběi",
            "长高" to "zhǎng gāo", "長高" to "zhǎng gāo", "长得" to "zhǎng de", "長得" to "zhǎng de",
            "教我" to "jiāo wǒ", "教你" to "jiāo nǐ", "教他" to "jiāo tā", "教她" to "jiāo tā",
            "教我们" to "jiāo wǒmen", "教我們" to "jiāo wǒmen",
            "銀行" to "yínháng", "音樂" to "yīnyuè", "音樂會" to "yīnyuèhuì", "長城" to "chángchéng", "覺得" to "juéde",
            "睡覺" to "shuìjiào", "頭髮" to "tóufa", "認為" to "rènwéi", "以為" to "yǐwéi", "成為" to "chéngwéi",
            "作為" to "zuòwéi", "乾淨" to "gānjìng", "一隻" to "yīzhī", "愛好" to "àihào", "了解" to "liǎojiě",
            "東西" to "dōngxi", "東西南北" to "dōngxīnánběi", "時候" to "shíhou", "學生" to "xuésheng", "謝謝" to "xièxie",
            "對不起" to "duìbuqǐ", "沒關係" to "méiguānxi", "不客氣" to "búkèqi", "困難" to "kùnnan", "看見" to "kànjiàn",
            "知識" to "zhīshi", "認識" to "rènshi", "一點兒" to "yīdiǎnr", "有點兒" to "yǒudiǎnr", "一會兒" to "yīhuìr",
            "一塊兒" to "yīkuàir", "哪兒" to "nǎr", "那兒" to "nàr", "這兒" to "zhèr", "玩兒" to "wánr", "花兒" to "huār",
            "事兒" to "shìr", "沒事兒" to "méishìr", "鳥兒" to "niǎor", "小孩兒" to "xiǎoháir", "女孩兒" to "nǚháir",
            "男孩兒" to "nánháir", "聊天兒" to "liáotiānr", "好玩兒" to "hǎowánr", "畫兒" to "huàr", "味兒" to "wèir",
        )
        private val CAPTION_WORDS = WORD_READINGS.keys - setOf("长高", "長高", "长得", "長得",
            "教我", "教你", "教他", "教她", "教我们", "教我們")

    }
}
