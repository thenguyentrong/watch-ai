package com.vinhnguyen.watchai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.DeviceContext
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.Preloadable
import com.vinhnguyen.watchai.brain.Preloaded
import com.vinhnguyen.watchai.brain.ReplyStyle
import com.vinhnguyen.watchai.brain.Toolbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Hands-free conversation fully on the phone: Android's speech recogniser (on-device when the phone
 * has one) → a brain (Gemma) → Android text-to-speech, started sentence by sentence while the answer
 * is still being written. Talking over the answer stops it (barge-in, via an echo-cancelled mic).
 * The model starts loading as soon as the session starts, while the user is still talking.
 */
class OnDeviceVoiceSession(
    context: Context,
    private val brain: Brain,
    private val logger: BrainLogger = BrainLogger.None,
    /** The app's phone actions; brains that can't use tools are told so, so they don't pretend. */
    private val tools: Toolbox? = null,
) : VoiceSession {
    override val name: String = "On-device voice (${brain.id.label})"

    private val appContext = context.applicationContext
    private val audio = CallAudio(appContext)
    private val _state = MutableStateFlow(VoiceState())
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null
    private var recognizer: SpeechRecognizer? = null
    private var onDeviceRecognizer = false
    private var tts: TextToSpeech? = null
    private var answerJob: Job? = null
    private var monitorJob: Job? = null
    private val history = mutableListOf<ChatTurn>()
    private var conversationId = UUID.randomUUID().toString()
    private val pendingUtterances = AtomicInteger(0)
    private var preloaded: Preloaded? = null

    @Volatile private var active = false

    @Volatile private var modelLoading = false

    @Volatile private var answerComplete = false

    @Volatile private var endOfSpeechAt = 0L

    @Volatile private var waitingForFirstSound = false

    override suspend fun start() {
        lifecycle.withLock {
            if (scope != null) return
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            active = true
            _state.value = VoiceState(VoicePhase.CONNECTING)
            if (brain is Preloadable) s.launch(Dispatchers.Default) { preload(brain) }
            s.launch { begin() }
        }
    }

    override suspend fun stop() {
        lifecycle.withLock {
            val s = scope ?: return
            scope = null
            active = false
            // Not joined: a model load can't be interrupted, and Stop must be instant. Late callbacks find nothing to touch.
            s.cancel()
            withContext(Dispatchers.Main.immediate) {
                runCatching { recognizer?.destroy() }
                runCatching { tts?.stop() }
                runCatching { tts?.shutdown() }
                recognizer = null
                tts = null
            }
            val handle = synchronized(this) { preloaded.also { preloaded = null } }
            withContext(NonCancellable) { handle?.release() }
            audio.exit()
            _level.value = 0f
            if (_state.value.phase != VoicePhase.ERROR) _state.update { it.copy(phase = VoicePhase.IDLE, detail = "Stopped") }
        }
    }

    private suspend fun begin() {
        audio.enter()
        val engine =
            initTts() ?: run {
                fail("No text-to-speech engine on this phone")
                return
            }
        tts = engine
        warmUpVoice(engine)
        createRecognizer(onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext))
        note("answers play on ${audio.output}")
        conversationId = UUID.randomUUID().toString()
        synchronized(history) { history.clear() }
        listen()
    }

    private suspend fun preload(brain: Preloadable) {
        val started = SystemClock.elapsedRealtime()
        modelLoading = true
        try {
            val handle = brain.preload()
            val kept =
                synchronized(this) {
                    if (active) preloaded = handle
                    active
                }
            if (!kept) withContext(NonCancellable) { handle.release() }
            note("model ready after ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            note("model preload failed: ${e::class.simpleName}")
        } finally {
            modelLoading = false
            _state.update { if (it.phase == VoicePhase.LISTENING) it.copy(detail = null) else it }
        }
    }

    private fun createRecognizer(onDevice: Boolean) {
        runCatching { recognizer?.destroy() }
        onDeviceRecognizer = onDevice
        recognizer =
            (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) else SpeechRecognizer.createSpeechRecognizer(appContext))
                .also { it.setRecognitionListener(listener) }
        note(if (onDevice) "speech recognition: on-device" else "speech recognition: system (may use the network)")
    }

    private fun listen() {
        val r = recognizer ?: return
        _state.update { it.copy(phase = VoicePhase.LISTENING, detail = if (modelLoading) "Loading the model - you can already talk" else null) }
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // Shorter end-of-speech wait; recognisers may ignore these.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
        r.startListening(intent)
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (_state.value.phase == VoicePhase.LISTENING) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                endOfSpeechAt = SystemClock.elapsedRealtime()
                _level.value = 0f
                _state.update { it.copy(phase = VoicePhase.THINKING, detail = if (modelLoading) "Loading the model…" else null) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) _state.update { it.copy(lastUserText = text) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (text.isNullOrEmpty()) listen() else answer(text)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()

                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> relisten(300)

                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        if (onDeviceRecognizer) {
                            note("on-device recogniser has no model for this language")
                            createRecognizer(onDevice = false)
                            listen()
                        } else {
                            fail("Speech recognition doesn't support this language")
                        }

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Microphone permission is needed")

                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                        note("recogniser disconnected")
                        createRecognizer(onDeviceRecognizer)
                        relisten(300)
                    }

                    else -> {
                        note("recogniser error $error")
                        relisten(500)
                    }
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private fun relisten(afterMs: Long) {
        recognizer?.cancel()
        scope?.launch {
            delay(afterMs)
            listen()
        }
    }

    private fun answer(userText: String) {
        val s = scope ?: return
        _state.update { it.copy(phase = VoicePhase.THINKING, lastUserText = userText, lastAssistantText = "") }
        answerComplete = false
        waitingForFirstSound = true
        pendingUtterances.set(0)
        val splitter = SentenceSplitter()
        val full = StringBuilder()
        val past = synchronized(history) { history.toList() }
        answerJob =
            s.launch(Dispatchers.Default) {
                try {
                    brain.stream(ChatRequest(conversationId, past, userText, style = ReplyStyle.SPOKEN, context = DeviceContext.describe(), tools = tools)).collect { event ->
                        when (event) {
                            is ChatEvent.Delta -> {
                                full.append(event.text)
                                _state.update { it.copy(lastAssistantText = full.toString()) }
                                splitter.add(event.text).forEach(::speak)
                            }

                            is ChatEvent.Done -> splitter.flush()?.let(::speak)

                            is ChatEvent.Failed -> {
                                splitter.flush()?.let(::speak)
                                if (full.isEmpty()) speak(sorry(event.error))
                            }
                        }
                    }
                } finally {
                    // Keep interrupted turns too, so "no, I meant…" still has its context.
                    remember(userText, full.toString())
                }
                answerComplete = true
                if (pendingUtterances.get() == 0) s.launch { afterSpeaking() }
            }
        startBargeInMonitor()
    }

    private fun remember(
        userText: String,
        answer: String,
    ) = synchronized(history) {
        history += ChatTurn(ChatTurn.Role.USER, userText)
        if (answer.isNotBlank()) history += ChatTurn(ChatTurn.Role.ASSISTANT, answer)
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    private fun sorry(error: BrainError): String = when (error) {
        BrainError.ModelNotReady -> "The on-device model isn't downloaded yet."
        BrainError.OutOfMemory -> "The phone is short on memory right now. Try again in a moment."
        else -> "Sorry, I couldn't answer that."
    }

    private fun speak(text: String) {
        pendingUtterances.incrementAndGet()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private fun afterSpeaking() {
        monitorJob?.cancel()
        _level.value = 0f
        listen()
    }

    private val progress =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == WARM_UP_ID) return
                if (waitingForFirstSound) {
                    waitingForFirstSound = false
                    val latency = SystemClock.elapsedRealtime() - endOfSpeechAt
                    _state.update { it.copy(phase = VoicePhase.SPEAKING, detail = "Talk to interrupt", metrics = it.metrics.withTurn(latency)) }
                    logger.log(LogEvent.Engine("voice_turn", "on-device", latency))
                }
            }

            override fun onDone(utteranceId: String?) = utteranceFinished(utteranceId)

            override fun onStop(
                utteranceId: String?,
                interrupted: Boolean,
            ) = utteranceFinished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = utteranceFinished(utteranceId)

            private fun utteranceFinished(utteranceId: String?) {
                if (utteranceId == WARM_UP_ID) return
                if (pendingUtterances.decrementAndGet() <= 0 && answerComplete) scope?.launch { afterSpeaking() }
            }
        }

    private suspend fun initTts(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null
        engine =
            TextToSpeech(appContext) { status ->
                val ready = engine
                if (status == TextToSpeech.SUCCESS && ready != null) {
                    ready.setAudioAttributes(CallAudio.SPEECH)
                    ready.setOnUtteranceProgressListener(progress)
                    if (cont.isActive) cont.resume(ready)
                } else if (cont.isActive) {
                    cont.resume(null)
                }
            }
        cont.invokeOnCancellation { runCatching { engine?.shutdown() } }
    }

    /** The first sentence a TTS voice speaks is slow while the voice loads; load it now, silently. */
    private fun warmUpVoice(engine: TextToSpeech) {
        val file = File(appContext.cacheDir, "tts-warmup.wav")
        runCatching { engine.synthesizeToFile("Hi.", Bundle(), file, WARM_UP_ID) }
    }

    /**
     * While the answer plays, watch the echo-cancelled microphone. Sustained speech means the user
     * is talking over the answer: stop speaking and listen.
     */
    @SuppressLint("MissingPermission") // the voice screen asks for RECORD_AUDIO before starting
    private fun startBargeInMonitor() {
        monitorJob?.cancel()
        monitorJob =
            scope?.launch(Dispatchers.IO) {
                val rate = 16_000
                val frame = rate / 50 // 20 ms
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record =
                    AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frame * 4))
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return@launch
                }
                val buffer = ShortArray(frame)
                var floor = 200.0
                var loudFrames = 0
                try {
                    record.startRecording()
                    while (isActive) {
                        val n = record.read(buffer, 0, frame)
                        if (n <= 0) continue
                        var sum = 0.0
                        for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
                        val rms = sqrt(sum / n)
                        val speaking = _state.value.phase == VoicePhase.SPEAKING
                        if (!speaking) {
                            floor = floor * 0.95 + rms * 0.05
                            loudFrames = 0
                            continue
                        }
                        loudFrames = if (rms > maxOf(floor * 4, 900.0)) loudFrames + 1 else 0
                        if (loudFrames >= BARGE_IN_FRAMES) {
                            withContext(Dispatchers.Main) { bargeIn() }
                            break
                        }
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            }
    }

    private fun bargeIn() {
        val started = SystemClock.elapsedRealtime()
        answerJob?.cancel()
        tts?.stop()
        pendingUtterances.set(0)
        answerComplete = true
        val stopMs = SystemClock.elapsedRealtime() - started + BARGE_IN_FRAMES * 20L
        _state.update { it.copy(metrics = it.metrics.withInterrupt(stopMs).withNote("interrupted")) }
        listen()
    }

    private fun fail(detail: String) {
        _level.value = 0f
        _state.value = VoiceState(VoicePhase.ERROR, detail, metrics = _state.value.metrics)
    }

    private fun note(text: String) = _state.update { it.copy(metrics = it.metrics.withNote(text)) }

    private companion object {
        const val MAX_HISTORY = 8
        const val BARGE_IN_FRAMES = 12 // 240 ms of sustained speech
        const val END_SILENCE_MS = 700L
        const val WARM_UP_ID = "warm-up"
    }
}
