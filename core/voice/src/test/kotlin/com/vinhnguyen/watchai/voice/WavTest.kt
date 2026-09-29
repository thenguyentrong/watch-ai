package com.vinhnguyen.watchai.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    /** A 16-bit PCM WAV with [samples] interleaved over [channels]; [dataSize] as the engine wrote it. */
    private fun wav(
        samples: ShortArray,
        channels: Int,
        rate: Int,
        dataSize: Int = samples.size * 2,
    ): ByteArray {
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        b.putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataSize)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun `mono comes back as it is`() {
        val audio = Wav.read(wav(shortArrayOf(1, -2, 3, 4), channels = 1, rate = 22_050))!!
        assertThat(audio.samples.toList()).containsExactly(1.toShort(), (-2).toShort(), 3.toShort(), 4.toShort()).inOrder()
        assertThat(audio.sampleRate).isEqualTo(22_050)
    }

    @Test
    fun `stereo keeps the first channel`() {
        val audio = Wav.read(wav(shortArrayOf(10, 99, 20, 99), channels = 2, rate = 16_000))!!
        assertThat(audio.samples.toList()).containsExactly(10.toShort(), 20.toShort()).inOrder()
    }

    @Test
    fun `a streaming engine's missing data size means up to the end`() {
        val audio = Wav.read(wav(shortArrayOf(5, 6, 7), channels = 1, rate = 24_000, dataSize = 0))!!
        assertThat(audio.samples).hasLength(3)
    }

    @Test
    fun `anything else is refused`() {
        assertThat(Wav.read(ByteArray(10))).isNull()
        assertThat(Wav.read("RIFF....NOPE".toByteArray() + ByteArray(40))).isNull()
    }
}
