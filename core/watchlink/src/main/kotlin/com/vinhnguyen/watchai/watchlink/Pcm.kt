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

    /** Linearly ramps the first [fadeSamples] samples from 0 up to full level to avoid clicks. */
    public fun fadeIn(
        samples: ShortArray,
        fadeSamples: Int,
    ) {
        val n = minOf(fadeSamples, samples.size)
        if (n <= 1) return
        for (i in 0 until n) {
            samples[i] = (samples[i] * i / n).toShort()
        }
    }

    /** Linearly ramps the [fadeSamples] samples ending at [endExclusive] down to 0 to avoid clicks. */
    public fun fadeOut(
        samples: ShortArray,
        endExclusive: Int,
        fadeSamples: Int,
    ) {
        val end = minOf(endExclusive, samples.size)
        val n = minOf(fadeSamples, end)
        if (n <= 1) return
        val start = end - n
        for (i in 0 until n) {
            samples[start + i] = (samples[start + i] * (n - 1 - i) / n).toShort()
        }
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

    /** Waits (without spinning) until at least one sample is there; false after [timeoutMs]. */
    @Synchronized
    public fun awaitAny(timeoutMs: Long): Boolean = awaitAtLeast(1, timeoutMs)

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

    /** Takes up to [maxCount] available samples without padding with silence. */
    @Synchronized
    public fun takeUpTo(maxCount: Int): ShortArray {
        val n = minOf(maxCount, size)
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = buffer[(start + i) % maxSamples]
        start = (start + n) % maxSamples
        size -= n
        return out
    }

    @Synchronized
    public fun clear() {
        start = 0
        size = 0
        (this as Object).notifyAll()
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

/**
 * A pre-buffering jitter buffer for pull-driven audio clocks (such as WebRTC's 10 ms capture
 * callback): waits until [prebufferSamples] have accumulated — or [maxHoldMs] has elapsed for
 * short words — before handing out samples, preventing silence holes during Bluetooth burst jitter.
 * If the buffer runs dry, it fades out smoothly and re-primes before the next speech burst.
 */
public class JitterBuffer(
    private val maxSamples: Int,
    private val prebufferSamples: Int,
    private val maxHoldMs: Long,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val buffer = ShortArray(maxSamples)
    private var start = 0
    private var size = 0
    private var streaming = false
    private var firstBufferedAtMs = -1L

    /** Samples currently buffered. */
    public val available: Int
        @Synchronized get() = size

    /** True while streaming samples out (false while idle or pre-buffering). */
    public val isStreaming: Boolean
        @Synchronized get() = streaming

    /** Number of times the buffer ran dry while streaming (for link diagnostics). */
    public var underruns: Long = 0
        @Synchronized get
        private set

    @Synchronized
    public fun offer(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (size == 0 && !streaming) firstBufferedAtMs = clockMs()
        for (s in samples) {
            if (size == maxSamples) {
                start = (start + 1) % maxSamples
                size--
            }
            buffer[(start + size) % maxSamples] = s
            size++
        }
    }

    @Synchronized
    public fun take(count: Int): ShortArray {
        val out = ShortArray(count)
        if (count == 0) return out
        if (!streaming) {
            if (size == 0) return out
            val waited = if (firstBufferedAtMs >= 0L) clockMs() - firstBufferedAtMs else 0L
            if (size < prebufferSamples && waited < maxHoldMs) return out
            streaming = true
            firstBufferedAtMs = -1L
            val n = drainInto(out, count)
            Pcm.fadeIn(out, minOf(n, FADE_SAMPLES))
            if (n < count) {
                Pcm.fadeOut(out, n, minOf(n, FADE_SAMPLES))
                streaming = false
            }
            return out
        }
        if (size == 0) {
            streaming = false
            underruns++
            return out
        }
        val n = drainInto(out, count)
        if (n < count) {
            Pcm.fadeOut(out, n, minOf(n, FADE_SAMPLES))
            streaming = false
            underruns++
        }
        return out
    }

    @Synchronized
    public fun clear() {
        start = 0
        size = 0
        streaming = false
        firstBufferedAtMs = -1L
    }

    /** Drops the oldest samples until at most [samples] are left, so latency stays bounded. */
    @Synchronized
    public fun trimTo(samples: Int) {
        if (size <= samples) return
        val drop = size - samples
        start = (start + drop) % maxSamples
        size = samples
        if (size == 0) {
            streaming = false
            firstBufferedAtMs = -1L
        }
    }

    private fun drainInto(
        out: ShortArray,
        count: Int,
    ): Int {
        val n = minOf(count, size)
        for (i in 0 until n) out[i] = buffer[(start + i) % maxSamples]
        start = (start + n) % maxSamples
        size -= n
        return n
    }

    private companion object {
        const val FADE_SAMPLES = 48 // 3 ms at 16 kHz
    }
}
