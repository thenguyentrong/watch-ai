package com.vinhnguyen.watchai.watchlink

import kotlin.math.sqrt

/** 16-bit PCM helpers: byte conversion, resampling between the link's 16 kHz and WebRTC's rates, loudness. */
public object Pcm {
    public fun toShorts(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): ShortArray {
        val out = ShortArray(length / 2)
        for (i in out.indices) {
            val lo = bytes[offset + 2 * i].toInt() and 0xFF
            val hi = bytes[offset + 2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    public fun toBytes(
        samples: ShortArray,
        count: Int = samples.size,
    ): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /**
     * Changes the sample rate. Going down by a whole factor averages each group of samples (a simple
     * low-pass, fine for speech); anything else interpolates linearly.
     */
    public fun resample(
        input: ShortArray,
        fromRate: Int,
        toRate: Int,
    ): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        if (fromRate > toRate && fromRate % toRate == 0) {
            val factor = fromRate / toRate
            return ShortArray(input.size / factor) { i ->
                var sum = 0
                for (j in 0 until factor) sum += input[i * factor + j]
                (sum / factor).toShort()
            }
        }
        val outSize = (input.size.toLong() * toRate / fromRate).toInt()
        return ShortArray(outSize) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val left = pos.toInt().coerceAtMost(input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val frac = pos - left
            (input[left] * (1 - frac) + input[right] * frac).toInt().toShort()
        }
    }

    /** Root-mean-square loudness, 0 (silence) to 1 (full scale). */
    public fun level(
        samples: ShortArray,
        count: Int = samples.size,
    ): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * A small jitter buffer for audio arriving in bursts over Bluetooth: [offer] adds samples as they
 * come, [take] hands out exactly what the audio clock asks for (silence when empty). Keeps at most
 * [maxSamples]; if the link bunches up, the oldest audio is dropped so latency can't grow.
 */
public class PcmQueue(
    private val maxSamples: Int,
) {
    private val buffer = ShortArray(maxSamples)
    private var start = 0
    private var size = 0

    /** Samples waiting. */
    public val available: Int
        @Synchronized get() = size

    @Synchronized
    public fun offer(samples: ShortArray) {
        for (s in samples) {
            if (size == maxSamples) {
                start = (start + 1) % maxSamples
                size--
            }
            buffer[(start + size) % maxSamples] = s
            size++
        }
        (this as Object).notifyAll()
    }

    /** Waits (without spinning) until [count] samples are there; false after [timeoutMs]. */
    @Synchronized
    public fun awaitAtLeast(
        count: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (size < count) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            (this as Object).wait(left)
        }
        return true
    }

    @Synchronized
    public fun take(count: Int): ShortArray {
        val out = ShortArray(count)
        val n = minOf(count, size)
        for (i in 0 until n) out[i] = buffer[(start + i) % maxSamples]
        start = (start + n) % maxSamples
        size -= n
        return out
    }

    @Synchronized
    public fun clear() {
        start = 0
        size = 0
    }

    /** Drops the oldest samples until at most [samples] are left, so latency is back to that. */
    @Synchronized
    public fun trimTo(samples: Int) {
        if (size <= samples) return
        val drop = size - samples
        start = (start + drop) % maxSamples
        size = samples
    }
}
