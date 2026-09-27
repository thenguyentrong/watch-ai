package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Where the conversation is: the base of Buddy's face and motion. */
public enum class Act { REST, AWAKE, CONNECT, LISTEN, THINK, SPEAK, ERROR }

public enum class EyeShape { OPEN, HAPPY, CLOSED, HEARTS, WIDE, SQUINT }

public enum class Arms { REST, WAVE, UP }

public enum class Effect { NONE, SPARKLES, HEARTS, ZZZ, QUESTION, EXCLAIM, SWEAT, DOTS }

/** What Buddy is doing: the conversation's [act] since [actAt], and a [reaction] since [reactionAt] (seconds). */
public data class Scene(
    val act: Act,
    val actAt: Double = 0.0,
    val reaction: Reaction? = null,
    val reactionAt: Double = 0.0,
)

/**
 * One frame of Buddy in body units: radius 1 is the body at rest, (0, 0) its centre, y up.
 * Renderers only draw this; they decide nothing.
 */
public data class Frame(
    /** Wider and shorter (> 0) or taller (< 0), keeping the area: bounces and breathing. */
    val squash: Float,
    val dx: Float,
    val dy: Float,
    /** Lean in degrees, positive = to the right. */
    val tilt: Float,
    val eyes: EyeShape,
    val eyeOpen: Float,
    val gazeX: Float,
    val gazeY: Float,
    /** -1 frown .. 1 big smile. */
    val smile: Float,
    val mouthOpen: Float,
    val blush: Float,
    val arms: Arms,
    /** Seconds into the gesture, for waving. */
    val armT: Float,
    val effect: Effect,
    /** Seconds into the effect: one-shot effects rise and fade, the thinking dots loop. */
    val effectT: Float,
    val intensity: Float,
)

/**
 * Buddy's animation as a pure function of time, like bloub's engine (MIT, github.com/jeremy-prt/bloub):
 * the same scene and time always give the same frame, so a frame can be frozen for a screenshot
 * or a test, and nothing here reads a clock. Life at rest is gaze drift and blinking; motion
 * comes from reactions and eases out without overshoot.
 */
public object Engine {
    /** How long a reaction's face stays, and how long its motion takes (seconds). */
    public const val REACTION_S: Double = 3.5
    public const val MOTION_S: Double = 1.2

    /** State changes blend over this long from what was on screen (seconds). */
    public const val FADE_S: Double = 0.35

    public fun frame(
        genes: Genes,
        scene: Scene,
        level: Float,
        t: Double,
    ): Frame {
        val base = act(genes, scene.act, (t - scene.actAt).toFloat(), t, level.coerceIn(0f, 1f))
        val reaction = scene.reaction
        val rt = (t - scene.reactionAt).toFloat()
        val pose = if (reaction != null && rt in 0f..REACTION_S.toFloat()) react(base, reaction, rt) else base
        return Frame(
            squash = pose.squash,
            dx = pose.dx,
            dy = pose.dy,
            tilt = pose.tilt,
            eyes = pose.eyes,
            eyeOpen = (pose.eyeOpen * blink(genes.seed, t)).coerceAtLeast(0f),
            gazeX = pose.gazeX,
            gazeY = pose.gazeY,
            smile = pose.smile,
            mouthOpen = pose.mouthOpen,
            blush = pose.blush,
            arms = pose.arms,
            armT = if (reaction != null && pose.arms != Arms.REST) rt else (t - scene.actAt).toFloat(),
            effect = pose.effect,
            effectT = if (pose.effect == Effect.DOTS) (t - scene.actAt).toFloat() else rt,
            intensity = reaction?.intensity ?: 0.5f,
        )
    }

    /** From [from] towards [to]: numbers ease out; eye shapes swap mid-way behind a quick blink. */
    public fun blend(
        from: Frame,
        to: Frame,
        u: Float,
    ): Frame {
        val e = 1f - (1f - u.coerceIn(0f, 1f)).pow(5)
        fun mix(
            a: Float,
            b: Float,
        ) = a + (b - a) * e
        val swap = from.eyes != to.eyes
        val blinkThrough = if (swap) abs(1f - 2f * e) else 1f
        return to.copy(
            squash = mix(from.squash, to.squash),
            dx = mix(from.dx, to.dx),
            dy = mix(from.dy, to.dy),
            tilt = mix(from.tilt, to.tilt),
            eyes = if (e < 0.5f) from.eyes else to.eyes,
            eyeOpen = mix(from.eyeOpen, to.eyeOpen) * blinkThrough,
            gazeX = mix(from.gazeX, to.gazeX),
            gazeY = mix(from.gazeY, to.gazeY),
            smile = mix(from.smile, to.smile),
            mouthOpen = mix(from.mouthOpen, to.mouthOpen),
            blush = mix(from.blush, to.blush),
        )
    }

