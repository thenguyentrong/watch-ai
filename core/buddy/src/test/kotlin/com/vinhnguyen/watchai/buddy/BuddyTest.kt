package com.vinhnguyen.watchai.buddy

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class BuddyTest {
    @Test
    fun `the same user always gets the same Buddy, other users mostly others`() {
        assertThat(Genes.forUser("account-123")).isEqualTo(Genes.forUser("account-123"))
        val many = (1..200).map { Genes.forUser("account-$it") }
        // Shape, colour and resting face alone give 8 x 12 x 8 combinations; the nudges are continuous.
        assertThat(many.map { listOf(it.shape, it.color, it.face) }.toSet().size).isEqualTo(200)
        assertThat(many.map { listOf(it.shape, it.color) }.toSet().size).isAtLeast(60)
        assertThat(many.map { it.shape }.toSet()).hasSize(BodyShape.entries.size)
        assertThat(many.map { it.color }.toSet()).hasSize(Tint.entries.size)
    }

    @Test
    fun `a seed gives the same Buddy forever`() {
        // Pinned: if this changes, every user's Buddy changes. Only add new traits at the end of Genes.of.
        val genes = Genes.of(42L)
        assertThat(listOf(genes.shape, genes.color, genes.rest)).isEqualTo(PINNED_42)
        assertThat(genes.face.gaze.yaw).isWithin(1e-4f).of(PINNED_42_YAW)
        assertThat(Genes.seedFor("account-123")).isNotEqualTo(Genes.seedFor("account-124"))
    }

    @Test
    fun `every resting body is about the size of the ball`() {
        BodyShape.entries.forEach { shape ->
            assertThat(shape.radii.max()).isIn(Range.closed(1f, 1.16f))
            assertThat(shape.radii.min()).isGreaterThan(0.65f)
        }
    }

    @Test
    fun `outlines morph point for point`() {
        val a = Silhouette(BodyShape.PEBBLE.radii)
        val b = Silhouette(BodyShape.CLOUD.radii, rot = 0.3f)
        assertThat(Silhouette.blend(a, b, 0f)).isEqualTo(a)
        assertThat(Silhouette.blend(a, b, 1f)).isEqualTo(b)
        assertThat(Silhouette.blend(a, b, 0.5f).radii[10]).isWithin(1e-6f).of((a.radii[10] + b.radii[10]) / 2)
        assertThat(Silhouette.radiusAt(a.radii, Silhouette.angle(10))).isWithin(1e-5f).of(a.radii[10])
        // The short way round: from +170 to -170 degrees passes 180, not 0.
        val turned = Silhouette.blend(Silhouette.circle(1f, rot = 2.967f), Silhouette.circle(1f, rot = -2.967f), 0.5f)
        assertThat(kotlin.math.abs(turned.rot)).isWithin(1e-3f).of(3.1416f)
    }

    @Test
    fun `a frame is a pure function of time and the changes before it`() {
        fun run(t: Double): BuddyFrame {
            val engine = BuddyEngine(Genes.of(7))
            engine.reset(State.SPEAK, 1.0)
            engine.setState(State.EXCITED, 2.0)
            return engine.sample(t, 0.5f)
        }
        listOf(1.5, 2.1, 2.45, 3.9, 30.0).forEach { t ->
            val a = run(t)
            val b = run(t)
            assertThat(a.body).isEqualTo(b.body)
            assertThat(a.features.map { it.outline().toList() }).isEqualTo(b.features.map { it.outline().toList() })
            assertThat(a.dots.map { listOf(it.x, it.y, it.r) }).isEqualTo(b.dots.map { listOf(it.x, it.y, it.r) })
        }
        assertThat(run(2.4).body).isNotEqualTo(run(2.5).body)
    }

    @Test
    fun `reactions play for their own time, then Buddy goes back to the conversation`() {
        val scene = Scene(Act.SPEAK, actAt = 1.0, reaction = Reaction(Mood.LOVE, 0.7f), reactionAt = 10.0)
        assertThat(Director.target(scene, 9.0)).isEqualTo(State.SPEAK to 1.0)
        assertThat(Director.target(scene, 11.0)).isEqualTo(State.LOVE to 10.0)
        assertThat(Director.target(scene, 10.0 + State.LOVE.hold + 0.1)).isEqualTo(State.SPEAK to 10.0 + State.LOVE.hold)
        assertThat(Director.target(scene.copy(reaction = Reaction(Mood.NEUTRAL, 0.5f)), 11.0)).isEqualTo(State.SPEAK to 1.0)
        Mood.entries.forEach { assertThat(Mood.of(it.wire)).isEqualTo(it) }
    }

    @Test
    fun `every state draws, with a face or without one`() {
        val genes = Genes.of(11)
        State.entries.forEach { state ->
            val engine = BuddyEngine(genes)
            engine.reset(state, 0.0)
            listOf(0.0, 0.5, 1.2, 2.5).forEach { t ->
                val frame = engine.sample(t, 0.6f)
                assertThat(frame.body.size).isEqualTo(Silhouette.SAMPLES * 2)
                assertThat(frame.body.all { it.isFinite() }).isTrue()
                frame.features.forEach { f -> assertThat(f.outline().all { it.isFinite() }).isTrue() }
            }
        }
        val faceless = listOf(State.THINK, State.ERROR, State.SURPRISED, State.SLEEPY)
        faceless.forEach { state ->
            val engine = BuddyEngine(genes)
            engine.reset(state, 0.0)
            assertThat(engine.sample(1.0).features).isEmpty()
        }
        val idle = BuddyEngine(genes).apply { reset(State.IDLE, 0.0) }.sample(1.0)
        assertThat(idle.features).hasSize(3)
    }

    @Test
    fun `eyes and mouth sit inside every resting body`() {
        BodyShape.entries.forEach { shape ->
            RestFace.entries.forEach { face ->
                val genes = Genes(0, shape, Tint.MILK, face)
                val engine = BuddyEngine(genes).apply { reset(State.IDLE, 0.0) }
                // Across the gaze drift, sampled over a minute.
                (0 until 120).forEach { i ->
                    val frame = engine.sample(i * 0.5)
                    frame.features.forEach { feature ->
                        val points = feature.outline()
                        var n = 0
                        var outside = 0
                        while (n < points.size) {
                            val x = points[n]
                            val y = points[n + 1]
                            if (hypot(x, y) > Silhouette.radiusAt(shape.radii, atan2(y, x)) * 0.97f) outside++
                            n += 2
                        }
                        assertThat(outside).isEqualTo(0)
                    }
                }
            }
        }
    }

    @Test
    fun `blinks are short and never close the eyes for long`() {
        val shut = (0 until 20_000).count { Face.life(it * 0.01, 1f, blink = true, still = false).lid < 0.5f }
        // About 0.09 s below half open every 3.2 s on average: a few percent of the time.
        assertThat(shut).isIn(Range.closed(200, 1_200))
        assertThat(Face.life(0.5, 1f, blink = true, still = false).lid).isEqualTo(1f)
    }

    @Test
    fun `a change blends from what was on screen`() {
        val director = Director(Genes.of(3))
        val before = director.frame(Scene(Act.REST), 0f, 5.0)
        val right = director.frame(Scene(Act.THINK, actAt = 5.0), 0f, 5.01)
        // Right after the change it is still close to the old picture, not jumping to the dots
        // (the body shrinks from 1 to 0.16: the ease-out starts fast, but not in one step).
        assertThat(maxGap(before.body, right.body)).isLessThan(0.15f)
        val settled = director.frame(Scene(Act.THINK, actAt = 5.0), 0f, 7.0)
        assertThat(settled.body.maxOf { kotlin.math.abs(it) }).isLessThan(0.4f)
        // A change in the middle of a blend starts from the blended picture, not from the full old state.
        director.frame(Scene(Act.REST, actAt = 8.0), 0f, 8.01)
        val halfWay = director.frame(Scene(Act.REST, actAt = 8.0), 0f, 8.029)
        val next = director.frame(Scene(Act.LISTEN, actAt = 8.03), 0f, 8.031)
        assertThat(maxGap(halfWay.body, next.body)).isLessThan(0.1f)
    }

    @Test
    fun `with animations off Buddy holds still`() {
        val engine = BuddyEngine(Genes.of(5), still = true).apply { reset(State.IDLE, 0.0) }
        assertThat(engine.sample(1.0).body).isEqualTo(engine.sample(7.3).body)
        engine.setState(State.THINK, 8.0)
        assertThat(engine.sample(8.01).dots).hasSize(2)
    }

    @Test
    fun `rings take the Buddy's own colour`() {
        val genes = Genes.of(21)
        val engine = BuddyEngine(genes).apply { reset(State.PROUD, 0.0) }
        val arcs = engine.sample(1.2).arcs
        assertThat(arcs).isNotEmpty()
        arcs.flatMap { it.colors.toList() }.forEach { argb ->
            assertThat(hueGap(hueOf(argb), genes.color.hue)).isLessThan(60f)
        }
    }

    private fun maxGap(
        a: FloatArray,
        b: FloatArray,
    ): Float = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }

    private fun hueOf(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        val h = when (max) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return (h + 360) % 360
    }

    private fun hueGap(
        a: Float,
        b: Float,
    ): Float = kotlin.math.abs(((a - b) % 360 + 540) % 360 - 180)

    private companion object {
        val PINNED_42: List<Any> = listOf(BodyShape.SQUIRCLE, Tint.LILAC, RestFace.BRIGHT)
        const val PINNED_42_YAW = 6.4419065f
    }
}
