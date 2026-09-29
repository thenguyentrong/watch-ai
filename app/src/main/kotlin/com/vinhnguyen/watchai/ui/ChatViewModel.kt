package com.vinhnguyen.watchai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.ReportStore
import com.vinhnguyen.watchai.actions.ChatReply
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.DeviceContext
import com.vinhnguyen.watchai.brain.RoutePreference
import com.vinhnguyen.watchai.brain.TurnStats
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The chat test console. Turns ChatGPT answered go to the history like a voice conversation's
 * (unless that's turned off); the phone's private answers never go anywhere, not even back to ChatGPT
 * as part of the conversation.
 */
class ChatViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    data class Message(
        val id: Long,
        val role: ChatTurn.Role,
        val text: String,
        val brain: BrainId? = null,
        val stats: TurnStats? = null,
        val error: String? = null,
        val streaming: Boolean = false,
        val flagged: Boolean = false,
        /** Made on this phone from the user's private data (messages, notes, calendar); never sent to ChatGPT. */
        val private: Boolean = false,
    )

    data class State(
        val messages: List<Message> = emptyList(),
        val preference: RoutePreference = RoutePreference.AUTO,
        val cloudAllowed: Boolean = false,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(State(cloudAllowed = graph.settings.cloudAllowed))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var nextId = 1L
    private var conversationId = UUID.randomUUID().toString()

    // Typed in the app, so the phone is in hand; private answers are shown here, made on the phone.
    private var guard = graph.guard(graph.tools, { true }, ChatReply(::showPrivate))

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return
        // A message or a call waiting for a yes goes out only on the user's own later turn.
        graph.userTurns.heard()
        // A private answer was made on this phone and stays here: it's never part of what goes to a brain.
        val history =
            _state.value.messages
                .filter { !it.private && it.error == null && !it.streaming && it.text.isNotEmpty() }
                .map { ChatTurn(it.role, it.text) }
        val answerId = nextId + 1
        _state.update {
            it.copy(
                messages =
                it.messages +
                    Message(nextId, ChatTurn.Role.USER, prompt) +
                    Message(answerId, ChatTurn.Role.ASSISTANT, "", streaming = true),
                busy = true,
            )
        }
        nextId += 2
        val snapshot = _state.value
        job =
            viewModelScope.launch {
                try {
                    val context = listOfNotNull(DeviceContext.describe(), graph.remembered(except = conversationId)).joinToString("\n")
                    graph.router
                        .stream(ChatRequest(conversationId, history, prompt, context = context, tools = guard), snapshot.preference, snapshot.cloudAllowed)
                        .collect { event ->
                            updateMessage(answerId) { m ->
                                when (event) {
                                    is ChatEvent.Delta -> {
                                        m.copy(text = m.text + event.text)
                                    }

                                    is ChatEvent.Done -> {
                                        m.copy(streaming = false, brain = event.stats.brain, stats = event.stats)
                                    }

                                    is ChatEvent.Failed -> {
                                        m.copy(streaming = false, text = event.partialText, error = Texts.error(event.error))
                                    }
                                }
                            }
                        }
                    // The chat is part of Buddy's history. A turn only the phone's own model heard stays marked as
                    // the phone's: History shows it, ChatGPT never gets it.
                    val answer = _state.value.messages.firstOrNull { it.id == answerId }
                    if (answer != null && answer.brain != null && answer.error == null && answer.text.isNotBlank()) {
                        val local = answer.brain != BrainId.CHATGPT
                        graph.keepTurn(conversationId, IN_CHAT, ChatTurn(ChatTurn.Role.USER, prompt), local)
                        graph.keepTurn(conversationId, IN_CHAT, ChatTurn(ChatTurn.Role.ASSISTANT, answer.text), local)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateMessage(answerId) { it.copy(streaming = false, error = Texts.error(BrainError.Unknown(code = e.code))) }
                } finally {
                    updateMessage(answerId) { if (it.streaming) it.copy(streaming = false, error = "Stopped.") else it }
                    _state.update { it.copy(busy = false) }
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    /** A private answer, made on this phone: shown before the reply that's still coming, never sent to ChatGPT. */
    private fun showPrivate(text: String) {
        _state.update { state ->
            val at = state.messages.indexOfFirst { it.streaming }.takeIf { it >= 0 } ?: state.messages.size
            val message = Message(nextId++, ChatTurn.Role.ASSISTANT, text, brain = BrainId.GEMMA, private = true)
            state.copy(messages = state.messages.toMutableList().apply { add(at, message) })
        }
    }

    fun newConversation() {
        stop()
        conversationId = UUID.randomUUID().toString()
        guard = graph.guard(graph.tools, { true }, ChatReply(::showPrivate))
        _state.update { it.copy(messages = emptyList()) }
    }

    fun setPreference(preference: RoutePreference) = _state.update { it.copy(preference = preference) }

    fun setCloudAllowed(allowed: Boolean) {
        graph.settings.cloudAllowed = allowed
        _state.update { it.copy(cloudAllowed = allowed) }
    }

    fun flag(
        id: Long,
        includePrompt: Boolean,
        reason: String,
    ) {
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val answer = messages[index]
        val prompt = messages.getOrNull(index - 1)?.takeIf { it.role == ChatTurn.Role.USER }?.text
        viewModelScope.launch {
            graph.reports.add(
                ReportStore.Report(
                    atEpochMillis = System.currentTimeMillis(),
                    brain = answer.brain?.name ?: "unknown",
                    model = answer.stats?.model,
                    answer = answer.text,
                    prompt = if (includePrompt) prompt else null,
                    reason = reason,
                ),
            )
            updateMessage(id) { it.copy(flagged = true) }
        }
    }

    private fun updateMessage(
        id: Long,
        change: (Message) -> Message,
    ) = _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }

    private companion object {
        const val IN_CHAT = "in the chat"
    }
}
