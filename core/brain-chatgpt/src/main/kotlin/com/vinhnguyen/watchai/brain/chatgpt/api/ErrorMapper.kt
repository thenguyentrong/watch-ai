package com.vinhnguyen.watchai.brain.chatgpt.api

import com.vinhnguyen.watchai.brain.BrainError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Turns provider responses into [BrainError]s. Provider text is only matched, never shown or logged. */
internal class ErrorMapper(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromHttp(
        status: Int,
        headers: Headers,
        body: String,
    ): BrainError {
        val requestId = requestId(headers)
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = obj?.get("error")
        val type = (error as? JsonObject)?.str("type") ?: (error as? JsonObject)?.str("code") ?: (error as? JsonPrimitive)?.contentOrNull
        val detail = obj?.str("detail") ?: (error as? JsonObject)?.str("message").orEmpty()
        return when {
            status == 429 && type == "usage_limit_reached" -> {
                BrainError.UsageLimitReached(
                    resetsAtEpochSeconds = (error as? JsonObject)?.long("resets_at") ?: headers["x-codex-primary-reset-at"]?.toLongOrNull(),
                    notIncluded = false,
                )
            }

            status == 429 && type == "usage_not_included" -> {
                BrainError.UsageLimitReached(null, notIncluded = true)
            }

            status == 429 && type == "insufficient_quota" -> {
                BrainError.QuotaExceeded
            }

            status == 429 -> {
                BrainError.RateLimited(retryAfterMillis(headers, detail))
            }

            status == 503 && (type == "server_is_overloaded" || type == "slow_down") -> {
                BrainError.RateLimited(retryAfterMillis(headers, detail))
            }

            status == 400 && MODEL_UNAVAILABLE.containsMatchIn(detail) -> {
                BrainError.ProviderChanged("model_unavailable", status, requestId)
            }

            status == 400 && CONTRACT.any { detail.contains(it, ignoreCase = true) } -> {
                BrainError.ProviderChanged("contract", status, requestId)
            }

            status == 403 && body.contains("cloudflare", ignoreCase = true) -> {
                BrainError.ProviderChanged("blocked", status, requestId)
            }

            status == 404 || status == 405 || status == 410 -> {
                BrainError.ProviderChanged("endpoint", status, requestId)
            }

            else -> {
                BrainError.Unknown(status, type, requestId)
            }
        }
    }

    /** For `response.failed` (error inside `response`) and top-level `error` events. */
    fun fromStream(error: JsonElement?): BrainError {
        val obj = error as? JsonObject
        val code = obj?.str("code") ?: obj?.str("type")
        val message = obj?.str("message").orEmpty()
        return when (code) {
            "rate_limit_exceeded" -> BrainError.RateLimited(parseTryAgain(message))
            "usage_limit_reached" -> BrainError.UsageLimitReached(obj?.long("resets_at"), notIncluded = false)
            "usage_not_included" -> BrainError.UsageLimitReached(null, notIncluded = true)
            "insufficient_quota" -> BrainError.QuotaExceeded
            "cyber_policy", "bio_policy", "invalid_prompt" -> BrainError.Refused(code)
            "server_is_overloaded", "slow_down" -> BrainError.RateLimited(null)
            else -> BrainError.Unknown(code = code)
        }
    }

    fun requestId(headers: Headers): String? = headers["x-request-id"] ?: headers["x-oai-request-id"] ?: headers["cf-ray"]

    private fun retryAfterMillis(
        headers: Headers,
        detail: String,
    ): Long? {
        headers["retry-after-ms"]?.toLongOrNull()?.let { return it }
        headers["retry-after"]?.let { value ->
            value.toLongOrNull()?.let { return it * 1_000 }
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()?.let {
                return (it.toInstant().toEpochMilli() - clock.millis()).coerceAtLeast(0)
            }
        }
        return parseTryAgain(detail)
    }

    private fun parseTryAgain(message: String): Long? = TRY_AGAIN
        .find(message)
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()
        ?.let { (it * 1_000).toLong() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private companion object {
        val TRY_AGAIN = Regex("try again in ([0-9]+(?:\\.[0-9]+)?)s", RegexOption.IGNORE_CASE)
        val MODEL_UNAVAILABLE = Regex("model.*(not supported|does not exist|not found|unsupported)", RegexOption.IGNORE_CASE)
        val CONTRACT =
            listOf("Store must be set to false", "Stream must be set to true", "Unsupported parameter", "Instructions are required")
    }
}

/** Usage of the user's ChatGPT plan, from `x-codex-*` response headers. */
public data class UsageSnapshot(
    val primaryUsedPercent: Double?,
    val primaryWindowMinutes: Long?,
    val primaryResetsAtEpochSeconds: Long?,
    val secondaryUsedPercent: Double?,
    val secondaryWindowMinutes: Long?,
    val secondaryResetsAtEpochSeconds: Long?,
) {
    val nearLimit: Boolean get() = (primaryUsedPercent ?: 0.0) >= 90 || (secondaryUsedPercent ?: 0.0) >= 90

    internal companion object {
        fun fromHeaders(headers: Headers): UsageSnapshot? {
            val snapshot =
                UsageSnapshot(
                    primaryUsedPercent = headers["x-codex-primary-used-percent"]?.toDoubleOrNull(),
                    primaryWindowMinutes = headers["x-codex-primary-window-minutes"]?.toLongOrNull(),
                    primaryResetsAtEpochSeconds = headers["x-codex-primary-reset-at"]?.toLongOrNull(),
                    secondaryUsedPercent = headers["x-codex-secondary-used-percent"]?.toDoubleOrNull(),
                    secondaryWindowMinutes = headers["x-codex-secondary-window-minutes"]?.toLongOrNull(),
                    secondaryResetsAtEpochSeconds = headers["x-codex-secondary-reset-at"]?.toLongOrNull(),
                )
            return if (snapshot.primaryUsedPercent == null && snapshot.secondaryUsedPercent == null) null else snapshot
        }
    }
}
