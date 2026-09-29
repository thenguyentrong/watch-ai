package com.vinhnguyen.watchai.debug

import android.os.SystemClock
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.actions.ConversationActions
import com.vinhnguyen.watchai.actions.MemoryActions
import com.vinhnguyen.watchai.actions.MessageInbox
import com.vinhnguyen.watchai.actions.OwnerPresence
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.DeviceContext
import com.vinhnguyen.watchai.brain.ReplyStyle
import com.vinhnguyen.watchai.brain.ToolContext
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.brain.Toolboxes
import com.vinhnguyen.watchai.brain.guard.PrivateReply
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID

/**
 * Debug builds only: a conversation with the real ChatGPT planner, as a watch call's look-ups have
 * it (the same tools, the same Guard, Gemma on the phone), driven by typed turns from adb instead of
 * speech. The log (tag BuddyE2E) shows each tool call and what ChatGPT got back, and ChatGPT's
 * answer; what the phone says privately only as a word count and its language, never its words.
 * Made-up messages can be put into the inbox for it ([inject]); [cleanUp] takes them out again.
 */
object EndToEnd {
    private const val TAG = "BuddyE2E"
    private const val FAKE_APP = "debug.e2e.chat"
    private const val SHOWN = 240

    private val lock = Mutex()
    private var conversation = UUID.randomUUID().toString()
    private val history = mutableListOf<ChatTurn>()
    private var guard: Toolbox? = null

    suspend fun reset() = lock.withLock {
        conversation = UUID.randomUUID().toString()
        history.clear()
        guard = null
        log("--- new conversation")
    }

    /** One turn: the user says [text]; ChatGPT plans, the tools run through the Guard, the answer is logged. */
    suspend fun turn(
        graph: AppGraph,
        text: String,
    ) = lock.withLock {
        val gate = guard ?: graph.guard(Toolboxes(listOf(graph.tools, ConversationActions({}, graph.logger))), OwnerPresence(graph.appContext) { null }, PhoneSays(graph)).also { guard = it }
        graph.userTurns.heard()
        log("user> $text")
        val started = SystemClock.elapsedRealtime()
        val context = listOfNotNull(DeviceContext.describe(), graph.remembered(except = conversation)).joinToString("\n")
        val request = ChatRequest(conversation, history.toList(), text, style = ReplyStyle.SPOKEN, context = context, webSearch = true, tools = Logged(gate))
        val answer = StringBuilder()
        var failure: String? = null
        graph.chatGpt.stream(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> answer.append(event.text)
                is ChatEvent.Failed -> failure = "${event.error}"
                is ChatEvent.Done -> Unit
            }
        }
        val said = answer.toString().trim()
        history += ChatTurn(ChatTurn.Role.USER, text)
        if (said.isNotEmpty()) history += ChatTurn(ChatTurn.Role.ASSISTANT, said)
        log("buddy> $said${failure?.let { "  [failed: $it]" }.orEmpty()}  (${SystemClock.elapsedRealtime() - started} ms)")
    }

    /** A made-up message in the inbox, from a made-up chat app that's turned on for Buddy. */
    fun inject(
        graph: AppGraph,
        from: String,
        text: String,
    ) {
        graph.limits.choose(FAKE_APP, true)
        val at = System.currentTimeMillis()
        graph.inbox.add(MessageInbox.Message("e2e-$at-${from.hashCode()}", FAKE_APP, "TestChat", from, null, text, at, null))
        log("inbox + $from: $text")
    }

    /** Takes out the made-up messages, the test's notes (they say [TEST_NOTE]) and turns [apps] off again. */
    suspend fun cleanUp(
        graph: AppGraph,
        apps: List<String>,
    ) {
        graph.inbox.recent(System.currentTimeMillis()).filter { it.packageName == FAKE_APP }.forEach { graph.inbox.remove(it.key) }
        graph.limits.choose(FAKE_APP, false)
        graph.notes.list().filter { it.text.contains(TEST_NOTE, ignoreCase = true) }.forEach { graph.notes.delete(it.id) }
        apps.forEach { graph.limits.choose(it, false) }
        log("cleaned up: made-up messages, test notes, turned off $apps")
    }

    const val TEST_NOTE = "e2e test note"

    private fun log(line: String) {
        Timber.tag(TAG).i("%s", line)
    }

    /** What the phone would say itself: only how much and in which language, never the words (they may be the user's real data). */
    private class PhoneSays(
        private val graph: AppGraph,
    ) : PrivateReply {
        override suspend fun tell(answer: suspend () -> String): Boolean = tell(null, answer)

        override suspend fun tell(
            language: String?,
            answer: suspend () -> String,
        ): Boolean {
            val said = answer()
            val got = graph.languages.of(said)
            log("phone says privately: ${said.split(' ').count { it.isNotBlank() }} words, asked in $language, said in $got")
            return true
        }
    }

    /** Logs each call and what ChatGPT gets back from the Guard. A recall of earlier conversations only as its length. */
    private class Logged(
        private val inner: Toolbox,
    ) : Toolbox {
        override fun tools(): List<ToolSpec> = inner.tools()

        override suspend fun run(
            name: String,
            argumentsJson: String,
        ): String = run(name, argumentsJson, ToolContext(""))

        override suspend fun run(
            name: String,
            argumentsJson: String,
            context: ToolContext,
        ): String {
            val result = inner.run(name, argumentsJson, context)
            val shown = if (name == MemoryActions.RECALL) "(${result.length} chars of earlier conversations)" else result.take(SHOWN)
            log("tool $name ${argumentsJson.take(SHOWN)} => $shown")
            return result
        }

        override val sharedPrivateData: Boolean get() = inner.sharedPrivateData
    }
}
