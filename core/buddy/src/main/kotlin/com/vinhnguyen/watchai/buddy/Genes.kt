package com.vinhnguyen.watchai.buddy

import java.security.MessageDigest
import kotlin.math.PI

/**
 * What makes one user's Buddy theirs: body shape, colour, eyes, a little something on top, and
 * markings, all from one seed. The seed comes from a hash of the user's id, so every user gets
 * their own Buddy and keeps it for good; the id itself never leaves the phone.
 *
 * Changing what a seed produces changes every existing Buddy, so [Rng] and the order of the
 * draws in [of] are fixed: add new traits at the end only.
 */
public data class Genes(
    val seed: Long,
    val shape: Shape,
    /** Small seeded bumps on the outline: amplitude and phase for harmonics 2, 3 and 4 (4 kept flat: it looked lumpy). */
    val bumps: List<Pair<Float, Float>>,
    val color: Palette,
    val eyes: EyeStyle,
    /** Eye distance from the middle, as a fraction of the body radius. */
    val eyeGap: Float,
    /** Eye height: 0 = the middle, negative = higher. */
    val eyeHeight: Float,
    val eyeSize: Float,
    val top: Top,
    val mark: Mark,
    val cheeks: Boolean,
) {
    public enum class Shape { ROUND, SQUIRCLE, PEBBLE, EGG, BELL, BEAN, CLOUD, GUMDROP }

    public enum class EyeStyle { CAPSULE, ROUND, DOT, BEAN }

    /** Something on top of the head (under any hat). */
    public enum class Top { NONE, LOOP, SPROUT, ANTENNA, EARS, TUFT, NUBS }

    public enum class Mark { NONE, BELLY, SPOTS }

    /** Body colours, picked to look good on a black watch screen and to keep dark eyes readable. */
    public enum class Palette(
        public val body: Long,
        public val shade: Long,
    ) {
        PEARL(0xFFF1EEFB, 0xFFCFC7EE),
        PINK(0xFFFFA8C0, 0xFFE67A98),
        PEACH(0xFFFFC09A, 0xFFE8946A),
        BUTTER(0xFFFFE08A, 0xFFE6B94E),
        LIME(0xFFC8E68A, 0xFF97C25A),
        MINT(0xFF9BE8C8, 0xFF5FC39C),
        SKY(0xFF9FD4FF, 0xFF64A9E6),
        LILAC(0xFFC9B6FF, 0xFF9A82E6),
        BERRY(0xFFF08BC0, 0xFFC95C96),
        COCOA(0xFFD9B8A0, 0xFFB08A70),
        SLATE(0xFFB9C7D6, 0xFF8799AD),
        CORAL(0xFFFF9C8A, 0xFFE06A57),
    }

    public companion object {
        /** A Buddy for [userId] (e.g. the ChatGPT account id): the same id always gives the same Buddy. */
        public fun forUser(userId: String): Genes = of(seedFor(userId))

        /** SHA-256 of a fixed prefix and the id, first 8 bytes: stable, and the id can't be read back. */
        public fun seedFor(userId: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest("watch-ai buddy v1|$userId".toByteArray(Charsets.UTF_8))
            return (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xFF) }
        }

        public fun of(seed: Long): Genes {
            val rng = Rng(seed)
            val shape = Shape.entries[rng.nextInt(Shape.entries.size)]
            val bumps = (2..4).map { k -> rng.nextFloat() * (if (k == 4) 0f else 0.012f) to rng.nextFloat() * 2 * PI.toFloat() }
            return Genes(
                seed = seed,
                shape = shape,
                bumps = bumps,
                color = Palette.entries[rng.nextInt(Palette.entries.size)],
                eyes = EyeStyle.entries[rng.nextInt(EyeStyle.entries.size)],
                eyeGap = 0.3f + rng.nextFloat() * 0.1f,
                eyeHeight = -0.16f + rng.nextFloat() * 0.14f,
                eyeSize = 0.9f + rng.nextFloat() * 0.25f,
                top = Top.entries[rng.nextInt(Top.entries.size)],
                mark = Mark.entries[rng.nextInt(Mark.entries.size)],
                cheeks = rng.nextFloat() < 0.7f,
            )
        }
    }
}

/**
 * SplitMix64: tiny, fast, and fixed by its definition (Steele, Lea and Flood, OOPSLA 2014), so
 * a seed gives the same Buddy on every device and every Kotlin version. kotlin.random makes no
 * such promise across versions.
 */
internal class Rng(
    private var state: Long,
) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 0 until [bound], without modulo bias worth caring about for tiny bounds. */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()

    /** 0..1 with 24 bits of precision. */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()
}
