package com.vinhnguyen.watchai.brain.chatgpt

import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.time.Duration

/**
 * HTTP clients for the ChatGPT brain. Credentials may only ever be sent to OpenAI's two hosts, so
 * every request to any other host is refused before it leaves the phone.
 */
public object ChatGptHttp {
    public val OPENAI_HOSTS: Set<String> = setOf("auth.openai.com", "chatgpt.com")

    /** Sign-in and refresh: never follows redirects or retries (a code is single-use). */
    public fun authClient(allowedHosts: Set<String> = OPENAI_HOSTS): OkHttpClient = base(allowedHosts)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(Duration.ofSeconds(30))
        .build()

    /** Streaming answers: read timeout acts as an idle timeout, pings detect dead mobile links. */
    public fun streamClient(allowedHosts: Set<String> = OPENAI_HOSTS): OkHttpClient = base(allowedHosts)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(Duration.ofSeconds(120))
        .pingInterval(Duration.ofSeconds(30))
        // Retries are ours: at most one, and never after the answer has started.
        .retryOnConnectionFailure(false)
        .build()

    private fun base(allowedHosts: Set<String>): OkHttpClient.Builder {
        val localTest = allowedHosts.any { it == "localhost" || it == "127.0.0.1" }
        return OkHttpClient
            .Builder()
            .connectionSpecs(if (localTest) listOf(ConnectionSpec.CLEARTEXT) else listOf(ConnectionSpec.RESTRICTED_TLS))
            .connectTimeout(Duration.ofSeconds(15))
            .cache(null)
            .addInterceptor(HostAllowList(allowedHosts))
    }

    private class HostAllowList(
        private val hosts: Set<String>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            if (host !in hosts) throw IOException("blocked host")
            return chain.proceed(chain.request())
        }
    }
}
