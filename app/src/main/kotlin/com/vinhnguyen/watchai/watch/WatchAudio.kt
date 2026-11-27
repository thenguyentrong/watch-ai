package com.vinhnguyen.watchai.watch

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.voice.ExternalAudio
import com.vinhnguyen.watchai.voice.VoicePhase
import com.vinhnguyen.watchai.watchlink.Adpcm
import com.vinhnguyen.watchai.watchlink.Chime
import com.vinhnguyen.watchai.watchlink.Control
import com.vinhnguyen.watchai.watchlink.Frame
import com.vinhnguyen.watchai.watchlink.FrameCodec
import com.vinhnguyen.watchai.watchlink.JitterBuffer
import com.vinhnguyen.watchai.watchlink.LinkStats
import com.vinhnguyen.watchai.watchlink.Outbox
import com.vinhnguyen.watchai.watchlink.Pcm
import com.vinhnguyen.watchai.watchlink.SilenceGate
import com.vinhnguyen.watchai.watchlink.WatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    private val mic =
        JitterBuffer(
            maxSamples = WatchLink.SAMPLE_RATE * EARLY_MAX_S,
            prebufferSamples = MIC_PREBUFFER_SAMPLES,
            maxHoldMs = MIC_HOLD_MS,
            clockMs = SystemClock::elapsedRealtime,
        )
    private val outbox = Outbox(maxAudio = OUTBOX_FRAMES)
    private val pending = ShortArray(WatchLink.FRAME_SAMPLES)
    private var pendingCount = 0
    private val answerEncoder = Adpcm.Encoder()
    private val answerGate = SilenceGate(threshold = ANSWER_SILENCE, hangoverFrames = ANSWER_HANGOVER)
    private var previousAnswer: ShortArray? = null
    private val pendingLock = Any()

    @Volatile private var lastStatusAt = 0L

    @Volatile private var lastPhase: VoicePhase? = null

    @Volatile private var chimed = false

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
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
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

    /** Sends waiting frames in coalesced batches: each flush costs the Bluetooth link a round trip. */
    private fun writeLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        var lastReport = SystemClock.elapsedRealtime()
        try {
            while (!closed) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= REPORT_MS) {
                    lastReport = now
                    Log.i(TAG, "phone ${stats.drain()} outbox=${outbox.size} dropped=${outbox.dropped} micQueue=${mic.available} micUnderruns=${mic.underruns}")
                }
                val frames = outbox.takeBatch(timeoutMs = 200, batchWindowMs = SEND_BATCH_MS, minAudioFrames = SEND_BATCH_FRAMES)
                if (frames.isEmpty()) continue
                for (frame in frames) {
                    if (frame !is Frame.Adpcm && frame !is Frame.Audio) stats.add("outOther")
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
                    offerAnswerFrameLocked(pending)
                    pendingCount = 0
                }
            }
        }
    }

    /** Silence-gates and encodes a 40 ms answer frame before it enters [outbox], so silence never evicts speech. */
    private fun offerAnswerFrameLocked(frame: ShortArray) {
        val level = Pcm.level(frame)
        val wasOpen = previousAnswer == null
        if (answerGate.shouldSend(level)) {
            // Include the frame right before speech starts so soft first consonants aren't clipped.
            if (!wasOpen) {
                previousAnswer?.let {
                    outbox.offer(Frame.Adpcm(answerEncoder.encode(it)))
                    stats.add("outAdpcm")
                }
            }
            outbox.offer(Frame.Adpcm(answerEncoder.encode(frame)))
            stats.add("outAdpcm")
            previousAnswer = null
        } else {
            stats.add("outSilent")
            previousAnswer = frame.copyOf()
        }
    }

    /** The chime into the earbuds or headphones, on the call's audio so it goes where the answers go. */
    private fun chimeOnPhone() {
        thread(name = "watch-chime", isDaemon = true) {
            runCatching {
                val pcm = Chime.pcm(WatchLink.SAMPLE_RATE)
                val track =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(WatchLink.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(pcm.size * 1_000L / WatchLink.SAMPLE_RATE + 100)
                } finally {
                    track.release()
                }
            }
        }
    }

    /**
     * Something private said by the phone's own voice, to the watch's speaker in real time (the
     * outbox only holds a second and drops the oldest if it overflows).
     */
    override suspend fun playLocal(
        samples: ShortArray,
        sampleRate: Int,
    ) {
        val pcm = Pcm.resample(samples, sampleRate, WatchLink.SAMPLE_RATE)
        val started = SystemClock.elapsedRealtime()
        var at = 0
        while (at < pcm.size && !closed) {
            val frame = pcm.copyOfRange(at, minOf(at + WatchLink.FRAME_SAMPLES, pcm.size)).copyOf(WatchLink.FRAME_SAMPLES)
            val packet = synchronized(pendingLock) { answerEncoder.encode(frame) }
            outbox.offer(Frame.Adpcm(packet))
            stats.add("outAdpcm")
            at += WatchLink.FRAME_SAMPLES
            val wait = started + at * 1_000L / WatchLink.SAMPLE_RATE - LOCAL_LEAD_MS - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
        }
        // Until the last of it has played on the watch.
        delay(LOCAL_LEAD_MS + LOCAL_TAIL_MS)
    }

    override fun flushAnswer() {
        synchronized(pendingLock) {
            pendingCount = 0
            previousAnswer = null
            answerGate.reset()
        }
        outbox.clearAudio()
        outbox.offer(Frame.Flush)
    }

    override fun status(
        phase: VoicePhase,
        level: Float,
    ) {
        // Listening for the first time: a chime where the answers play. The watch chimes itself.
        if (phase == VoicePhase.LISTENING && !chimed) {
            chimed = true
            if (answerOnPhone) chimeOnPhone()
        }
        val phaseChanged = phase != lastPhase
        if (!phaseChanged) {
            // While the watch is the mic it measures listening loudness locally; skip redundant Bluetooth traffic.
            if (phase == VoicePhase.LISTENING && micOnDevice) return
            if (phase == VoicePhase.THINKING) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatusAt < STATUS_EVERY_MS) return
        }
        lastPhase = phase
        lastStatusAt = SystemClock.elapsedRealtime()
        outbox.offer(Frame.Message(Control("status", phase = phase.name.lowercase(), level = level)), urgent = phaseChanged)
    }

    private companion object {
        const val OUTBOX_FRAMES = 40 // 1.6 s of 40 ms audio frames
        const val SEND_BATCH_FRAMES = 3 // 120 ms (~980 bytes) per Bluetooth flush
        const val SEND_BATCH_MS = 95L
        const val LOCAL_LEAD_MS = 300L
        const val LOCAL_TAIL_MS = 300L
        const val STATUS_EVERY_MS = 250L
        const val REPORT_MS = 2_000L
        const val ANSWER_SILENCE = 0.002f
        const val ANSWER_HANGOVER = 12 // 480 ms: keeps speech continuous across natural word pauses
        const val ASK_TIMEOUT_MS = 5_000L
        const val FRAME_MS = 40L
        const val TAG = "WatchLink"

        /** Room for the early burst (the watch keeps up to 8 s). */
        const val EARLY_MAX_S = 10

        /** Pre-buffer for watch mic bursts so Bluetooth jitter doesn't punch silence holes into speech. */
        const val MIC_PREBUFFER_SAMPLES = WatchLink.FRAME_SAMPLES * 4 // 160 ms
        const val MIC_HOLD_MS = 160L

        /** After the early burst, keep latency bounded while leaving headroom for batches and jitter. */
        const val LIVE_MAX_SAMPLES = WatchLink.SAMPLE_RATE * 640 / 1_000

        /** How long a call waits for the early burst before it's plain live audio. */
        const val CATCH_UP_WAIT_MS = 2_500L
    }
}
