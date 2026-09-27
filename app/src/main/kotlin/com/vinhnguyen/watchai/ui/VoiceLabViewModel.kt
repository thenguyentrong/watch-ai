package com.vinhnguyen.watchai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.PhoneTalkService
import com.vinhnguyen.watchai.actions.NoteStore
import com.vinhnguyen.watchai.brain.Preloaded
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptHttp
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.GemmaAudioProbe
import com.vinhnguyen.watchai.voice.OnDeviceVoiceSession
import com.vinhnguyen.watchai.voice.VoiceSession
import com.vinhnguyen.watchai.voice.VoiceState
import com.vinhnguyen.watchai.watch.WatchCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Debug-only lab to try the two voice engines and measure them. Timings only are saved, each time
 * a session stops. While the screen is open, Gemma is kept loaded so the first answer is fast.
 */
class VoiceLabViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    enum class Engine { CHATGPT, ON_DEVICE }

    enum class ModelWarmth { COLD, LOADING, READY, MISSING }

    data class State(
        val engine: Engine = Engine.CHATGPT,
        val active: Engine? = null,
        val chatGpt: VoiceState = VoiceState(),
        val onDevice: VoiceState = VoiceState(),
        val voice: String,
        val model: ModelWarmth = ModelWarmth.COLD,
        val probeRunning: Boolean = false,
        val probe: GemmaAudioProbe.Result? = null,
        val savedTo: String? = null,
    ) {
        val current: VoiceState get() = if (engine == Engine.CHATGPT) chatGpt else onDevice
    }

    private val _state = MutableStateFlow(State(voice = graph.settings.voice))
    val state: StateFlow<State> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val sessions = Mutex()
    private var session: VoiceSession? = null
    private var watchJobs: List<Job> = emptyList()
    private val probe = GemmaAudioProbe(graph.appContext, graph.engines)

    private var warm: Preloaded? = null
    private var warmJob: Job? = null
    private var visible = false
    private var stopLater: Job? = null

    /** The user's notes (newest last) and which actions the user has allowed, for "Things it can do". */
    val notes: StateFlow<List<NoteStore.Note>> = graph.notes.notes
    private val _calendarAllowed = MutableStateFlow(graph.actions.calendarAllowed())
    val calendarAllowed: StateFlow<Boolean> = _calendarAllowed.asStateFlow()
    private val _clockFromPocket = MutableStateFlow(graph.phoneClock.worksFromPocket())
    val clockFromPocket: StateFlow<Boolean> = _clockFromPocket.asStateFlow()
    private val _doNotDisturbAllowed = MutableStateFlow(graph.controls.doNotDisturbAllowed())
    val doNotDisturbAllowed: StateFlow<Boolean> = _doNotDisturbAllowed.asStateFlow()

    init {
        viewModelScope.launch { runCatching { graph.notes.list() } }
    }

    fun refreshPermissions() {
        _calendarAllowed.value = graph.actions.calendarAllowed()
        _clockFromPocket.value = graph.phoneClock.worksFromPocket()
        _doNotDisturbAllowed.value = graph.controls.doNotDisturbAllowed()
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { runCatching { graph.notes.delete(id) } }
    }

    /**
     * The app went to the background (e.g. the clock app opened for a timer). With the talk service
     * holding the mic the conversation just goes on; without it Android silences the mic, so keep
     * the conversation for a little while only. Coming back cancels this.
     */
    fun onBackground() {
        stopLater?.cancel()
        if (session != null && PhoneTalkService.running) return
        stopLater =
            viewModelScope.launch {
                delay(BACKGROUND_GRACE_MS)
                sessions.withLock { stopCurrentLocked() }
            }
    }

    fun onForeground() {
        stopLater?.cancel()
        stopLater = null
        refreshPermissions()
    }

    fun setVoice(voice: String) {
        graph.settings.voice = voice
        _state.update { it.copy(voice = voice) }
    }

    fun setEngine(engine: Engine) {
        if (_state.value.active != null) return
        _state.update { it.copy(engine = engine) }
    }

    /** The screen is on show: keep Gemma loaded so talking to it starts without the 7-11 s load. */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) warmUp() else coolDown()
    }

    /** A call from the watch (run by the phone's watch service, app open or not). */
    val watchCall: StateFlow<WatchCalls.Call?> = graph.watchCalls.call

    fun endWatchCall() {
        graph.scope.launch { graph.watchCalls.hangUp() }
    }

    init {
        // The watch called: one conversation at a time, so this screen's own session gives way.
        viewModelScope.launch { graph.watchCalls.call.collect { if (it != null) sessions.withLock { stopCurrentLocked() } } }
    }

    fun toggle() {
        val s = _state.value
        if (s.active != null) stop() else start(s.engine)
    }

    fun start(engine: Engine) {
        viewModelScope.launch {
            sessions.withLock {
                stopCurrentLocked()
                startLocked(engine)
            }
        }
    }

    private suspend fun startLocked(engine: Engine) {
        val s =
            when (engine) {
                Engine.CHATGPT ->
                    ChatGptRealtimeSession(
                        graph.appContext,
                        graph.session,
                        ChatGptHttp.authClient(),
                        graph.chatGpt,
                        graph.tools,
                        graph.logger,
                        voice = _state.value.voice,
                    )

                Engine.ON_DEVICE -> OnDeviceVoiceSession(graph.appContext, graph.gemma, graph.logger, graph.tools)
            }
        session = s
        _state.update { it.copy(engine = engine, active = engine, savedTo = null) }
        watchJobs =
            listOf(
                viewModelScope.launch { s.state.collect { vs -> _state.update { if (engine == Engine.CHATGPT) it.copy(chatGpt = vs) else it.copy(onDevice = vs) } } },
                viewModelScope.launch { s.level.collect { _level.value = it } },
            )
        s.start()
        PhoneTalkService.start(graph.appContext) { stop() }
    }

    fun stop() {
        viewModelScope.launch { sessions.withLock { stopCurrentLocked() } }
    }

    private suspend fun stopCurrentLocked() {
        val current = session ?: return
        val engine = _state.value.active
        session = null
        PhoneTalkService.stop(graph.appContext)
        current.stop()
        watchJobs.forEach { it.cancel() }
        watchJobs = emptyList()
        _level.value = 0f
        // Keep the final state (the watcher may not have seen it before being cancelled).
        val last = current.state.value
        _state.update { (if (engine == Engine.CHATGPT) it.copy(chatGpt = last) else it.copy(onDevice = last)).copy(active = null) }
        if (last.metrics.turnLatenciesMs.isNotEmpty() || last.metrics.notes.isNotEmpty()) save()
    }

    private fun warmUp() {
        if (warm != null || warmJob?.isActive == true) return
        warmJob =
            viewModelScope.launch(Dispatchers.Default) {
                if (graph.models.readyPath(graph.models.spec(graph.onDeviceSettings.modelId)) == null) {
                    _state.update { it.copy(model = ModelWarmth.MISSING) }
                    return@launch
                }
                _state.update { it.copy(model = ModelWarmth.LOADING) }
                val handle = runCatching { graph.gemma.preload() }.getOrNull()
                when {
                    handle == null -> _state.update { it.copy(model = ModelWarmth.COLD) }

                    !visible -> {
                        withContext(NonCancellable) { handle.release() }
                        _state.update { it.copy(model = ModelWarmth.COLD) }
                    }

                    else -> {
                        warm = handle
                        _state.update { it.copy(model = ModelWarmth.READY) }
                    }
                }
            }
    }

    private fun coolDown() {
        val handle = warm ?: return
        warm = null
        graph.scope.launch { handle.release() }
        _state.update { it.copy(model = ModelWarmth.COLD) }
    }

    fun runProbe() {
        if (_state.value.probeRunning) return
        viewModelScope.launch {
            sessions.withLock { stopCurrentLocked() }
            // The probe loads its own engine with audio input; free the warm text engine first.
            warmJob?.join()
            coolDown()
            val path = graph.models.readyPath(graph.models.spec(graph.onDeviceSettings.modelId))
            _state.update { it.copy(probeRunning = true, probe = null) }
            val result = if (path == null) GemmaAudioProbe.Result(false, error = "model not downloaded") else probe.recordAndAsk(path)
            _state.update { it.copy(probeRunning = false, probe = result) }
            if (visible) warmUp()
        }
    }

    @Serializable
    data class Report(
        val chatGptTurnsMs: List<Long>,
        val chatGptInterruptsMs: List<Long>,
        val chatGptNotes: List<String>,
        val onDeviceTurnsMs: List<Long>,
        val onDeviceInterruptsMs: List<Long>,
        val onDeviceNotes: List<String>,
        val probeSupportsAudio: Boolean?,
        val probeFirstTokenMs: Long?,
        val probeTotalMs: Long?,
        val probeLoadMs: Long?,
        val probeError: String?,
    )

    /** Timings and notes only - never what was said. */
    fun save() {
        val s = _state.value
        val report =
            Report(
                s.chatGpt.metrics.turnLatenciesMs,
                s.chatGpt.metrics.interruptLatenciesMs,
                s.chatGpt.metrics.notes,
                s.onDevice.metrics.turnLatenciesMs,
                s.onDevice.metrics.interruptLatenciesMs,
                s.onDevice.metrics.notes,
                s.probe?.supportsAudio,
                s.probe?.firstTokenMs,
                s.probe?.totalMs,
                s.probe?.loadMs,
                s.probe?.error,
            )
        val dir = File(graph.appContext.getExternalFilesDir(null), "voice").apply { mkdirs() }
        val file = File(dir, "voice-${System.currentTimeMillis()}.json")
        file.writeText(PRETTY.encodeToString(Report.serializer(), report))
        _state.update { it.copy(savedTo = file.name) }
    }

    override fun onCleared() {
        val current = session
        val handle = warm
        session = null
        warm = null
        // viewModelScope is already cancelled here; finish the clean-up on the app scope.
        if (current != null) PhoneTalkService.stop(graph.appContext)
        graph.scope.launch {
            current?.stop()
            handle?.release()
        }
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }
        const val BACKGROUND_GRACE_MS = 30_000L
    }
}
