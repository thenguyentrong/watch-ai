package com.vinhnguyen.watchai.brain.chatgpt.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest

public sealed interface CallbackResult {
    public data class Code(
        val code: String,
    ) : CallbackResult

    public data class ProviderError(
        val error: String,
        val missingEntitlement: Boolean,
    ) : CallbackResult
}

/**
 * One-shot HTTP listener on 127.0.0.1 that receives the OAuth redirect. It's bound before the
 * browser opens so no other app can take the port first, and it only accepts a request that
 * carries our exact `state`. PKCE makes a stolen code useless anyway: the verifier never leaves us.
 */
public class LoopbackReceiver private constructor(
    private val server: ServerSocket,
) : Closeable {
    public val port: Int get() = server.localPort
    public val redirectUri: String get() = "http://127.0.0.1:$port${OpenAiAuth.CALLBACK_PATH}"

    public suspend fun await(expectedState: String): CallbackResult = withContext(Dispatchers.IO) {
        try {
            var strikes = 0
            while (true) {
                ensureActive()
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                val verdict = socket.use { handle(it, expectedState) }
                when (verdict) {
                    is Verdict.Done -> return@withContext verdict.result
                    Verdict.Rejected -> if (++strikes >= MAX_STRIKES) throw SignInException(SignInError.TooManyBadCallbacks)
                    Verdict.Ignored -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            close()
        }
    }

    private fun handle(
        socket: Socket,
        expectedState: String,
    ): Verdict {
        socket.soTimeout = READ_TIMEOUT_MS
        val head =
            try {
                readHead(socket.getInputStream())
            } catch (_: Exception) {
                null
            } ?: return Verdict.Ignored // not HTTP (e.g. a TLS probe): drop silently
        return when (val check = CallbackValidator.check(head, expectedState, port)) {
            is CallbackValidator.Result.Accepted -> {
                respond(socket.getOutputStream(), 200, if (check.result is CallbackResult.Code) Pages.SUCCESS else Pages.ERROR)
                Verdict.Done(check.result)
            }

            is CallbackValidator.Result.Rejected -> {
                respond(socket.getOutputStream(), check.status, Pages.ERROR)
                Verdict.Rejected
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private sealed interface Verdict {
        data class Done(
            val result: CallbackResult,
        ) : Verdict

        data object Rejected : Verdict

        data object Ignored : Verdict
    }

    public companion object {
        private const val MAX_STRIKES = 10
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEAD_BYTES = 8_192
        private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Binds the first free allow-listed port, or returns null so the caller can offer device code. */
        public fun bind(ports: List<Int> = OpenAiAuth.LOOPBACK_PORTS): LoopbackReceiver? = ports.firstNotNullOfOrNull { port ->
            runCatching {
                ServerSocket().apply {
                    reuseAddress = false
                    soTimeout = 1_000
                    bind(InetSocketAddress(LOOPBACK_V4, port), 4)
                }
            }.getOrNull()?.let(::LoopbackReceiver)
        }

        /** Reads the request line and headers only; the callback never has a body. */
        internal fun readHead(input: InputStream): String? {
            val buffer = StringBuilder()
            var matched = 0
            val terminator = "\r\n\r\n"
            while (buffer.length < MAX_HEAD_BYTES) {
                val b = input.read()
                if (b < 0) return null
                val c = b.toChar()
                if (buffer.isEmpty() && (b < 0x20 || b > 0x7e)) return null
                buffer.append(c)
                matched =
                    if (c == terminator[matched]) {
                        matched + 1
                    } else if (c == '\r') {
                        1
                    } else {
                        0
                    }
                if (matched == terminator.length) return buffer.toString()
            }
            return null
        }

        private fun respond(
            out: OutputStream,
            status: Int,
            page: String,
        ) {
            val reason =
                when (status) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    404 -> "Not Found"
                    else -> "Error"
                }
            val body = page.toByteArray(Charsets.UTF_8)
            val headers =
                "HTTP/1.1 $status $reason\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Referrer-Policy: no-referrer\r\n" +
                    "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"
            runCatching {
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            }
        }
    }
}

public object CallbackValidator {
    public sealed interface Result {
        public data class Accepted(
            val result: CallbackResult,
        ) : Result

        public data class Rejected(
            val status: Int,
        ) : Result
    }

    public fun check(
        head: String,
        expectedState: String,
        port: Int,
    ): Result {
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return Result.Rejected(400)
        if (requestLine[0] != "GET") return Result.Rejected(404)
        val target = requestLine[1]
        val path = target.substringBefore('?')
        if (path != OpenAiAuth.CALLBACK_PATH) return Result.Rejected(404)

        val hosts =
            lines
                .drop(1)
                .filter { it.startsWith("host:", ignoreCase = true) }
                .map { it.substringAfter(':').trim() }
        if (hosts.size != 1 || hosts[0] !in setOf("127.0.0.1:$port", "localhost:$port")) return Result.Rejected(400)

        val params = parseQuery(target.substringAfter('?', ""))
        val states = params["state"].orEmpty()
        if (states.size != 1 || !stateMatches(states[0], expectedState)) return Result.Rejected(400)

        val errors = params["error"].orEmpty()
        if (errors.isNotEmpty()) {
            val description = params["error_description"]?.firstOrNull().orEmpty()
            return Result.Accepted(
                CallbackResult.ProviderError(
                    error = errors.first().take(64),
                    missingEntitlement = description.contains("missing_codex_entitlement"),
                ),
            )
        }
        val codes = params["code"].orEmpty()
        if (codes.size != 1 || codes[0].isEmpty()) return Result.Rejected(400)
        return Result.Accepted(CallbackResult.Code(codes[0]))
    }

    private fun stateMatches(
        received: String,
        expected: String,
    ): Boolean {
        val trimmed = received.removeSuffix(OpenAiAuth.STATE_SUFFIX)
        return MessageDigest.isEqual(trimmed.toByteArray(), expected.toByteArray())
    }

    private fun parseQuery(query: String): Map<String, List<String>> = query
        .split('&')
        .filter { it.isNotEmpty() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }.groupBy({ it.first }, { it.second })
}

private object Pages {
    private const val STYLE =
        "body{font-family:sans-serif;margin:3em auto;max-width:28em;padding:0 1em;line-height:1.5}"
    const val SUCCESS: String =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<title>Signed in</title><style>$STYLE</style><h1>You're signed in</h1>" +
            "<p>Go back to Buddy. You can close this tab.</p>"
    const val ERROR: String =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<title>Sign-in didn't work</title><style>$STYLE</style><h1>Sign-in didn't work</h1>" +
            "<p>Go back to Buddy and try again.</p>"
}
