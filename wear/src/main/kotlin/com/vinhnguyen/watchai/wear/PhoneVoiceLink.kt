package com.vinhnguyen.watchai.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.watchlink.Adpcm
import com.vinhnguyen.watchai.watchlink.Chime
import com.vinhnguyen.watchai.watchlink.Control
import com.vinhnguyen.watchai.watchlink.EarlySpeech
import com.vinhnguyen.watchai.watchlink.Frame
import com.vinhnguyen.watchai.watchlink.FrameCodec
import com.vinhnguyen.watchai.watchlink.LinkStats
import com.vinhnguyen.watchai.watchlink.Outbox
import com.vinhnguyen.watchai.watchlink.Pcm
import com.vinhnguyen.watchai.watchlink.PcmQueue
import com.vinhnguyen.watchai.watchlink.SilenceGate
import com.vinhnguyen.watchai.watchlink.WatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The watch side of a conversation: opens a voice channel to the phone app, sends the watch's
 * microphone and plays the answer on the watch speaker. The phone does the talking to ChatGPT, and
 * says which of the two the watch does: with earbuds on the phone, the watch may do neither.
 */
class PhoneVoiceLink private constructor(
    context: Context,
) {
    enum class Phase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Loudness 0..1 of whoever talks: the user while listening, the answer while speaking. */
        val level: Float = 0f,
        val detail: String? = null,
        val roundTripMs: Long? = null,
        /** False while earbuds on the phone play the answer. */
        val answersOnWatch: Boolean = true,
        /** False while earbuds on the phone are the microphone too: the watch only shows the face. */
        val micOnWatch: Boolean = true,
        /** Buddy's latest reaction from the phone; [reactionId] goes up with each, so a repeat plays again. */
        val reaction: Reaction? = null,
        val reactionId: Int = 0,
    )

    private val appContext = context.applicationContext
    private val channels = Wearable.getChannelClient(appContext)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null

    /** For stopping from inside the call (the phone hung up): stop() cancels the call's own scope. */
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: ChannelClient.Channel? = null
    private var output: DataOutputStream? = null
    private val playback = PcmQueue(WatchLink.SAMPLE_RATE * 3)
    private var previousMode = AudioManager.MODE_NORMAL
    private var audioFocus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var flushRequested = false
    private val stats = LinkStats()

    /** What the mic and the pings have for the phone; the sender takes it all at once. */
    private val outbox = Outbox(maxAudio = OUTBOX_FRAMES)

    @Volatile private var micLevel = 0f

    /** The phone picked up (sent its first frame). Until then the mic only fills [early]. */
    @Volatile private var answered = false

    /** What the user says while the phone picks up ("Hey Buddy, what time is it?" in one breath). */
    private val early = EarlySpeech(maxFrames = EARLY_FRAMES, voiceLevel = MIC_SILENCE)

    @Volatile private var earlySent = false

    @Volatile private var chimed = false

    /** Whether the watch plays the answer and records the user; null until the phone says (it does so right away). */
    @Volatile private var speakerOn: Boolean? = null

    @Volatile private var micOn: Boolean? = null

    /** The phone said goodbye: the conversation ended normally (hung up, or nobody talked for a while). */
    @Volatile private var phoneHungUp = false

    /** The app's screen is showing (set by the activity): only then can it open the watch's clock for the phone. */
    @Volatile var onScreen = false

    private val actions = WatchActions(appContext)

    private val prefs = appContext.getSharedPreferences("wear", Context.MODE_PRIVATE)

    /** This user's Buddy: kept on the watch, updated by the phone at every call. Until the first call, a Buddy of this watch's own. */
    private val _genes = MutableStateFlow(Genes.of(prefs.getLong(SEED, 0L).takeIf { it != 0L } ?: Random.nextLong().also { seed -> prefs.edit { putLong(SEED, seed) } }))
    val genes: StateFlow<Genes> = _genes.asStateFlow()

    suspend fun start() {
        lifecycle.withLock {
            if (scope != null) return
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _state.value = State(Phase.ERROR, detail = "Microphone permission needed")
                return
            }
            answered = false
            early.drain()
            earlySent = false
            chimed = false
            speakerOn = null
            micOn = null
            phoneHungUp = false
            flushRequested = false
            playback.clear()
            outbox.clearAudio()
            acquireWakeLock()
            _state.value = State(Phase.CONNECTING, detail = "Calling your phone…")
            Log.i(TAG, "calling the phone")
            CallService.talk(appContext)
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch { run(s) }
        }
    }

    suspend fun stop() {
        lifecycle.withLock {
            val s = scope ?: return
            scope = null
            runCatching { output?.let { FrameCodec.write(it, Frame.Message(Control("bye"))) } }
            channel?.let { runCatching { channels.close(it).await() } }
            channel = null
            output = null
            s.coroutineContext[Job]?.cancelAndJoin()
            playback.clear()
            restoreAudio()
            releaseWakeLock()
            Log.i(TAG, "call ended")
            CallService.talked()
            if (answered) buzz(VibrationEffect.EFFECT_DOUBLE_CLICK)
            _state.value = State()
        }
    }

    /** Ends any conversation without waiting (e.g. when the screen goes away). */
    fun release() {
        cleanup.launch { stop() }
    }

    private suspend fun run(s: CoroutineScope) {
        try {
            // The mic opens at once: what the user says while the phone picks up is kept, not lost.
            s.launch { listen() }
            // A phone that never picks up must not keep the watch's mic open.
            s.launch {
                delay(ANSWER_TIMEOUT_MS)
                if (!answered) {
                    Log.w(TAG, "the phone didn't answer")
                    cleanup.launch {
                        stop()
                        _state.value = State(Phase.ERROR, detail = "Your phone didn't answer")
                    }
                }
            }
            val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw IOException("Phone not connected")
            val opened = channels.openChannel(phone.id, WatchLink.VOICE_PATH).await()
            channel = opened
            val input = DataInputStream(BufferedInputStream(channels.getInputStream(opened).await()))
            val out = DataOutputStream(BufferedOutputStream(channels.getOutputStream(opened).await()))
            output = out
            s.launch { speak() }
            s.launch { send(out) }
            s.launch { ping() }
            runOnDedicatedThread("watch-receive", Process.THREAD_PRIORITY_DISPLAY) { receive(input) }
            // The phone closed the channel: back to the idle face if it said goodbye, else the link broke.
            if (scope != null) {
                val ended = phoneHungUp
                cleanup.launch {
                    stop()
                    if (!ended) _state.value = State(Phase.ERROR, detail = "Lost the connection to your phone")
                }
            }
        } catch (e: Exception) {
            if (!currentScopeActive(s)) return
            Log.w(TAG, "call failed: ${e::class.simpleName}")
            val detail = e.message ?: "Could not reach the phone"
            // Hang up properly: the mic closes, "Hey Buddy" listens again and a tap tries again.
            // Only showing the error left the call "on": the wake word stayed paused, taps did nothing.
            cleanup.launch {
                stop()
                _state.value = State(Phase.ERROR, detail = detail)
            }
        }
    }

    private fun currentScopeActive(s: CoroutineScope) = s.coroutineContext[Job]?.isActive == true

    /** Frames from the phone: the answer's audio, "stop playing" when the user interrupts, and status. */
    private fun receive(input: DataInputStream) {
        while (true) {
            val frame = FrameCodec.read(input) ?: return
            if (!answered) buzz(VibrationEffect.EFFECT_CLICK)
            answered = true
            when (frame) {
                is Frame.Audio -> {
                    stats.add("inPcm")
                    playback.offer(Pcm.toShorts(frame.pcm))
                }

                is Frame.Adpcm -> {
                    stats.add("inAdpcm")
                    playback.offer(Adpcm.decode(frame.packet))
                }

                Frame.Flush -> {
                    flushRequested = true
                    playback.clear()
                }

                is Frame.Message -> {
                    stats.add("inMsg")
                    handle(frame.control)
                }
            }
        }
    }

    private fun handle(control: Control) {
        when (control.type) {
            "status" -> {
                val phase = runCatching { Phase.valueOf(control.phase.orEmpty().uppercase()) }.getOrNull() ?: return
                // Listening for the first time: a chime on the watch, unless the answers play on the phone (which chimes itself).
                if (phase == Phase.LISTENING && !chimed) {
                    chimed = true
                    if (speakerOn == true) playback.offer(Chime.pcm(WatchLink.SAMPLE_RATE))
                }
                // Listening: show the user's own loudness, measured here with no delay (unless earbuds are the mic).
                val level = if (phase == Phase.LISTENING && micOn != false) micLevel else control.level ?: 0f
                _state.update { it.copy(phase = phase, level = level, detail = null) }
            }

            "pong" ->
                control.at?.let { at ->
                    val rtt = SystemClock.elapsedRealtime() - at
                    Log.i(TAG, "link round trip $rtt ms")
                    _state.update { it.copy(roundTripMs = rtt) }
                }

            "bye" -> phoneHungUp = true

            "timer", "alarm", "battery", "locked" -> outbox.offer(Frame.Message(Control("done", id = control.id, text = actions.run(control, onScreen))))

            "mascot" -> {
                control.seed?.takeIf { it != _genes.value.seed }?.let { seed ->
                    prefs.edit { putLong(SEED, seed) }
                    _genes.value = Genes.of(seed)
                }
                Mood.of(control.mood)?.let { mood ->
                    _state.update { it.copy(reaction = Reaction(mood, control.level ?: 0.6f), reactionId = it.reactionId + 1) }
                }
            }

            "route" -> {
                val speaker = control.speaker ?: true
                val mic = control.mic ?: true
                speakerOn = speaker
                micOn = mic
                _state.update { it.copy(answersOnWatch = speaker, micOnWatch = mic) }
            }
        }
    }

    /** The watch microphone, whenever the phone wants it (not while earbuds on the phone are the mic). */
    private suspend fun listen() = withContext(Dispatchers.IO) {
        while (isActive) {
            if (micOn == false) {
                delay(100)
                continue
            }
            record()
        }
    }

    /** Records echo-cancelled 40 ms frames for the phone until the phone switches the mic off. */
    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun record() = runOnDedicatedThread("watch-record", Process.THREAD_PRIORITY_URGENT_AUDIO) { running ->
        val minBuffer = AudioRecord.getMinBufferSize(WatchLink.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                WatchLink.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, WatchLink.FRAME_BYTES * 8),
            )
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        // ADPCM keeps the Bluetooth link light; silence isn't sent (the phone feeds silence itself).
        val encoder = Adpcm.Encoder()
        val gate = SilenceGate(threshold = MIC_SILENCE, hangoverFrames = MIC_HANGOVER)
        var previous: ShortArray? = null
        val frame = ShortArray(WatchLink.FRAME_SAMPLES)
        var lastLevelAt = 0L
        try {
            record.startRecording()
            Log.i(TAG, "mic open, audio mode ${audioManager.mode}")
            while (running() && micOn != false) {
                val readStart = SystemClock.elapsedRealtime()
                val n = record.read(frame, 0, frame.size)
                stats.add("reads")
                stats.max("readMaxMs", SystemClock.elapsedRealtime() - readStart)
                if (n < frame.size) stats.add("readShort")
                if (n <= 0) continue
                micLevel = Pcm.level(frame, n)
                stats.max("micPeak", (micLevel * 10_000).toLong())
                // The face follows the voice at 10 updates a second; more only costs the watch CPU.
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= LEVEL_EVERY_MS && _state.value.phase == Phase.LISTENING) {
                    lastLevelAt = now
                    _state.update { it.copy(level = micLevel) }
                }
                if (!answered) {
                    early.add(frame.copyOf(n), micLevel)
                    continue
                }
                if (!earlySent) sendEarly(encoder)
                val pcm = frame.copyOf(n)
                val wasOpen = previous == null
                if (gate.shouldSend(micLevel)) {
                    // The frame before speech starts too, so soft first sounds aren't clipped.
                    if (!wasOpen) previous?.let { outbox.offer(Frame.Adpcm(encoder.encode(it))) }
                    outbox.offer(Frame.Adpcm(encoder.encode(pcm)))
                    stats.add("outAdpcm")
                    previous = null
                } else {
                    stats.add("outSilent")
                    previous = pcm
                }
            }
        } finally {
            // Earbuds on the phone took over as the mic: the phone still takes what was said before.
            if (answered && !earlySent) sendEarly(encoder)
            runCatching { record.stop() }
            aec?.release()
            record.release()
            micLevel = 0f
        }
    }

    /** What was said before the phone picked up, all at once and ahead of the live audio. */
    private fun sendEarly(encoder: Adpcm.Encoder) {
        earlySent = true
        val frames = early.drain()
        if (frames.isEmpty()) return
        outbox.offerBacklog(frames.map { Frame.Adpcm(encoder.encode(it)) })
        Log.i(TAG, "sent ${frames.size * FRAME_MS} ms said before the phone picked up")
    }

    /**
     * Plays the answer with a jitter pre-buffer so bursty Bluetooth packets don't starve AudioTrack:
     * waits until [PLAY_PREBUFFER_SAMPLES] arrive (or [PLAY_PREBUFFER_MAX_WAIT_MS] for short words),
     * pre-fills the paused AudioTrack before calling play(), and pauses cleanly between utterances.
     * A flush drops what is queued the moment the user interrupts.
     */
    private suspend fun speak() = runOnDedicatedThread("watch-speak", Process.THREAD_PRIORITY_URGENT_AUDIO) { running ->
        var track: AudioTrack? = null
        var playingAudio = false
        var bufferingSinceMs = -1L
        var drySinceMs = -1L
        var lastUnderruns = 0
        val silenceFrame = ShortArray(WatchLink.FRAME_SAMPLES)
        try {
            while (running()) {
                // Earbuds on the phone play the answer: no speaker, no call audio mode on the watch.
                when (speakerOn) {
                    true ->
                        if (track == null) {
                            useWatchSpeaker()
                            track = newTrack()
                            playingAudio = false
                            bufferingSinceMs = -1L
                            drySinceMs = -1L
                            lastUnderruns = 0
                        }

                    false ->
                        track?.let {
                            runCatching {
                                it.pause()
                                it.flush()
                                it.stop()
                            }
                            it.release()
                            track = null
                            playingAudio = false
                            bufferingSinceMs = -1L
                            drySinceMs = -1L
                            playback.clear()
                            restoreAudio()
                        }

                    null -> Unit
                }
                val activeTrack = track
                if (activeTrack == null) {
                    Thread.sleep(50)
                    continue
                }
                if (flushRequested) {
                    flushRequested = false
                    if (playingAudio) {
                        runCatching {
                            activeTrack.pause()
                            activeTrack.flush()
                        }
                        playingAudio = false
                    } else {
                        runCatching { activeTrack.flush() }
                    }
                    bufferingSinceMs = -1L
                    drySinceMs = -1L
                    continue
                }
                // Cap extreme backlog after a severe Bluetooth stall so latency stays bounded.
                if (playback.available > PLAY_MAX_QUEUE_SAMPLES) {
                    playback.trimTo(PLAY_TRIM_TO_SAMPLES)
                    stats.add("playTrim")
                }
                if (!playingAudio) {
                    if (playback.available == 0) {
                        bufferingSinceMs = -1L
                        playback.awaitAny(timeoutMs = 50)
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (bufferingSinceMs < 0L) bufferingSinceMs = now
                    val ready = playback.available >= PLAY_PREBUFFER_SAMPLES || now - bufferingSinceMs >= PLAY_PREBUFFER_MAX_WAIT_MS
                    if (!ready) {
                        val left = (PLAY_PREBUFFER_MAX_WAIT_MS - (now - bufferingSinceMs)).coerceIn(1L, 30L)
                        playback.awaitAtLeast(PLAY_PREBUFFER_SAMPLES, timeoutMs = left)
                        continue
                    }
                    // Pre-fill the paused AudioTrack before play() so hardware playback starts with a full cushion.
                    val maxPrime = minOf(PLAY_PRIME_FRAMES, maxOf(1, activeTrack.bufferSizeInFrames / WatchLink.FRAME_SAMPLES - 1))
                    var primedFrames = 0
                    var firstChunk = true
                    while (running() && !flushRequested && primedFrames < maxPrime && playback.available > 0) {
                        val chunk = takePaddedFrame()
                        if (firstChunk) {
                            Pcm.fadeIn(chunk, FADE_SAMPLES)
                            firstChunk = false
                        }
                        activeTrack.write(chunk, 0, chunk.size)
                        primedFrames++
                    }
                    if (!flushRequested && primedFrames > 0) {
                        activeTrack.play()
                        playingAudio = true
                        bufferingSinceMs = -1L
                        drySinceMs = -1L
                        lastUnderruns = activeTrack.underrunCount
                    }
                } else {
                    if (playback.available >= WatchLink.FRAME_SAMPLES) {
                        drySinceMs = -1L
                        val chunk = playback.take(WatchLink.FRAME_SAMPLES)
                        activeTrack.write(chunk, 0, chunk.size)
                        val underruns = activeTrack.underrunCount
                        if (underruns > lastUnderruns) {
                            stats.add("underruns", (underruns - lastUnderruns).toLong())
                            lastUnderruns = underruns
                        }
                        continue
                    }
                    // Queue dipped below one frame; wait briefly while AudioTrack drains its hardware cushion.
                    val now = SystemClock.elapsedRealtime()
                    if (drySinceMs < 0L) drySinceMs = now
                    val waited = now - drySinceMs
                    if (waited < PLAY_DRAIN_WAIT_MS) {
                        val left = (PLAY_DRAIN_WAIT_MS - waited).coerceIn(1L, 25L)
                        playback.awaitAtLeast(WatchLink.FRAME_SAMPLES, timeoutMs = left)
                        continue
                    }
                    // Utterance ended (or Bluetooth stalled past the cushion): drain any partial tail cleanly and pause.
                    if (playback.available > 0) {
                        val tail = playback.takeUpTo(WatchLink.FRAME_SAMPLES)
                        val padded = ShortArray(WatchLink.FRAME_SAMPLES)
                        tail.copyInto(padded)
                        Pcm.fadeOut(padded, tail.size, FADE_SAMPLES)
                        activeTrack.write(padded, 0, padded.size)
                    }
                    activeTrack.write(silenceFrame, 0, silenceFrame.size)
                    val pauseAt = SystemClock.elapsedRealtime() + PLAY_TAIL_HOLD_MS
                    while (running() && !flushRequested && playback.available == 0) {
                        val left = pauseAt - SystemClock.elapsedRealtime()
                        if (left <= 0L) break
                        playback.awaitAny(timeoutMs = minOf(left, 25L))
                    }
                    if (playback.available > 0 && !flushRequested) {
                        drySinceMs = -1L
                        continue
                    }
                    runCatching {
                        activeTrack.pause()
                        activeTrack.flush()
                    }
                    playingAudio = false
                    drySinceMs = -1L
                }
            }
        } catch (_: InterruptedException) {
            // Stopped cleanly.
        } finally {
            track?.let {
                runCatching {
                    it.pause()
                    it.flush()
                    it.stop()
                }
                it.release()
            }
        }
    }

    private fun takePaddedFrame(): ShortArray {
        if (playback.available >= WatchLink.FRAME_SAMPLES) return playback.take(WatchLink.FRAME_SAMPLES)
        val tail = playback.takeUpTo(WatchLink.FRAME_SAMPLES)
        val padded = ShortArray(WatchLink.FRAME_SAMPLES)
        tail.copyInto(padded)
        Pcm.fadeOut(padded, tail.size, FADE_SAMPLES)
        return padded
    }

    private fun newTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(WatchLink.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack
            .Builder()
            .setAudioAttributes(VOICE_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(WatchLink.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setBufferSizeInBytes(maxOf(minBuffer * 2, WatchLink.FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun buzz(effect: Int) {
        runCatching {
            appContext
                .getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(effect))
        }
    }

    /** Sends waiting frames in coalesced batches, so Bluetooth isn't hammered with per-frame flushes. */
    private suspend fun send(out: DataOutputStream) = runOnDedicatedThread("watch-send", Process.THREAD_PRIORITY_DISPLAY) { running ->
        try {
            while (running()) {
                val frames = outbox.takeBatch(timeoutMs = 150, batchWindowMs = SEND_BATCH_MS, minAudioFrames = SEND_BATCH_FRAMES)
                if (frames.isEmpty()) continue
                val started = SystemClock.elapsedRealtime()
                FrameCodec.writeAll(out, frames)
                stats.add("writes")
                stats.max("writeMaxMs", SystemClock.elapsedRealtime() - started)
            }
        } catch (_: IOException) {
            // The phone hung up mid-write (e.g. after a quiet spell); receive() ends the conversation.
        } catch (_: InterruptedException) {
            // Stopped cleanly.
        }
    }

    /** Runs [block] on a dedicated thread at [priority], cancelling cleanly when the coroutine cancels. */
    private suspend fun runOnDedicatedThread(
        name: String,
        priority: Int,
        block: (isRunning: () -> Boolean) -> Unit,
    ) {
        val running = AtomicBoolean(true)
        val done = CompletableDeferred<Unit>()
        val worker =
            thread(name = name, isDaemon = true) {
                runCatching { Process.setThreadPriority(priority) }
                try {
                    block { running.get() }
                    done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                }
            }
        try {
            done.await()
        } finally {
            running.set(false)
            worker.interrupt()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { worker.join(THREAD_JOIN_MS) }
            }
        }
    }

    /** Measures the watch-phone round trip every 2 s (shown small on the face). */
    private suspend fun ping() {
        while (!answered) delay(50)
        while (true) {
            outbox.offer(Frame.Message(Control("ping", at = SystemClock.elapsedRealtime())))
            Log.i(TAG, "watch ${stats.drain()} outbox=${outbox.size} dropped=${outbox.dropped} playQueue=${playback.available}")
            delay(PING_MS)
        }
    }

    private fun useWatchSpeaker() {
        if (audioFocus == null) {
            audioFocus =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(VOICE_ATTRIBUTES)
                    .build()
                    .also { audioManager.requestAudioFocus(it) }
        }
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { audioManager.setCommunicationDevice(it) }
    }

    private fun restoreAudio() {
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = previousMode
        audioFocus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        audioFocus = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            powerManager
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchai:call")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
        wakeLock = null
    }

    companion object {
        private val VOICE_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        private const val PING_MS = 2_000L
        private const val SEED = "buddy_seed"
        private const val TAG = "WatchLink"
        private const val MIC_SILENCE = 0.004f
        private const val MIC_HANGOVER = 10 // 400 ms
        private const val OUTBOX_FRAMES = 40 // 1.6 s of audio at most waiting for the link
        private const val SEND_BATCH_FRAMES = 3 // 120 ms (~980 bytes) per Bluetooth flush
        private const val SEND_BATCH_MS = 95L
        private const val PLAY_PREBUFFER_SAMPLES = WatchLink.FRAME_SAMPLES * 5 // 200 ms jitter cushion
        private const val PLAY_PREBUFFER_MAX_WAIT_MS = 200L
        private const val PLAY_PRIME_FRAMES = 4 // 160 ms pre-filled into AudioTrack before play()
        private const val PLAY_DRAIN_WAIT_MS = 160L
        private const val PLAY_TAIL_HOLD_MS = 120L
        private const val PLAY_MAX_QUEUE_SAMPLES = WatchLink.SAMPLE_RATE * 12 / 10 // 1.2 s
        private const val PLAY_TRIM_TO_SAMPLES = WatchLink.SAMPLE_RATE * 4 / 10 // 400 ms
        private const val FADE_SAMPLES = 64 // 4 ms at 16 kHz
        private const val THREAD_JOIN_MS = 1_500L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1_000L
        private const val LEVEL_EVERY_MS = 100L
        private const val FRAME_MS = 40
        private const val EARLY_FRAMES = 200 // 8 s said before the phone picks up
        private const val ANSWER_TIMEOUT_MS = 20_000L

        @Volatile private var instance: PhoneVoiceLink? = null

        /** The app's one link, shared by the screen and the call service. */
        fun get(context: Context): PhoneVoiceLink = instance ?: synchronized(this) { instance ?: PhoneVoiceLink(context).also { instance = it } }
    }
}
