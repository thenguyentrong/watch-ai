package com.vinhnguyen.watchai.buddy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuddyTest {
    @Test
    fun `the same user always gets the same Buddy, other users mostly others`() {
        assertThat(Genes.forUser("account-123")).isEqualTo(Genes.forUser("account-123"))
        val many = (1..200).map { Genes.forUser("account-$it") }
        // Shape, colour, eyes, top and marks alone give 8 x 12 x 4 x 7 x 3 combinations; the rest is continuous.
        assertThat(many.map { listOf(it.shape, it.color, it.eyes, it.top, it.mark) }.toSet().size).isAtLeast(150)
        assertThat(many.map { it.shape }.toSet()).hasSize(Genes.Shape.entries.size)
        assertThat(many.map { it.color }.toSet()).hasSize(Genes.Palette.entries.size)
    }

    @Test
    fun `a seed gives the same Buddy forever`() {
        // Pinned: if this changes, every user's Buddy changes. Only add new traits at the end of Genes.of.
        val genes = Genes.of(42L)
        assertThat(listOf(genes.shape, genes.color, genes.eyes, genes.top, genes.mark, genes.cheeks)).isEqualTo(PINNED_42)
        assertThat(Genes.seedFor("account-123")).isEqualTo(Genes.seedFor("account-123"))
        assertThat(Genes.seedFor("account-123")).isNotEqualTo(Genes.seedFor("account-124"))
    }

    @Test
    fun `every body fits in the unit circle and touches it`() {
        (1L..100L).forEach { seed ->
            val body = Profile.of(Genes.of(seed))
            assertThat(body.radii.max()).isWithin(1e-5f).of(1f)
            assertThat(body.radii.min()).isGreaterThan(0.6f)
        }
    }

    @Test
    fun `shapes morph point for point`() {
        val a = Profile.of(Genes.of(1))
        val b = Profile.of(Genes.of(2))
        assertThat(Profile.lerp(a, b, 0f).radii).isEqualTo(a.radii)
        assertThat(Profile.lerp(a, b, 1f).radii).isEqualTo(b.radii)
        val mid = Profile.lerp(a, b, 0.5f)
        assertThat(mid.radii[10]).isWithin(1e-6f).of((a.radii[10] + b.radii[10]) / 2)
        assertThat(a.radiusAt(Profile.angle(10))).isWithin(1e-5f).of(a.radii[10])
    }

    @Test
    fun `a frame is a pure function of time`() {
        val genes = Genes.of(7)
        val scene = Scene(Act.SPEAK, actAt = 1.0, reaction = Reaction(Mood.EXCITED, 0.8f), reactionAt = 2.0)
        val t = 2.4
        assertThat(Engine.frame(genes, scene, 0.5f, t)).isEqualTo(Engine.frame(genes, scene, 0.5f, t))
        val later = Engine.frame(genes, scene, 0.5f, t + 0.1)
        assertThat(later).isNotEqualTo(Engine.frame(genes, scene, 0.5f, t))
    }

    @Test
    fun `reactions show their face, then Buddy goes back to the conversation`() {
        val genes = Genes.of(7)
        val scene = Scene(Act.AWAKE, reaction = Reaction(Mood.LOVE, 0.7f), reactionAt = 10.0)
        assertThat(Engine.frame(genes, scene, 0f, 11.0).eyes).isEqualTo(EyeShape.HEARTS)
        assertThat(Engine.frame(genes, scene, 0f, 11.0).effect).isEqualTo(Effect.HEARTS)
        assertThat(Engine.frame(genes, scene, 0f, 10.0 + Engine.REACTION_S + 0.1).eyes).isEqualTo(EyeShape.OPEN)
    }

    @Test
    fun `blinks are short and never close the eyes for long`() {
        val closedSamples = (0 until 10_000).count { Engine.blink(9L, it * 0.01) < 0.5f }
        // About 0.16 s of blink every 3.7 s: a few percent of the time.
        assertThat(closedSamples).isIn(com.google.common.collect.Range.closed(50, 600))
    }

    @Test
    fun `the director blends a scene change from what was on screen`() {
        val director = Director(Genes.of(3))
        val before = director.frame(Scene(Act.AWAKE), 0f, 5.0)
        val right = director.frame(Scene(Act.LISTEN, actAt = 5.01), 1f, 5.01)
        // Right after the change it's still close to the old frame, not jumping to the new pose.
        assertThat(kotlin.math.abs(right.dy - before.dy)).isLessThan(0.01f)
        val settled = director.frame(Scene(Act.LISTEN, actAt = 5.01), 1f, 6.0)
        assertThat(settled.dy).isWithin(1e-4f).of(0.03f)
    }

    @Test
    fun `outfits keep one item per slot and survive the wire`() {
        val outfit = Outfit.of(listOf(Item.CAP, Item.JERSEY, Item.BEANIE))
        assertThat(outfit[Slot.HEAD]).isEqualTo(Item.BEANIE)
        assertThat(Outfit.parse(outfit.wire)).isEqualTo(outfit)
        assertThat(Outfit.parse("cap,unknown_future_item,book")).isEqualTo(Outfit.of(listOf(Item.CAP, Item.BOOK)))
        assertThat(Outfit.parse(null)).isEqualTo(Outfit.NONE)
        Look.entries.forEach { look -> assertThat(look.outfit.items.values).containsExactlyElementsIn(look.items) }
    }

    private companion object {
        val PINNED_42: List<Any> = listOf(Genes.Shape.PEBBLE, Genes.Palette.SLATE, Genes.EyeStyle.DOT, Genes.Top.EARS, Genes.Mark.NONE, true)
    }
}
