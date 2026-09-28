package com.vinhnguyen.watchai.brain.chatgpt.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class CallbackTest {
    private val state = "SYNTHETIC_STATE_abc"
    private val port = 1455

    private fun head(
        target: String,
        method: String = "GET",
        host: String = "127.0.0.1:$port",
        extraHeaders: String = "",
    ) = "$method $target HTTP/1.1\r\nHost: $host\r\n${extraHeaders}User-Agent: test\r\n\r\n"

    private fun check(head: String) = CallbackValidator.check(head, state, port)

    @Test
    fun `accepts the code with our state`() {
        val result = check(head("/auth/callback?code=SYNTHETIC_CODE&state=$state"))
        assertThat(result).isEqualTo(CallbackValidator.Result.Accepted(CallbackResult.Code("SYNTHETIC_CODE")))
    }

    @Test
    fun `accepts localhost as host and the one known state suffix`() {
        val suffixed = "$state.onboarding_entrypoint%3Dlife_sciences"
        val result = check(head("/auth/callback?code=C&state=$suffixed", host = "localhost:$port"))
        assertThat(result).isInstanceOf(CallbackValidator.Result.Accepted::class.java)
    }

    @Test
    fun `rejects any other state, suffix or duplicates`() {
        listOf(
            "/auth/callback?code=C&state=WRONG",
            "/auth/callback?code=C&state=$state.other=1",
            "/auth/callback?code=C&state=$state&state=$state",
            "/auth/callback?code=C&code=D&state=$state",
            "/auth/callback?state=$state",
            "/auth/callback?code=&state=$state",
        ).forEach { assertThat(check(head(it))).isEqualTo(CallbackValidator.Result.Rejected(400)) }
    }

    @Test
    fun `rejects wrong path, method, host and non-http`() {
        assertThat(check(head("/other?code=C&state=$state"))).isEqualTo(CallbackValidator.Result.Rejected(404))
        assertThat(check(head("/auth/callback?code=C&state=$state", method = "POST"))).isEqualTo(CallbackValidator.Result.Rejected(404))
        assertThat(check(head("/auth/callback?code=C&state=$state", host = "evil.example:$port")))
            .isEqualTo(CallbackValidator.Result.Rejected(400))
        assertThat(check(head("/auth/callback?code=C&state=$state", host = "127.0.0.1:9999")))
            .isEqualTo(CallbackValidator.Result.Rejected(400))
        assertThat(check(head("/auth/callback?code=C&state=$state", extraHeaders = "Host: 127.0.0.1:$port\r\n")))
            .isEqualTo(CallbackValidator.Result.Rejected(400))
        assertThat(check("garbage\r\n\r\n")).isEqualTo(CallbackValidator.Result.Rejected(400))
    }

    @Test
    fun `provider errors are accepted and a missing Codex entitlement is recognised`() {
        val result =
            check(head("/auth/callback?error=access_denied&error_description=missing_codex_entitlement&state=$state"))
        assertThat(result)
            .isEqualTo(CallbackValidator.Result.Accepted(CallbackResult.ProviderError("access_denied", missingEntitlement = true)))
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun send(
        port: Int,
        raw: String,
    ): String = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { s ->
        s.getOutputStream().write(raw.toByteArray())
        s.getInputStream().readBytes().decodeToString()
    }

    @Test
    fun `listener binds loopback only, falls back to the second port, and gives up when both are busy`() {
        val first = freePort()
        val second = freePort()
        ServerSocket(first, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { squatter ->
            val receiver = LoopbackReceiver.bind(listOf(first, second))!!
            receiver.use {
                assertThat(it.port).isEqualTo(second)
                assertThat(it.redirectUri).isEqualTo("http://127.0.0.1:$second/auth/callback")
            }
            ServerSocket(second, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use {
                assertThat(LoopbackReceiver.bind(listOf(first, second))).isNull()
            }
            assertThat(squatter.isBound).isTrue()
        }
    }

    @Test
    fun `listener keeps waiting after a bad request, then takes the good one and closes`() = runBlocking {
        val p = freePort()
        val receiver = LoopbackReceiver.bind(listOf(p))!!
        val result = async(Dispatchers.IO) { withTimeout(10_000) { receiver.await(state) } }
        val bad = send(p, "GET /auth/callback?code=C&state=WRONG HTTP/1.1\r\nHost: 127.0.0.1:$p\r\n\r\n")
        assertThat(bad).startsWith("HTTP/1.1 400")
        assertThat(bad).contains("Cache-Control: no-store")
        val good = send(p, "GET /auth/callback?code=SYNTHETIC_CODE&state=$state HTTP/1.1\r\nHost: 127.0.0.1:$p\r\n\r\n")
        assertThat(good).startsWith("HTTP/1.1 200")
        assertThat(good).doesNotContain("SYNTHETIC_CODE")
        assertThat(result.await()).isEqualTo(CallbackResult.Code("SYNTHETIC_CODE"))
        // One-shot: nothing listens on the port any more. (Binding it again isn't the test: Linux keeps
        // a just-closed connection's port for a while, CI 28.09.)
        val refused = runCatching { Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), p).close() }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ConnectException::class.java)
    }

    @Test
    fun `listener ignores non-http bytes and aborts after ten bad requests`() = runBlocking {
        val p = freePort()
        val receiver = LoopbackReceiver.bind(listOf(p))!!
        val result = async(Dispatchers.IO) { runCatching { withTimeout(20_000) { receiver.await(state) } } }
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), p).use { it.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01)) }
        repeat(10) { send(p, "GET /nope HTTP/1.1\r\nHost: 127.0.0.1:$p\r\n\r\n") }
        val error = result.await().exceptionOrNull()
        assertThat((error as SignInException).error).isEqualTo(SignInError.TooManyBadCallbacks)
    }

    @Test
    fun `head reader stops at 8 KB`() {
        val huge = "GET /auth/callback?x=" + "a".repeat(9_000) + " HTTP/1.1\r\n\r\n"
        assertThat(LoopbackReceiver.readHead(huge.byteInputStream())).isNull()
    }
}
