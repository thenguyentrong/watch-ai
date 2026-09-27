package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class PkceAndUrlTest {
    @Test
    fun `S256 challenge matches the RFC 7636 appendix B example`() {
        val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertThat(pkce.challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `generated verifier and state are long, url-safe and unique`() {
        val a = Pkce.generate()
        val b = Pkce.generate()
        assertThat(a.verifier).hasLength(86) // 64 random bytes, base64url without padding
        assertThat(a.verifier).matches("[A-Za-z0-9_-]+")
        assertThat(a.verifier).isNotEqualTo(b.verifier)
        assertThat(newState()).hasLength(43) // 32 random bytes
    }

    @Test
    fun `authorize url carries exactly the expected parameters`() {
        val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        val url = authorizeUrl(OpenAiEndpoints(), "http://127.0.0.1:1455/auth/callback", pkce, "SYNTHETIC_STATE").toHttpUrl()
        assertThat(url.host).isEqualTo("auth.openai.com")
        assertThat(url.encodedPath).isEqualTo("/oauth/authorize")
        val params = url.queryParameterNames.associateWith { url.queryParameterValues(it) }
        assertThat(params)
            .containsExactly(
                "response_type",
                listOf("code"),
                "client_id",
                listOf("app_EMoamEEZ73f0CkXaXp7hrann"),
                "redirect_uri",
                listOf("http://127.0.0.1:1455/auth/callback"),
                "scope",
                listOf("openid profile email offline_access"),
                "code_challenge",
                listOf("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
                "code_challenge_method",
                listOf("S256"),
                "state",
                listOf("SYNTHETIC_STATE"),
                "codex_cli_simplified_flow",
                listOf("true"),
                "originator",
                listOf("watchai"),
            )
    }
}
