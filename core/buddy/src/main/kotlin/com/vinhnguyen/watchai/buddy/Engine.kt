package com.vinhnguyen.watchai.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Where the conversation is: what Buddy does when no reaction plays. */
public enum class Act { REST, AWAKE, CONNECT, LISTEN, THINK, SPEAK, ERROR }

/** What Buddy is doing: the conversation's [act] since [actAt], and a [reaction] since [reactionAt] (seconds). */
public data class Scene(
    val act: Act,
    val actAt: Double = 0.0,
    val reaction: Reaction? = null,
    val reactionAt: Double = 0.0,
)

/**
 * An eye or the mouth, cut out of the body, and how far it shows. It is drawn in its own frame
 * (a pill [w] by [h] centred on the origin, or the [band] of a mouth or a squinting eye), then
 * mapped to body units: x' = a x + c y + e, y' = b x + d y + f. That keeps a frame to a handful
 * of numbers, and lets a renderer draw a rounded rect or one curve under a transform instead of
 * building outlines point by point.
 */
public class Feature(
    public val w: Float,
    public val h: Float,
    public val band: Mouth?,
    public val a: Float,
    public val b: Float,
    public val c: Float,
    public val d: Float,
    public val e: Float,
    public val f: Float,
    public val alpha: Float,
) {
    /** The outline in body units, as points (x, y interleaved). */
    public fun outline(): FloatArray {
        val local = band?.let { Face.mouth(it) } ?: Face.pill(w, h)
        return FloatArray(local.size) { i ->
            val x = local[i - i % 2]
            val y = local[i - i % 2 + 1]
            if (i % 2 == 0) a * x + c * y + e else b * x + d * y + f
        }
    }
}

/**
 * One picture of Buddy in body units: 1 is the radius of the ball at rest, (0, 0) its centre,
 * y points down. Renderers only draw this, in order: [arcs] behind, [dots] if [dotsBehind], the
 * [body] with its [features] and the [badge] gap cut out, [dots] in front, the [badge], [arcs] in front.
 */
public class BuddyFrame(
    /** [Silhouette.SAMPLES] outline points, x and y interleaved; draw as a smooth closed curve. */
    public val body: FloatArray,
    public val features: List<Feature>,
    public val dots: List<Dot>,
    public val dotsBehind: Boolean,
    public val arcs: List<Arc>,
    public val badge: Badge?,
)

/**
 * Buddy's animation, a port of bloub's engine by Jérémy Perret (MIT, github.com/jeremy-prt/bloub,
 * src/bot/engine.ts): no clock inside, [sample] is a pure function of the time it is given and
 * of the state changes set before, so pausing, jumping or freezing always gives the same picture.
 * A change blends from what was on screen, frozen if it lands in the middle of another blend.
 *
 * With [still] (the system's animations are off) there is no drift, breathing or blinking, each
 * state shows its most readable moment and changes are instant.
 */
