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
    fun `adpcm packets keep their bytes`() {
        val packet = byteArrayOf(0x48, 1, 2, 3, 4)
        assertThat((roundTrip(Frame.Adpcm(packet)).single() as Frame.Adpcm).packet).isEqualTo(packet)
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
    fun `waiting for audio returns as soon as it arrives, or false after the timeout`() {
        val q = PcmQueue(maxSamples = 1_000)
        assertThat(q.awaitAtLeast(10, timeoutMs = 20)).isFalse()
        Thread {
            Thread.sleep(30)
            q.offer(ShortArray(10))
        }.start()
        val started = System.currentTimeMillis()
        assertThat(q.awaitAtLeast(10, timeoutMs = 2_000)).isTrue()
        assertThat(System.currentTimeMillis() - started).isLessThan(1_000)
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

    @Test
    fun `takeUpTo drains partial tails without zero padding`() {
        val q = PcmQueue(maxSamples = 10)
        q.offer(shortArrayOf(10, 20, 30))
        assertThat(q.takeUpTo(5).toList()).containsExactly(10.toShort(), 20.toShort(), 30.toShort()).inOrder()
        assertThat(q.available).isEqualTo(0)
        assertThat(q.takeUpTo(5).toList()).isEmpty()
    }

    @Test
    fun `pre-buffering jitter buffer holds until primed, absorbs jitter, and re-primes after underrun`() {
        var now = 0L
        val jb = JitterBuffer(maxSamples = 1_000, prebufferSamples = 120, maxHoldMs = 150, clockMs = { now })

        // First 60 samples arrive: still below prebuffer and maxHoldMs, so silence is handed out.
        jb.offer(ShortArray(60) { 1000 })
        assertThat(jb.isStreaming).isFalse()
        assertThat(jb.take(40).all { it == 0.toShort() }).isTrue()
        assertThat(jb.available).isEqualTo(60)

        // Second 60 samples arrive: prebuffer reached -> starts streaming with fade-in.
        now = 40L
        jb.offer(ShortArray(60) { 1000 })
        val first = jb.take(60)
        assertThat(jb.isStreaming).isTrue()
        assertThat(first.first()).isEqualTo(0.toShort())
        assertThat(first.last()).isEqualTo(1000.toShort())

        // Drain the remaining 60 samples cleanly (no underrun yet).
        val second = jb.take(60)
        assertThat(second.all { it == 1000.toShort() }).isTrue()
        assertThat(jb.underruns).isEqualTo(0)

        // Next pull finds buffer empty -> records underrun and returns to pre-buffering.
        assertThat(jb.take(40).all { it == 0.toShort() }).isTrue()
        assertThat(jb.isStreaming).isFalse()
        assertThat(jb.underruns).isEqualTo(1)

        // Short utterance below prebuffer is released once maxHoldMs elapses.
        now = 100L
        jb.offer(ShortArray(50) { 2000 })
        assertThat(jb.take(50).all { it == 0.toShort() }).isTrue()
        now = 260L
        val released = jb.take(50)
        assertThat(released.last()).isEqualTo(2000.toShort())
    }

    @Test
    fun `fadeIn and fadeOut ramp smoothly without abrupt steps`() {
        val samples = ShortArray(100) { 1000 }
        Pcm.fadeIn(samples, 10)
        assertThat(samples[0]).isEqualTo(0.toShort())
        assertThat(samples[5]).isEqualTo(500.toShort())
        assertThat(samples[10]).isEqualTo(1000.toShort())

        Pcm.fadeOut(samples, endExclusive = 80, fadeSamples = 10)
        assertThat(samples[70]).isEqualTo(900.toShort())
        assertThat(samples[79]).isEqualTo(0.toShort())
        assertThat(samples[80]).isEqualTo(1000.toShort())
    }
}
