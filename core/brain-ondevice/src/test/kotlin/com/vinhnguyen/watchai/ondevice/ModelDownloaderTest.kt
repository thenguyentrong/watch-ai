package com.vinhnguyen.watchai.ondevice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class ModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    /** 10 MB of deterministic synthetic bytes standing in for a model file. */
    private val model = Random(42).nextBytes(10 * 1024 * 1024)
    private val sha = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = temp.newFolder("models")
    }

    @After
    fun tearDown() = server.close()

    private fun spec(hash: String = sha) = ModelSpec(
        id = "synthetic",
        displayName = "Synthetic",
        url = server.url("/model.litertlm").toString(),
        sizeBytes = model.size.toLong(),
        sha256 = hash,
        license = "none",
        licenseUrl = "https://example.invalid",
        source = "test",
        minRamGb = 0,
    )

    private fun downloader(hasSpace: (Long) -> Boolean = { true }) = ModelDownloader(ModelDownloader.httpClient { it == "localhost" || it == "127.0.0.1" }, dir, hasSpace)

    private fun body(
        bytes: ByteArray,
        code: Int = 200,
        range: String? = null,
    ) = MockResponse
        .Builder()
        .code(code)
        .apply { if (range != null) addHeader("Content-Range", range) }
        .body(Buffer().write(bytes))
        .build()

    @Test
    fun `fresh download is verified and renamed`() = runBlocking {
        server.enqueue(body(model))
        val file = downloader().download(spec())
        assertThat(file.name).isEqualTo("$sha.litertlm")
        assertThat(file.readBytes()).isEqualTo(model)
        assertThat(File(dir, "$sha.part").exists()).isFalse()
        assertThat(downloader().isReady(spec())).isTrue()
    }

    @Test
    fun `resumes from a partial file with a range request`() = runBlocking {
        val half = model.size / 2
        File(dir, "$sha.part").writeBytes(model.copyOfRange(0, half))
        server.enqueue(body(model.copyOfRange(half, model.size), 206, "bytes $half-${model.size - 1}/${model.size}"))
        val file = downloader().download(spec())
        assertThat(server.takeRequest().headers["Range"]).isEqualTo("bytes=$half-")
        assertThat(file.readBytes()).isEqualTo(model)
    }

    @Test
    fun `a server that ignores range makes it start over`() = runBlocking {
        File(dir, "$sha.part").writeBytes(model.copyOfRange(0, 1_000))
        server.enqueue(body(model))
        assertThat(downloader().download(spec()).readBytes()).isEqualTo(model)
    }

    @Test
    fun `a wrong hash is rejected and nothing usable is left behind`() = runBlocking {
        server.enqueue(body(model))
        val wrong = "0".repeat(64)
        val error = runCatching { downloader().download(spec(wrong)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ModelIntegrityException::class.java)
        assertThat(dir.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `more bytes than the catalog says is refused`() = runBlocking {
        server.enqueue(body(model + byteArrayOf(1, 2, 3)))
        val error = runCatching { downloader().download(spec()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(OversizeException::class.java)
        assertThat(downloader().isReady(spec())).isFalse()
    }

    @Test
    fun `server errors are retryable, missing space is not`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertThat(runCatching { downloader().download(spec()) }.exceptionOrNull())
            .isInstanceOf(RetryableDownloadException::class.java)
        assertThat(runCatching { downloader { false }.download(spec()) }.exceptionOrNull())
            .isInstanceOf(NotEnoughSpaceException::class.java)
    }

    @Test
    fun `only hugging face hosts are allowed`() {
        assertThat(ModelDownloader.isHuggingFace("huggingface.co")).isTrue()
        assertThat(ModelDownloader.isHuggingFace("us.aws.cdn.hf.co")).isTrue()
        assertThat(ModelDownloader.isHuggingFace("evil-hf.co")).isFalse()
        assertThat(ModelDownloader.isHuggingFace("huggingface.co.evil.example")).isFalse()
    }

    @Test
    fun `the shipped catalog parses and pins every model`() {
        val json = File("src/main/assets/models.json").readText()
        val specs = ModelCatalog.parse(json)
        assertThat(specs.map { it.id }).contains(ModelCatalog.DEFAULT_ID)
        specs.forEach {
            assertThat(it.sha256).matches("[0-9a-f]{64}")
            assertThat(it.url).startsWith("https://huggingface.co/")
            assertThat(it.url).contains("/resolve/")
            assertThat(it.sizeBytes).isGreaterThan(1_000_000_000L)
        }
    }
}
