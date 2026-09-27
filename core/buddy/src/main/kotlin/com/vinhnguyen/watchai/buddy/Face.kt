package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Head orientation in degrees: [yaw] > 0 looks right, [pitch] > 0 looks up, [roll] tilts the
 * head. The eyes and the mouth are painted on a sphere turned this way, so looking aside
 * squeezes and tilts them by itself; that is what gives the flat body its volume.
 */
public data class Gaze(
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
)

/**
 * One eye in body units: a pill [w] across and [h] tall, [open] 1..0 lid, own [tilt] in degrees.
 * With [arc] above one half it is a bent line instead ("^", [h] thick): squinting with joy.
 */
public data class Eye(
    val w: Float,
    val h: Float,
    val open: Float = 1f,
    val tilt: Float = 0f,
    val arc: Float = 0f,
)

/**
 * The mouth, a band on the sphere below the eyes, in body units: [w] corner to corner,
 * [curve] > 0 smiles and < 0 frowns, [open] how far it opens (a grin opens downwards, an "o"
 * both ways, by [up]), [thick] the line when closed, [tilt] in degrees.
 */
public data class Mouth(
    val w: Float,
    val curve: Float,
    val open: Float = 0f,
    val thick: Float = 0.05f,
    val up: Float = 0f,
    val tilt: Float = 0f,
)

/** A whole face: where the head looks, how far apart the eyes sit (half, in degrees), both eyes, the mouth and how far below the eyes it sits (degrees). */
public data class Expression(
    val gaze: Gaze,
    val split: Float,
    val eyes: Pair<Eye, Eye>,
    val mouth: Mouth,
    val drop: Float = 24f,
) {
    public companion object {
        public fun blend(
            a: Expression,
            b: Expression,
            t: Float,
        ): Expression = Expression(
            Gaze(lerp(a.gaze.yaw, b.gaze.yaw, t), lerp(a.gaze.pitch, b.gaze.pitch, t), lerp(a.gaze.roll, b.gaze.roll, t)),
            lerp(a.split, b.split, t),
            blend(a.eyes.first, b.eyes.first, t) to blend(a.eyes.second, b.eyes.second, t),
            blend(a.mouth, b.mouth, t),
            lerp(a.drop, b.drop, t),
        )

        private fun blend(
            a: Eye,
            b: Eye,
            t: Float,
        ) = Eye(lerp(a.w, b.w, t), lerp(a.h, b.h, t), lerp(a.open, b.open, t), lerp(a.tilt, b.tilt, t), lerp(a.arc, b.arc, t))

        private fun blend(
            a: Mouth,
            b: Mouth,
            t: Float,
        ) = Mouth(lerp(a.w, b.w, t), lerp(a.curve, b.curve, t), lerp(a.open, b.open, t), lerp(a.thick, b.thick, t), lerp(a.up, b.up, t), lerp(a.tilt, b.tilt, t))
    }
}

/**
 * A spot on the sphere seen from the front: its centre ([x], [y] on the unit sphere), the
 * tangent frame projected to the screen (columns (a, b) and (c, d), like an SVG matrix), and
 * [depth] > 0 while it faces the viewer.
 */
internal class Spot(
    val x: Float,
    val y: Float,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val depth: Float,
)

/** The life at rest, as offsets to add to the pose: head drift, the lid (1 open, 0 shut), body float and breath. */
internal class Life(
    val dYaw: Float,
    val dPitch: Float,
    val dRoll: Float,
    val lid: Float,
    val driftX: Float,
    val driftY: Float,
    val breath: Float,
)

/**
 * Sphere geometry, liveliness and blinking, ported from bloub by Jérémy Perret (MIT,
 * github.com/jeremy-prt/bloub, src/bot/face.ts). The mouth is ours: bloub has none.
 * All pure functions of time, so pausing, resuming or jumping to any time gives the same picture.
 */
internal object Face {
    private const val DEG = PI.toFloat() / 180f
    private const val TAU = 2 * PI.toFloat()

