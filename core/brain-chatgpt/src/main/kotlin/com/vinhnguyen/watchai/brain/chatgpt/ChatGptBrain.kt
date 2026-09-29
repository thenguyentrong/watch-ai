package com.vinhnguyen.watchai.brain.chatgpt

import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.Brain
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.BrainException
import com.vinhnguyen.watchai.brain.BrainId
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ToolContext
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.brain.TurnStats
import com.vinhnguyen.watchai.brain.UnavailableReason
import com.vinhnguyen.watchai.brain.chatgpt.api.ErrorMapper
import com.vinhnguyen.watchai.brain.chatgpt.api.ResponsesRequest
import com.vinhnguyen.watchai.brain.chatgpt.api.SseEvent
import com.vinhnguyen.watchai.brain.chatgpt.api.SseReader
import com.vinhnguyen.watchai.brain.chatgpt.api.UsageSnapshot
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthSession
import com.vinhnguyen.watchai.brain.chatgpt.auth.Bearer
import com.vinhnguyen.watchai.brain.chatgpt.auth.OpenAiEndpoints
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

public data class ChatGptSettings(
    /** Tried in order; the first one this account can use is remembered for the session. */
    val models: List<String> = listOf("gpt-6-luna", "gpt-6-sol", "gpt-5.6-luna"),
    val reasoningEffort: String = "low",
    val userAgent: String = "WatchAI/0.1 (Android)",
    val enabled: Boolean = true,
    /** Debug builds: log redacted error-body snippets. Never on in release. */
    val diagnostics: Boolean = false,
)

