package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pose declared by a state, in body units: the outline, an offset for body and face, the
 * face and how visible it is, and the decor. The engine only blends poses and adds the life
 * at rest; states never read a clock.
 */
internal class Pose(
    val sil: Silhouette,
    val face: Expression,
    val offX: Float = 0f,
    val offY: Float = 0f,
    val faceAlpha: Float = 1f,
    val dots: List<Dot> = emptyList(),
    val dotsBehind: Boolean = false,
    val arcs: List<ArcSpec> = emptyList(),
    /** The badge, placed on the outline at [Decor.BADGE_ANGLE]. */
    val badge: BadgeSpec? = null,
) {
    fun copy(
        sil: Silhouette = this.sil,
        face: Expression = this.face,
        offX: Float = this.offX,
        offY: Float = this.offY,
    ): Pose = Pose(sil, face, offX, offY, faceAlpha, dots, dotsBehind, arcs, badge)

    companion object {
        /** From [a] to [b]: shapes and faces morph, decor cross-fades, the badge switches half-way. */
        fun blend(
            a: Pose,
            b: Pose,
            t: Float,
        ): Pose {
            val out = 1 - t
            fun fade(
                d: Dot,
                k: Float,
            ) = Dot(d.x, d.y, d.r, d.opacity * k, d.depth, d.shape, d.rot)
            return Pose(
                sil = Silhouette.blend(a.sil, b.sil, t),
                face = Expression.blend(a.face, b.face, t),
                offX = lerp(a.offX, b.offX, t),
                offY = lerp(a.offY, b.offY, t),
                faceAlpha = lerp(a.faceAlpha, b.faceAlpha, t),
                dots = a.dots.map { fade(it, out) } + b.dots.map { fade(it, t) },
                dotsBehind = if (t < 0.5f) a.dotsBehind else b.dotsBehind,
                arcs = a.arcs.map { ArcSpec(it.seed, it.t, it.opacity * out) } + b.arcs.map { ArcSpec(it.seed, it.t, it.opacity * t) },
                badge = if (t < 0.5f) a.badge else b.badge,
            )
        }
    }
}

internal class BadgeSpec(
    val r: Float,
    val heart: Boolean,
)

/** What a state knows besides its own time: this Buddy's resting body and face, and the voice level (0..1). */
internal class Cue(
    val body: FloatArray,
    val rest: Expression,
    val level: Float,
)

/**
 * Everything Buddy can do. The first seven follow the conversation ([Act]); the rest are
 * reactions ([Mood]) that play for [hold] seconds and hand back to the conversation.
 *
 * The animations are bloub's (MIT, github.com/jeremy-prt/bloub, src/bot/states.ts): the
 * thinking dots, the sliding and the upright "!", the badge, the bouncing sleep dot, the egg and
 * hexagon, the play triangle with its swoosh, the orbit, the burst and the comet. Sizes, faces
 * and timings are our own, and every face has a mouth.
 */
internal enum class State(
    /** How long it plays as a reaction; conversation states last as long as the act. */
    val hold: Double,
    /** How long the morph into it takes. */
    val morph: Float,
    /** Masks the change with a blink, as bloub does for every change of face. */
    val blinkIn: Boolean,
    /** When in its own time the state reads best: the still picture shown with animations off. */
    val readable: Float,
) {
    IDLE(0.0, 0.45f, false, 1f),
    AWAKE(0.0, 0.3f, true, 0.5f),
    CONNECT(0.0, 0.5f, true, 0.9f),
    LISTEN(0.0, 0.5f, true, 0.8f),
    THINK(0.0, 0.4f, true, 1.1f),
    SPEAK(0.0, 0.3f, false, 1f),
    ERROR(0.0, 0.45f, false, 0.75f),
    GREET(1.8, 0.3f, true, 0.8f),
    HAPPY(2.4, 0.35f, true, 1.2f),
    PROUD(3.4, 0.6f, false, 1.2f),
    EXCITED(2.8, 0.4f, false, 0.45f),
    LAUGH(2.8, 0.45f, false, 1.15f),
    LOVE(2.6, 0.5f, true, 0.9f),
    CURIOUS(2.2, 0.4f, true, 0.8f),
    CONFUSED(2.0, 0.4f, true, 0.8f),
    SURPRISED(2.0, 0.45f, false, 0.8f),
    SAD(3.0, 0.5f, true, 1.2f),
    SLEEPY(2.4, 0.5f, false, 0.45f),
    PLAY(2.0, 0.5f, true, 0.9f),
    ;

    fun pose(
        t: Float,
        cue: Cue,
    ): Pose = Poses.pose(this, t, cue)

    companion object {
        fun of(act: Act): State = when (act) {
            Act.REST -> IDLE
            Act.AWAKE -> AWAKE
            Act.CONNECT -> CONNECT
            Act.LISTEN -> LISTEN
            Act.THINK -> THINK
            Act.SPEAK -> SPEAK
            Act.ERROR -> ERROR
        }

        fun of(mood: Mood): State? = when (mood) {
            Mood.NEUTRAL -> null
            Mood.HAPPY -> HAPPY
            Mood.GREET -> GREET
            Mood.EXCITED -> EXCITED
            Mood.LAUGH -> LAUGH
            Mood.LOVE -> LOVE
            Mood.CURIOUS -> CURIOUS
            Mood.THINKING -> THINK
            Mood.SURPRISED -> SURPRISED
            Mood.SAD -> SAD
            Mood.SLEEPY -> SLEEPY
            Mood.CONFUSED -> CONFUSED
            Mood.PROUD -> PROUD
            Mood.OOPS -> ERROR
            Mood.PLAY -> PLAY
        }
    }
}

