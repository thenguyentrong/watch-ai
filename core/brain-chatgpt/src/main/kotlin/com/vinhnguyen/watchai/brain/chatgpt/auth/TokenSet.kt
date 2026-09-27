package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.vinhnguyen.watchai.brain.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What we keep after sign-in. The raw id_token is dropped once its claims are read. */
@Serializable
public data class TokenSet(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val accountId: String,
    val planType: String? = null,
    val emailMasked: String? = null,
    val fedramp: Boolean = false,
    val lastRefreshEpochSeconds: Long,
    val schema: Int = 1,
) {
    override fun toString(): String = "TokenSet(account=${accountId.take(8)}…, plan=$planType, exp=$expiresAtEpochSeconds)"
}

internal class TokenStore(
    private val store: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): TokenSet? = store.read(NAME)?.let { bytes ->
        runCatching { json.decodeFromString(TokenSet.serializer(), bytes.decodeToString()) }.getOrNull()
    }

    suspend fun write(tokens: TokenSet) {
        store.write(NAME, json.encodeToString(TokenSet.serializer(), tokens).encodeToByteArray())
    }

    suspend fun clear() {
        store.delete(NAME)
    }

    private companion object {
        const val NAME = "chatgpt_tokens"
    }
}
