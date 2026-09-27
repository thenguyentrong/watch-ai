package com.vinhnguyen.watchai.ondevice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration

@Serializable
data class ModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val source: String,
    val minRamGb: Int,
) {
    val fileName: String get() = "$sha256.litertlm"
}

object ModelCatalog {
    const val DEFAULT_ID = "gemma-4-e2b"

    /** The catalog ships inside the signed APK, so a model can only change through an app update. */
    fun parse(json: String): List<ModelSpec> = Json.decodeFromString(ListSerializer(ModelSpec.serializer()), json)
}

class RetryableDownloadException(
    reason: String,
) : IOException(reason)

class ModelIntegrityException : IOException("hash mismatch")

class OversizeException : IOException("more data than expected")

class NotEnoughSpaceException(
    val neededBytes: Long,
) : IOException("not enough space")

/**
 * Downloads a model into [dir] with resume support, then checks size and SHA-256 before the file
 * is ever used. A partial or failed file is never mistaken for a model: only a verified download
 * gets its final name.
 */
class ModelDownloader(
    private val http: OkHttpClient,
    private val dir: File,
    private val hasSpace: (neededBytes: Long) -> Boolean = { true },
) {
    fun finalFile(spec: ModelSpec): File = File(dir, spec.fileName)

    fun isReady(spec: ModelSpec): Boolean = finalFile(spec).let { it.isFile && it.length() == spec.sizeBytes }

    suspend fun download(
        spec: ModelSpec,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val target = finalFile(spec)
        if (isReady(spec)) return target
        dir.mkdirs()
        val part = File(dir, "${spec.sha256}.part")
        var offset = if (part.isFile) part.length() else 0L
        if (offset > spec.sizeBytes) {
            part.delete()
            offset = 0
        }
        if (!hasSpace(spec.sizeBytes - offset + CACHE_HEADROOM_BYTES)) throw NotEnoughSpaceException(spec.sizeBytes - offset)

        if (offset < spec.sizeBytes) {
            val request =
                Request
                    .Builder()
                    .url(spec.url)
                    .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                    .build()
            val response =
                try {
                    http.newCall(request).executeAsync()
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw RetryableDownloadException("network")
                }
            response.use { r ->
                val append =
                    when {
                        r.code == 206 && contentRangeStart(r) == offset && contentRangeTotal(r) == spec.sizeBytes -> {
                            true
                        }

                        r.code == 206 -> {
                            part.delete()
                            throw RetryableDownloadException("bad range")
                        }

                        r.code == 200 -> {
                            false
                        }

                        // server ignored Range: start over
                        r.code == 429 || r.code >= 500 -> {
                            throw RetryableDownloadException("status ${r.code}")
                        }

                        else -> {
                            throw IOException("status ${r.code}")
                        }
                    }
                if (!append) offset = 0
                copyBody(r, part, append, offset, spec.sizeBytes, onProgress)
            }
        }
        if (part.length() != spec.sizeBytes) throw RetryableDownloadException("incomplete")
        if (!sha256(part).equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw ModelIntegrityException()
        }
        if (!part.renameTo(target)) throw IOException("rename failed")
        return target
    }

    fun delete(spec: ModelSpec) {
        finalFile(spec).delete()
        File(dir, "${spec.sha256}.part").delete()
    }

    private suspend fun copyBody(
        response: Response,
        part: File,
        append: Boolean,
        start: Long,
        total: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        var done = start
        var lastReported = -1L
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            response.body.byteStream().use { input ->
                FileOutputStream(part, append).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (done + n > total) throw OversizeException()
                        out.write(buffer, 0, n)
                        done += n
                        val percent = done * 100 / total
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(done, total)
                        }
                    }
                    out.fd.sync()
                }
            }
        } catch (e: OversizeException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw RetryableDownloadException("interrupted")
        }
    }

    private fun contentRangeStart(r: Response): Long? = r.header("Content-Range")?.let {
        CONTENT_RANGE
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }

    private fun contentRangeTotal(r: Response): Long? = r.header("Content-Range")?.let {
        CONTENT_RANGE
            .find(it)
            ?.groupValues
            ?.get(3)
            ?.toLongOrNull()
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val CACHE_HEADROOM_BYTES = 1_000_000_000L
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Redirects may only lead to Hugging Face and its CDN. */
        fun httpClient(allowedHost: (String) -> Boolean = ::isHuggingFace): OkHttpClient = OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(60))
            .cache(null)
            .addNetworkInterceptor(
                Interceptor { chain ->
                    if (!allowedHost(chain.request().url.host)) throw IOException("blocked host")
                    chain.proceed(chain.request())
                },
            ).build()

        fun isHuggingFace(host: String): Boolean = host == "huggingface.co" || host.endsWith(".hf.co")
    }
}