    /**
     * Head frame, then the eyes (inner, outer) at ±[split] degrees and the mouth [drop] degrees
     * below them. Screen frame: x right, y down, z towards the viewer.
     */
    fun spots(
        gaze: Gaze,
        split: Float,
        drop: Float,
    ): Triple<Spot, Spot, Spot> {
        var f = floatArrayOf(0f, 0f, 1f)
        var right = floatArrayOf(1f, 0f, 0f)
        var down = floatArrayOf(0f, 1f, 0f)
        spin(f, right, gaze.yaw * DEG).let {
            f = it.first
            right = it.second
        }
        spin(down, f, gaze.pitch * DEG).let {
            down = it.first
            f = it.second
        }
        spin(right, down, gaze.roll * DEG).let {
            right = it.first
            down = it.second
        }
        fun eye(side: Int): Spot {
            val (ef, er) = spin(f, right, split * side * DEG)
            return Spot(ef[0], ef[1], er[0], er[1], down[0], down[1], ef[2])
        }
        // Tipping the forward vector towards "down" slides the mouth under the eyes; its across stays "right".
        val (mf, md) = spin(f, down, drop * DEG)
        return Triple(eye(-1), eye(1), Spot(mf[0], mf[1], right[0], right[1], md[0], md[1], mf[2]))
    }

    /** Turns two vectors of an orthonormal frame by [angle] within their plane. */
    private fun spin(
        u: FloatArray,
        v: FloatArray,
        angle: Float,
    ): Pair<FloatArray, FloatArray> {
        val c = cos(angle)
        val s = sin(angle)
        return FloatArray(3) { u[it] * c + v[it] * s } to FloatArray(3) { v[it] * c - u[it] * s }
    }

    /** A seamless periodic wobble: never repeats to the eye when periods are coprime. */
    fun loopNoise(
        t: Double,
        period: Double,
        seed: Double,
    ): Float {
        val p = t / period * TAU
        return (0.55 * sin(p + seed) + 0.3 * sin(2 * p + seed * 1.7 + 1.1) + 0.15 * sin(3 * p + seed * 2.3 + 2.4)).toFloat()
    }

    /** Head drift, blinks, float and breath at [t]; [wander] scales the drift, 0 when there are no eyes. */
    fun life(
        t: Double,
        wander: Float,
        blink: Boolean,
        still: Boolean,
    ): Life {
        if (still) return Life(0f, 0f, 0f, 1f, 0f, 0f, 1f)
        return Life(
            dYaw = (loopNoise(t, 11.3, 0.4) * 5.5f + loopNoise(t, 3.7, 2.1) * 1.6f) * wander,
            dPitch = (loopNoise(t, 9.1, 1.3) * 4.2f + loopNoise(t, 4.3, 0.7) * 1.3f) * wander,
            dRoll = loopNoise(t, 13.7, 3.2) * 2.2f * wander,
            lid = if (blink) lid(t) else 1f,
            driftX = loopNoise(t, 7.9, 1.9) * 0.006f,
            driftY = loopNoise(t, 5.3, 0.3) * 0.007f,
            breath = 1f + sin(t / 3.4 * TAU).toFloat() * 0.005f,
        )
    }

    /** A blink squashes the eye on screen (height down to 6%), not along the pill's own axis. */
    fun blinkScale(lid: Float): Float = 0.06f + 0.94f * clamp01(lid)

    /** Blink times drawn once: 1.9 to 4.6 s apart, sometimes a double blink. Fixed, so time alone decides. */
    private val BLINKS: DoubleArray = run {
        val rng = Mulberry(0x0b11_4c5L)
        val out = ArrayList<Double>()
        var t = 1.4
        while (t < BLINK_TABLE_S) {
            out += t
            t += 1.9 + rng.next() * 2.7
            if (rng.next() < 0.18) {
                out += t
                t += 0.24
            }
        }
        out.toDoubleArray()
    }

    private const val BLINK_S = 0.18
    private const val BLINK_TABLE_S = 900.0

