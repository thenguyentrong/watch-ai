package com.vinhnguyen.watchai.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(
        samples: ShortArray,
        rate: Int = 16_000,
        channels: Int = 1,
        extraChunk: Boolean = false,
    ): ByteArray {
        fun le(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le(4) { putInt(0) })
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le(20) { putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16) })
        if (extraChunk) {
            out.write("LIST".toByteArray())
            out.write(le(4) { putInt(3) })
            out.write(byteArrayOf(1, 2, 3, 0))
        }
        out.write("data".toByteArray())
        out.write(le(4) { putInt(samples.size * 2) })
        out.write(le(samples.size * 2) { samples.forEach { putShort(it) } })
        return out.toByteArray()
    }

    @Test
    fun `reads the samples of a 16 kHz mono file, skipping other chunks`() {
        val samples = shortArrayOf(0, 1, -1, 32_767, -32_768)
        assertThat(Wav.read16kMono(wav(samples))).isEqualTo(samples)
        assertThat(Wav.read16kMono(wav(samples, extraChunk = true))).isEqualTo(samples)
    }

    @Test
    fun `anything else is refused`() {
        val samples = shortArrayOf(1, 2, 3)
        assertThat(Wav.read16kMono(wav(samples, rate = 44_100))).isNull()
        assertThat(Wav.read16kMono(wav(samples, channels = 2))).isNull()
        assertThat(Wav.read16kMono("not a wav file at all".toByteArray())).isNull()
        assertThat(Wav.read16kMono(wav(samples).copyOf(30))).isNull()
    }
}
