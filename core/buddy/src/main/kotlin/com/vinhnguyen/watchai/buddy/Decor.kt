package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything drawn around the body: orbit arcs, dots, particles and the badge. Ported from
 * bloub by Jérémy Perret (MIT, github.com/jeremy-prt/bloub, src/bot/decor.ts), with our own
 * sizes, speeds (slower, so a watch at 30 fps still draws them smoothly) and colours: the arcs
 * stay within the user's own hue instead of bloub's full rainbow.
 */

/** A circle in 3D seen edge-on: half axis [a], flattening [k], screen [tilt] (rad), turns per second, how much of the turn is drawn. */
internal class ArcSeed(
    val a: Float,
    val k: Float,
    val tilt: Float,
    val speed: Float,
    val phase: Float,
    val sweep: Float,
    /** Hue offset from the Buddy's accent, degrees, and how far the colour walks along the arc (centred on it). */
    val hue: Float,
    val hueSpan: Float,
    val width: Float,
    val cx: Float = 0f,
    val cy: Float = 0f,
) {
    fun copy(cx: Float = this.cx): ArcSeed = ArcSeed(a, k, tilt, speed, phase, sweep, hue, hueSpan, width, cx, cy)
}

/** What a state asks for: an arc, its own clock and how visible it is. */
internal class ArcSpec(
    val seed: ArcSeed,
    val t: Float,
    val opacity: Float,
)

/**
 * An arc ready to draw, in body units: the runs in front of the body and the runs behind it
 * (drawn first, so the body hides them), a colour gradient along the arc from ([x1], [y1]) to ([x2], [y2]).
 */
public class Arc(
    public val front: List<FloatArray>,
    public val back: List<FloatArray>,
    public val width: Float,
    public val opacity: Float,
    public val x1: Float,
    public val y1: Float,
    public val x2: Float,
    public val y2: Float,
    public val colors: IntArray,
)

/**
 * A dot in body units. [depth] < 1 fades it towards the background (particles far away);
 * [shape], when set, replaces the disc with that outline, turned by [rot] degrees (the tear
 * under the leaning "!").
 */
public class Dot(
    public val x: Float,
    public val y: Float,
    public val r: Float,
    public val opacity: Float,
    public val depth: Float = 1f,
    public val shape: FloatArray? = null,
    public val rot: Float = 0f,
)

/** The badge on the outline, and the gap cut around it. */
public class Badge(
    public val x: Float,
    public val y: Float,
    public val r: Float,
    public val gap: Float,
    public val heart: Boolean,
)

internal object Decor {
    private const val TAU = 2 * PI.toFloat()

    /**
     * Projects [seed] at [t]: the circle lives in the plane of u (on screen) and v (diving into
     * the depth); z splits it into front and back runs, which is what makes it read as an orbit.
     */
    fun arc(
        seed: ArcSeed,
        t: Float,
        opacity: Float,
        accentHue: Float,
    ): Arc {
        val spin = seed.phase + t * seed.speed * TAU
        val cu = cos(seed.tilt)
        val su = sin(seed.tilt)
        val kz = sqrt(maxOf(0f, 1 - seed.k * seed.k))
        val n = 32
        val span = seed.sweep * TAU
        val front = ArrayList<FloatArray>(2)
        val back = ArrayList<FloatArray>(2)
        var run = FloatArray(2 * (n + 1))
        var len = 0
        var behind: Boolean? = null
        fun flush() {
            if (len >= 4) (if (behind == true) back else front) += run.copyOf(len)
            len = 0
        }
        for (i in 0..n) {
            val th = spin + i.toFloat() / n * span
            val ct = cos(th)
            val st = sin(th)
            val x = seed.a * (ct * cu - st * su * seed.k) + seed.cx
            val y = seed.a * (ct * su + st * cu * seed.k) + seed.cy
            val isBehind = seed.a * st * kz < 0
            if (behind != null && isBehind != behind) {
                flush()
                run = FloatArray(2 * (n + 1))
            }
            behind = isBehind
            run[len++] = x
            run[len++] = y
        }
        flush()
        val gx = cu * seed.a
        val gy = su * seed.a
        val h = accentHue + seed.hue
        return Arc(
            front,
            back,
            seed.width,
            opacity,
            seed.cx - gx,
            seed.cy - gy,
            seed.cx + gx,
            seed.cy + gy,
            intArrayOf(hsl(h - seed.hueSpan / 2, RING_S, RING_L), hsl(h, RING_S, RING_L), hsl(h + seed.hueSpan / 2, RING_S, RING_L)),
        )
    }

