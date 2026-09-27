package com.vinhnguyen.watchai.brain.chatgpt

import com.vinhnguyen.watchai.brain.chatgpt.auth.OpenAiEndpoints
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Unit tests may only ever reach the local mock server. */
object LoopbackOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
        "localhost", "127.0.0.1" -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
        else -> throw UnknownHostException("tests are offline: $hostname")
    }
}

private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

fun testAuthClient(): OkHttpClient = ChatGptHttp
    .authClient(LOCAL_HOSTS)
    .newBuilder()
    .dns(LoopbackOnlyDns)
    .build()

fun testStreamClient(): OkHttpClient = ChatGptHttp
    .streamClient(LOCAL_HOSTS)
    .newBuilder()
    .dns(LoopbackOnlyDns)
    .build()

fun MockWebServer.endpoints(): OpenAiEndpoints {
    val base = url("/").toString().removeSuffix("/")
    return OpenAiEndpoints(issuer = base, codexBase = "$base/backend-api/codex")
}

class TestClock(
    var now: Instant = Instant.parse("2026-09-27T10:00:00Z"),
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

fun json(
    body: String,
    code: Int = 200,
): MockResponse = MockResponse
    .Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .body(body)
    .build()

/** Synthetic server-sent events in the Responses format. */
object Sse {
    fun delta(text: String) = event("response.output_text.delta", """{"type":"response.output_text.delta","delta":${quote(text)}}""")

    fun completed() = event("response.completed", """{"type":"response.completed","response":{"id":"resp_SYNTHETIC","output":[]}}""")

    fun created() = event("response.created", """{"type":"response.created","response":{"id":"resp_SYNTHETIC"}}""")

    fun event(
        type: String,
        data: String,
        lineEnd: String = "\n",
    ) = "event: $type${lineEnd}data: $data$lineEnd$lineEnd"

    fun response(
        vararg events: String,
        headers: Map<String, String> = emptyMap(),
    ): MockResponse = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        .body(events.joinToString(""))
        .build()

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
