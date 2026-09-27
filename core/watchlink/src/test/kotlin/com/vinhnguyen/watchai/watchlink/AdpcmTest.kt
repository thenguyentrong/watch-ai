package com.vinhnguyen.watchai.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AdpcmTest {
    private fun tone(
        count: Int,
        start: Int = 0,
    ) = ShortArray(count) { (sin(2 * PI * 440 * (start + it) / 16_000) * 8_000).toInt().toShort() }

    @Test
    fun `a quarter of the size of raw pcm`() {
        val packet = Adpcm.Encoder().encode(tone(WatchLink.FRAME_SAMPLES))
        assertThat(packet.size).isEqualTo(3 + WatchLink.FRAME_SAMPLES / 2)
    }

    @Test
    fun `speech-band audio comes back close to the original`() {
        val encoder = Adpcm.Encoder()
        var error = 0L
        var count = 0
        repeat(25) { frame ->
            val original = tone(WatchLink.FRAME_SAMPLES, start = frame * WatchLink.FRAME_SAMPLES)
            val decoded = Adpcm.decode(encoder.encode(original))
            assertThat(decoded.size).isEqualTo(original.size)
            // Skip the first frame while the coder adapts.
            if (frame > 0) {
                for (i in original.indices) error += abs(original[i] - decoded[i])
                count += original.size
            }
        }
        // Mean error well under 3% of the tone's 8000 amplitude.
        assertThat(error / count).isLessThan(240)
    }

    @Test
    fun `every packet decodes on its own, so a lost one doesn't break the next`() {
        val encoder = Adpcm.Encoder()
        encoder.encode(tone(WatchLink.FRAME_SAMPLES))
        val lostSecond = encoder.encode(tone(WatchLink.FRAME_SAMPLES, start = WatchLink.FRAME_SAMPLES))
        val third = tone(WatchLink.FRAME_SAMPLES, start = 2 * WatchLink.FRAME_SAMPLES)
        val decoded = Adpcm.decode(encoder.encode(third))
        val meanError = third.indices.sumOf { abs(third[it] - decoded[it]).toLong() } / third.size
        assertThat(lostSecond).isNotEmpty()
        assertThat(meanError).isLessThan(240)
    }

    @Test
    fun `silence stays silent and a short packet is rejected`() {
        val decoded = Adpcm.decode(Adpcm.Encoder().encode(ShortArray(WatchLink.FRAME_SAMPLES)))
        assertThat(decoded.maxOf { abs(it.toInt()) }).isLessThan(10)
        assertThat(Adpcm.decode(byteArrayOf(1, 2))).isEmpty()
    }

    @Test
    fun `the silence gate sends speech and a short tail, then stops`() {
        val gate = SilenceGate(threshold = 0.01f, hangoverFrames = 2)
        assertThat(gate.shouldSend(0f)).isFalse()
        assertThat(gate.shouldSend(0.2f)).isTrue()
        assertThat(gate.shouldSend(0f)).isTrue()
        assertThat(gate.shouldSend(0f)).isTrue()
        assertThat(gate.shouldSend(0f)).isFalse()
        assertThat(gate.shouldSend(0.05f)).isTrue()
    }
}
