package com.vinhnguyen.watchai.brain.chatgpt.api

import com.vinhnguyen.watchai.brain.ChatRequest
import com.vinhnguyen.watchai.brain.ChatTurn
import com.vinhnguyen.watchai.brain.PromptStyle
import com.vinhnguyen.watchai.brain.chatgpt.auth.Bearer
import com.vinhnguyen.watchai.brain.chatgpt.auth.OpenAiAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One chat turn against the Codex Responses endpoint. Contract (measured 08.2026, matches Codex):
 * `store` must be false and `stream` true, `instructions` must be non-empty, and `temperature`,
 * `max_output_tokens` and `previous_response_id` are rejected - so history is replayed each turn.
 */
internal object ResponsesRequest {
    private val jsonType = "application/json".toMediaType()

    /**
     * [extraInput]: earlier output items (reasoning, tool calls) and tool results, replayed after the
     * question. [liveWeb]: search may open pages live. Off once the conversation holds the user's
     * private data: then search only uses OpenAI's cached index, so no page is fetched with that
     * data in its address.
     */
    fun body(
        request: ChatRequest,
        model: String,
        effort: String,
        extraInput: List<JsonObject> = emptyList(),
        liveWeb: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", PromptStyle.system(request))
        putJsonArray("input") {
            (PromptStyle.trim(request.history) + ChatTurn(ChatTurn.Role.USER, request.userText)).forEach { turn ->
                addJsonObject {
                    put("type", "message")
                    put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", if (turn.role == ChatTurn.Role.USER) "input_text" else "output_text")
                            put("text", turn.text)
                        }
                    }
                }
            }
            extraInput.forEach { add(it) }
        }
        put("store", false)
        put("stream", true)
        putJsonObject("reasoning") { put("effort", effort) }
        putJsonObject("text") { put("verbosity", "low") }
        putJsonArray("include") { add("reasoning.encrypted_content") }
        put("prompt_cache_key", request.conversationId)
        val functions = request.tools?.tools().orEmpty()
        if (request.webSearch || functions.isNotEmpty()) {
            putJsonArray("tools") {
                if (request.webSearch) {
                    // Same tool spec as Codex's web search: live, or cached only.
                    addJsonObject {
                        put("type", "web_search")
                        put("external_web_access", liveWeb)
                    }
                }
                functions.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        put("name", spec.name)
                        put("description", spec.description)
                        put("strict", false)
                        put("parameters", Json.parseToJsonElement(spec.parametersJson))
                    }
                }
            }
        }
        put("tool_choice", "auto")
        put("parallel_tool_calls", false)
    }

    fun http(
        url: String,
        body: JsonObject,
        bearer: Bearer,
        sessionId: String,
        userAgent: String,
    ): Request = Request
        .Builder()
        .url(url)
        .post(body.toString().toRequestBody(jsonType))
        .header("Authorization", "Bearer ${bearer.accessToken}")
        .header("ChatGPT-Account-ID", bearer.accountId)
        .header("Accept", "text/event-stream")
        .header("originator", OpenAiAuth.ORIGINATOR)
        .header("User-Agent", userAgent)
        .header("session-id", sessionId)
        .apply { if (bearer.fedramp) header("X-OpenAI-Fedramp", "true") }
        .build()
}
