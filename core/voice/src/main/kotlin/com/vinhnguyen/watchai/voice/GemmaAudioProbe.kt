package com.vinhnguyen.watchai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.vinhnguyen.watchai.ondevice.EngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/**
 * Experiment: can Gemma 4 listen directly? Records a few seconds, sends the audio itself to the
 * model (no speech recogniser), and times the reply. Loads its own engine with an audio backend.
 */
class GemmaAudioProbe(
    context: Context,
    private val holder: EngineHolder,
) {
    private val appContext = context.applicationContext

    data class Result(
        val supportsAudio: Boolean,
        val recordMs: Long = 0,
        val loadMs: Long = 0,
        val firstTokenMs: Long? = null,
        val totalMs: Long = 0,
        val answer: String = "",
        val error: String? = null,
    )

    suspend fun supportsAudio(modelPath: String): Boolean = withContext(Dispatchers.Default) {
        runCatching { Capabilities(modelPath).use { it.inputModalities().audio } }.getOrDefault(false)
    }

    suspend fun recordAndAsk(
        modelPath: String,
        seconds: Int = 4,
    ): Result = withContext(Dispatchers.Default) {
        if (!supportsAudio(modelPath)) return@withContext Result(supportsAudio = false, error = "model has no audio input")
        val recordStart = System.currentTimeMillis()
        val wav = record(seconds)
        val recordMs = System.currentTimeMillis() - recordStart
        holder.unloadNow()
        val loadStart = System.currentTimeMillis()
        val cache = File(appContext.noBackupFilesDir, "litertlm-cache").apply { mkdirs() }.path
        val engine =
            try {
                Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        audioBackend = Backend.CPU(),
                        maxNumTokens = 2_048,
                        cacheDir = cache,
                    ),
                ).also { it.initialize() }
            } catch (t: Throwable) {
                return@withContext Result(true, recordMs, error = "engine: ${t::class.simpleName}")
            }
        val loadMs = System.currentTimeMillis() - loadStart
        try {
            val conversation =
                engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(OnDeviceVoicePrompt.SYSTEM),
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7),
                        maxOutputToken = 160,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    ),
                )
            conversation.use {
                val started = System.currentTimeMillis()
                var first: Long? = null
                val text = StringBuilder()
                val error =
                    suspendCancellableCoroutine { cont ->
                        it.sendMessageAsync(
                            Contents.of(Content.AudioBytes(wav), Content.Text("Reply to what I just said.")),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    val chunk = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
                                    if (chunk.isNotEmpty() && first == null) first = System.currentTimeMillis() - started
                                    text.append(chunk)
                                }

                                override fun onDone() {
                                    if (cont.isActive) cont.resume(null)
                                }

                                override fun onError(throwable: Throwable) {
                                    if (cont.isActive) cont.resume(throwable::class.simpleName ?: "error")
                                }
                            },
                        )
                    }
                Result(true, recordMs, loadMs, first, System.currentTimeMillis() - started, text.toString(), error)
            }
        } finally {
            runCatching { engine.close() }
        }
    }

    @SuppressLint("MissingPermission") // the lab screen asks for RECORD_AUDIO first
    private fun record(seconds: Int): ByteArray {
        val rate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        val pcm = ByteArrayOutputStream()
        val chunk = ByteArray(minBuffer)
        try {
            record.startRecording()
            val until = System.currentTimeMillis() + seconds * 1_000L
            while (System.currentTimeMillis() < until) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) pcm.write(chunk, 0, n)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return wav(pcm.toByteArray(), rate)
    }

    private fun wav(
        pcm: ByteArray,
        rate: Int,
    ): ByteArray {
        val header =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(rate)
                putInt(rate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm.size)
            }
        return header.array() + pcm
    }
}

object OnDeviceVoicePrompt {
    const val SYSTEM: String =
        "You are a friendly voice assistant on a smartwatch. The user speaks to you. Reply naturally in one or " +
            "two short sentences, plain text, in the language the user speaks."
}