    /** The conversation's base pose, [t] seconds into the act, [clock] the absolute time for the life at rest. */
    private fun act(
        genes: Genes,
        act: Act,
        t: Float,
        clock: Double,
        level: Float,
    ): Pose {
        val (gx, gy) = drift(genes.seed, clock)
        val c = clock.toFloat()
        return when (act) {
            Act.REST -> Pose(squash = 0.02f * sin(1.1f * c), eyeOpen = 0.45f, gazeX = gx * 0.5f, gazeY = gy * 0.5f - 0.1f, smile = 0.2f)
            Act.AWAKE -> Pose(squash = 0.012f * sin(1.3f * c), gazeX = gx, gazeY = gy, smile = 0.3f)
            Act.CONNECT -> Pose(eyeOpen = 0.3f + 0.7f * easeOut(t / 0.6f), gazeY = 0.5f, smile = 0.2f)
            Act.LISTEN -> Pose(squash = -0.06f * level, dy = 0.03f * level, eyeOpen = 1.1f, gazeX = gx * 0.3f, gazeY = gy * 0.3f, smile = 0.3f)
            Act.THINK -> Pose(gazeX = 0.55f + 0.12f * cos(2 * t), gazeY = 0.6f + 0.1f * sin(2 * t), smile = 0f, tilt = 4f * sin(1.4f * t), effect = Effect.DOTS)
            Act.SPEAK -> Pose(mouthOpen = 0.12f + 0.6f * level, dy = 0.02f * level * sin(9 * t), gazeX = gx, gazeY = gy, smile = 0.45f)
            Act.ERROR -> Pose(eyes = EyeShape.SQUINT, smile = -0.3f, tilt = -5f, effect = Effect.SWEAT)
        }
    }

    /** A reaction's face over the base, and its one motion in the first [MOTION_S] seconds. */
    private fun react(
        base: Pose,
        reaction: Reaction,
        rt: Float,
    ): Pose {
        val a = 0.4f + 0.6f * reaction.intensity
        val m = (rt / MOTION_S.toFloat()).coerceIn(0f, 1f)
        val fade = 1f - m
        val pi = PI.toFloat()
        fun bounce() = base.copy(dy = base.dy + 0.1f * a * abs(sin(3 * pi * m)) * fade, squash = base.squash + 0.12f * a * sin(6 * pi * m) * fade)
        fun jump() = base.copy(dy = base.dy + 0.16f * a * sin(pi * m), squash = base.squash - 0.12f * a * sin(pi * m))
        fun shake() = base.copy(dx = base.dx + 0.04f * a * sin(8 * pi * m) * fade)
        fun nod() = base.copy(dy = base.dy - 0.03f * a * sin(4 * pi * m) * fade)
        fun sway() = base.copy(tilt = base.tilt + 7f * a * sin(2 * pi * m))
        fun droop() = base.copy(dy = base.dy - 0.04f * a * m, squash = base.squash + 0.1f * a * m)
        return when (reaction.mood) {
            Mood.NEUTRAL -> base
            Mood.HAPPY -> nod().copy(eyes = EyeShape.HAPPY, smile = 0.5f + 0.4f * reaction.intensity, blush = 0.6f)
            Mood.GREET -> base.copy(eyes = EyeShape.HAPPY, smile = 0.8f, blush = 0.5f, arms = Arms.WAVE)
            Mood.EXCITED -> bounce().copy(eyes = EyeShape.WIDE, smile = 1f, mouthOpen = 0.35f, blush = 0.6f, arms = Arms.UP, effect = Effect.SPARKLES)
            Mood.LAUGH -> shake().copy(eyes = EyeShape.SQUINT, smile = 1f, mouthOpen = 0.55f, blush = 0.7f)
            Mood.LOVE -> sway().copy(eyes = EyeShape.HEARTS, smile = 0.7f, blush = 1f, effect = Effect.HEARTS)
            Mood.CURIOUS -> base.copy(gazeX = -0.4f, gazeY = 0.1f, smile = 0.15f, tilt = -10f * a, effect = Effect.QUESTION)
            Mood.THINKING -> base.copy(gazeX = 0.55f, gazeY = 0.6f, smile = 0f, effect = Effect.DOTS)
            Mood.SURPRISED -> jump().copy(eyes = EyeShape.WIDE, smile = 0f, mouthOpen = 0.45f, effect = Effect.EXCLAIM)
            Mood.SAD -> droop().copy(eyeOpen = 0.7f, gazeY = -0.5f, smile = -0.6f, blush = 0.1f)
            Mood.SLEEPY -> sway().copy(eyes = EyeShape.CLOSED, smile = 0.1f, effect = Effect.ZZZ)
            Mood.CONFUSED -> base.copy(gazeX = 0.3f, smile = -0.15f, tilt = 9f * a, effect = Effect.QUESTION)
            Mood.PROUD -> jump().copy(eyes = EyeShape.HAPPY, smile = 0.9f, blush = 0.6f, arms = Arms.UP, effect = Effect.SPARKLES)
            Mood.OOPS -> shake().copy(eyes = EyeShape.SQUINT, smile = -0.3f, effect = Effect.SWEAT)
        }
    }

