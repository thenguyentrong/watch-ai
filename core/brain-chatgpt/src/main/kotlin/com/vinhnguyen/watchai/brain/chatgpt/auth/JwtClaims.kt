package com.vinhnguyen.watchai.brain.chatgpt.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * The few claims we read from OpenAI's tokens. Tokens arrive over TLS straight from the token
 * endpoint, so we only decode them; we never trust claims from anywhere else.
 */
public data class TokenClaims(
    val expiresAtEpochSeconds: Long?,
    val accountId: String?,
    val planType: String?,
    val email: String?,
    val fedramp: Boolean,
)

public object JwtClaims {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null for anything that isn't a well-formed three-part JWT with a JSON payload. */
    public fun parse(jwt: String): TokenClaims? {
        val parts = jwt.split('.')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        val payload =
            runCatching {
                json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1].padBase64()))).jsonObject
            }.getOrNull() ?: return null
        val auth = payload[OpenAiAuth.AUTH_CLAIM] as? JsonObject
        val profile = payload[OpenAiAuth.PROFILE_CLAIM] as? JsonObject
        return TokenClaims(
            expiresAtEpochSeconds = payload["exp"]?.jsonPrimitive?.longOrNull,
            accountId = auth.string("chatgpt_account_id"),
            planType = auth.string("chatgpt_plan_type"),
            email = payload.string("email") ?: profile.string("email"),
            fedramp = (auth?.get("chatgpt_account_is_fedramp"))?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    private fun JsonObject?.string(key: String): String? = (this?.get(key))?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
}

/** "a***@example.com" - enough to recognise the account, not enough to identify someone. */
public fun maskEmail(email: String?): String? {
    if (email == null) return null
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    return email.first() + "***" + email.substring(at)
}