public class BuddyEngine(
    private val genes: Genes,
    private val still: Boolean = false,
) {
    private var cur = State.IDLE
    private var prev: State? = null
    private var frozen: Pose? = null
    private var tCur = 0.0
    private var tPrev = 0.0
    private var blinkAt = -10.0

    internal val state: State get() = cur

    /** Starts over on [state] with nothing to blend from, as a new engine would. */
    internal fun reset(
        state: State,
        now: Double,
    ) {
        cur = state
        prev = null
        frozen = null
        tCur = now
        tPrev = now
        blinkAt = -10.0
    }

    /** Switches to [state] at [now]; a change mid-blend starts from the blended picture, so nothing jumps. */
    internal fun setState(
        state: State,
        now: Double,
    ) {
        if (state == cur) return
        val midBlend = prev != null && now - tCur < cur.morph
        frozen = if (midBlend) composed(now, 0f) else null
        prev = cur
        tPrev = tCur
        cur = state
        tCur = now
        if (state.blinkIn) blinkAt = now
    }

    private fun posed(
        state: State,
        since: Double,
        level: Float,
    ): Pose = state.pose(if (still) state.readable else maxOf(0.0, since).toFloat(), Cue(genes.body, genes.face, level))

    /** The blended pose at [now], before the life at rest. */
    private fun composed(
        now: Double,
        level: Float,
    ): Pose {
        val since = now - tCur
        val pose = posed(cur, since, level)
        if (still || since >= cur.morph) return pose
        val from = frozen ?: prev?.let { posed(it, now - tPrev, level) } ?: return pose
        return Pose.blend(from, pose, easeOutQuint(clamp01((since / cur.morph).toFloat())))
    }

    /** Buddy at [now], with the voice [level] (0..1) for the mouth and the listening lean. */
    public fun sample(
        now: Double,
        level: Float = 0f,
    ): BuddyFrame {
        val pose = composed(now, level.coerceIn(0f, 1f))
        val alive = pose.faceAlpha > 0.01f
        val life = Face.life(now, if (alive) 1f else 0f, alive, still)
        val gaze = pose.face.gaze.let { Gaze(it.yaw + life.dYaw, it.pitch + life.dPitch, it.roll + life.dRoll) }
        // A blink forced by the state change, on top of the schedule.
        val forced = clamp01(((now - blinkAt) / 0.2).toFloat())
        val lid = if (still) 1f else min(life.lid, if (forced < 1f) abs(forced * 2 - 1) else 1f)
        val offX = pose.offX + life.driftX
        val offY = pose.offY + life.driftY
        val sil = pose.sil.copy(cx = pose.sil.cx + offX, cy = pose.sil.cy + offY, sy = pose.sil.sy * life.breath)

        // Features live on a unit sphere; on other outlines they move in or out with the radius in their direction.
        fun fit(
            x: Float,
            y: Float,
        ) = Silhouette.radiusAt(pose.sil.radii, atan2(y, x) - pose.sil.rot)

        val features = ArrayList<Feature>(3)
        if (alive) {
            val (inner, outer, mouth) = Face.spots(gaze, pose.face.split, pose.face.drop)
            for ((spot, eye) in listOf(inner to pose.face.eyes.first, outer to pose.face.eyes.second)) {
                if (spot.depth <= 0.02f) continue
                val k = fit(spot.x, spot.y)
                val squash = Face.blinkScale(min(lid, eye.open))
                features += Face.feature(spot, eye.tilt, squash, spot.x * k + offX, spot.y * k + offY, eye.w, eye.h, Face.band(eye), pose.faceAlpha * Face.facing(spot.depth))
            }
            if (mouth.depth > 0.02f) {
                val k = fit(mouth.x, mouth.y)
                val m = pose.face.mouth
                features += Face.feature(mouth, m.tilt, 1f, mouth.x * k + offX, mouth.y * k + offY, m.w, m.thick, m, pose.faceAlpha * Face.facing(mouth.depth))
            }
        }

        val badge = pose.badge?.let { b ->
            val a = Decor.BADGE_ANGLE * PI.toFloat() / 180f
            val k = fit(cos(a), sin(a))
            Badge(cos(a) * k + offX, sin(a) * k + offY, b.r, Decor.BADGE_GAP, b.heart)
        }

        return BuddyFrame(
            body = sil.points(),
            features = features,
            dots = pose.dots.filter { it.opacity > 0.01f && it.r > 0.0005f }.map { Dot(it.x + offX, it.y + offY, it.r, it.opacity, it.depth, it.shape, it.rot) },
            dotsBehind = pose.dotsBehind,
            arcs = pose.arcs.filter { it.opacity > 0.01f }.map { Decor.arc(it.seed, it.t, it.opacity, genes.color.hue) },
            badge = badge,
        )
    }
}

/**
 * Turns scenes into engine states: the reaction while it plays, else the conversation. Each
 * change is set at the moment it really happened (not when the next frame came), so the
 * picture doesn't depend on the frame rate.
 */
public class Director(
    genes: Genes,
    still: Boolean = false,
) {
    private val engine = BuddyEngine(genes, still)
    private var started = false

    /** The frame at [t]; with [blend] off (a frozen view, where time doesn't move) the state as if it had always been on. */
    public fun frame(
        scene: Scene,
        level: Float,
        t: Double,
        blend: Boolean = true,
    ): BuddyFrame {
        val (state, since) = target(scene, t)
        if (!blend || !started) {
            engine.reset(state, since)
            started = true
        } else {
            engine.setState(state, since)
        }
        return engine.sample(t, level)
    }

    internal companion object {
        /** What plays at [t] and since when. */
        fun target(
            scene: Scene,
            t: Double,
        ): Pair<State, Double> {
            val act = State.of(scene.act)
            val mood = scene.reaction?.let { State.of(it.mood) } ?: return act to scene.actAt
            val end = scene.reactionAt + mood.hold
            return when {
                t < scene.reactionAt -> act to scene.actAt
                t < end -> mood to scene.reactionAt
                else -> act to maxOf(scene.actAt, end)
            }
        }
    }
}
