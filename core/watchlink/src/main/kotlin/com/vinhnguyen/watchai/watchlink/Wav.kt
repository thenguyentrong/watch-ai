package com.vinhnguyen.watchai.watchlink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plain PCM WAV files, for the debug hooks that play synthetic speech into the apps. */
public object Wav {
    /** The samples of a 16 kHz mono 16-bit PCM WAV, or null if it's anything else. */
    public fun read16kMono(bytes: ByteArray): ShortArray? {
        if (bytes.size < HEADER || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = HEADER
        var formatOk = false
        while (at + CHUNK_HEADER <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            val body = at + CHUNK_HEADER
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    if (body + FMT_SIZE > bytes.size) return null
                    formatOk = b.getShort(body).toInt() == PCM && b.getShort(body + 2).toInt() == 1 &&
                        b.getInt(body + 4) == WatchLink.SAMPLE_RATE && b.getShort(body + 14).toInt() == BITS
                }

                "data" -> {
                    if (!formatOk) return null
                    val n = minOf(size, bytes.size - body) / 2
                    return ShortArray(n) { b.getShort(body + it * 2) }
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }

    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val FMT_SIZE = 16
    private const val PCM = 1
    private const val BITS = 16
}
