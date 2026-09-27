package com.vinhnguyen.watchai.watchlink

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for diagnosing the link (frames in and out, the slowest write…), read and reset every
 * few seconds and logged. Counts and timings only, never audio or text.
 */
public class LinkStats {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    public fun add(
        key: String,
        n: Long = 1,
    ) {
        counters.getOrPut(key) { AtomicLong() }.addAndGet(n)
    }

    public fun max(
        key: String,
        value: Long,
    ) {
        counters.getOrPut(key) { AtomicLong() }.accumulateAndGet(value) { a, b -> maxOf(a, b) }
    }

    /** "inAdpcm=25 outAdpcm=24 writeMaxMs=3", sorted by key, and starts counting again. */
    public fun drain(): String = counters.keys.sorted().joinToString(" ") { key -> "$key=${counters[key]?.getAndSet(0) ?: 0}" }
}
