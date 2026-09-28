package com.vinhnguyen.watchai.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class OutboxTest {
    private fun audio(n: Int) = Frame.Adpcm(byteArrayOf(n.toByte()))

    @Test
    fun `the writer gets everything waiting in one go`() {
        val box = Outbox(maxAudio = 10)
        box.offer(audio(1))
        box.offer(Frame.Message(Control("status")))
        box.offer(audio(2))
        assertThat(box.takeAll(10)).hasSize(3)
        assertThat(box.takeAll(10)).isEmpty()
    }

    @Test
    fun `when the link falls behind the oldest audio goes, control frames stay`() {
        val box = Outbox(maxAudio = 2)
        box.offer(audio(1))
        box.offer(Frame.Message(Control("pong", at = 5)))
        box.offer(audio(2))
        box.offer(audio(3))
        val taken = box.takeAll(10)
        assertThat(taken.filterIsInstance<Frame.Adpcm>().map { it.packet[0].toInt() }).containsExactly(2, 3).inOrder()
        assertThat(taken.filterIsInstance<Frame.Message>()).hasSize(1)
        assertThat(box.dropped).isEqualTo(1)
    }

    @Test
    fun `an interruption clears queued audio only`() {
        val box = Outbox(maxAudio = 10)
        box.offer(audio(1))
        box.offer(Frame.Message(Control("status")))
        box.clearAudio()
        box.offer(Frame.Flush)
        assertThat(box.takeAll(10)).containsExactly(Frame.Message(Control("status")), Frame.Flush).inOrder()
    }

    @Test
    fun `what was said before the phone picked up goes first and is never dropped`() {
        val box = Outbox(maxAudio = 2)
        box.offer(audio(9))
        box.offerBacklog((1..5).map { audio(it) })
        box.offer(audio(10))
        box.offer(audio(11))
        val sent = box.takeAll(10).filterIsInstance<Frame.Adpcm>().map { it.packet[0].toInt() }
        // The live audio still keeps to its limit; all five early frames make it, in front.
        assertThat(sent).containsExactly(1, 2, 3, 4, 5, 10, 11).inOrder()
    }

    @Test
    fun `early speech starts just before the voice and keeps the last few seconds`() {
        val early = EarlySpeech(maxFrames = 4, voiceLevel = 0.1f)
        fun frame(n: Int) = ShortArray(1) { n.toShort() }
        early.add(frame(1), 0f)
        early.add(frame(2), 0f)
        early.add(frame(3), 0.2f)
        early.add(frame(4), 0.3f)
        early.add(frame(5), 0f)
        // Frame 1 fell out (only four fit); the drain starts one frame before the first voiced one.
        assertThat(early.drain().map { it[0].toInt() }).containsExactly(2, 3, 4, 5).inOrder()
        assertThat(early.drain()).isEmpty()
        early.add(frame(6), 0f)
        assertThat(early.drain()).isEmpty()
    }

    @Test
    fun `the listening chime is short, soft and starts and ends quietly`() {
        val chime = Chime.pcm(16_000)
        assertThat(chime.size).isIn(com.google.common.collect.Range.closed(3_500, 4_500))
        assertThat(chime.maxOf { kotlin.math.abs(it.toInt()) }).isLessThan((Short.MAX_VALUE * 0.31).toInt())
        assertThat(kotlin.math.abs(chime.first().toInt())).isLessThan(200)
        assertThat(kotlin.math.abs(chime.last().toInt())).isLessThan(2_000)
    }

    @Test
    fun `a queue trimmed back keeps the newest samples`() {
        val q = PcmQueue(10)
        q.offer(ShortArray(8) { it.toShort() })
        q.trimTo(3)
        assertThat(q.available).isEqualTo(3)
        assertThat(q.take(3).toList()).containsExactly(5.toShort(), 6.toShort(), 7.toShort()).inOrder()
    }

    @Test
    fun `frames written together read back one by one`() {
        val bytes = ByteArrayOutputStream()
        FrameCodec.writeAll(DataOutputStream(bytes), listOf(audio(7), Frame.Flush, Frame.Message(Control("ping", at = 1))))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertThat((FrameCodec.read(input) as Frame.Adpcm).packet[0].toInt()).isEqualTo(7)
        assertThat(FrameCodec.read(input)).isEqualTo(Frame.Flush)
        assertThat(FrameCodec.read(input)).isEqualTo(Frame.Message(Control("ping", at = 1)))
        assertThat(FrameCodec.read(input)).isNull()
    }
}