internal object Poses {
    private const val TAU = 2 * PI.toFloat()
    private const val DEG = PI.toFloat() / 180f

    // -------------------------------------------------------------- shapes of the animations

    /** The upright "!": a tapered bar (hull of two circles) over a dot. */
    private const val BAR_CY = -0.19f
    private val BAR_UPRIGHT = Silhouette.fromPolygon(Silhouette.hullOfCircles(0f, -0.5f, 0.13f, 0f, 0.13f, 0.075f), 0f, BAR_CY)

    /** The leaning "!": a plain capsule, with a tear-shaped dot. */
    private val BAR_ITALIC = Silhouette.fromPolygon(Silhouette.hullOfCircles(0f, -0.25f, 0.13f, 0f, 0.25f, 0.13f))
    private val TEAR: FloatArray = Silhouette.hullOfCircles(0f, 0f, 0.115f, 0f, 0.17f, 0.012f, steps = 40).flatMap { listOf(it.x, it.y) }.toFloatArray()
    private const val ITALIC = 16f

    val TRIANGLE: FloatArray = Silhouette.regularPolygon(3, 1.1f, 0.3f, -90f)
    val HEXAGON: FloatArray = Silhouette.regularPolygon(6, 1.02f, 0.22f, 30f)
    val EGG: FloatArray = Shapes.EGG

    /** The triangle doesn't spin on the spot: its centre circles the middle, so it tumbles. */
    private const val TRI_ORBIT = 0.2f

    private fun spinningTriangle(rot: Float) = Silhouette(TRIANGLE, rot, -TRI_ORBIT * sin(rot), TRI_ORBIT * cos(rot))

    // ---------------------------------------------------------------------------- faces

    private fun pair(
        w: Float,
        h: Float,
        tilt: Float = 0f,
        open: Float = 1f,
    ) = Eye(w, h, open, tilt) to Eye(w, h, open, -tilt)

    /** Squinting "^ ^" eyes: [w] wide, [thick] lines, bent by [arc]. */
    private fun smiling(
        w: Float,
        thick: Float,
        arc: Float,
    ) = Eye(w, thick, arc = arc) to Eye(w, thick, arc = arc)

    val GREET = Expression(Gaze(-6f, 6f, 7f), 16.5f, Eye(0.23f, 0.44f) to Eye(0.4f, 0.08f), Mouth(0.28f, 0.45f, open = 0.04f))
    val HAPPY = Expression(Gaze(6f, 12f, -4f), 17f, smiling(0.27f, 0.075f, 0.55f), Mouth(0.3f, 0.42f, open = 0.07f))
    val LAUGH = Expression(Gaze(4f, 16f, 0f), 18f, smiling(0.3f, 0.085f, 0.7f), Mouth(0.34f, 0.4f, open = 0.15f))
    val EXCITED = Expression(Gaze(6f, -8f, 0f), 18.5f, pair(0.33f, 0.48f, -8f), Mouth(0.3f, 0.4f, open = 0.12f))
    val PROUD = Expression(Gaze(8f, 18f, -6f), 17f, smiling(0.26f, 0.075f, 0.55f), Mouth(0.26f, 0.5f, open = 0.03f))
    val LOVE = Expression(Gaze(8f, 10f, -6f), 17f, smiling(0.25f, 0.075f, 0.5f), Mouth(0.24f, 0.45f, open = 0.03f))
    val CURIOUS = Expression(Gaze(16f, 20f, -16f), 12f, pair(0.17f, 0.38f), Mouth(0.09f, 0f, open = 0.06f, thick = 0.04f, up = 0.5f))
    val CONFUSED = Expression(Gaze(-12f, 8f, 8f), 14f, Eye(0.19f, 0.42f, tilt = -16f) to Eye(0.26f, 0.16f, tilt = 12f), Mouth(0.18f, -0.08f, thick = 0.045f, tilt = 10f))
    val SAD = Expression(Gaze(3f, -12f, 0f), 16f, pair(0.21f, 0.38f, -26f), Mouth(0.2f, -0.4f))
    val LISTEN = Expression(Gaze(4f, -12f, 8f), 18f, pair(0.3f, 0.6f), Mouth(0.12f, 0.15f, thick = 0.045f))
    val CONNECT = Expression(Gaze(-18f, -6f, -10f), 18f, pair(0.42f, 0.42f), Mouth(0.1f, 0f, open = 0.05f, thick = 0.045f, up = 0.5f))
    val PLAY = Expression(Gaze(12f, -8f, -6f), 15f, pair(0.18f, 0.34f), Mouth(0.2f, 0.4f, open = 0.02f))

