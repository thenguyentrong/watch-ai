package com.vinhnguyen.watchai.brain.chatgpt.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

public class Pkce internal constructor(
    public val verifier: String,
) {
    public val challenge: String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    public companion object {
        /** 64 random bytes, like Codex. */
        public fun generate(random: SecureRandom = SecureRandom()): Pkce = Pkce(b64(ByteArray(64).also(random::nextBytes)))

        public fun fromVerifier(verifier: String): Pkce = Pkce(verifier)
    }
}

public fun newState(random: SecureRandom = SecureRandom()): String = b64(ByteArray(32).also(random::nextBytes))

internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

public fun authorizeUrl(
    endpoints: OpenAiEndpoints,
    redirectUri: String,
    pkce: Pkce,
    state: String,
): String = endpoints.authorize
    .toHttpUrl()
    .newBuilder()
    .addQueryParameter("response_type", "code")
    .addQueryParameter("client_id", OpenAiAuth.CLIENT_ID)
    .addQueryParameter("redirect_uri", redirectUri)
    .addQueryParameter("scope", OpenAiAuth.SCOPES)
    .addQueryParameter("code_challenge", pkce.challenge)
    .addQueryParameter("code_challenge_method", "S256")
    .addQueryParameter("state", state)
    .addQueryParameter("codex_cli_simplified_flow", "true")
    .addQueryParameter("originator", OpenAiAuth.ORIGINATOR)
    .build()
    .toString()
