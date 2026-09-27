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
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
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

/**
 * The watch side of a conversation: opens a voice channel to the phone app, sends the watch's
 * microphone and plays the answer on the watch speaker. The phone does the talking to ChatGPT.
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

    /** The phone picked up (sent its first frame). Until then nothing is sent, so no audio piles up. */
    @Volatile private var answered = false

    suspend fun start() {
        lifecycle.withLock {
            if (scope != null) return
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _state.value = State(Phase.ERROR, detail = "Microphone permission needed")
                return
            }
            answered = false
            _state.value = State(Phase.CONNECTING, detail = "Calling your phone…")
            CallService.start(appContext)
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
            CallService.stop(appContext)
            _state.value = State()
        }
    }

    /** Ends any conversation without waiting (e.g. when the screen goes away). */
    fun release() {
        cleanup.launch { stop() }
    }

    private suspend fun run(s: CoroutineScope) {
        try {
            val nodes = Wearable.getNodeClient(appContext).connectedNodes.await()
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: throw IOException("Phone not connected")
            val opened = channels.openChannel(phone.id, WatchLink.VOICE_PATH).await()
            channel = opened
            val input = DataInputStream(BufferedInputStream(channels.getInputStream(opened).await()))
            val out = DataOutputStream(BufferedOutputStream(channels.getOutputStream(opened).await()))
            output = out
            useWatchSpeaker()
            s.launch { speak() }
            s.launch { listen() }
            s.launch { send(out) }
            s.launch { ping() }
            receive(input)
            // The phone closed the channel.
            if (scope != null) {
                cleanup.launch {
                    stop()
                    _state.value = State(Phase.ERROR, detail = "The phone ended the call")
                }
            }
        } catch (e: Exception) {
            if (!currentScopeActive(s)) return
            _state.value = State(Phase.ERROR, detail = e.message ?: "Could not reach the phone")
        }
    }

    private fun currentScopeActive(s: CoroutineScope) = s.coroutineContext[Job]?.isActive == true

    /** Frames from the phone: the answer's audio, "stop playing" when the user interrupts, and status. */
    private fun receive(input: DataInputStream) {
        while (true) {
            val frame = FrameCodec.read(input) ?: return
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
                // Listening: show the user's own loudness, measured here with no delay.
                val level = if (phase == Phase.LISTENING) micLevel else control.level ?: 0f
                _state.update { it.copy(phase = phase, level = level, detail = null) }
            }

            "pong" ->
                control.at?.let { at ->
                    val rtt = SystemClock.elapsedRealtime() - at
                    Log.i(TAG, "link round trip $rtt ms")
                    _state.update { it.copy(roundTripMs = rtt) }
                }

            "bye" -> _state.update { it.copy(phase = Phase.ERROR, detail = "The phone ended the call") }
        }
    }

    /** The watch microphone, echo-cancelled, in 20 ms frames to the phone. */
    @SuppressLint("MissingPermission") // checked in start()
    private suspend fun listen() = withContext(Dispatchers.IO) {
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
            while (isActive) {
                val readStart = SystemClock.elapsedRealtime()
                val n = record.read(frame, 0, frame.size)
                stats.add("reads")
                stats.max("readMaxMs", SystemClock.elapsedRealtime() - readStart)
                if (n < frame.size) stats.add("readShort")
                if (n <= 0) continue
                micLevel = Pcm.level(frame, n)
                // The face follows the voice at 10 updates a second; more only costs the watch CPU.
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= LEVEL_EVERY_MS && _state.value.phase == Phase.LISTENING) {
                    lastLevelAt = now
                    _state.update { it.copy(level = micLevel) }
                }
                if (!answered) continue
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
            runCatching { record.stop() }
            aec?.release()
            record.release()
        }
    }

    /** Plays the answer as it arrives; a flush drops what is queued the moment the user interrupts. */
    private suspend fun speak() = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(WatchLink.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track =
            AudioTrack
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
        try {
            track.play()
            while (isActive) {
                if (flushRequested) {
                    flushRequested = false
                    track.pause()
                    track.flush()
                    track.play()
                }
                // Sleeps until audio arrives (no polling: this runs for the whole conversation).
                if (playback.awaitAtLeast(WatchLink.FRAME_SAMPLES, timeoutMs = 50)) {
                    val chunk = playback.take(WatchLink.FRAME_SAMPLES)
                    track.write(chunk, 0, chunk.size)
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Sends whatever is waiting in one write, so a slow Bluetooth flush never holds up the mic. */
    private suspend fun send(out: DataOutputStream) = withContext(Dispatchers.IO) {
        while (isActive) {
            val frames = outbox.takeAll(100)
            if (frames.isEmpty()) continue
            val started = SystemClock.elapsedRealtime()
            FrameCodec.writeAll(out, frames)
            stats.add("writes")
            stats.max("writeMaxMs", SystemClock.elapsedRealtime() - started)
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
        previousMode = audioManager.mode
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
        private const val TAG = "WatchLink"
        private const val MIC_SILENCE = 0.004f
        private const val MIC_HANGOVER = 8 // 320 ms
        private const val OUTBOX_FRAMES = 25 // 1 s of audio at most waiting for the link
        private const val LEVEL_EVERY_MS = 100L

        @Volatile private var instance: PhoneVoiceLink? = null

        /** The app's one link, shared by the screen and the call service. */
        fun get(context: Context): PhoneVoiceLink = instance ?: synchronized(this) { instance ?: PhoneVoiceLink(context).also { instance = it } }
    }
}
