package com.vinhnguyen.watchai.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WatchLinkTest {
    private fun roundTrip(vararg frames: Frame): List<Frame> {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        frames.forEach { FrameCodec.write(out, it) }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        return generateSequence { FrameCodec.read(input) }.toList()
    }

    @Test
    fun `audio, messages and flush survive the channel in order`() {
        val pcm = ByteArray(WatchLink.FRAME_BYTES) { it.toByte() }
        val frames = roundTrip(Frame.Audio(pcm), Frame.Message(Control("status", phase = "listening", level = 0.5f)), Frame.Flush)
        assertThat((frames[0] as Frame.Audio).pcm).isEqualTo(pcm)
        assertThat(frames[1]).isEqualTo(Frame.Message(Control("status", phase = "listening", level = 0.5f)))
        assertThat(frames[2]).isEqualTo(Frame.Flush)
        assertThat(frames).hasSize(3)
    }

    @Test
    fun `opus packets keep their bytes`() {
        val packet = byteArrayOf(0x48, 1, 2, 3, 4)
        assertThat((roundTrip(Frame.Opus(packet)).single() as Frame.Opus).packet).isEqualTo(packet)
    }

    @Test
    fun `a channel cut in the middle of a frame ends cleanly`() {
        val bytes = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(bytes), Frame.Audio(ByteArray(640)))
        val cut = bytes.toByteArray().copyOf(100)
        assertThat(FrameCodec.read(DataInputStream(ByteArrayInputStream(cut)))).isNull()
    }

    @Test
    fun `unknown frame kinds are skipped`() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(9)
        out.writeShort(2)
        out.write(byteArrayOf(1, 2))
        FrameCodec.write(out, Frame.Flush)
        assertThat(FrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))).isEqualTo(Frame.Flush)
    }

    @Test
    fun `pcm bytes are little-endian 16-bit`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234)
        val bytes = Pcm.toBytes(samples)
        assertThat(bytes.copyOfRange(10, 12).toList()).containsExactly(0x34.toByte(), 0x12.toByte()).inOrder()
        assertThat(Pcm.toShorts(bytes).toList()).isEqualTo(samples.toList())
    }

    @Test
    fun `48 kHz goes down to 16 kHz by averaging and back up by interpolating`() {
        val high = ShortArray(480) { 3000 }
        val low = Pcm.resample(high, 48_000, 16_000)
        assertThat(low.size).isEqualTo(160)
        assertThat(low.all { it == 3000.toShort() }).isTrue()
        val back = Pcm.resample(low, 16_000, 48_000)
        assertThat(back.size).isEqualTo(480)
        assertThat(back.all { it == 3000.toShort() }).isTrue()
    }

    @Test
    fun `other rates are interpolated`() {
        val ramp = ShortArray(441) { (it * 10).toShort() }
        val out = Pcm.resample(ramp, 44_100, 16_000)
        assertThat(out.size).isEqualTo(160)
        assertThat(out.first()).isEqualTo(0)
        assertThat(out.toList()).isInOrder()
    }

    @Test
    fun `loudness of silence and full scale`() {
        assertThat(Pcm.level(ShortArray(320))).isEqualTo(0f)
        assertThat(Pcm.level(ShortArray(320) { Short.MAX_VALUE })).isWithin(0.001f).of(1f)
    }

    @Test
    fun `the jitter buffer hands out silence when empty and drops the oldest when full`() {
        val q = PcmQueue(maxSamples = 4)
        assertThat(q.take(2).toList()).containsExactly(0.toShort(), 0.toShort())
        q.offer(shortArrayOf(1, 2, 3))
        assertThat(q.take(2).toList()).containsExactly(1.toShort(), 2.toShort()).inOrder()
        q.offer(shortArrayOf(4, 5, 6, 7, 8))
        assertThat(q.available).isEqualTo(4)
        assertThat(q.take(5).toList()).containsExactly(5.toShort(), 6.toShort(), 7.toShort(), 8.toShort(), 0.toShort()).inOrder()
    }
}
