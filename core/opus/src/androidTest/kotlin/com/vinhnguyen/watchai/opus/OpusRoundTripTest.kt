package com.vinhnguyen.watchai.opus

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.watchlink.Pcm
import com.vinhnguyen.watchai.watchlink.WatchLink
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** Runs on a phone or watch: the platform's Opus codec takes our 16 kHz frames and gives them back. */
@RunWith(AndroidJUnit4::class)
class OpusRoundTripTest {
    private fun tone(frames: Int): List<ShortArray> {
        var t = 0
        return List(frames) {
            ShortArray(WatchLink.FRAME_SAMPLES) { (sin(2 * PI * 440 * t++ / WatchLink.SAMPLE_RATE) * 8_000).toInt().toShort() }
        }
    }

    @Test
    fun opusIsAvailable() {
        assertThat(Opus.available()).isTrue()
    }

    @Test
    fun oneSecondOfSpeechBandAudioRoundTrips() {
        val encoder = OpusEncoder(WatchLink.SAMPLE_RATE)
        val decoder = OpusDecoder(WatchLink.SAMPLE_RATE)
        try {
            val packets = tone(50).flatMap { encoder.encode(it) }
            val bytes = packets.sumOf { it.size }
            // About 24 kbit/s instead of 256 kbit/s of raw PCM.
            assertThat(bytes).isLessThan(WatchLink.FRAME_BYTES * 50 / 4)
            assertThat(packets.size).isAtLeast(40)
            val decoded = packets.map { decoder.decode(it) }
            val all = decoded.fold(ShortArray(0)) { acc, s -> acc + s }
            // Most of the second comes back (the codec holds a little), and it isn't silence.
            assertThat(all.size).isAtLeast(WatchLink.SAMPLE_RATE * 8 / 10)
            assertThat(Pcm.level(all)).isGreaterThan(0.05f)
        } finally {
            encoder.close()
            decoder.close()
        }
    }
}
