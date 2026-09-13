package chat.mural.network

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import chat.mural.core.Passage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Detects the dominant language of assistant speech with the platform classifier (Android 10+). */
class LanguageDetector(private val context: Context) {
    data class Hypothesis(val languageID: String, val confidence: Double)

    suspend fun detect(text: String): Hypothesis? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return withContext(Dispatchers.Default) {
            val manager = context.getSystemService(TextClassificationManager::class.java) ?: return@withContext null
            val result = manager.textClassifier.detectLanguage(TextLanguage.Request.Builder(text).build())
            if (result.localeHypothesisCount == 0) return@withContext null
            val locale = result.getLocale(0)
            Hypothesis(normalizeLanguageID(locale.language), result.getConfidenceScore(locale).toDouble())
        }
    }

    companion object {
        fun normalizeLanguageID(code: String): String = code.lowercase().let { if (it == "no") "nb" else it }

        fun shouldCheck(passage: Passage?, lastRedirectedPassageID: String?): Boolean =
            passage != null && passage.text.length > 70 && passage.id != lastRedirectedPassageID
    }
}
