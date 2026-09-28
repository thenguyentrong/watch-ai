package com.vinhnguyen.watchai.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevellerTest {
    @Test
    fun `quiet speech is brought up to the target`() {
        val leveller = Leveller()
        val gain = leveller.gain(level = 0.01f, sound = true)
        assertThat(0.01f * gain).isWithin(0.001f).of(Leveller.TARGET)
    }

    @Test
    fun `loud speech is never turned down`() {
        assertThat(Leveller().gain(level = 0.4f, sound = true)).isEqualTo(1f)
    }

    @Test
    fun `nothing is raised more than the most it may`() {
        val leveller = Leveller()
        assertThat(leveller.gain(level = 0.0001f, sound = true)).isEqualTo(Leveller.MAX_GAIN)
        // Before any sound at all, too.
        assertThat(Leveller().gain(level = 0f, sound = false)).isEqualTo(Leveller.MAX_GAIN)
    }

    @Test
    fun `the gain holds through a sentence and recovers after a loud sound`() {
        val leveller = Leveller()
        val first = leveller.gain(level = 0.02f, sound = true)
        // Quieter syllables right after don't pump it up.
        assertThat(leveller.gain(level = 0.005f, sound = true)).isWithin(0.3f).of(first)
        // A door slam holds it down, but not for good.
        assertThat(leveller.gain(level = 0.5f, sound = true)).isEqualTo(1f)
        repeat(200) { leveller.gain(level = 0.001f, sound = false) }
        assertThat(leveller.gain(level = 0.02f, sound = true)).isWithin(0.5f).of(first)
    }

    @Test
    fun `a whole recording is brought to the target by its loudest part`() {
        assertThat(0.02f * Leveller.gainFor(0.02f)).isWithin(0.001f).of(Leveller.TARGET)
        assertThat(Leveller.gainFor(0.3f)).isEqualTo(1f)
    }

    @Test
    fun `after a reset it starts over`() {
        val leveller = Leveller()
        leveller.gain(level = 0.5f, sound = true)
        leveller.reset()
        assertThat(leveller.gain(level = 0.01f, sound = true)).isWithin(0.1f).of(10f)
    }
}
