package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in body units: 1 is the radius of the ball at rest, y points down. */
public data class Pt(
    val x: Float,
    val y: Float,
)

/**
 * A body outline: its radius at [SAMPLES] fixed angles (θ = 0 points right and grows clockwise
 * on screen, y down), plus a pose. Every outline shares the same angles, so any two morph by
 * blending their radii point for point, without a path-morphing library.
 *
 * Ported from bloub by Jérémy Perret (MIT, github.com/jeremy-prt/bloub, src/bot/shape.ts);
 * see THIRD_PARTY_NOTICES.md.
 */
public class Silhouette(
    public val radii: FloatArray,
    /** Rotation of the profile, radians. */
    public val rot: Float = 0f,
    public val cx: Float = 0f,
    public val cy: Float = 0f,
    /** Squash and stretch, on screen after the rotation. */
    public val sx: Float = 1f,
    public val sy: Float = 1f,
) {
    public fun copy(
        radii: FloatArray = this.radii,
        rot: Float = this.rot,
        cx: Float = this.cx,
        cy: Float = this.cy,
        sx: Float = this.sx,
        sy: Float = this.sy,
    ): Silhouette = Silhouette(radii, rot, cx, cy, sx, sy)

    /** The outline as points (x, y interleaved), pose applied. */
    public fun points(): FloatArray {
        val out = FloatArray(SAMPLES * 2)
        val cr = cos(rot)
        val sr = sin(rot)
        for (i in 0 until SAMPLES) {
            val x = radii[i] * COS[i]
            val y = radii[i] * SIN[i]
            out[2 * i] = (x * cr - y * sr) * sx + cx
            out[2 * i + 1] = (x * sr + y * cr) * sy + cy
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is Silhouette && other.radii.contentEquals(radii) && other.rot == rot &&
        other.cx == cx && other.cy == cy && other.sx == sx && other.sy == sy

    override fun hashCode(): Int = radii.contentHashCode() * 31 + rot.hashCode()

    public companion object {
        public const val SAMPLES: Int = 64
        private const val TAU = 2 * PI.toFloat()
        private val COS = FloatArray(SAMPLES) { cos(angle(it)) }
        private val SIN = FloatArray(SAMPLES) { sin(angle(it)) }

        public fun angle(i: Int): Float = i * TAU / SAMPLES

        public fun circle(
            radius: Float,
            cx: Float = 0f,
            cy: Float = 0f,
            rot: Float = 0f,
        ): Silhouette = Silhouette(FloatArray(SAMPLES) { radius }, rot, cx, cy)

        /** From [a] to [b]; the rotation takes the short way round. */
        public fun blend(
            a: Silhouette,
            b: Silhouette,
            t: Float,
        ): Silhouette {
            var dRot = b.rot - a.rot
            while (dRot > PI) dRot -= TAU
            while (dRot < -PI) dRot += TAU
            return Silhouette(
                FloatArray(SAMPLES) { a.radii[it] + (b.radii[it] - a.radii[it]) * t },
                a.rot + dRot * t,
                lerp(a.cx, b.cx, t),
                lerp(a.cy, b.cy, t),
                lerp(a.sx, b.sx, t),
                lerp(a.sy, b.sy, t),
            )
        }

        /** Radius of [radii] towards [angle], between the two nearest samples. */
        public fun radiusAt(
            radii: FloatArray,
            angle: Float,
        ): Float {
            val t = (((angle / TAU) % 1f) + 1f) % 1f * SAMPLES
            val i = t.toInt()
            return lerp(radii[i % SAMPLES], radii[(i + 1) % SAMPLES], t - i)
        }

        /** |x/sx|^n + |y/sy|^n = 1: n = 2 is an ellipse, n near 4 a squircle. */
        public fun superellipse(
            n: Float,
            sx: Float = 1f,
            sy: Float = 1f,
        ): FloatArray = FloatArray(SAMPLES) { i -> (abs(COS[i] / sx).pow(n) + abs(SIN[i] / sy).pow(n)).pow(-1f / n) }

        /** Radius of a union of discs (the origin inside it): the farthest ray exit. */
        public fun unionOfCircles(circles: List<Triple<Float, Float, Float>>): FloatArray = FloatArray(SAMPLES) { i ->
            circles.maxOf { (x, y, r) ->
                val b = COS[i] * x + SIN[i] * y
                val disc = b * b - (x * x + y * y - r * r)
                if (disc < 0) 0f else b + sqrt(disc)
            }
        }

        /** Any polygon to a radial profile, by casting rays from (cx, cy). Built once, never per frame. */
        public fun fromPolygon(
            poly: List<Pt>,
            cx: Float = 0f,
            cy: Float = 0f,
        ): FloatArray = FloatArray(SAMPLES) { k ->
            var best = 0f
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val ex = b.x - a.x
                val ey = b.y - a.y
                val den = COS[k] * ey - SIN[k] * ex
                if (abs(den) < 1e-9f) continue
                val px = a.x - cx
                val py = a.y - cy
                val t = (px * ey - py * ex) / den
                val u = (px * SIN[k] - py * COS[k]) / den
                if (t > best && u in 0f..1f) best = t
            }
            best
        }

        /** The convex hull of two circles: a tapered bar, a capsule or a drop. */
        public fun hullOfCircles(
            x1: Float,
            y1: Float,
            r1: Float,
            x2: Float,
            y2: Float,
            r2: Float,
            steps: Int = 96,
        ): List<Pt> {
            val dist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1e-6f)
            val base = atan2(y2 - y1, x2 - x1)
            val spread = acos(((r1 - r2) / dist).coerceIn(-1f, 1f))
            val half = steps / 2
            val big = (0..half).map { i ->
                val a = base + spread + (TAU - 2 * spread) * i / half
                Pt(x1 + cos(a) * r1, y1 + sin(a) * r1)
            }
            val small = (0..half).map { i ->
                val a = base - spread + 2 * spread * i / half
                Pt(x2 + cos(a) * r2, y2 + sin(a) * r2)
            }
            return big + small
        }

        /** A regular polygon with rounded corners (radius [rc]), inscribed in [radius]. */
        public fun regularPolygon(
            sides: Int,
            radius: Float,
            rc: Float,
            rotationDeg: Float = 0f,
        ): FloatArray {
            val rot = rotationDeg * PI.toFloat() / 180f
            val verts = List(sides) { i ->
                val a = rot + i * TAU / sides
                Pt(cos(a) * (radius - rc), sin(a) * (radius - rc))
            }
            return fromPolygon(rounded(verts, rc))
        }

        /** Scales [radii] so its widest point is [max]: every body then weighs the same to the eye. */
        public fun normalized(
            radii: FloatArray,
            max: Float = 1f,
        ): FloatArray {
            val peak = radii.max()
            return if (peak <= 0f) radii else FloatArray(SAMPLES) { radii[it] * max / peak }
        }

        /** Minkowski sum with a disc: each corner becomes an arc of radius [rc]. Clockwise on screen. */
        private fun rounded(
            verts: List<Pt>,
            rc: Float,
            arcSteps: Int = 10,
        ): List<Pt> {
            fun normal(
                a: Pt,
                b: Pt,
            ): Float {
                val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-6f)
                return atan2(-(b.x - a.x) / len, (b.y - a.y) / len)
            }
            val out = mutableListOf<Pt>()
            for (i in verts.indices) {
                val prev = verts[(i - 1 + verts.size) % verts.size]
                val cur = verts[i]
                val next = verts[(i + 1) % verts.size]
                val a0 = normal(prev, cur)
                var d = normal(cur, next) - a0
                while (d > PI) d -= TAU
                while (d < -PI) d += TAU
                for (k in 0..arcSteps) {
                    val a = a0 + d * k / arcSteps
                    out += Pt(cur.x + cos(a) * rc, cur.y + sin(a) * rc)
                }
            }
            return out
        }
    }
}

internal fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

internal fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

internal fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

internal fun easeInOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

internal fun easeOutQuint(t: Float): Float = 1f - (1f - t).pow(5)
