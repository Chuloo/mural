package chat.mural.network

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.CaptionWords
import chat.mural.core.MandarinPinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    }

    @Test fun platformReadingsUseWordContextAndKeepPunctuation() {
        assumeTrue("Han-Latin readings need Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val expected = mapOf(
            "银行" to "yínháng", "旅行" to "lǚxíng", "音乐" to "yīnyuè", "快乐" to "kuàilè", "重新" to "chóngxīn", "重庆" to "chóngqìng",
            "女儿" to "nǚér", "你好" to "nǐhǎo", "觉得" to "juéde", "睡觉" to "shuìjiào", "一点儿" to "yīdiǎnr", "哪儿" to "nǎr",
            "中国" to "zhōngguó", "咖啡" to "kāfēi", "一会儿" to "yīhuìr", "有点儿" to "yǒudiǎnr", "东西" to "dōngxi",
            "东西南北" to "dōngxīnánběi", "銀行" to "yínháng", "音樂" to "yīnyuè",
        )
        val readings = expected.keys.associateWith { MandarinPinyin.reading(it, reader) }
        Log.i("HanReadingsDeviceTest", "readings=$readings")
        expected.forEach { (text, reading) -> assertEquals(text, reading, readings[text]) }
        val sentence = MandarinPinyin.reading("我想去银行，然后去旅行。", reader)!!
        Log.i("HanReadingsDeviceTest", "sentence=$sentence")
        assertEquals("wǒ xiǎng qù yínháng，ránhòu qù lǚxíng。", sentence)
        assertNull(MandarinPinyin.reading("Hello, Mural 2026! ☕️", reader))
    }
}
