package com.vinhnguyen.watchai.watchlink

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A short rising two-note chime (G5 then D6, about a quarter second): Buddy is listening now.
 * Without it nothing said so, apart from the face, which nobody looks at with earbuds in (28.09).
 */
public object Chime {
    private val NOTES = listOf(784.0 to 0.09, 1175.0 to 0.15)
    private const val LOUDNESS = 0.3

    public fun pcm(sampleRate: Int): ShortArray {
        val out = ArrayList<Short>()
        for ((hz, seconds) in NOTES) {
            val n = (sampleRate * seconds).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                // 8 ms fade in, then a soft decay: no click at either end.
                val envelope = min(1.0, t / 0.008) * exp(-t / (seconds / 3))
                out += (sin(2 * PI * hz * t) * envelope * LOUDNESS * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
