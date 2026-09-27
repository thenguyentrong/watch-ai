package com.vinhnguyen.watchai.brain.chatgpt.auth

/**
 * "Sign in with ChatGPT" through OpenAI's public Codex OAuth client. OpenAI supports using a
 * ChatGPT plan in third-party tools this way; we identify ourselves with our own originator and
 * never pretend to be Codex. Values checked against openai/codex main on 24.09.2026.
 */
public object OpenAiAuth {
    public const val CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    public const val SCOPES: String = "openid profile email offline_access"
    public const val ORIGINATOR: String = "watchai"
    public const val CALLBACK_PATH: String = "/auth/callback"

    /** The only two ports on OpenAI's redirect allow-list for this client. */
    public val LOOPBACK_PORTS: List<Int> = listOf(1455, 1457)

    /** ChatGPT sometimes appends this to `state`; it's the only suffix we accept. */
    public const val STATE_SUFFIX: String = ".onboarding_entrypoint=life_sciences"

    public const val AUTH_CLAIM: String = "https://api.openai.com/auth"
    public const val PROFILE_CLAIM: String = "https://api.openai.com/profile"
}

public data class OpenAiEndpoints(
    val issuer: String = "https://auth.openai.com",
    val codexBase: String = "https://chatgpt.com/backend-api/codex",
) {
    val authorize: String get() = "$issuer/oauth/authorize"
    val token: String get() = "$issuer/oauth/token"
    val revoke: String get() = "$issuer/oauth/revoke"
    val deviceUserCode: String get() = "$issuer/api/accounts/deviceauth/usercode"
    val deviceToken: String get() = "$issuer/api/accounts/deviceauth/token"
    val deviceVerificationPage: String get() = "$issuer/codex/device"
    val deviceRedirect: String get() = "$issuer/deviceauth/callback"
    val responses: String get() = "$codexBase/responses"
}
