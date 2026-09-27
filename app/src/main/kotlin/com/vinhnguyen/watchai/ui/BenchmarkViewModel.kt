package com.vinhnguyen.watchai.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Debug-only: ten synthetic prompts per brain, timings saved for the test report. */
class BenchmarkViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    @Serializable
    data class Row(
        val brain: String,
        val prompt: Int,
        val ok: Boolean,
        val firstTokenMillis: Long?,
        val totalMillis: Long?,
        val chars: Int,
        val backend: String?,
        val model: String?,
        val error: String?,
        val javaHeapMb: Long,
    )

    data class State(
        val running: Boolean = false,
        val rows: List<Row> = emptyList(),
        val savedTo: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun run(brainId: BrainId) {
        if (_state.value.running) return
        val brain: Brain =
            when (brainId) {
                BrainId.CHATGPT -> graph.chatGpt
                BrainId.GEMMA -> graph.gemma
                BrainId.GEMINI_NANO -> graph.nano
            }
        job =
            viewModelScope.launch {
                _state.update { it.copy(running = true, savedTo = null) }
                try {
                    PROMPTS.forEachIndexed { index, prompt ->
                        val events = mutableListOf<ChatEvent>()
                        brain.stream(ChatRequest(UUID.randomUUID().toString(), emptyList(), prompt)).collect { events += it }
                        val done = events.filterIsInstance<ChatEvent.Done>().firstOrNull()
                        val failed = events.filterIsInstance<ChatEvent.Failed>().firstOrNull()
                        val runtime = Runtime.getRuntime()
                        val row =
                            Row(
                                brain = brainId.name,
                                prompt = index + 1,
                                ok = done != null,
                                firstTokenMillis = done?.stats?.firstTokenMillis,
                                totalMillis = done?.stats?.totalMillis,
                                chars = done?.stats?.outputChars ?: 0,
                                backend = done?.stats?.backend,
                                model = done?.stats?.model,
                                error = failed?.error?.code,
                                javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
                            )
                        _state.update { it.copy(rows = it.rows + row) }
                    }
                } finally {
                    _state.update { it.copy(running = false) }
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() = _state.update { State() }

    /** Timings only - prompts are synthetic and answers are not saved. adb pull from Android/data/.../files/benchmarks. */
    fun save(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "benchmarks").apply { mkdirs() }
        val file = File(dir, "benchmark-${System.currentTimeMillis()}.json")
        file.writeText(PRETTY.encodeToString(ListSerializer(Row.serializer()), _state.value.rows))
        _state.update { it.copy(savedTo = file.path) }
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        val PROMPTS =
            listOf(
                "What is 15% of 80?",
                "Name three primary colours.",
                "How many minutes are in three hours?",
                "Translate 'good morning' into German.",
                "What is the capital of Australia?",
                "Give me one tip to sleep better.",
                "Convert 30 degrees Celsius to Fahrenheit.",
                "What does GPU stand for?",
                "Suggest a name for a small friendly robot.",
                "Is 97 a prime number?",
            )
    }
}
