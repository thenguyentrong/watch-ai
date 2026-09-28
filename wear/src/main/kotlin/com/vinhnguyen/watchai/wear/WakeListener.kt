package com.vinhnguyen.watchai.wear

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.vinhnguyen.watchai.watchlink.Leveller
import com.vinhnguyen.watchai.watchlink.Pcm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listens for "Hey Buddy". Normally only while the watch screen is on: from when it comes on (a
 * raised wrist) for [WINDOW_MS], never with the screen off, so it costs nothing while the watch
 * sleeps. "Always listening" keeps the mic on with the screen off too, for when there's no hand
 * free, and costs battery for it. Never during a conversation. The audio stays on the watch, and
 * quiet stretches aren't even fed to the model. Runs inside [CallService], which keeps the
 * microphone allowed while the app is in the background.
 */
internal class WakeListener(
    context: Context,
    private val onWake: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val displays = appContext.getSystemService(DisplayManager::class.java)

    // Loads right away (about 20 s on a Watch5), so it's ready by the first raised wrist.
    private val word = scope.async { WakeWord(appContext, WakeSetting.keywords(appContext)) }

    // The room's noise, kept from one window to the next, so a new one starts with a good guess.
    @Volatile private var noiseFloor = GATE

    // The model isn't thread-safe: each window (or test) waits for the one before it to finish.
    private var window: Job? = null
    private var paused = false

    @Volatile private var always = WakeSetting.isAlways(appContext)

    private val screen =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when {
                    intent.action == Intent.ACTION_SCREEN_ON -> open("screen on")
                    !always -> close()
                }
            }
        }

    // The display turning on comes about 100 ms before the screen-on broadcast (28.09), which goes
    // to every app in turn. Only the change to on counts (it also reports "on" while switching
    // off), and only when the watch is awake: the charging screen turns the display on while asleep.
    private val display =
        object : DisplayManager.DisplayListener {
            private var wasOn = true

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val on = displays.getDisplay(displayId)?.state == Display.STATE_ON
                if (on && !wasOn && power.isInteractive) open("display on")
                wasOn = on
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(appContext, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        displays.registerDisplayListener(display, Handler(Looper.getMainLooper()))
        if (always || power.isInteractive) open("started")
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(screen) }
        displays.unregisterDisplayListener(display)
        val last = synchronized(this) {
            paused = true
            window?.also { it.cancel() }
        }
        scope.launch(NonCancellable) {
            last?.join()
            runCatching { word.await().release() }
            scope.cancel()
        }
    }

    /** A conversation (or learning the user's voice) started: the microphone is its now. */
    @Synchronized
    fun pause() {
        Log.i(TAG, "paused, the mic is needed")
        paused = true
        close()
    }

    /** That's over: listen again (while the screen is still on, or all the time). */
    @Synchronized
    fun resume() {
        Log.i(TAG, "back")
        paused = false
        if (always || power.isInteractive) open("back")
    }

    /** Switches between always listening and listening only after a raised wrist. */
    @Synchronized
    fun listenAlways(on: Boolean) {
        if (always == on) return
        always = on
        close()
        if (on || power.isInteractive) open(if (on) "always listening" else "only after a raised wrist")
    }

    /** The loaded model (for teaching and debug tuning), or null if it didn't load. */
    suspend fun model(): WakeWord? = loaded()

    /** Listens for the user's taught spellings from now on (null: the default phrase). */
    fun useKeywords(keywords: String?) {
        scope.launch { loaded()?.use(keywords) }
    }

    /** Debug builds: runs [pcm] (16 kHz mono) through the wake word as if the watch had heard it. */
    @Synchronized
    fun test(pcm: ShortArray) {
        val previous = window?.also { it.cancel() }
        window =
            scope
                .launch {
                    previous?.join()
                    val w = loaded() ?: return@launch
                    w.reset()
                    // Through the same steps as the mic (gate, leveller), then a second of quiet.
                    var at = 0
                    val heard =
                        heardIn({ chunk ->
                            val n = minOf(CHUNK, pcm.size + SAMPLE_RATE - at)
                            if (n <= 0) return@heardIn END_OF_CLIP
                            for (i in 0 until n) chunk[i] = if (at + i < pcm.size) pcm[at + i] else 0
                            at += n
                            n
                        }, w)
                    Log.i(TAG, if (heard) "test: heard the wake word" else "test: no wake word")
                    if (heard) onWake()
                }.also { it.invokeOnCompletion { if (always) open("after the test") } }
    }

    @Synchronized
    private fun open(why: String) {
        if (paused) {
            Log.i(TAG, "$why, but the mic is needed elsewhere")
            return
        }
        // Both signals come for one raised wrist, and always listening is one long window: don't restart it.
        if (window?.isActive == true) {
            Log.i(TAG, "$why: already listening")
            return
        }
        Log.i(TAG, why)
        val previous = window
        var openedAt = SystemClock.elapsedRealtime()
        window =
            scope.launch {
                try {
                    while (true) {
                        if (listen(openedAt, previous)) {
                            onWake()
                            break
                        }
                        // Always listening, the mic can drop out (another app took it): try again in a moment.
                        if (!always || loaded() == null) break
                        delay(RETRY_MS)
                        openedAt = SystemClock.elapsedRealtime()
                    }
                } finally {
                    // Settling the model takes about 270 ms on a Watch5 (28.09): after a window (and
                    // after the wake), not when the next window opens.
                    withContext(NonCancellable) { settle() }
                }
            }
    }

    /** Stops the current window; the next one still waits for it to wind down. */
    @Synchronized
    private fun close() {
        window?.cancel()
    }

    /**
     * One window: true if the phrase was heard. The mic opens first, before waiting for [previous]
     * (the window before, still winding down) and the model: the audio waits in the mic's buffer.
     * It's closed again before this returns, and the model is made ready for the next window.
     */
    @SuppressLint("MissingPermission") // the service only runs with the microphone allowed
    private suspend fun listen(
        openedAt: Long,
        previous: Job?,
    ): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val made = SystemClock.elapsedRealtime()
        val record =
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, MIC_BUFFER_BYTES))
        return try {
            val started = SystemClock.elapsedRealtime()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "mic didn't start")
                return false
            }
            val on = SystemClock.elapsedRealtime()
            previous?.join()
            val w = loaded() ?: return false
            val left = if (always) Long.MAX_VALUE else WINDOW_MS - (SystemClock.elapsedRealtime() - openedAt)
            if (left <= 0) return false
            _listening.value = true
            Log.i(TAG, "listening: mic on ${on - openedAt} ms after it was asked (${started - made} + ${on - started} ms), model ${SystemClock.elapsedRealtime() - on} ms later")
            (withTimeoutOrNull(left) { heardIn({ record.read(it, 0, CHUNK) }, w) } ?: false).also { Log.i(TAG, if (it) "heard it" else "stopped listening") }
        } catch (e: IllegalStateException) {
            // Not initialised: the mic is gone (permission taken back, or a call has it). A cancelled window isn't that.
            if (e is CancellationException) throw e
            Log.w(TAG, "mic failed: ${e.message}")
            false
        } finally {
            _listening.value = false
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Readies a loaded model for the next window; never waits for it to load. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun settle() {
        if (word.isCompleted) runCatching { word.getCompleted().reset() }
    }

    private suspend fun loaded(): WakeWord? = try {
        word.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "wake word didn't load: ${e.message}")
        null
    }

    /**
     * Reads 100 ms chunks; only sound goes to the model, with the chunk just before it so the first
     * syllable isn't cut, and evened out ([Leveller]). "Sound" is measured against the room's own
     * noise, not a fixed level: mics, rooms and voices differ.
     */
    private suspend fun heardIn(
        read: (ShortArray) -> Int,
        w: WakeWord,
    ): Boolean = withContext(Dispatchers.IO) {
        val chunk = ShortArray(CHUNK)
        val leveller = Leveller()
        var before: ShortArray? = null
        var loud = 0
        var first = true
        var floor = noiseFloor
        var chunks = 0
        var fed = 0
        var second = 0
        var peak = 0f
        while (isActive) {
            val n = read(chunk)
            // An error doesn't clear by itself (the mic was taken away): stop instead of spinning on it.
            if (n < 0) {
                if (n != END_OF_CLIP) Log.w(TAG, "mic stopped: $n")
                return@withContext false
            }
            if (n == 0) continue
            val level = Pcm.level(chunk, n)
            if (first) {
                // Already loud means the user began before the mic was on, and the start of the phrase is lost.
                Log.i(TAG, "first sound level ${(level * 10_000).toInt()}")
                first = false
            }
            // Steady noise (a fan, a car) would keep the model busy: only what stands out from it
            // counts. The floor drops at once and creeps up over ~10 s.
            floor = if (level < floor) level else floor + (level - floor) * FLOOR_RISE
            noiseFloor = floor
            val sound = level >= maxOf(if (always) ALWAYS_GATE else GATE, floor * ABOVE_FLOOR)
            val gain = leveller.gain(level, sound)
            if (sound) loud = HANGOVER_CHUNKS
            peak = maxOf(peak, level)
            if (++second == CHUNKS_PER_SECOND) {
                if (peak >= GATE) Log.i(TAG, "sound: peak ${(peak * 10_000).toInt()}")
                peak = 0f
                second = 0
            }
            if (always && ++chunks == CHUNKS_PER_MINUTE) {
                Log.i(TAG, "model busy ${fed * 100 / chunks}% of the last minute, floor ${(floor * 10_000).toInt()}")
                chunks = 0
                fed = 0
            }
            if (loud == 0) {
                before = chunk.copyOf(n)
                continue
            }
            loud--
            fed++
            before?.let { if (w.heard(it, it.size, gain)) return@withContext true }
            before = null
            if (w.heard(chunk, n, gain)) return@withContext true
        }
        false
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = SAMPLE_RATE / 10
        private const val CHUNKS_PER_SECOND = 10
        private const val END_OF_CLIP = Int.MIN_VALUE
        private const val CHUNKS_PER_MINUTE = 600
        private const val MIC_BUFFER_BYTES = SAMPLE_RATE * 2 * 2
        private const val WINDOW_MS = 30_000L
        private const val RETRY_MS = 5_000L
        private const val GATE = 0.002f
        private const val ALWAYS_GATE = 0.004f
        private const val ABOVE_FLOOR = 2f
        private const val FLOOR_RISE = 0.01f
        private const val HANGOVER_CHUNKS = 8

        private val _listening = MutableStateFlow(false)

        /** The microphone is open for "Hey Buddy" right now (for the face's hint). */
        val listening: StateFlow<Boolean> = _listening.asStateFlow()
    }
}

