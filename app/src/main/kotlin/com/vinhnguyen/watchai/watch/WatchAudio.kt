package com.vinhnguyen.watchai.watch

import android.os.SystemClock
import android.util.Log
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.voice.ExternalAudio
import com.vinhnguyen.watchai.voice.VoicePhase
import com.vinhnguyen.watchai.watchlink.Adpcm
import com.vinhnguyen.watchai.watchlink.Control
import com.vinhnguyen.watchai.watchlink.Frame
import com.vinhnguyen.watchai.watchlink.FrameCodec
import com.vinhnguyen.watchai.watchlink.LinkStats
import com.vinhnguyen.watchai.watchlink.Outbox
import com.vinhnguyen.watchai.watchlink.Pcm
import com.vinhnguyen.watchai.watchlink.PcmQueue
import com.vinhnguyen.watchai.watchlink.SilenceGate
import com.vinhnguyen.watchai.watchlink.WatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The watch as the microphone and speaker of a phone conversation, over one Data Layer channel.
 * The watch's mic audio waits in a small jitter buffer until WebRTC asks for it; the answer is
 * cut into 40 ms frames and queued to the watch. Two threads do the blocking channel I/O and the
 * ADPCM coding, so WebRTC's audio thread never waits; the answer's silences aren't sent at all.
 */
