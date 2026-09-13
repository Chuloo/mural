package chat.mural.network

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageDetectorDeviceTest {
    private val detector = LanguageDetector(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun platformClassifierIdentifiesSupportedPracticeLanguages() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val samples = mapOf(
            "es" to "Hoy vamos a practicar un poco de español. Cuéntame qué hiciste el fin de semana con tu familia.",
            "nb" to "I dag skal vi øve litt på norsk. Fortell meg hva du gjorde i helgen sammen med familien din.",
            "fr" to "Aujourd'hui, nous allons pratiquer un peu le français. Raconte-moi ce que tu as fait ce week-end.",
            "en" to "Today we are going to practise a little English. Tell me what you did at the weekend with your family.",
        )
        for ((expected, text) in samples) {
            val hypothesis = detector.detect(text)
            assertEquals(text, expected, hypothesis?.languageID)
            assertTrue("$expected confidence ${hypothesis?.confidence}", (hypothesis?.confidence ?: 0.0) > 0.88)
        }
    }
}
