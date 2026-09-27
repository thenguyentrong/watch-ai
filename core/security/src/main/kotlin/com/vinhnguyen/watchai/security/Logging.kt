package com.vinhnguyen.watchai.security

import android.util.Log
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import timber.log.Timber

/** Brain events go to Timber at info level; release builds strip info logs entirely. */
class TimberBrainLogger : BrainLogger {
    override fun log(event: LogEvent) {
        Timber.tag("brain").i(Redactor.redact(event.toString()))
    }
}

/**
 * Release builds: warnings and errors only, and only after redaction. Messages are expected to be
 * codes; the redactor is a second line of defence against a token or prompt slipping through.
 */
class ReleaseTree : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= Log.WARN

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        // Throwables are reduced to their class name: messages can carry provider or user text.
        val suffix = t?.let { " (${it::class.java.simpleName})" }.orEmpty()
        Log.println(priority, tag ?: "watchai", Redactor.redact(message) + suffix)
    }
}

object Redactor {
    private val rules =
        listOf(
            // JWTs (access and id tokens)
            Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*") to "[redacted]",
            // Authorization headers and bearer values
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+") to "Bearer [redacted]",
            // OAuth parameters in URLs, form bodies or JSON
            Regex("(?i)(code|state|refresh_token|access_token|id_token|code_verifier|user_code)([\"']?\\s*[=:]\\s*[\"']?)[^&\\s\"',}]+") to
                "$1$2[redacted]",
            // API-key-shaped secrets
            Regex("sk-[A-Za-z0-9_-]{10,}") to "[redacted]",
        )

    fun redact(message: String): String = rules.fold(message) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
