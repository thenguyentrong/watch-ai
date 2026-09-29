package com.vinhnguyen.watchai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * The phone's own voice, for private things (messages, notes, the calendar): Android's speech
 * engine turns text into audio on the phone, with a voice that needs no network, so what it says
 * never goes anywhere. The audio comes back as samples, to be played wherever the conversation
 * plays (the watch, earbuds, the phone).
 */
class LocalSpeech(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech =
        TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    class Audio(
        val samples: ShortArray,
        val sampleRate: Int,
    ) {
        val millis: Long get() = samples.size * 1_000L / sampleRate
    }

    private val lock = Mutex()

    /**
     * [text] spoken in [language] (a BCP 47 tag; null: the phone's own language), or null when the
     * phone has no offline voice for that language or the engine fails. Never another language's voice
     * reading it, and never a network voice: the text is private. One at a time.
     */
    suspend fun synthesize(
        text: String,
        language: String? = null,
    ): Audio? = lock.withLock {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return@withLock null
        val voice = offlineVoice(language) ?: return@withLock null
        withContext(Dispatchers.IO) {
            // A file in the app's own cache, read and deleted straight away.
            val file = File(appContext.cacheDir, "speech-${UUID.randomUUID()}.wav")
            try {
                val id = UUID.randomUUID().toString()
                val done = CompletableDeferred<Boolean>()
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == id) done.complete(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == id) done.complete(false)
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) {
                            if (utteranceId == id) done.complete(false)
                        }
                    },
                )
                tts.voice = voice
                val params = android.os.Bundle().apply { putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false") }
                if (tts.synthesizeToFile(text, params, file, id) != TextToSpeech.SUCCESS) return@withContext null
                if (withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } != true) return@withContext null
                Wav.read(file.readBytes())
            } finally {
                file.delete()
            }
        }
    }

    /** The languages the phone can speak offline, as tags, for the checks. */
    suspend fun languages(): Set<String> {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return emptySet()
        return offlineVoices().map { it.locale.language }.toSortedSet()
    }

    /** Whether the phone has an offline voice for [language] (null: the phone's own language). */
    suspend fun canSpeak(language: String?): Boolean {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) return false
        return offlineVoice(language) != null
    }

    private fun offlineVoices(): List<Voice> = runCatching { tts.voices }.getOrNull().orEmpty().filter {
        !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
    }

    /**
     * The best installed offline voice for [language], in its region if the tag has one ("pt-BR"),
     * else in the phone's region if there's a voice for it. Without a language, the phone's own.
     * Null when there's no voice for that language: another language's voice would only garble it.
     */
    private fun offlineVoice(language: String?): Voice? {
        val phone = Locale.getDefault()
        val wanted = language?.let { Locale.forLanguageTag(it) }?.takeIf { it.language.isNotEmpty() } ?: phone
        val voices = offlineVoices().filter { it.locale.language == wanted.language }
        val region = wanted.country.ifEmpty { phone.country }
        return voices.filter { it.locale.country == region }.maxByOrNull { it.quality } ?: voices.maxByOrNull { it.quality }
    }

    /** Plays [audio] on the phone, outside a conversation (to hear what the phone's voice sounds like). */
    suspend fun play(audio: Audio) = playOnPhone(audio, ASSISTANT_SPEECH)

    fun shutdown() = tts.shutdown()

    private companion object {
        const val INIT_TIMEOUT_MS = 3_000L
        const val SYNTH_TIMEOUT_MS = 15_000L
    }
}

/** Reads the 16-bit PCM out of a WAV file, down to one channel. */
internal object Wav {
    fun read(bytes: ByteArray): LocalSpeech.Audio? {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            // Some engines write a data size of 0 or -1 while streaming: then the data runs to the end.
            val size = b.getInt(at + 4).let { if (it <= 0 || at + 8 + it > bytes.size) bytes.size - at - 8 else it }
            val body = at + 8
            when (id) {
                "fmt " -> {
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }

                "data" -> {
                    if (bits != 16 || channels < 1 || rate <= 0) return null
                    val frames = size / 2 / channels
                    val mono = ShortArray(frames) { i -> b.getShort(body + i * channels * 2) }
                    return LocalSpeech.Audio(mono, rate)
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }
}

private val ASSISTANT_SPEECH: android.media.AudioAttributes =
    android.media.AudioAttributes
        .Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/**
 * Plays [audio] through the phone with [attributes] (so it goes where the conversation's audio
 * goes), streamed in small pieces, and returns once it has played. Stops when cancelled.
 */
internal suspend fun playOnPhone(
    audio: LocalSpeech.Audio,
    attributes: android.media.AudioAttributes,
) = withContext(Dispatchers.IO) {
    val format =
        android.media.AudioFormat
            .Builder()
            .setSampleRate(audio.sampleRate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .build()
    val min = android.media.AudioTrack.getMinBufferSize(audio.sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
    val bufferBytes = maxOf(min * 4, 16_384)
    val track =
        android.media.AudioTrack
            .Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
    try {
        track.play()
        var at = 0
        while (at < audio.samples.size) {
            ensureActive()
            val written = track.write(audio.samples, at, minOf(CHUNK_SAMPLES, audio.samples.size - at))
            if (written <= 0) break
            at += written
        }
        // What's still in the track's buffer plays out.
        delay(bufferBytes / 2 * 1_000L / audio.sampleRate + 100)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}

private const val CHUNK_SAMPLES = 2_048