    private fun lid(time: Double): Float {
        // The table covers 15 minutes; after that it starts over, a jump nobody sees mid-blink.
        val t = time % BLINK_TABLE_S
        var lo = 0
        var hi = BLINKS.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (BLINKS[mid] <= t) lo = mid + 1 else hi = mid - 1
        }
        if (hi < 0) return 1f
        val k = ((t - BLINKS[hi]) / BLINK_S).toFloat()
        if (k > 1f) return 1f
        // Shuts fast, opens a little slower.
        return if (k < 0.45f) 1f - k / 0.45f else (k - 0.45f) / 0.55f
    }

    /** An eye as a band when it squints with joy ([Eye.arc] over one half): a bent "^" line; else null, a pill. */
    fun band(e: Eye): Mouth? = if (e.arc >= 0.5f) Mouth(e.w, -e.arc, thick = e.h) else null

    /**
     * A feature on the sphere: its own [tilt] (degrees) first, then the spot's tangent frame,
     * then [squashY] on screen around its centre (a blink), then moved to ([x], [y]).
     */
    fun feature(
        spot: Spot,
        tilt: Float,
        squashY: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        band: Mouth?,
        alpha: Float,
    ): Feature {
        val phi = tilt * DEG
        val cp = cos(phi)
        val sp = sin(phi)
        return Feature(
            w,
            h,
            band,
            a = spot.a * cp + spot.c * sp,
            b = (spot.b * cp + spot.d * sp) * squashY,
            c = -spot.a * sp + spot.c * cp,
            d = (-spot.b * sp + spot.d * cp) * squashY,
            e = x,
            f = y,
            alpha = alpha,
        )
    }

    /** A pill [w] by [h] centred on the origin, as polygon points (x, y interleaved). */
    fun pill(
        w: Float,
        h: Float,
    ): FloatArray {
        val hw = maxOf(w, 0.002f) / 2
        val hh = maxOf(h, 0.002f) / 2
        val r = min(hw, hh)
        val steps = 8
        val out = FloatArray((steps + 1) * 4 * 2)
        var n = 0
        // Corner centres clockwise from the top left, each with its quarter arc.
        val corners = arrayOf(floatArrayOf(-hw + r, -hh + r), floatArrayOf(hw - r, -hh + r), floatArrayOf(hw - r, hh - r), floatArrayOf(-hw + r, hh - r))
        for (q in 0 until 4) {
            val start = PI.toFloat() + q * PI.toFloat() / 2
            for (i in 0..steps) {
                val a = start + i * (PI.toFloat() / 2) / steps
                out[n++] = corners[q][0] + cos(a) * r
                out[n++] = corners[q][1] + sin(a) * r
            }
        }
        return out
    }

    /**
     * The mouth outline centred on the origin (x, y interleaved): the upper edge left to right,
     * a round cap, the lower edge back, another cap.
     */
    fun mouth(m: Mouth): FloatArray {
        val hw = maxOf(m.w, 0.01f) / 2
        val half = m.thick / 2
        val steps = 12
        val cap = 6
        val out = FloatArray(((steps + 1) * 2 + (cap - 1) * 2) * 2)
        var n = 0
        fun centre(u: Float) = m.curve * hw * (1 - u * u)
        fun opening(u: Float) = m.open * sqrt(maxOf(0f, 1 - u * u))
        for (i in 0..steps) {
            val u = -1f + 2f * i / steps
            out[n++] = u * hw
            out[n++] = centre(u) - half - opening(u) * m.up
        }
        for (i in 1 until cap) {
            val a = -PI.toFloat() / 2 + PI.toFloat() * i / cap
            out[n++] = hw + cos(a) * half
            out[n++] = sin(a) * half
        }
        for (i in steps downTo 0) {
            val u = -1f + 2f * i / steps
            out[n++] = u * hw
            out[n++] = centre(u) + half + opening(u) * (1 - m.up)
        }
        for (i in 1 until cap) {
            val a = PI.toFloat() / 2 + PI.toFloat() * i / cap
            out[n++] = -hw + cos(a) * half
            out[n++] = sin(a) * half
        }
        return out
    }

    /** How much of a spot shows: fades out as it turns away over the edge of the sphere. */
    fun facing(depth: Float): Float = clamp01(depth / 0.12f)
}

/** mulberry32: a tiny fixed generator, the same sequence everywhere, for tables built once. */
internal class Mulberry(
    seed: Long,
) {
    private var a = seed.toInt()

    fun next(): Double {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}