class WatchAudio(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    override val name: String,
    route: Route,
    private val onEnd: () -> Unit,
) : ExternalAudio {
    @Volatile
    var route: Route = route
        private set

    override val answerOnPhone: Boolean get() = route != Route.WATCH
    override val micOnDevice: Boolean get() = route != Route.HEADSET

    /**
     * At the start of a call the watch sends what the user said while the phone picked up, in one
     * burst: it all goes in, and plays out in real time before the usual half-second limit applies.
     * Ends once that has played out, or soon after the call started if nothing was said.
     *
     * With earbuds as the mic the watch keeps listening until then too: the earbuds only hear
     * once GPT-Live is connected, about 5 s after "Hey Buddy" (28.09), and what was said to the
     * watch meanwhile was lost. Once caught up, the watch is told to hand over.
     */
    @Volatile private var catchUp = true

    @Volatile private var heardEarly = false
    private var startedAt = 0L

    override val catchingUp: Boolean get() = catchUp

    /** Headphones or a headset came or went on the phone: tell the watch what it's used for now. */
    fun setRoute(next: Route) {
        if (next == route) return
        route = next
        if (answerOnPhone) flushAnswer()
        if (!micOnDevice && !catchUp) mic.clear()
        outbox.offer(routeMessage())
    }

    private fun routeMessage() = Frame.Message(Control("route", speaker = route == Route.WATCH, mic = route != Route.HEADSET || catchUp))

    private val mic = PcmQueue(WatchLink.SAMPLE_RATE * EARLY_MAX_S)
    private val outbox = Outbox(maxAudio = OUTBOX_FRAMES)
    private val pending = ShortArray(WatchLink.FRAME_SAMPLES)
    private var pendingCount = 0
    private val pendingLock = Any()

    @Volatile private var lastStatusAt = 0L

    @Volatile private var closed = false
    private var writer: Thread? = null
    private val stats = LinkStats()
    private val asked = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextId = AtomicLong()

    @Volatile private var saying = false

    /**
     * Feeds [pcm] (16 kHz mono) in as if the watch had heard it, in real time, with the watch's own
     * mic ignored meanwhile. Debug builds use it to test calls without talking.
     */
    fun say(pcm: ShortArray) {
        thread(name = "watch-say", isDaemon = true) {
            saying = true
            try {
                for (start in pcm.indices step WatchLink.FRAME_SAMPLES) {
                    if (closed) break
                    mic.offer(pcm.copyOfRange(start, minOf(start + WatchLink.FRAME_SAMPLES, pcm.size)))
                    Thread.sleep(FRAME_MS)
                }
            } finally {
                saying = false
            }
        }
    }

    /** Buddy on the watch: a reaction (played once, for a few seconds) and/or the seed of this user's Buddy. */
    fun mascot(
        reaction: Reaction? = null,
        seed: Long? = null,
    ) {
        outbox.offer(Frame.Message(Control("mascot", mood = reaction?.mood?.wire, level = reaction?.intensity, seed = seed)))
    }

    /** Asks the watch to do something (e.g. set a timer): its answer, or null if none comes in time. */
    suspend fun ask(request: Control): String? {
        val id = nextId.incrementAndGet()
        val answer = CompletableDeferred<String>()
        asked[id] = answer
        outbox.offer(Frame.Message(request.copy(id = id)))
        return try {
            withTimeoutOrNull(ASK_TIMEOUT_MS) { answer.await() }
        } finally {
            asked.remove(id)
        }
    }

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        outbox.offer(Frame.Message(Control("status", phase = "connecting")))
        outbox.offer(routeMessage())
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
                    is Frame.Audio -> {
                        stats.add("inPcm")
                        heard(Pcm.toShorts(frame.pcm))
                    }

                    is Frame.Adpcm -> {
                        stats.add("inAdpcm")
                        heard(Adpcm.decode(frame.packet))
                    }

                    is Frame.Message ->
                        when (frame.control.type) {
                            "ping" -> {
                                stats.add("inPing")
                                outbox.offer(Frame.Message(Control("pong", at = frame.control.at)))
                            }

                            "done" -> frame.control.id?.let { asked[it]?.complete(frame.control.text.orEmpty()) }

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

    /** The watch mic's audio: queued while the watch is the mic, or while the early burst comes in. */
    private fun heard(pcm: ShortArray) {
        if (saying) return
        if (catchUp) {
            heardEarly = true
            mic.offer(pcm)
        } else if (micOnDevice) {
            mic.offer(pcm)
            mic.trimTo(LIVE_MAX_SAMPLES)
        }
    }

    /** Sends everything waiting in one write: each flush costs the watch a Bluetooth round trip. */
    private fun writeLoop() {
        val encoder = Adpcm.Encoder()
        val gate = SilenceGate(threshold = ANSWER_SILENCE, hangoverFrames = ANSWER_HANGOVER)
        var lastReport = SystemClock.elapsedRealtime()
        try {
            while (!closed) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= REPORT_MS) {
                    lastReport = now
                    Log.i(TAG, "phone ${stats.drain()} outbox=${outbox.size} dropped=${outbox.dropped} micQueue=${mic.available}")
                }
                val waiting = outbox.takeAll(200)
                if (waiting.isEmpty()) continue
                val frames =
                    waiting.mapNotNull { frame ->
                        if (frame is Frame.Audio) {
                            val pcm = Pcm.toShorts(frame.pcm)
                            if (gate.shouldSend(Pcm.level(pcm))) {
                                stats.add("outAdpcm")
                                Frame.Adpcm(encoder.encode(pcm))
                            } else {
                                stats.add("outSilent")
                                null
                            }
                        } else {
                            stats.add("outOther")
                            frame
                        }
                    }
                val started = SystemClock.elapsedRealtime()
                FrameCodec.writeAll(output, frames)
                stats.add("writes")
                stats.max("writeMaxMs", SystemClock.elapsedRealtime() - started)
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
        // The early burst has played out (or none came): from the next buffer on, live audio only,
        // and with earbuds as the mic WebRTC stops calling here and the watch stops listening.
        if (catchUp && mic.available == 0 && (heardEarly || SystemClock.elapsedRealtime() - startedAt > CATCH_UP_WAIT_MS)) {
            catchUp = false
            if (!micOnDevice) outbox.offer(routeMessage())
        }
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
                    // If the link can't keep up, the outbox drops the oldest audio rather than fall behind.
                    outbox.offer(Frame.Audio(Pcm.toBytes(pending)))
                    pendingCount = 0
                }
            }
        }
    }

    override fun flushAnswer() {
        synchronized(pendingLock) { pendingCount = 0 }
        outbox.clearAudio()
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
        const val OUTBOX_FRAMES = 25 // 1 s of 40 ms audio frames
        const val STATUS_EVERY_MS = 250L
        const val REPORT_MS = 2_000L
        const val ANSWER_SILENCE = 0.002f
        const val ANSWER_HANGOVER = 5 // 200 ms
        const val ASK_TIMEOUT_MS = 5_000L
        const val FRAME_MS = 40L
        const val TAG = "WatchLink"

        /** Room for the early burst (the watch keeps up to 8 s). */
        const val EARLY_MAX_S = 10

        /** After the early burst, a half second at most waits for WebRTC, so latency can't grow. */
        const val LIVE_MAX_SAMPLES = WatchLink.SAMPLE_RATE / 2

        /** How long a call waits for the early burst before it's plain live audio. */
        const val CATCH_UP_WAIT_MS = 2_500L
    }
}
