package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A body outline as its radius at [SAMPLES] evenly spaced angles, starting at 3 o'clock and
 * going counter-clockwise (maths angles, y up). Every outline shares the same angles, so two
 * shapes morph by blending their radii point for point, with no path-morphing library. The
 * idea is from bloub by Jérémy Perret (MIT, github.com/jeremy-prt/bloub); the shapes are ours.
 */
public class Profile(
    public val radii: FloatArray,
) {
    init {
        require(radii.size == SAMPLES) { "a profile has $SAMPLES radii" }
    }

    override fun equals(other: Any?): Boolean = other is Profile && other.radii.contentEquals(radii)

    override fun hashCode(): Int = radii.contentHashCode()

    override fun toString(): String = "Profile(${radii.joinToString(limit = 4)})"

    /** The radius at [angle] (radians, maths orientation), between the two nearest samples. */
    public fun radiusAt(angle: Float): Float {
        val turns = ((angle / (2 * PI.toFloat())) % 1f + 1f) % 1f
        val x = turns * SAMPLES
        val i = x.toInt() % SAMPLES
        val f = x - x.toInt()
        return radii[i] * (1 - f) + radii[(i + 1) % SAMPLES] * f
    }

    /** Squashed ([amount] > 0: wider and shorter) or stretched (< 0), keeping the area roughly the same. */
    public fun squashed(amount: Float): Profile {
        if (amount == 0f) return this
        val sx = 1f + amount
        val sy = 1f / sx
        return Profile(
            FloatArray(SAMPLES) { i ->
                val a = angle(i)
                val r = radii[i]
                val x = cos(a) * r * sx
                val y = sin(a) * r * sy
                kotlin.math.sqrt(x * x + y * y)
            },
        )
    }

    public companion object {
        public const val SAMPLES: Int = 64

        public fun angle(i: Int): Float = i * 2 * PI.toFloat() / SAMPLES

        public fun lerp(
            a: Profile,
            b: Profile,
            t: Float,
        ): Profile = Profile(FloatArray(SAMPLES) { a.radii[it] + (b.radii[it] - a.radii[it]) * t })

        /** The body for [genes]: its shape family, the seeded bumps on top, scaled so the widest point is 1. */
        public fun of(genes: Genes): Profile {
            val r =
                FloatArray(SAMPLES) { i ->
                    val a = angle(i)
                    val bumps = genes.bumps.withIndex().fold(1f) { acc, (k, bump) -> acc + bump.first * cos((k + 2) * a - bump.second) }
                    base(genes.shape, a) * bumps
                }
            val widest = r.max()
            return Profile(FloatArray(SAMPLES) { r[it] / widest })
        }

        /** The shape families. `up` is sin(a): 1 at the top of the head, -1 at the bottom. */
        internal fun base(
            shape: Genes.Shape,
            a: Float,
        ): Float {
            val up = sin(a)
            val side = cos(a)
            return when (shape) {
                Genes.Shape.ROUND -> 1f
                Genes.Shape.SQUIRCLE -> superellipse(side, up, 3.2f, 1f, 1f)
                Genes.Shape.PEBBLE -> superellipse(side, up, 2.4f, 1.1f, 0.92f)
                Genes.Shape.EGG -> superellipse(side, up, 2f, 0.92f, 1.04f) * (1f - 0.05f * up)
                Genes.Shape.BELL -> superellipse(side, up, 2.6f, 1f, 1f) * (1f - 0.1f * up)
                Genes.Shape.BEAN -> 1f + 0.08f * cos(2 * a) - 0.04f * up
                Genes.Shape.CLOUD -> 1f + 0.05f * max(0f, up) * cos(6 * a)
                Genes.Shape.GUMDROP -> superellipse(side, up, 2.2f, 1f, 1f) * (1f - 0.12f * up + 0.1f * max(0f, up).pow(6))
            }
        }

        /** Radius of |x/w|^n + |y/h|^n = 1 in the direction (side, up). */
        private fun superellipse(
            side: Float,
            up: Float,
            n: Float,
            w: Float,
            h: Float,
        ): Float = (abs(side / w).pow(n) + abs(up / h).pow(n)).pow(-1f / n)
    }
}
