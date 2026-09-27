package com.vinhnguyen.watchai.watch

import android.os.SystemClock
import com.vinhnguyen.watchai.voice.ExternalAudio
import com.vinhnguyen.watchai.voice.VoicePhase
import com.vinhnguyen.watchai.watchlink.Control
import com.vinhnguyen.watchai.watchlink.Frame
import com.vinhnguyen.watchai.watchlink.FrameCodec
import com.vinhnguyen.watchai.watchlink.Pcm
import com.vinhnguyen.watchai.watchlink.PcmQueue
import com.vinhnguyen.watchai.watchlink.WatchLink
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The watch as the microphone and speaker of a phone conversation, over one Data Layer channel.
 * The watch's mic audio waits in a small jitter buffer until WebRTC asks for it; the answer is
 * cut into 20 ms frames and queued to the watch. Two threads do the blocking channel I/O.
 */
class WatchAudio(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    override val name: String,
    private val onEnd: () -> Unit,
) : ExternalAudio {
    private val mic = PcmQueue(WatchLink.SAMPLE_RATE / 2)
    private val outbox = LinkedBlockingQueue<Frame>(OUTBOX_FRAMES)
    private val pending = ShortArray(WatchLink.FRAME_SAMPLES)
    private var pendingCount = 0
    private val pendingLock = Any()

    @Volatile private var lastStatusAt = 0L

    @Volatile private var closed = false
    private var writer: Thread? = null

    fun start() {
        outbox.offer(Frame.Message(Control("status", phase = "connecting")))
        thread(name = "watch-in", isDaemon = true) { readLoop() }
        writer = thread(name = "watch-out", isDaemon = true) { writeLoop() }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { FrameCodec.write(output, Frame.Message(Control("bye"))) }
        writer?.interrupt()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                when (val frame = FrameCodec.read(input) ?: break) {
                    is Frame.Audio -> mic.offer(Pcm.toShorts(frame.pcm))

                    is Frame.Message ->
                        when (frame.control.type) {
                            "ping" -> outbox.offer(Frame.Message(Control("pong", at = frame.control.at)))
                            "bye" -> break
                        }

                    Frame.Flush -> Unit
                }
            }
        } catch (e: IOException) {
            // The watch went away; handled below.
        }
        if (!closed) onEnd()
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val frame = outbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
                FrameCodec.write(output, frame)
            }
        } catch (e: IOException) {
            if (!closed) onEnd()
        } catch (e: InterruptedException) {
            // close()
        }
    }

    override fun fillMicrophone(
        buffer: ByteBuffer,
        bytes: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        val frames = bytes / 2 / channels
        val wanted = (frames.toLong() * WatchLink.SAMPLE_RATE / sampleRate).toInt()
        val samples = Pcm.resample(mic.take(wanted), WatchLink.SAMPLE_RATE, sampleRate)
        // WebRTC reads the buffer from its start; write there without moving its position.
        val out = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = if (i < samples.size) samples[i] else 0
            for (ch in 0 until channels) out.putShort((i * channels + ch) * 2, v)
        }
    }

    override fun playAnswer(
        data: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ) {
        if (closed || channels < 1) return
        val src = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = src.position()
        val mono = ShortArray(frames) { src.getShort(start + it * channels * 2) }
        val down = Pcm.resample(mono, sampleRate, WatchLink.SAMPLE_RATE)
        synchronized(pendingLock) {
            for (s in down) {
                pending[pendingCount++] = s
                if (pendingCount == pending.size) {
                    // If the link can't keep up, drop audio rather than fall ever further behind.
                    outbox.offer(Frame.Audio(Pcm.toBytes(pending)))
                    pendingCount = 0
                }
            }
        }
    }

    override fun flushAnswer() {
        synchronized(pendingLock) { pendingCount = 0 }
        outbox.removeIf { it is Frame.Audio }
        outbox.offer(Frame.Flush)
    }

    override fun status(
        phase: VoicePhase,
        level: Float,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < STATUS_EVERY_MS) return
        lastStatusAt = now
        outbox.offer(Frame.Message(Control("status", phase = phase.name.lowercase(), level = level)))
    }

    private companion object {
        const val OUTBOX_FRAMES = 150 // 3 s of 20 ms audio frames
        const val STATUS_EVERY_MS = 100L
    }
}
