package com.vinhnguyen.watchai.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The GPT-Live call's data-channel events (the Codex "quicksilver" variant), as OpenClaw's client
 * reads and writes them (openclaw/openclaw extensions/openai/realtime-quicksilver-*.ts, 27.09.2026).
 *
 * The voice model hands questions it can't answer itself to the client as a delegation; the client
 * works out the answer and appends it to that delegation, and the voice then says it. A delegation
 * that never gets an answer leaves the voice waiting.
 */
object QuicksilverWire {
    sealed interface Event {
        /** Part of what the user is saying (deltas), or the whole finished turn. */
        data class UserText(
            val text: String,
            val final: Boolean,
        ) : Event

        data class AssistantText(
            val text: String,
            val final: Boolean,
        ) : Event

        /** [prompt] is the voice model's own wording of the task; empty when it sent none. */
        data class Delegation(
            val id: String,
            val prompt: String,
        ) : Event

        data class Failure(
            val code: String?,
        ) : Event

        data class Closed(
            val reason: String?,
        ) : Event

        data object Other : Event
    }

    /** Longest answer handed back (same as OpenClaw), and the largest single append frame. */
    const val MAX_RESULT_CHARS = 1_800
    const val MAX_FRAME_BYTES = 500

    const val FAILED_RESULT = "The lookup failed. Tell the user you couldn't find out right now and offer to try again."
    const val NO_INPUT_RESULT = "Ask the user to repeat their request; no user transcript was received."

    fun parse(
        json: Json,
        payload: String,
    ): Pair<String, Event>? {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val type = obj.string("type") ?: return null
        val event =
            when (type) {
                "input_transcript.added" -> obj.obj("item")?.string("text")?.let { Event.UserText(it, final = false) }

                "output_transcript.added" -> obj.obj("item")?.string("text")?.let { Event.AssistantText(it, final = false) }

                "turn.done" -> {
                    val turn = obj.obj("turn")
                    val text = turn?.string("transcript")
                    when {
                        text == null -> null
                        turn.string("role") == "user" -> Event.UserText(text, final = true)
                        turn.string("role") == "assistant" -> Event.AssistantText(text, final = true)
                        else -> null
                    }
                }

                "delegation.created" -> {
                    val item = obj.obj("item")
                    val id = item?.string("id")
                    if (item == null || id.isNullOrEmpty() || item.string("type") != "delegation" || item.string("target") != "client") {
                        null
                    } else {
                        val prompt =
                            (item["content"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .filter { it.string("type") == "input_text" }
                                .joinToString("") { it.string("text").orEmpty() }
                        Event.Delegation(id, prompt)
                    }
                }

                "error" -> Event.Failure(obj.obj("error")?.string("code") ?: obj.string("code"))

                "session.closed" -> Event.Closed(obj.string("reason"))

                else -> null
            }
        return type to (event ?: Event.Other)
    }

    /** Frames that hand [text] back as the answer to delegation [id]; the voice says it in its own words. */
    fun resultFrames(
        id: String,
        text: String,
    ): List<String> = chunk(bound(text)).map { part ->
        buildJsonObject {
            put("type", "delegation.context.append")
            put("delegation_item_id", id)
            put("channel", "speakable")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "input_text")
                    put("text", part)
                }
            }
        }.toString()
    }

    fun bound(text: String): String = if (text.length <= MAX_RESULT_CHARS) text else text.take(MAX_RESULT_CHARS - 16).trimEnd() + " [truncated]"

    /** Splits at character boundaries so no frame's text is over [maxBytes] of UTF-8. */
    fun chunk(
        text: String,
        maxBytes: Int = MAX_FRAME_BYTES,
    ): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val char = String(Character.toChars(cp))
            val size = char.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && bytes + size > maxBytes) {
                chunks += current.toString()
                current.clear()
                bytes = 0
            }
            current.append(char)
            bytes += size
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