    private fun speaking(
        rest: Expression,
        level: Float,
    ) = rest.copy(mouth = rest.mouth.copy(w = rest.mouth.w * 1.05f, curve = maxOf(rest.mouth.curve, 0.2f) * 0.7f, open = 0.03f + 0.14f * level, up = 0.2f))

    // ---------------------------------------------------------------------------- poses

    /** A pulse running through the three dots from left to right. */
    private fun dotPulse(
        t: Float,
        index: Int,
    ): Float {
        val p = ((((t - index * 0.5f) / 1.5f) % 1f) + 1f) % 1f
        val k = if (p < 0.5f) 0.5f - 0.5f * cos(p * TAU) else 0f
        return clamp01(k * 2)
    }

    fun pose(
        state: State,
        t: Float,
        cue: Cue,
    ): Pose = when (state) {
        State.IDLE -> Pose(Silhouette(cue.body), cue.rest)

        State.AWAKE ->
            // Half of the orbit's rings, quickly in and out: Buddy is up.
            Pose(
                Silhouette(cue.body),
                cue.rest,
                arcs = Decor.RINGS.take(3).mapIndexed { i, s -> ArcSpec(s, t, clamp01((t - i * 0.06f) / 0.14f) * clamp01((1.22f - t) / 0.34f)) },
            )

        State.CONNECT -> {
            // The badge pops: 15 % over size at about 0.3 s, then settles.
            val p = clamp01(t / 0.45f)
            val pop = 1 + (Decor.BADGE_POP - 1) * sin(p * PI.toFloat()) * (1 - p * 0.35f)
            Pose(Silhouette(cue.body), CONNECT, badge = BadgeSpec(Decor.BADGE_R * (if (p < 1f) pop else 1f), heart = false))
        }

        State.LISTEN -> {
            // Leans in with the user's voice.
            val l = cue.level
            Pose(Silhouette(cue.body, sx = 1 + 0.02f * l, sy = 1 + 0.035f * l), LISTEN.copy(eyes = LISTEN.eyes.let { (a, b) -> a.copy(h = a.h * (1 + 0.08f * l)) to b.copy(h = b.h * (1 + 0.08f * l)) }), offY = -0.02f * l)
        }

        State.THINK -> {
            // The body becomes the middle dot, the other two come out of its sides.
            val mid = dotPulse(t, 1)
            val emerge = 0.3f + 0.7f * easeOutCubic(clamp01(t / 0.3f))
            Pose(
                Silhouette.circle(Decor.DOT_R * (1 + (Decor.DOT_PEAK - 1) * mid), cx = Decor.DOT_X[1]),
                cue.rest,
                faceAlpha = 0f,
                dots = listOf(0, 2).map { i ->
                    val k = dotPulse(t, i)
                    Dot(Decor.DOT_X[i] * emerge, 0f, Decor.DOT_R * (1 + (Decor.DOT_PEAK - 1) * k), 0.55f + 0.45f * k)
                },
            )
        }

        State.SPEAK -> {
            val l = cue.level
            Pose(Silhouette(cue.body, sy = 1 + 0.02f * l), speaking(cue.rest, l), offY = -0.012f * l)
        }

        State.ERROR -> {
            // The leaning "!" slides across and comes back, with a small buzz.
            val p = clamp01(t / 1.5f)
            val travel = easeInOutCubic(p) * 0.8f - 0.09f
            val back = if (t > 1.6f) clamp01((t - 1.6f) / 0.4f) else 0f
            val x = travel * (1 - back) + 0.1f * back
            val buzz = sin(t * 2.5f * TAU) * 0.005f
            val tilt = ITALIC * DEG
            Pose(
                Silhouette(BAR_ITALIC, rot = tilt, cx = x, cy = -0.32f - buzz),
                cue.rest,
                faceAlpha = 0f,
                dots = listOf(Dot(x - sin(tilt) * 0.58f, -0.32f + cos(tilt) * 0.58f + buzz * 2.8f, 0.115f, 1f, shape = TEAR, rot = ITALIC)),
            )
        }

        State.GREET -> Pose(Silhouette(cue.body), GREET)

        State.HAPPY -> {
            // Two small hops.
            val hop = if (t < 0.9f) abs(sin(PI.toFloat() * t / 0.45f)) else 0f
            Pose(Silhouette(cue.body, sx = 1 + 0.02f * hop, sy = 1 - 0.02f * hop), HAPPY, offY = -0.05f * hop)
        }

        State.PROUD -> {
            // The triangle tumbles round, rings come in one by one, then it relaxes into Buddy's own body.
            val ramp = easeInOutCubic(clamp01(t / 0.35f))
            val rot = -TAU * 1.1f * t * ramp
            val back = easeInOutCubic(clamp01((t - 1.6f) / 0.9f))
            val tri = spinningTriangle(rot)
            val sil = Silhouette(FloatArray(Silhouette.SAMPLES) { lerp(tri.radii[it], Silhouette.radiusAt(cue.body, Silhouette.angle(it) + rot), back) }, rot, tri.cx * (1 - back), tri.cy * (1 - back))
            val fade = clamp01(t / 0.8f) * clamp01((3.6f - t) / 0.9f)
            Pose(
                sil,
                PROUD.copy(gaze = Gaze(PROUD.gaze.yaw + sin(t * 6.5f) * 60f * (1 - back), -4f + back * (PROUD.gaze.pitch + 4f), PROUD.gaze.roll)),
                arcs = Decor.RINGS.mapIndexed { i, s -> ArcSpec(s, t, fade * clamp01((t - i * 0.13f) / 0.3f)) },
            )
        }

        State.EXCITED -> {
            // Collapses into a small core that swallows five sparks, then grows back.
            val collapse = 1 - 0.83f * easeOutQuint(clamp01(t / 0.7f))
            val regrow = easeOutQuint(clamp01((t - 1.7f) / 0.7f))
            val k = collapse + (1 - collapse) * regrow
            Pose(Silhouette(FloatArray(Silhouette.SAMPLES) { lerp(1f, cue.body[it], regrow) * k }), EXCITED, faceAlpha = clamp01((t - 1.85f) / 0.4f), dots = Decor.particles(t), dotsBehind = true)
        }

        State.LAUGH -> {
            // Shrinks to a dot that wobbles while ribbons orbit it, then grows back laughing.
            val collapse = 1 - (1 - COMET_DOT) * easeOutQuint(clamp01(t / 0.55f))
            val regrow = easeOutQuint(clamp01((t - 1.85f) / 0.6f))
            val k = collapse + (1 - collapse) * regrow
            val fade = clamp01((t - 0.15f) / 0.25f) * clamp01((1.95f - t) / 0.3f)
            Pose(
                Silhouette(FloatArray(Silhouette.SAMPLES) { lerp(1f, cue.body[it], regrow) * k }, cy = sin(clamp01(t / 1.7f) * PI.toFloat()) * 0.035f),
                LAUGH,
                faceAlpha = clamp01((t - 2f) / 0.35f),
                arcs = Decor.COMET.map { ArcSpec(it, t, fade) },
            )
        }

        State.LOVE -> {
            val p = clamp01(t / 0.45f)
            val pop = 1 + (Decor.BADGE_POP - 1) * sin(p * PI.toFloat()) * (1 - p * 0.35f)
            Pose(Silhouette(cue.body), LOVE, badge = BadgeSpec(Decor.BADGE_R * 1.1f * (if (p < 1f) pop else 1f), heart = true))
        }

        State.CURIOUS -> Pose(Silhouette(EGG), CURIOUS)

        State.CONFUSED -> Pose(Silhouette(HEXAGON), CONFUSED)

        State.SURPRISED ->
            Pose(Silhouette(BAR_UPRIGHT, cy = BAR_CY), cue.rest, faceAlpha = 0f, dots = listOf(Dot(0f, 0.52f, 0.11f, 1f)))

        State.SAD -> Pose(Silhouette(cue.body, sy = 0.97f), SAD, offY = 0.03f)

        State.SLEEPY ->
            // Buddy shrinks to a dot bouncing gently.
            Pose(Silhouette.circle(0.16f, cy = 0.11f + sin(t * (TAU / 0.6f)) * 0.19f), cue.rest, faceAlpha = 0f)

        State.PLAY -> {
            // A play triangle, and a swoosh sweeping over it from right to left.
            val fade = clamp01(t / 0.35f) * clamp01((2.2f - t) / 0.5f)
            Pose(spinningTriangle(0f), PLAY, arcs = Decor.SWOOSH.map { ArcSpec(it.copy(cx = 0.45f - t * 0.42f), t, fade) })
        }
    }

    /** How small the comet's dot gets. */
    private const val COMET_DOT = 0.13f
}
