package com.vinhnguyen.watchai.wear

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.vinhnguyen.watchai.watchlink.Pcm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listens for "Hey Buddy" while the watch screen is on: from when it comes on (a raised wrist) for
 * [WINDOW_MS], never with the screen off or during a conversation, so it costs nothing while the
 * watch sleeps. The audio stays on the watch, and quiet stretches aren't even fed to the model.
 * Runs inside [CallService], which keeps the microphone allowed while the app is in the background.
 */
internal class WakeListener(
    context: Context,
    private val onWake: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val power = appContext.getSystemService(PowerManager::class.java)

    // Loads right away (about 20 s on a Watch5), so it's ready by the first raised wrist.
    private val word = scope.async { WakeWord(appContext) }

    // The model isn't thread-safe: each window (or test) waits for the one before it to finish.
    private var window: Job? = null
    private var paused = false

    private val screen =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == Intent.ACTION_SCREEN_ON) open() else close()
            }
        }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(appContext, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (power.isInteractive) open()
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(screen) }
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

    /** A conversation started: the microphone is its now. */
    @Synchronized
    fun pause() {
        paused = true
        close()
    }

    /** The conversation ended: listen again while the screen is still on. */
    @Synchronized
    fun resume() {
        paused = false
        if (power.isInteractive) open()
    }

    /** Debug builds: runs [pcm] (16 kHz mono) through the wake word as if the watch had heard it. */
    @Synchronized
    fun test(pcm: ShortArray) {
        val previous = window?.also { it.cancel() }
        window =
            scope.launch {
                previous?.join()
                val w = loaded() ?: return@launch
                w.reset()
                var heard = false
                for (start in pcm.indices step CHUNK) {
                    ensureActive()
                    val end = minOf(start + CHUNK, pcm.size)
                    if (w.heard(pcm.copyOfRange(start, end), end - start)) {
                        heard = true
                        break
                    }
                }
                Log.i(TAG, if (heard) "test: heard the wake word" else "test: no wake word")
                if (heard) onWake()
            }
    }

    @Synchronized
    private fun open() {
        if (paused) return
        val previous = window?.also { it.cancel() }
        val openedAt = SystemClock.elapsedRealtime()
        window =
            scope.launch {
                previous?.join()
                if (listen(openedAt)) onWake()
            }
    }

    /** Stops the current window; the next one still waits for it to wind down. */
    @Synchronized
    private fun close() {
        window?.cancel()
    }

    /** One window: true if the phrase was heard. The microphone is closed again before it returns. */
    @SuppressLint("MissingPermission") // the service only runs with the microphone allowed
    private suspend fun listen(openedAt: Long): Boolean {
        val w = loaded() ?: return false
        val left = WINDOW_MS - (SystemClock.elapsedRealtime() - openedAt)
        if (left <= 0) return false
        w.reset()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 2 * 4))
        return try {
            record.startRecording()
            _listening.value = true
            withTimeoutOrNull(left) { heardIn(record, w) } ?: false
        } finally {
            _listening.value = false
            runCatching { record.stop() }
            record.release()
        }
    }

    private suspend fun loaded(): WakeWord? = try {
        word.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "wake word didn't load: ${e.message}")
        null
    }

    /** Reads 100 ms chunks; only sound goes to the model, with the chunk just before it so the first syllable isn't cut. */
    private suspend fun heardIn(
        record: AudioRecord,
        w: WakeWord,
    ): Boolean = withContext(Dispatchers.IO) {
        val chunk = ShortArray(CHUNK)
        var before: ShortArray? = null
        var loud = 0
        while (isActive) {
            val n = record.read(chunk, 0, CHUNK)
            if (n <= 0) continue
            if (Pcm.level(chunk, n) >= GATE) loud = HANGOVER_CHUNKS
            if (loud == 0) {
                before = chunk.copyOf(n)
                continue
            }
            loud--
            before?.let { if (w.heard(it, it.size)) return@withContext true }
            before = null
            if (w.heard(chunk, n)) return@withContext true
        }
        false
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = SAMPLE_RATE / 10
        private const val WINDOW_MS = 30_000L
        private const val GATE = 0.002f
        private const val HANGOVER_CHUNKS = 8

        private val _listening = MutableStateFlow(false)

        /** The microphone is open for "Hey Buddy" right now (for the face's hint). */
        val listening: StateFlow<Boolean> = _listening.asStateFlow()
    }
}

/** Whether the user switched "Hey Buddy" on; kept across restarts so opening the app turns it back on. */
internal object WakeSetting {
    private const val PREFS = "wear"
    private const val KEY = "hey_buddy"

    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(
        context: Context,
        on: Boolean,
    ) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
}
