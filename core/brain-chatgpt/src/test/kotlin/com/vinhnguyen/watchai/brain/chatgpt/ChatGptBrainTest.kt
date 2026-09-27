package com.vinhnguyen.watchai.brain.chatgpt

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.brain.Availability
import com.vinhnguyen.watchai.brain.BrainError
import com.vinhnguyen.watchai.brain.ChatEvent
import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthSession
import com.vinhnguyen.watchai.brain.chatgpt.auth.CodexOAuthClient
import com.vinhnguyen.watchai.brain.chatgpt.auth.TokenResponse
import com.vinhnguyen.watchai.testing.InMemorySecretStore
import com.vinhnguyen.watchai.testing.Synthetic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatGptBrainTest {
    private lateinit var server: MockWebServer
    private val clock = TestClock()
    private val store = InMemorySecretStore()
    private lateinit var session: AuthSession
    private lateinit var brain: ChatGptBrain
    private val request =
        ChatRequest(
            conversationId = "conv_SYNTHETIC",
            history = listOf(ChatTurn(ChatTurn.Role.USER, "hi"), ChatTurn(ChatTurn.Role.ASSISTANT, "Hello!")),
            userText = "What is 15% of 80?",
        )

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer().apply { start() }
        val endpoints = server.endpoints()
        session = AuthSession(store, CodexOAuthClient(testAuthClient(), endpoints), clock)
        brain = ChatGptBrain(session, testStreamClient(), endpoints, clock = clock)
        val exp = clock.now.epochSecond + 864_000
        session.completeSignIn(TokenResponse(Synthetic.jwt(exp), Synthetic.jwt(exp), "SYNTHETIC_RT", 864_000))
        Unit
    }

    @After
    fun tearDown() = server.close()

    private fun run() = runBlocking { withTimeout(15_000) { brain.stream(request).toList() } }

    private fun Json.obj(s: String) = parseToJsonElement(s).jsonObject

    @Test
    fun `streams deltas then done`() {
        server.enqueue(Sse.response(Sse.created(), Sse.delta("It's "), Sse.delta("12."), Sse.completed()))
        val events = run()
        assertThat(events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }).isEqualTo("It's 12.")
        val done = events.last() as ChatEvent.Done
        assertThat(done.stats.model).isEqualTo("gpt-6-luna")
        assertThat(done.stats.outputChars).isEqualTo(8)
    }

    @Test
    fun `request follows the Codex contract and carries the account header`() {
        server.enqueue(Sse.response(Sse.delta("ok"), Sse.completed()))
        run()
        val recorded = server.takeRequest()
        assertThat(recorded.url.encodedPath).isEqualTo("/backend-api/codex/responses")
        assertThat(recorded.headers["Authorization"]).startsWith("Bearer ")
        assertThat(recorded.headers["ChatGPT-Account-ID"]).isEqualTo(Synthetic.ACCOUNT_ID)
        assertThat(recorded.headers["Accept"]).isEqualTo("text/event-stream")
        assertThat(recorded.headers["originator"]).isEqualTo("watchai")
        assertThat(recorded.headers["session-id"]).isEqualTo("conv_SYNTHETIC")
        assertThat(recorded.headers["X-OpenAI-Fedramp"]).isNull()
        val body = Json.obj(recorded.body!!.utf8())
        assertThat(body["store"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(body["stream"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["instructions"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(body.keys).containsNoneOf("temperature", "max_output_tokens", "previous_response_id")
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertThat(input.map { it["role"]!!.jsonPrimitive.content }).containsExactly("user", "assistant", "user").inOrder()
        val lastContent = (input.last()["content"]!!.jsonArray.first() as JsonObject)
        assertThat(lastContent["type"]!!.jsonPrimitive.content).isEqualTo("input_text")
        assertThat(lastContent["text"]!!.jsonPrimitive.content).isEqualTo("What is 15% of 80?")
    }

    @Test
    fun `a 401 refreshes once and retries`() {
        server.enqueue(json("{}", 401))
        val exp = clock.now.epochSecond + 864_000
        server.enqueue(json("""{"access_token":"${Synthetic.jwt(exp + 1)}","refresh_token":"SYNTHETIC_RT2","expires_in":864000}"""))
        server.enqueue(Sse.response(Sse.delta("12."), Sse.completed()))
        val events = run()
        assertThat(events.last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(server.takeRequest().url.encodedPath).endsWith("/responses")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/oauth/token")
        assertThat(server.takeRequest().url.encodedPath).endsWith("/responses")
    }

    @Test
    fun `usage limit is reported with its reset time and never retried`() {
        server.enqueue(
            json("""{"error":{"type":"usage_limit_reached","plan_type":"plus","resets_at":1790000000,"limit_window_minutes":300}}""", 429),
        )
        val failed = run().single() as ChatEvent.Failed
        assertThat(failed.error).isEqualTo(BrainError.UsageLimitReached(1_790_000_000, notIncluded = false))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `plain rate limit carries retry-after`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(429)
                .addHeader("Retry-After", "7")
                .body("{}")
                .build(),
        )
        assertThat((run().single() as ChatEvent.Failed).error).isEqualTo(BrainError.RateLimited(7_000))
    }

    @Test
    fun `an unavailable model falls through to the next one`() {
        server.enqueue(json("""{"detail":"The 'gpt-6-luna' model is not supported when using Codex with a ChatGPT account."}""", 400))
        server.enqueue(Sse.response(Sse.delta("12."), Sse.completed()))
        val done = run().last() as ChatEvent.Done
        assertThat(done.stats.model).isEqualTo("gpt-6-sol")
        server.takeRequest()
        assertThat(Json.obj(server.takeRequest().body!!.utf8())["model"]!!.jsonPrimitive.content).isEqualTo("gpt-6-sol")
    }

    @Test
    fun `web search and device facts go into the request`() {
        server.enqueue(Sse.response(Sse.delta("ok"), Sse.completed()))
        runBlocking { brain.stream(request.copy(webSearch = true, context = "The user's local time is noon.")).toList() }
        val body = Json.obj(server.takeRequest().body!!.utf8())
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertThat(tool["type"]!!.jsonPrimitive.content).isEqualTo("web_search")
        assertThat(tool["external_web_access"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["instructions"]!!.jsonPrimitive.content).endsWith("The user's local time is noon.")
    }

    @Test
    fun `plain requests carry no tools`() {
        server.enqueue(Sse.response(Sse.delta("ok"), Sse.completed()))
        run()
        assertThat(Json.obj(server.takeRequest().body!!.utf8()).keys).doesNotContain("tools")
    }

    @Test
    fun `if the endpoint refuses web search, the question is asked again without it`() {
        server.enqueue(json("""{"detail":"Unsupported tool type: web_search"}""", 400))
        server.enqueue(Sse.response(Sse.delta("JEV is Japanese encephalitis virus."), Sse.completed()))
        val events = runBlocking { brain.stream(request.copy(webSearch = true)).toList() }
        assertThat(events.last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(Json.obj(server.takeRequest().body!!.utf8()).keys).contains("tools")
        assertThat(Json.obj(server.takeRequest().body!!.utf8()).keys).doesNotContain("tools")
    }

    private class RecordingToolbox : Toolbox {
        val calls = mutableListOf<Pair<String, String>>()

        override fun tools() = listOf(ToolSpec("add_note", "Adds a note.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""))

        override suspend fun run(
            name: String,
            argumentsJson: String,
        ): String {
            calls += name to argumentsJson
            return "ok: note saved"
        }
    }

    private fun functionCall(
        callId: String,
        name: String,
        arguments: String,
    ) = Sse.event(
        "response.output_item.done",
        """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_SYNTHETIC","call_id":"$callId","name":"$name","arguments":${Json.encodeToString(arguments)}}}""",
    )

    @Test
    fun `a tool call runs on the phone and the answer continues with its result`() {
        val toolbox = RecordingToolbox()
        server.enqueue(
            Sse.response(
                Sse.event("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_SYNTHETIC","encrypted_content":"SYNTHETIC","summary":[]}}"""),
                functionCall("call_SYNTHETIC", "add_note", """{"text":"buy milk"}"""),
                Sse.completed(),
            ),
        )
        server.enqueue(Sse.response(Sse.delta("Added buy milk to your notes."), Sse.completed()))

        val events = runBlocking { withTimeout(15_000) { brain.stream(request.copy(tools = toolbox)).toList() } }

        assertThat(events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }).isEqualTo("Added buy milk to your notes.")
        assertThat(events.last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(toolbox.calls).containsExactly("add_note" to """{"text":"buy milk"}""")

        val first = Json.obj(server.takeRequest().body!!.utf8())
        val function = first["tools"]!!.jsonArray.single().jsonObject
        assertThat(function["type"]!!.jsonPrimitive.content).isEqualTo("function")
        assertThat(function["name"]!!.jsonPrimitive.content).isEqualTo("add_note")
        assertThat(function["parameters"]!!.jsonObject["required"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("text")

        // Stateless endpoint: the second request replays the reasoning and the call, then the result.
        val second = Json.obj(server.takeRequest().body!!.utf8())
        val replayed = second["input"]!!.jsonArray.map { it.jsonObject }.takeLast(3)
        assertThat(replayed.map { it["type"]!!.jsonPrimitive.content }).containsExactly("reasoning", "function_call", "function_call_output").inOrder()
        assertThat(replayed[2]["call_id"]!!.jsonPrimitive.content).isEqualTo("call_SYNTHETIC")
        assertThat(replayed[2]["output"]!!.jsonPrimitive.content).isEqualTo("ok: note saved")
    }

    @Test
    fun `tool calls without a toolbox end the turn instead of looping`() {
        server.enqueue(Sse.response(functionCall("call_SYNTHETIC", "add_note", "{}"), Sse.completed()))
        assertThat(run().last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a model that keeps calling tools is stopped after a few rounds`() {
        val toolbox = RecordingToolbox()
        repeat(6) { server.enqueue(Sse.response(functionCall("call_$it", "add_note", """{"text":"x"}"""), Sse.completed())) }
        val events = runBlocking { withTimeout(15_000) { brain.stream(request.copy(tools = toolbox)).toList() } }
        assertThat(events.last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(toolbox.calls).hasSize(4)
        assertThat(server.requestCount).isEqualTo(5)
    }

    @Test
    fun `contract changes are reported as needing an update`() {
        server.enqueue(json("""{"detail":"Store must be set to false"}""", 400))
        val error = (run().single() as ChatEvent.Failed).error as BrainError.ProviderChanged
        assertThat(error.kind).isEqualTo("contract")
    }

    @Test
    fun `a mid-stream failure keeps the partial text`() {
        server.enqueue(
            Sse.response(
                Sse.delta("Twel"),
                Sse.event(
                    "response.failed",
                    """{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"Please try again in 11.054s."}}}""",
                ),
            ),
        )
        val events = run()
        val failed = events.last() as ChatEvent.Failed
        assertThat(failed.partialText).isEqualTo("Twel")
        assertThat(failed.error).isEqualTo(BrainError.RateLimited(11_054))
    }

    @Test
    fun `policy refusals and error events are mapped`() {
        server.enqueue(Sse.response(Sse.event("error", """{"type":"error","error":{"code":"cyber_policy","message":"SYNTHETIC"}}""")))
        assertThat((run().single() as ChatEvent.Failed).error).isEqualTo(BrainError.Refused("cyber_policy"))
    }

    @Test
    fun `a stream that ends without a final event is truncated`() {
        server.enqueue(Sse.response(Sse.delta("Twel")))
        val failed = run().last() as ChatEvent.Failed
        assertThat(failed.error).isEqualTo(BrainError.Unknown(code = "truncated"))
    }

    @Test
    fun `a stream without a content-type header still works (the Codex endpoint sends none)`() {
        server.enqueue(
            MockResponse
                .Builder()
                .body(Sse.created() + Sse.delta("12.") + Sse.completed())
                .build(),
        )
        val events = run()
        assertThat(events.filterIsInstance<ChatEvent.Delta>().single().text).isEqualTo("12.")
        assertThat(events.last()).isInstanceOf(ChatEvent.Done::class.java)
    }

    @Test
    fun `non-sse responses are a provider change`() {
        server.enqueue(json("""{"hello":"world"}"""))
        val error = (run().single() as ChatEvent.Failed).error as BrainError.ProviderChanged
        assertThat(error.kind).isEqualTo("non_sse")
    }

    @Test
    fun `crlf line endings, multi-line data and unknown events are handled`() {
        val crlf = Sse.event("response.output_text.delta", """{"type":"response.output_text.delta","delta":"A"}""", "\r\n")
        val unknown = Sse.event("response.content_part.added", """{"type":"response.content_part.added"}""")
        val multiLine = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\ndata: \"delta\":\"B\"}\n\n"
        server.enqueue(Sse.response(": keep-alive comment\n\n", crlf, unknown, multiLine, Sse.completed()))
        val text = run().filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertThat(text).isEqualTo("AB")
    }

    @Test
    fun `usage headers feed the usage meter`() {
        server.enqueue(
            Sse.response(
                Sse.delta("x"),
                Sse.completed(),
                headers = mapOf("x-codex-primary-used-percent" to "91.5", "x-codex-primary-reset-at" to "1790000000"),
            ),
        )
        run()
        val usage = brain.usage.value!!
        assertThat(usage.primaryUsedPercent).isEqualTo(91.5)
        assertThat(usage.nearLimit).isTrue()
    }

    @Test
    fun `server errors before the first word are retried once`() {
        server.enqueue(json("{}", 502))
        server.enqueue(Sse.response(Sse.delta("12."), Sse.completed()))
        assertThat(run().last()).isInstanceOf(ChatEvent.Done::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `losing the connection twice reports offline`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertThat((run().last() as ChatEvent.Failed).error).isEqualTo(BrainError.Offline)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `stopping the answer cancels the request promptly`() = runBlocking {
        // After the first word, a long comment keeps the stream busy for ~25 s unless we hang up.
        val padding = ": " + "x".repeat(200_000) + "\n\n"
        val slow =
            MockResponse
                .Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(Sse.delta("first") + padding + Sse.delta("second") + Sse.completed())
                .throttleBody(8_192, 1, TimeUnit.SECONDS)
                .build()
        server.enqueue(slow)
        val started = System.nanoTime()
        val first = withTimeout(10_000) { brain.stream(request).take(1).first() }
        assertThat(first).isInstanceOf(ChatEvent.Delta::class.java)
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(6_000)
    }

    @Test
    fun `signed out means needs sign-in`() = runBlocking {
        assertThat(brain.availability()).isEqualTo(Availability.Ready)
        store.wipe()
        val fresh = ChatGptBrain(AuthSession(store, CodexOAuthClient(testAuthClient(), server.endpoints()), clock), testStreamClient())
        assertThat(fresh.availability()).isEqualTo(Availability.NeedsSignIn)
    }
}