    /** Where the eyes wander at rest: two slow waves per axis, phased by the seed so no two Buddies look alike. */
    internal fun drift(
        seed: Long,
        t: Double,
    ): Pair<Float, Float> {
        val p = phases(seed)
        val x = 0.18 * sin(0.61 * t + p[0]) + 0.07 * sin(1.73 * t + p[1])
        val y = 0.1 * sin(0.47 * t + p[2]) + 0.05 * sin(1.31 * t + p[3])
        return x.toFloat() to y.toFloat()
    }

    /** 1 with the eyes open, down to 0.08 in a blink: one blink every 3.7 s, give or take, at seeded moments. */
    internal fun blink(
        seed: Long,
        t: Double,
    ): Float {
        val k = floor(t / BLINK_EVERY).toLong()
        for (i in k - 1..k) {
            val start = i * BLINK_EVERY + jitter(seed, i) * 1.8
            val u = (t - start) / BLINK_S
            if (u in 0.0..1.0) return (1.0 - 0.92 * sin(PI * u)).toFloat()
        }
        return 1f
    }

    private fun phases(seed: Long): DoubleArray {
        val rng = Rng(seed xor 0x5EED)
        return DoubleArray(4) { rng.nextFloat() * 2 * PI }
    }

    private fun jitter(
        seed: Long,
        i: Long,
    ): Double = Rng(seed xor (i * 0x9E3779B9L)).nextFloat().toDouble()

    private fun easeOut(u: Float): Float = 1f - (1f - u.coerceIn(0f, 1f)).pow(5)

    private const val BLINK_EVERY = 3.7
    private const val BLINK_S = 0.16
}

/** Keeps what's on screen continuous when the scene changes mid-animation: the new scene blends from the last frame shown. */
public class Director(
    private val genes: Genes,
) {
    private var scene: Scene? = null
    private var from: Frame? = null
    private var changedAt = 0.0
    private var last: Frame? = null

    /** The frame at [t]; with [blend] off (a frozen view, where time doesn't move) the scene's own frame, no fade. */
    public fun frame(
        scene: Scene,
        level: Float,
        t: Double,
        blend: Boolean = true,
    ): Frame {
        if (!blend) return Engine.frame(genes, scene, level, t).also { last = it }
        if (scene != this.scene) {
            from = last
            changedAt = t
            this.scene = scene
        }
        val target = Engine.frame(genes, scene, level, t)
        val start = from
        val shown = if (start != null && t - changedAt < Engine.FADE_S) Engine.blend(start, target, ((t - changedAt) / Engine.FADE_S).toFloat()) else target
        last = shown
        return shown
    }
}

internal data class Pose(
    val squash: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val tilt: Float = 0f,
    val eyes: EyeShape = EyeShape.OPEN,
    val eyeOpen: Float = 1f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val smile: Float = 0.3f,
    val mouthOpen: Float = 0f,
    val blush: Float = 0.3f,
    val arms: Arms = Arms.REST,
    val effect: Effect = Effect.NONE,
)
