package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.brain.chatgpt.endpoints
import com.vinhnguyen.watchai.brain.chatgpt.json
import com.vinhnguyen.watchai.brain.chatgpt.testAuthClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration

class CodexOAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CodexOAuthClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = CodexOAuthClient(testAuthClient(), server.endpoints())
    }

    @After
    fun tearDown() = server.close()

    private fun form(body: String?) = body!!.split('&').associate {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test
    fun `code exchange sends a form body with the verifier`() = runTest {
        server.enqueue(
            json("""{"id_token":"SYNTHETIC_ID","access_token":"SYNTHETIC_AT","refresh_token":"SYNTHETIC_RT","expires_in":864000}"""),
        )
        val tokens = client.exchangeCode("SYNTHETIC_CODE", "SYNTHETIC_VERIFIER", "http://127.0.0.1:1455/auth/callback")
        assertThat(tokens.accessToken).isEqualTo("SYNTHETIC_AT")
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/oauth/token")
        assertThat(request.headers["Content-Type"]).startsWith("application/x-www-form-urlencoded")
        assertThat(form(request.body?.utf8()))
            .containsExactly(
                "grant_type",
                "authorization_code",
                "client_id",
                OpenAiAuth.CLIENT_ID,
                "code",
                "SYNTHETIC_CODE",
                "redirect_uri",
                "http://127.0.0.1:1455/auth/callback",
                "code_verifier",
                "SYNTHETIC_VERIFIER",
            )
    }

    @Test
    fun `a redirect is never followed and fails the exchange`() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", "/elsewhere")
                .build(),
        )
        val error = runCatching { client.exchangeCode("C", "V", "R") }.exceptionOrNull() as SignInException
        assertThat(error.error).isEqualTo(SignInError.ExchangeFailed(302))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a timed out exchange is not retried`() = runTest {
        val slow =
            CodexOAuthClient(
                testAuthClient().newBuilder().callTimeout(Duration.ofMillis(500)).build(),
                server.endpoints(),
            )
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val error = runCatching { slow.exchangeCode("C", "V", "R") }.exceptionOrNull() as SignInException
        assertThat(error.error).isEqualTo(SignInError.Offline)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `refresh sends json and classifies failures`() = runTest {
        server.enqueue(json("""{"access_token":"SYNTHETIC_AT2","refresh_token":"SYNTHETIC_RT2","expires_in":864000}"""))
        server.enqueue(json("""{"error":"invalid_grant"}""", 400))
        server.enqueue(json("""{"error":{"code":"refresh_token_reused","message":"SYNTHETIC"}}""", 400))
        server.enqueue(json("""{}""", 401))
        server.enqueue(json("""{"error":"server_error"}""", 500))
        server.enqueue(json("""{"error":"invalid_request"}""", 400))

        val ok = client.refresh("SYNTHETIC_RT") as RefreshResult.Ok
        assertThat(ok.tokens.refreshToken).isEqualTo("SYNTHETIC_RT2")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertThat(body["grant_type"]!!.jsonPrimitive.content).isEqualTo("refresh_token")
        assertThat(body["client_id"]!!.jsonPrimitive.content).isEqualTo(OpenAiAuth.CLIENT_ID)

        assertThat(client.refresh("x")).isEqualTo(RefreshResult.Permanent("invalid_grant"))
        assertThat(client.refresh("x")).isEqualTo(RefreshResult.Permanent("refresh_token_reused"))
        assertThat(client.refresh("x")).isEqualTo(RefreshResult.Permanent("unauthorized"))
        assertThat(client.refresh("x")).isEqualTo(RefreshResult.Transient(500))
        assertThat(client.refresh("x")).isEqualTo(RefreshResult.Transient(400))
    }

    @Test
    fun `revoke posts the refresh token`() = runTest {
        server.enqueue(json("{}"))
        assertThat(client.revoke("SYNTHETIC_RT")).isTrue()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertThat(body["token_type_hint"]!!.jsonPrimitive.content).isEqualTo("refresh_token")
    }

    @Test
    fun `device code flow - disabled, pending, slow down, authorized`() = runTest {
        server.enqueue(json("""{"error":"not_found"}""", 404))
        val disabled = runCatching { client.requestDeviceCode() }.exceptionOrNull() as SignInException
        assertThat(disabled.error).isEqualTo(SignInError.DeviceCodeDisabled)

        server.enqueue(json("""{"device_auth_id":"SYNTHETIC_DAI","usercode":"ABCD-1234","interval":"5"}"""))
        val code = client.requestDeviceCode()
        assertThat(code.userCode).isEqualTo("ABCD-1234")
        assertThat(code.intervalSeconds).isEqualTo(5)

        server.enqueue(json("{}", 403))
        server.enqueue(json("""{"error":"slow_down"}""", 400))
        server.enqueue(json("""{"authorization_code":"SYNTHETIC_AC","code_challenge":"x","code_verifier":"SYNTHETIC_CV"}"""))
        assertThat(client.pollDeviceCode(code)).isEqualTo(DevicePoll.Pending(slowDown = false))
        assertThat(client.pollDeviceCode(code)).isEqualTo(DevicePoll.Pending(slowDown = true))
        assertThat(client.pollDeviceCode(code)).isEqualTo(DevicePoll.Authorized("SYNTHETIC_AC", "SYNTHETIC_CV"))
    }
}
