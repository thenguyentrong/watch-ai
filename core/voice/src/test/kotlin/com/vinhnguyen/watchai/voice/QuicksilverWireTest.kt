package com.vinhnguyen.watchai.voice

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.voice.QuicksilverWire.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class QuicksilverWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(payload: String) = QuicksilverWire.parse(json, payload)

    @Test
    fun `a client delegation is read with the voice model's own wording`() {
        val (type, event) =
            parse(
                """{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"dlg_SYNTHETIC",
                   "content":[{"type":"input_text","text":"What does "},{"type":"input_text","text":"JEV stand for?"}]}}""",
            )!!
        assertThat(type).isEqualTo("delegation.created")
        assertThat(event).isEqualTo(Event.Delegation("dlg_SYNTHETIC", "What does JEV stand for?"))
    }

    @Test
    fun `delegations for someone else, or without an id, are ignored`() {
        assertThat(parse("""{"type":"delegation.created","item":{"type":"delegation","target":"server","id":"d1"}}""")!!.second).isEqualTo(Event.Other)
        assertThat(parse("""{"type":"delegation.created","item":{"type":"delegation","target":"client"}}""")!!.second).isEqualTo(Event.Other)
    }

    @Test
    fun `a delegation without content has an empty prompt`() {
        val event = parse("""{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"d1"}}""")!!.second
        assertThat(event).isEqualTo(Event.Delegation("d1", ""))
    }

    @Test
    fun `transcripts come as pieces and as whole turns`() {
        assertThat(parse("""{"type":"input_transcript.added","item":{"text":"what time"}}""")!!.second).isEqualTo(Event.UserText("what time", final = false))
        assertThat(parse("""{"type":"output_transcript.added","item":{"text":"It's"}}""")!!.second).isEqualTo(Event.AssistantText("It's", final = false))
        assertThat(parse("""{"type":"turn.done","turn":{"role":"user","transcript":"what time is it"}}""")!!.second)
            .isEqualTo(Event.UserText("what time is it", final = true))
        assertThat(parse("""{"type":"turn.done","turn":{"role":"assistant","transcript":"It's four."}}""")!!.second)
            .isEqualTo(Event.AssistantText("It's four.", final = true))
    }

    @Test
    fun `errors, closing and unknown events`() {
        assertThat(parse("""{"type":"error","error":{"code":"rate_limited"}}""")!!.second).isEqualTo(Event.Failure("rate_limited"))
        assertThat(parse("""{"type":"session.closed","reason":"expired"}""")!!.second).isEqualTo(Event.Closed("expired"))
        assertThat(parse("""{"type":"session.usage.updated"}""")).isEqualTo("session.usage.updated" to Event.Other)
        assertThat(parse("not json")).isNull()
        assertThat(parse("""{"no_type":1}""")).isNull()
    }

    @Test
    fun `the answer goes back as a speakable append to that delegation`() {
        val frame = json.parseToJsonElement(QuicksilverWire.resultFrames("dlg_SYNTHETIC", "JEV is Japanese encephalitis virus.").single()).jsonObject
        assertThat(frame["type"]!!.jsonPrimitive.content).isEqualTo("delegation.context.append")
        assertThat(frame["delegation_item_id"]!!.jsonPrimitive.content).isEqualTo("dlg_SYNTHETIC")
        assertThat(frame["channel"]!!.jsonPrimitive.content).isEqualTo("speakable")
        val part = frame["content"]!!.jsonArray.single().jsonObject
        assertThat(part["type"]!!.jsonPrimitive.content).isEqualTo("input_text")
        assertThat(part["text"]!!.jsonPrimitive.content).isEqualTo("JEV is Japanese encephalitis virus.")
    }

    @Test
    fun `long answers are split into frames of at most 500 bytes without breaking characters`() {
        val text = "Xin chào 👋 ".repeat(60) // multi-byte letters and a surrogate pair
        val chunks = QuicksilverWire.chunk(text)
        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it.toByteArray(Charsets.UTF_8).size).isAtMost(500) }
        assertThat(chunks.joinToString("")).isEqualTo(text)
    }

    @Test
    fun `answers are capped like OpenClaw does`() {
        val long = "a".repeat(5_000)
        val bounded = QuicksilverWire.bound(long)
        assertThat(bounded.length).isAtMost(QuicksilverWire.MAX_RESULT_CHARS)
        assertThat(bounded).endsWith("[truncated]")
        assertThat(QuicksilverWire.bound("short")).isEqualTo("short")
    }
}
