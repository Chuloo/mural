package chat.mural.network

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.CaptionWords
import chat.mural.core.MandarinPinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HanReadingsDeviceTest {
    private val reader = IcuHanReader()

    @Test fun platformSegmentationKeepsEveryCharacterAndLinksDictionaryWords() {
        for (text in listOf("你好！", "  我想去银行，然后去旅行。\n", "你好，Mural 2026！☕️\n再见", "銀行與音樂", "café e pão")) {
            assertEquals(text, reader.words(text).joinToString(""))
            assertEquals(text, CaptionWords.segments(text, "zh", reader).joinToString("") { it.text })
        }
        val links = CaptionWords.segments("我想去银行，然后去旅行。", "zh", reader).mapNotNull { it.lookup }
        Log.i("HanReadingsDeviceTest", "links=$links")
        assertTrue(links.toString(), links.contains("银行"))
        assertTrue(links.toString(), links.contains("旅行"))
        assertTrue(links.none { it.contains("，") || it.contains("。") })
        for (word in listOf("𠮷", "𠀀")) {
            assertEquals(listOf(word), CaptionWords.segments(word, "zh", reader).mapNotNull { it.lookup })
        }
    }

    @Test fun platformReadingsUseWordContextAndKeepPunctuation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Dictionary overrides must not expose partial readings on Android 8–9.
            for (word in listOf("银行", "銀行", "一会儿", "你好")) assertNull(reader.reading(word))
            assertNull(MandarinPinyin.reading("我想去银行，然后去旅行。", reader))
            return
        }
        val expected = mapOf(
            "银行" to "yínháng", "旅行" to "lǚxíng", "音乐" to "yīnyuè", "快乐" to "kuàilè", "重新" to "chóngxīn", "重庆" to "chóngqìng",
            "女儿" to "nǚér", "你好" to "nǐhǎo", "觉得" to "juéde", "睡觉" to "shuìjiào", "一点儿" to "yīdiǎnr", "哪儿" to "nǎr",
            "中国" to "zhōngguó", "咖啡" to "kāfēi", "一会儿" to "yīhuìr", "有点儿" to "yǒudiǎnr", "东西" to "dōngxi",
            "东西南北" to "dōngxīnánběi", "銀行" to "yínháng", "音樂" to "yīnyuè",
            "重复" to "chóngfù", "重要" to "zhòngyào", "银行行长" to "yínháng hángzhǎng",
            "还钱" to "huánqián", "还给" to "huángěi", "长高" to "zhǎng gāo", "长得" to "zhǎng de",
            "教我中文" to "jiāo wǒ zhōngwén",
        )
        val readings = expected.keys.associateWith { MandarinPinyin.reading(it, reader) }
        Log.i("HanReadingsDeviceTest", "readings=$readings")
        expected.forEach { (text, reading) -> assertEquals(text, reading, readings[text]) }
        val sentence = MandarinPinyin.reading("我想去银行，然后去旅行。", reader)!!
        Log.i("HanReadingsDeviceTest", "sentence=$sentence")
        assertEquals("wǒ xiǎng qù yínháng，ránhòu qù lǚxíng。", sentence)
        assertNull(MandarinPinyin.reading("Hello, Mural 2026! ☕️", reader))
        // Without a resolving word/phrase, common ambiguous characters retain their Han text.
        for (word in listOf("行", "重", "还", "教")) assertNull(word, reader.reading(word))
        assertEquals(listOf("教", "我", "中文"), CaptionWords.segments("教我中文", "zh", reader).mapNotNull { it.lookup })
    }
}
