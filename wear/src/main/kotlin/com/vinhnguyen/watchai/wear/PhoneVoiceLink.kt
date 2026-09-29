package com.vinhnguyen.watchai.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null

    /** For stopping from inside the call (the phone hung up): stop() cancels the call's own scope. */
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: ChannelClient.Channel? = null
    private var output: DataOutputStream? = null
    private val playback = PcmQueue(WatchLink.SAMPLE_RATE * 2)
    private var previousMode = AudioManager.MODE_NORMAL

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
            restoreAudio()
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
            receive(input)
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
                    playback.clear()
                    flushRequested = true
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
    private suspend fun record() = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(WatchLink.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                WatchLink.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, WatchLink.FRAME_BYTES * 4),
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
            while (isActive && micOn != false) {
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

    /** Plays the answer as it arrives; a flush drops what is queued the moment the user interrupts. */
    private suspend fun speak() = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        try {
            while (isActive) {
                // Earbuds on the phone play the answer: no speaker, no call audio mode on the watch.
                when (speakerOn) {
                    true ->
                        if (track == null) {
                            useWatchSpeaker()
                            track = newTrack().also { it.play() }
                        }

                    false ->
                        track?.let {
                            runCatching { it.stop() }
                            it.release()
                            track = null
                            playback.clear()
                            restoreAudio()
                        }

                    null -> Unit
                }
                val playing = track
                if (playing == null) {
                    delay(100)
                    continue
                }
                if (flushRequested) {
                    flushRequested = false
                    playing.pause()
                    playing.flush()
                    playing.play()
                }
                // Sleeps until audio arrives (no polling: this runs for the whole conversation).
                if (playback.awaitAtLeast(WatchLink.FRAME_SAMPLES, timeoutMs = 50)) {
                    val chunk = playback.take(WatchLink.FRAME_SAMPLES)
                    playing.write(chunk, 0, chunk.size)
                }
            }
        } finally {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(WatchLink.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(WatchLink.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setBufferSizeInBytes(maxOf(minBuffer, WatchLink.FRAME_BYTES * 4))
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

    /** Sends whatever is waiting in one write, so a slow Bluetooth flush never holds up the mic. */
    private suspend fun send(out: DataOutputStream) = withContext(Dispatchers.IO) {
        try {
            while (isActive) {
                val frames = outbox.takeAll(100)
                if (frames.isEmpty()) continue
                val started = SystemClock.elapsedRealtime()
                FrameCodec.writeAll(out, frames)
                stats.add("writes")
                stats.max("writeMaxMs", SystemClock.elapsedRealtime() - started)
            }
        } catch (e: IOException) {
            // The phone hung up mid-write (e.g. after a quiet spell); receive() ends the conversation.
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
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { audioManager.setCommunicationDevice(it) }
    }

    private fun restoreAudio() {
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = previousMode
    }

    companion object {
        private const val PING_MS = 2_000L
        private const val SEED = "buddy_seed"
        private const val TAG = "WatchLink"
        private const val MIC_SILENCE = 0.004f
        private const val MIC_HANGOVER = 8 // 320 ms
        private const val OUTBOX_FRAMES = 25 // 1 s of audio at most waiting for the link
        private const val LEVEL_EVERY_MS = 100L
        private const val FRAME_MS = 40
        private const val EARLY_FRAMES = 200 // 8 s said before the phone picks up
        private const val ANSWER_TIMEOUT_MS = 20_000L

        @Volatile private var instance: PhoneVoiceLink? = null

        /** The app's one link, shared by the screen and the call service. */
        fun get(context: Context): PhoneVoiceLink = instance ?: synchronized(this) { instance ?: PhoneVoiceLink(context).also { instance = it } }
    }
}
