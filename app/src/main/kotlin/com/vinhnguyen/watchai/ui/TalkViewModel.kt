package com.vinhnguyen.watchai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.PhoneTalkService
import com.vinhnguyen.watchai.actions.ConversationActions
import com.vinhnguyen.watchai.brain.Toolboxes
import com.vinhnguyen.watchai.brain.chatgpt.ChatGptHttp
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.buddy.Mood
import com.vinhnguyen.watchai.buddy.MoodReader
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.VoicePhase
import com.vinhnguyen.watchai.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Talking to Buddy on the phone, like on the watch: tap Buddy, talk, tap again or say bye. ChatGPT
 * voice on the user's own plan, with everything Buddy can do. A conversation from the watch shows
 * here too, and a tap ends it. It keeps going when the user switches apps (the talk service holds
 * the mic); without that service, it ends after a short while in the background.
 */
class TalkViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    data class State(
        val phase: VoicePhase = VoicePhase.IDLE,
        val detail: String? = null,
        /** The conversation is the watch's; the phone only shows it. */
        val onWatch: Boolean = false,
        val reaction: Reaction? = null,
        val reactionId: Int = 0,
    )

    /** What a tap on Buddy did. */
    enum class Tap { TALKING, ENDED, NEEDS_SIGN_IN, NEEDS_MIC }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    val auth: StateFlow<AuthState> = graph.session.state

    private val lock = Mutex()
    private var session: ChatGptRealtimeSession? = null
    private var jobs: List<Job> = emptyList()
    private var stopLater: Job? = null
    private var heard: String? = null
    private var said: String? = null
    private var shownMood: Mood? = null

    init {
        viewModelScope.launch { runCatching { graph.session.isSignedIn() } }
        // The watch called: it's shown here, and this phone's own conversation makes way.
        viewModelScope.launch {
            graph.watchCalls.call.collect { call ->
                if (call != null) {
                    lock.withLock { endLocked() }
                    show(call.voice, onWatch = true)
                } else if (session == null) {
                    _state.update { State(reaction = it.reaction, reactionId = it.reactionId) }
                    _level.value = 0f
                }
            }
        }
    }

    suspend fun buddySeed(): Long = graph.buddySeed()

    fun tap(micAllowed: Boolean): Tap = when {
        graph.watchCalls.call.value != null -> {
            graph.scope.launch { graph.watchCalls.hangUp() }
            Tap.ENDED
        }

        session != null -> {
            end()
            Tap.ENDED
        }

        // Still loading counts as signed in: the conversation says so itself if it isn't.
        auth.value is AuthState.SignedOut -> Tap.NEEDS_SIGN_IN

        !micAllowed -> Tap.NEEDS_MIC

        else -> {
            start()
            Tap.TALKING
        }
    }

    private fun start() {
        viewModelScope.launch {
            lock.withLock {
                endLocked()
                val s =
                    ChatGptRealtimeSession(
                        graph.appContext,
                        graph.session,
                        ChatGptHttp.authClient(),
                        graph.chatGpt,
                        Toolboxes(listOf(graph.tools, ConversationActions({ session?.endSoon() }, graph.logger))),
                        graph.logger,
                        voice = graph.settings.voice,
                        idleHangUpMs = IDLE_HANG_UP_MS,
                        onIdle = { end() },
                        onUserWords = graph.userTurns::heard,
                    )
                session = s
                jobs =
                    listOf(
                        viewModelScope.launch { s.state.collect { show(it, onWatch = false) } },
                        viewModelScope.launch { s.level.collect { _level.value = it } },
                    )
                s.start()
                PhoneTalkService.start(graph.appContext) { end() }
            }
        }
    }

    fun end() {
        viewModelScope.launch { lock.withLock { endLocked() } }
    }

    private suspend fun endLocked() {
        val current = session ?: return
        session = null
        PhoneTalkService.stop(graph.appContext)
        current.stop()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _level.value = 0f
        _state.update { State(reaction = it.reaction, reactionId = it.reactionId) }
    }

    /**
     * Buddy follows the conversation, and its face the words: the user's thanks and greetings, the
     * answer's tone. Each mood plays once per turn, as on the watch.
     */
    private fun show(
        voice: VoiceState,
        onWatch: Boolean,
    ) {
        _state.update { it.copy(phase = voice.phase, detail = voice.detail, onWatch = onWatch) }
        voice.lastUserText?.takeIf { it != heard }?.let { text ->
            if (!continues(heard, text)) shownMood = null
            heard = text
            react(MoodReader.user(text))
        }
        voice.lastAssistantText?.takeIf { it != said }?.let { text ->
            if (!continues(said, text)) shownMood = null
            said = text
            react(MoodReader.assistant(text))
        }
    }

    private fun react(reaction: Reaction?) {
        if (reaction == null || reaction.mood == shownMood) return
        shownMood = reaction.mood
        _state.update { it.copy(reaction = reaction, reactionId = it.reactionId + 1) }
    }

    /** The same turn still being written, rather than a new one. */
    private fun continues(
        before: String?,
        now: String,
    ) = before != null && now.startsWith(before.take(TURN_PREFIX))

    /** Leaving the app: with the talk service holding the mic the conversation goes on, otherwise it ends soon. */
    fun onBackground() {
        stopLater?.cancel()
        if (session == null || PhoneTalkService.running) return
        stopLater =
            viewModelScope.launch {
                delay(BACKGROUND_GRACE_MS)
                lock.withLock { endLocked() }
            }
    }

    fun onForeground() {
        stopLater?.cancel()
        stopLater = null
    }

    override fun onCleared() {
        val current = session ?: return
        session = null
        PhoneTalkService.stop(graph.appContext)
        graph.scope.launch { current.stop() }
    }

    private companion object {
        /** Like on the watch: back to rest after 10 s with nobody talking. */
        const val IDLE_HANG_UP_MS = 10_000L
        const val BACKGROUND_GRACE_MS = 30_000L
        const val TURN_PREFIX = 12
    }
}