/** ChatGPT answers on the user's own Plus/Pro plan, through the Codex Responses endpoint. */
public class ChatGptBrain(
    private val session: AuthSession,
    private val http: OkHttpClient,
    private val endpoints: OpenAiEndpoints = OpenAiEndpoints(),
    private val settings: ChatGptSettings = ChatGptSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: BrainLogger = BrainLogger.None,
) : Brain {
    override val id: BrainId = BrainId.CHATGPT

    private val errors = ErrorMapper(clock)
    private val json = Json { ignoreUnknownKeys = true }
    private val modelIndex = AtomicInteger(0)
    private val _usage = MutableStateFlow<UsageSnapshot?>(null)
    public val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    override suspend fun availability(): Availability = when {
        !settings.enabled -> Availability.Unavailable(UnavailableReason.DISABLED)
        session.isSignedIn() -> Availability.Ready
        else -> Availability.NeedsSignIn
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val started = clock.millis()
        var firstToken: Long? = null
        val text = StringBuilder()
        logger.log(LogEvent.TurnStarted(id))
        try {
            val model =
                runTurn(request) { delta ->
                    if (firstToken == null) firstToken = clock.millis() - started
                    text.append(delta)
                    emit(ChatEvent.Delta(delta))
                }
            val total = clock.millis() - started
            logger.log(LogEvent.TurnFinished(id, model, backend = "cloud", firstTokenMillis = firstToken, totalMillis = total))
            emit(ChatEvent.Done(TurnStats(id, model, "cloud", firstToken, total, text.length)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrainException) {
            logger.log(LogEvent.TurnFailed(id, e.error.code, (e.error as? BrainError.Unknown)?.status))
            emit(ChatEvent.Failed(e.error, text.toString()))
        } catch (e: Exception) {
            logger.log(LogEvent.TurnFailed(id, e.code, null))
            emit(ChatEvent.Failed(BrainError.Unknown(code = e.code), text.toString()))
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface Attempt {
        /** [calls]: tools the model wants run; [items]: its output items, replayed with the results. */
        data class Finished(
            val model: String,
            val calls: List<FunctionCall> = emptyList(),
            val items: List<JsonObject> = emptyList(),
        ) : Attempt

        data object RetryAuth : Attempt

        data object RetryNetwork : Attempt

        data object NextModel : Attempt

        data object WithoutSearch : Attempt
    }

    /** Returns the model that answered. Throws [BrainException]. */
    private suspend fun runTurn(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
    ): String {
        var bearer = session.bearer()
        var authRetried = false
        var networkRetried = false
        var search = request.webSearch
        var spoke = false
        var replay = emptyList<JsonObject>()
        var toolRounds = 0
        val relay: suspend (String) -> Unit = {
            spoke = true
            onDelta(it)
        }
        while (true) {
            val index = modelIndex.get().coerceAtMost(settings.models.lastIndex)
            val model = settings.models[index]
            // Checked every round: a tool result in the last one may have brought private data in.
            val liveWeb = request.tools?.sharedPrivateData != true
            val body = ResponsesRequest.body(request.copy(webSearch = search), model, settings.reasoningEffort, replay, liveWeb)
            val call = http.newCall(ResponsesRequest.http(endpoints.responses, body, bearer, request.conversationId, settings.userAgent))
            when (
                val attempt =
                    attempt(
                        call = call,
                        model = model,
                        canRetryAuth = !authRetried,
                        canRetryNetwork = !networkRetried && !spoke,
                        hasNextModel = index < settings.models.lastIndex && !spoke,
                        canDropSearch = search && !spoke,
                        onDelta = relay,
                    )
            ) {
                is Attempt.Finished -> {
                    val tools = request.tools
                    if (attempt.calls.isEmpty() || tools == null || toolRounds >= MAX_TOOL_ROUNDS) return attempt.model
                    // Stateless endpoint: replay the model's items, then the results, and ask again.
                    toolRounds++
                    val results = attempt.calls.map { call -> functionOutput(call.callId, runTool(tools, call, request.userText)) }
                    replay = replay + attempt.items + results
                }

                Attempt.RetryAuth -> {
                    authRetried = true
                    bearer = session.onUnauthorized(bearer)
                }

                Attempt.RetryNetwork -> {
                    networkRetried = true
                    delay(RETRY_BASE_MS + Random.nextLong(RETRY_BASE_MS))
                }

                Attempt.NextModel -> {
                    modelIndex.compareAndSet(index, index + 1)
                }

                Attempt.WithoutSearch -> {
                    search = false
                }
            }
        }
    }

    internal data class FunctionCall(
        val callId: String,
        val name: String,
        val arguments: String,
    )

    private suspend fun runTool(
        tools: Toolbox,
        call: FunctionCall,
        question: String,
    ): String = try {
        tools.run(call.name, call.arguments, ToolContext(question))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log(LogEvent.ToolUsed(call.name, "crashed_${e.code}"))
        "error: the action failed"
    }

    private fun functionOutput(
        callId: String,
        output: String,
    ): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", callId)
        put("output", output)
    }

    private suspend fun attempt(
        call: Call,
        model: String,
        canRetryAuth: Boolean,
        canRetryNetwork: Boolean,
        hasNextModel: Boolean,
        canDropSearch: Boolean,
        onDelta: suspend (String) -> Unit,
    ): Attempt {
        val response =
            try {
                call.executeAsync()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (canRetryNetwork) return Attempt.RetryNetwork
                throw BrainException(BrainError.Offline, e)
            }
        response.use { r ->
            if (r.code == 401) {
                if (canRetryAuth) return Attempt.RetryAuth
                throw BrainException(BrainError.AuthExpired("unauthorized_after_refresh"))
            }
            if (!r.isSuccessful) {
                val body = r.body.string().take(MAX_ERROR_BODY)
                val error = errors.fromHttp(r.code, r.headers, body)
                logger.log(LogEvent.ProviderResponse(model, r.code, (error as? BrainError.ProviderChanged)?.kind ?: error.code))
                if (settings.diagnostics) logger.log(LogEvent.ProviderDiagnostic(r.code, body.take(DIAGNOSTIC_CHARS)))
                if (error is BrainError.ProviderChanged && error.kind == "model_unavailable" && hasNextModel) return Attempt.NextModel
                // Search is an extra: if the endpoint refuses the request with it, ask again without.
                if (r.code == 400 && canDropSearch) return Attempt.WithoutSearch
                if (r.code >= 500 && error is BrainError.Unknown && canRetryNetwork) return Attempt.RetryNetwork
                throw BrainException(error)
            }
            // The Codex endpoint streams without a Content-Type header, so only a declared non-stream type is wrong.
            val contentType = r.body.contentType()
            if (contentType != null && contentType.subtype != "event-stream") {
                logger.log(LogEvent.ProviderResponse(model, r.code, "non_sse"))
                if (settings.diagnostics) logger.log(LogEvent.ProviderDiagnostic(r.code, "content-type=$contentType " + r.body.string().take(DIAGNOSTIC_CHARS)))
                throw BrainException(BrainError.ProviderChanged("non_sse", r.code, errors.requestId(r.headers)))
            }
            UsageSnapshot.fromHeaders(r.headers)?.let { _usage.value = it }
            val served = r.header("openai-model") ?: model
            return readEvents(call, SseReader(r.body.source()), served, onDelta)
        }
    }

    /**
     * Socket reads block and ignore coroutine cancellation, so they run on their own job and the
     * consumer cancels the HTTP call whenever it stops (answer done, error, or the user hit stop).
     */
    private suspend fun readEvents(
        call: Call,
        reader: SseReader,
        model: String,
        onDelta: suspend (String) -> Unit,
    ): Attempt.Finished = coroutineScope {
        val events = Channel<SseEvent>(capacity = 64)
        launch(Dispatchers.IO) {
            try {
                while (true) events.send(reader.next() ?: break)
                events.close()
            } catch (e: IOException) {
                events.close(e)
            }
        }
        try {
            handleEvents(events, model, onDelta)
        } finally {
            call.cancel()
        }
    }

    private suspend fun handleEvents(
        events: ReceiveChannel<SseEvent>,
        model: String,
        onDelta: suspend (String) -> Unit,
    ): Attempt.Finished {
        var produced = false
        val calls = mutableListOf<FunctionCall>()
        val items = mutableListOf<JsonObject>()
        while (true) {
            val result = events.receiveCatching()
            val event =
                result.getOrNull() ?: run {
                    val cause = result.exceptionOrNull()
                    if (cause is IOException) {
                        currentCoroutineContext().ensureActive()
                        throw BrainException(BrainError.Offline, cause)
                    }
                    throw BrainException(BrainError.Unknown(code = "truncated"))
                }
            if (event.data.isEmpty() || event.data == "[DONE]") continue
            val obj = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: continue
            when (obj.str("type") ?: event.event) {
                "response.output_text.delta", "response.refusal.delta" -> {
                    val delta = obj.str("delta").orEmpty()
                    if (delta.isNotEmpty()) {
                        produced = true
                        onDelta(delta)
                    }
                }

                "response.output_item.done" -> {
                    val item = obj["item"] as? JsonObject
                    if (item != null) {
                        items += item
                        if (item.str("type") == "function_call") {
                            val callId = item.str("call_id")
                            val name = item.str("name")
                            if (callId != null && name != null) calls += FunctionCall(callId, name, item.str("arguments") ?: "{}")
                        }
                    }
                }

                "response.completed" -> {
                    return Attempt.Finished(model, calls, items)
                }

                "response.incomplete" -> {
                    val reason = (obj["response"] as? JsonObject)?.let { (it["incomplete_details"] as? JsonObject)?.str("reason") }
                    if (produced && reason != "content_filter") return Attempt.Finished(model, calls, items)
                    throw BrainException(BrainError.Unknown(code = "incomplete_${reason ?: "unknown"}"))
                }

                "response.failed" -> {
                    if (settings.diagnostics) logger.log(LogEvent.ProviderDiagnostic(200, event.data.take(DIAGNOSTIC_CHARS)))
                    throw BrainException(errors.fromStream((obj["response"] as? JsonObject)?.get("error")))
                }

                "error" -> {
                    if (settings.diagnostics) logger.log(LogEvent.ProviderDiagnostic(200, event.data.take(DIAGNOSTIC_CHARS)))
                    throw BrainException(errors.fromStream(obj["error"] ?: obj))
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val RETRY_BASE_MS = 400L
        const val MAX_ERROR_BODY = 8_192
        const val DIAGNOSTIC_CHARS = 400
        const val MAX_TOOL_ROUNDS = 4
    }
}
