package com.vinhnguyen.watchai.buddy

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What makes one user's Buddy theirs: body shape, colour and resting face, all from one seed.
 * The seed comes from a hash of the user's id, so every user gets their own Buddy and keeps it
 * for good; the id itself never leaves the phone.
 *
 * Changing what a seed produces changes every existing Buddy, so [Rng] and the order of the
 * draws in [of] are fixed: add new traits at the end only.
 */
public data class Genes(
    val seed: Long,
    val shape: BodyShape,
    val color: Tint,
    val rest: RestFace,
    /** The resting face: [rest] nudged a little by the seed. */
    val face: Expression = rest.face,
) {
    /** The resting outline, radii at [Silhouette.SAMPLES] angles. */
    public val body: FloatArray get() = shape.radii

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
            val shape = BodyShape.entries[rng.nextInt(BodyShape.entries.size)]
            val color = Tint.entries[rng.nextInt(Tint.entries.size)]
            val rest = RestFace.entries[rng.nextInt(RestFace.entries.size)]
            val base = rest.face
            fun around(span: Float) = (rng.nextFloat() * 2 - 1) * span
            val gaze = Gaze(base.gaze.yaw + around(5f), base.gaze.pitch + around(4f), base.gaze.roll + around(4f))
            val eyeScale = 1 + around(0.08f)
            val face = base.copy(
                gaze = gaze,
                split = base.split + around(1f),
                eyes = base.eyes.let { (a, b) -> a.copy(w = a.w * eyeScale, h = a.h * eyeScale) to b.copy(w = b.w * eyeScale, h = b.h * eyeScale) },
                mouth = base.mouth.copy(w = base.mouth.w * (1 + around(0.1f))),
            )
            return Genes(seed, shape, color, rest, face)
        }
    }
}

/**
 * Resting bodies, built from simple maths rather than traced from anything, each scaled so they
 * weigh about the same to the eye.
 */
public enum class BodyShape(
    internal val radii: FloatArray,
) {
    ROUND(FloatArray(Silhouette.SAMPLES) { 1f }),
    PEBBLE(Shapes.PEBBLE),
    SQUIRCLE(Silhouette.normalized(Silhouette.superellipse(3.6f), 1.1f)),
    EGG(Shapes.EGG),
    GUMDROP(Shapes.GUMDROP),
    BUN(Silhouette.normalized(Silhouette.superellipse(2.6f, 1.12f, 0.92f), 1.1f)),
    CLOUD(Shapes.CLOUD),
    TALL(Silhouette.normalized(Silhouette.superellipse(2.5f, 0.9f, 1.06f), 1.06f)),
}

internal object Shapes {
    private fun each(f: (Float) -> Float) = FloatArray(Silhouette.SAMPLES) { f(Silhouette.angle(it)) }

    /** A circle with two low, soft bumps: uneven but smooth. */
    val PEBBLE: FloatArray = Silhouette.normalized(each { a -> 1 + 0.07f * cos(2 * a + 0.9f) + 0.03f * cos(3 * a + 1.4f) }, 1.02f)

    /** Wider at the bottom (y points down). */
    val EGG: FloatArray = Silhouette.normalized(
        Silhouette.fromPolygon(List(96) { i -> (i * 2 * PI.toFloat() / 96).let { a -> Pt(0.84f * cos(a) * (1 + 0.13f * sin(a)), sin(a)) } }),
        1.04f,
    )

    /** A dome over a flat, soft bottom. */
    val GUMDROP: FloatArray = Silhouette.normalized(
        Silhouette.superellipse(3.2f).let { flat -> each { a -> if (sin(a) > 0) lerp(1f, Silhouette.radiusAt(flat, a), sin(a)) else 1f } },
        1.06f,
    )

    /** Bumps: two lobes on top, wide at the bottom. */
    val CLOUD: FloatArray = Silhouette.normalized(
        Silhouette.unionOfCircles(
            listOf(
                Triple(-0.42f, 0.18f, 0.56f),
                Triple(0.44f, 0.2f, 0.52f),
                Triple(0f, 0.28f, 0.62f),
                Triple(-0.22f, -0.28f, 0.5f),
                Triple(0.28f, -0.22f, 0.46f),
            ),
        ),
        1.04f,
    )
}

/**
 * Body colours, picked to read on a black watch screen with black eyes cut out of them, and the
 * hue their rings and sparks take: one colour per Buddy, never a rainbow.
 */
public enum class Tint(
    public val body: Long,
    public val hue: Float,
) {
    MILK(0xFFF3F1EC, 205f),
    CORAL(0xFFFF8A73, 9f),
    TANGERINE(0xFFFFA64D, 30f),
    SUN(0xFFFFD45C, 44f),
    LIME(0xFFC2E66B, 78f),
    MINT(0xFF6FE3B4, 155f),
    AQUA(0xFF5ED6E0, 185f),
    SKY(0xFF6CB8FF, 210f),
    PERI(0xFF8F9BFF, 233f),
    LILAC(0xFFC0A2FF, 262f),
    PINK(0xFFFF8CC6, 330f),
    SAND(0xFFE8C9A0, 35f),
}

/** The resting faces a Buddy is born with. Eyes are pills, the mouth a band below them. */
public enum class RestFace(
    public val face: Expression,
) {
    CALM(Expression(Gaze(12f, 16f, -8f), 16.5f, pair(0.2f, 0.4f), Mouth(0.22f, 0.32f))),
    BRIGHT(Expression(Gaze(8f, 12f, -4f), 17.5f, pair(0.22f, 0.44f), Mouth(0.26f, 0.42f, open = 0.03f))),
    SLY(Expression(Gaze(15f, 14f, -10f), 16f, Eye(0.2f, 0.4f) to Eye(0.21f, 0.3f), Mouth(0.2f, 0.26f, tilt = -10f))),
    SOFT(Expression(Gaze(10f, 18f, -6f), 15.5f, pair(0.18f, 0.34f), Mouth(0.16f, 0.36f, thick = 0.045f))),
    BUTTON(Expression(Gaze(6f, 14f, -5f), 17f, pair(0.25f, 0.3f), Mouth(0.18f, 0.3f))),
    DREAMY(Expression(Gaze(8f, 10f, -3f), 16.5f, pair(0.2f, 0.4f, open = 0.62f), Mouth(0.18f, 0.22f))),
    PERKY(Expression(Gaze(-6f, 18f, 7f), 16.5f, pair(0.21f, 0.42f, tilt = 5f), Mouth(0.24f, 0.45f))),
    CHEEKY(Expression(Gaze(14f, 12f, -9f), 17f, pair(0.21f, 0.38f, tilt = -6f), Mouth(0.26f, 0.38f, open = 0.06f))),
}

private fun pair(
    w: Float,
    h: Float,
    tilt: Float = 0f,
    open: Float = 1f,
) = Eye(w, h, open, tilt) to Eye(w, h, open, -tilt)

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