/** Whether the user switched "Hey Buddy" on (and how); kept across restarts so opening the app turns it back on. */
internal object WakeSetting {
    private const val PREFS = "wear"
    private const val SWITCH = "hey_buddy"
    private const val ALWAYS = "hey_buddy_always"
    private const val KEYWORDS = "hey_buddy_keywords"

    private val taughtNow = MutableStateFlow<Boolean?>(null)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(SWITCH, false)

    fun set(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean(SWITCH, on) }

    /** Listening with the screen off too, not only after a raised wrist. */
    fun isAlways(context: Context): Boolean = prefs(context).getBoolean(ALWAYS, false)

    fun setAlways(
        context: Context,
        on: Boolean,
    ) = prefs(context).edit { putBoolean(ALWAYS, on) }

    /** How the user says it, once taught ([VoiceTeacher]): keywords.txt lines. Null for the default phrase. */
    fun keywords(context: Context): String? = prefs(context).getString(KEYWORDS, null)

    fun setKeywords(
        context: Context,
        keywords: String?,
    ) {
        prefs(context).edit { if (keywords == null) remove(KEYWORDS) else putString(KEYWORDS, keywords) }
        taughtNow.value = keywords != null
    }

    /** Whether the voice was taught, for the screens to follow. */
    fun taught(context: Context): StateFlow<Boolean?> {
        if (taughtNow.value == null) taughtNow.value = keywords(context) != null
        return taughtNow.asStateFlow()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