    private const val RING_S = 0.62f
    private const val RING_L = 0.64f

    /** Six rings around the body: larger than it, seen nearly edge-on, one after another. */
    val RINGS: List<ArcSeed> = Mulberry(0x0b1_7a1L).let { r ->
        List(6) { i ->
            ArcSeed(
                a = 1.16f + r.next().toFloat() * 0.1f,
                k = 0.06f + r.next().toFloat() * 0.36f,
                tilt = i / 6f * PI.toFloat() + r.next().toFloat() * 0.5f,
                speed = 1.7f + r.next().toFloat() * 0.5f,
                phase = r.next().toFloat() * TAU,
                sweep = 0.55f + r.next().toFloat() * 0.25f,
                hue = (i - 2.5f) * 8f + r.next().toFloat() * 6f,
                hueSpan = 16f + r.next().toFloat() * 14f,
                width = 0.05f + r.next().toFloat() * 0.012f,
                cy = 0.08f,
            )
        }
    }

    /** Nested arcs that sweep across the body, seen edge-on, so they bend like a hairpin. */
    val SWOOSH: List<ArcSeed> = List(4) { i ->
        ArcSeed(a = 0.74f + i * 0.17f, k = 0.05f + i * 0.02f, tilt = -0.58f + i * 0.05f, speed = 0.3f, phase = 0.06f * i, sweep = 0.4f, hue = -18f + i * 12f, hueSpan = 30f, width = 0.05f, cy = -0.1f)
    }

    /** The comet: the dot stays put and four tight ribbons orbit it. */
    val COMET: List<ArcSeed> = Mulberry(0x0c0_3e7L).let { r ->
        List(4) { i ->
            val d = i - 1.5f
            ArcSeed(
                a = 0.8f * (1 + d * 0.03f),
                k = 0.18f * (1 + d * 0.16f),
                tilt = 30f * PI.toFloat() / 180f + d * 0.035f,
                speed = 0.55f,
                phase = -i * 0.045f + r.next().toFloat() * 0.012f,
                sweep = 0.34f,
                hue = d * 12f + r.next().toFloat() * 6f,
                hueSpan = 24f,
                width = 0.09f,
            )
        }
    }

    /** The thinking dots: where they sit, their size and how big the pulse gets. */
    val DOT_X: FloatArray = floatArrayOf(-0.56f, 0f, 0.56f)
    const val DOT_R: Float = 0.16f
    const val DOT_PEAK: Float = 1.25f

    /** Where the badge sits on the outline (degrees, 0 = right, negative = up), its size and pop. */
    const val BADGE_ANGLE: Float = -40f
    const val BADGE_R: Float = 0.14f
    const val BADGE_POP: Float = 1.15f
    const val BADGE_GAP: Float = 0.05f

    private class Particle(
        val birth: Float,
        val angle: Float,
        val rho: Float,
    )

    private val PARTICLES: List<Particle> = Mulberry(0x0b3e_ef1L).let { r ->
        List(5) { i -> Particle(i * 0.2f, r.next().toFloat() * TAU, 0.58f + r.next().toFloat() * 0.18f) }
    }

    /** Five sparks spiralling in towards the shrunken body and passing behind it: a new one every 0.2 s, each lives 0.62 s. */
    fun particles(t: Float): List<Dot> = PARTICLES.mapNotNull { p ->
        val u = t - p.birth
        if (u < 0f || u > 0.62f) return@mapNotNull null
        val rho = p.rho * 0.75f.pow(u * 10)
        val a = p.angle + u * 100f * PI.toFloat() / 180f
        Dot(
            x = cos(a) * rho,
            y = sin(a) * rho,
            r = 0.05f + 0.036f * clamp01(u / 0.55f),
            opacity = clamp01(u / 0.06f) * clamp01((0.62f - u) / 0.08f),
            depth = clamp01(1 - rho / 0.8f),
        )
    }

    /** HSL (degrees, 0..1, 0..1) to an opaque ARGB int. */
    fun hsl(
        hue: Float,
        s: Float,
        l: Float,
    ): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
