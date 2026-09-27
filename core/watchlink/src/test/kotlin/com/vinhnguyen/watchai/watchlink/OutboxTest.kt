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
