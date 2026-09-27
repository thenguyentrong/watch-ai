package com.vinhnguyen.watchai.watchlink

/**
 * IMA ADPCM: 4 bits per sample, a quarter of raw PCM (64 kbit/s at 16 kHz), in a few lines of
 * plain code. The platform's Opus (MediaCodec) runs in a separate media process and took up to
 * 1.1 s per 20 ms frame on the Galaxy Watch5 (27.09); this costs next to nothing on any CPU.
 *
 * Each packet starts with the coder state (predictor, 2 bytes little-endian; step index, 1 byte),
 * so every packet decodes on its own and a lost or skipped packet doesn't break the next one.
 */
public object Adpcm {
    private val STEPS =
        intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
            5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767,
        )
    private val INDEX_CHANGE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** Carries the coder state from one packet to the next on the sending side (better than restarting). */
    public class Encoder {
        private var predictor = 0
        private var index = 0

        public fun encode(samples: ShortArray): ByteArray {
            val out = ByteArray(3 + (samples.size + 1) / 2)
            out[0] = predictor.toByte()
            out[1] = (predictor shr 8).toByte()
            out[2] = index.toByte()
            for (i in samples.indices) {
                val nibble = encodeSample(samples[i].toInt())
                val at = 3 + i / 2
                out[at] = if (i % 2 == 0) nibble.toByte() else (out[at].toInt() or (nibble shl 4)).toByte()
            }
            return out
        }

        private fun encodeSample(sample: Int): Int {
            val step = STEPS[index]
            var diff = sample - predictor
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            var delta = step shr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                delta += step
            }
            if (diff >= step shr 1) {
                nibble = nibble or 2
                diff -= step shr 1
                delta += step shr 1
            }
            if (diff >= step shr 2) {
                nibble = nibble or 1
                delta += step shr 2
            }
            predictor = (if (nibble and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
            index = (index + INDEX_CHANGE[nibble]).coerceIn(0, STEPS.lastIndex)
            return nibble
        }
    }

    /** Decodes one packet from [Encoder.encode] into [sampleCount] samples. */
    public fun decode(
        packet: ByteArray,
        sampleCount: Int = (packet.size - 3) * 2,
    ): ShortArray {
        if (packet.size < 3) return ShortArray(0)
        var predictor = ((packet[1].toInt() shl 8) or (packet[0].toInt() and 0xFF)).toShort().toInt()
        var index = (packet[2].toInt() and 0xFF).coerceIn(0, STEPS.lastIndex)
        val count = minOf(sampleCount, (packet.size - 3) * 2)
        return ShortArray(count) { i ->
            val byte = packet[3 + i / 2].toInt()
            val nibble = if (i % 2 == 0) byte and 0x0F else (byte shr 4) and 0x0F
            val step = STEPS[index]
            var delta = step shr 3
            if (nibble and 4 != 0) delta += step
            if (nibble and 2 != 0) delta += step shr 1
            if (nibble and 1 != 0) delta += step shr 2
            predictor = (if (nibble and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
            index = (index + INDEX_CHANGE[nibble]).coerceIn(0, STEPS.lastIndex)
            predictor.toShort()
        }
    }
}

/**
 * Decides which audio frames are worth sending: anything audible, plus [hangoverFrames] after it so
 * word endings aren't clipped. Silence isn't sent at all; the other side plays or feeds silence.
 */
public class SilenceGate(
    private val threshold: Float,
    private val hangoverFrames: Int,
) {
    private var quietFrames = Int.MAX_VALUE / 2

    public fun shouldSend(level: Float): Boolean {
        quietFrames = if (level >= threshold) 0 else quietFrames + 1
        return quietFrames <= hangoverFrames
    }
}
