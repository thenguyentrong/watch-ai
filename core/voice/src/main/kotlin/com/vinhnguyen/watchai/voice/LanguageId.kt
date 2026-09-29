package com.vinhnguyen.watchai.voice

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Which language a text is in, told on the phone by Android's own text classifier: it knows about
 * a hundred languages and needs no network, so private text can go through it. Null when it isn't
 * sure, or the phone has no classifier.
 */
class LanguageId(
    context: Context,
) {
    private val manager = context.applicationContext.getSystemService(TextClassificationManager::class.java)

    /** The language of [text] as a tag ("ja", "pt"), or null. */
    suspend fun of(text: String): String? {
        if (text.isBlank()) return null
        return withContext(Dispatchers.Default) {
            try {
                val found = manager.textClassifier.detectLanguage(TextLanguage.Request.Builder(text).build())
                if (found.localeHypothesisCount == 0) return@withContext null
                val top = found.getLocale(0)
                top.toLanguageTag().takeIf { top.language.isNotEmpty() && found.getConfidenceScore(top) >= MIN_CONFIDENCE }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.6f

        /** Whether two tags name the same language, whatever their region or script ("pt-BR" and "pt", "he" and "iw"). */
        fun same(
            a: String?,
            b: String?,
        ): Boolean = a != null && b != null && Locale.forLanguageTag(a).language == Locale.forLanguageTag(b).language
    }
}
