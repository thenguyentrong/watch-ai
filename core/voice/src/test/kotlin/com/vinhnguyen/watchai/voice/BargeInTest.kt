package com.vinhnguyen.watchai.voice

import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.voice.BargeIn.Action
import org.junit.Test

class BargeInTest {
    /** Feeds 50 ms samples from [from] until [to] and returns the actions with their times. */
    private fun BargeIn.feed(
        from: Long,
        to: Long,
        user: Double,
        speaking: Boolean = true,
    ): List<Pair<Long, Action>> = (from until to step 50).mapNotNull { t -> sample(t, user, speaking)?.let { t to it } }

    @Test
    fun `talking over the answer mutes it after 200 ms`() {
        val b = BargeIn()
        assertThat(b.feed(0, 1_000, user = 0.0)).isEmpty()
        val actions = b.feed(1_000, 1_400, user = 0.3)
        assertThat(actions).containsExactly(1_200L to Action.MUTE)
        assertThat(b.lastMuteAfterMs).isEqualTo(200)
    }

    @Test
    fun `a short sound does not mute`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        assertThat(b.feed(1_000, 1_150, user = 0.3) + b.feed(1_150, 2_000, user = 0.0)).isEmpty()
    }

    @Test
    fun `speech with short dips between words still mutes`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        val actions =
            b.feed(1_000, 1_100, user = 0.3) +
                b.feed(1_100, 1_150, user = 0.01) +
                b.feed(1_150, 1_400, user = 0.3)
        assertThat(actions).containsExactly(1_200L to Action.MUTE)
    }

    @Test
    fun `a quieter voice than before is still heard`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        // 0.07 is below the old 0.08 threshold, which missed the interruption in the 27.09 test.
        assertThat(b.feed(1_000, 1_400, user = 0.07)).containsExactly(1_200L to Action.MUTE)
    }

    @Test
    fun `nobody is muted in the first moments of an answer`() {
        val b = BargeIn()
        assertThat(b.feed(0, 400, user = 0.3)).isEmpty()
    }

    @Test
    fun `when the answer stops, the next one plays again`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        b.feed(1_000, 1_300, user = 0.3)
        assertThat(b.muted).isTrue()
        assertThat(b.feed(1_300, 1_400, user = 0.3, speaking = false)).containsExactly(1_300L to Action.UNMUTE)
        assertThat(b.muted).isFalse()
    }

    @Test
    fun `a false alarm unmutes after a second of quiet`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        b.feed(1_000, 1_300, user = 0.3) // muted at 1200
        assertThat(b.feed(1_300, 2_400, user = 0.0)).containsExactly(2_300L to Action.UNMUTE)
    }

    @Test
    fun `a leaking answer does not silence itself`() {
        val b = BargeIn()
        // The echo canceller lets 0.1 of the answer through the whole time.
        assertThat(b.feed(0, 5_000, user = 0.1)).isEmpty()
        // The user is clearly louder than the leak.
        assertThat(b.feed(5_000, 5_300, user = 0.4)).containsExactly(5_200L to Action.MUTE)
    }

    @Test
    fun `a mute never lasts longer than three seconds`() {
        val b = BargeIn()
        b.feed(0, 1_000, user = 0.0)
        val actions = b.feed(1_000, 6_000, user = 0.3)
        assertThat(actions).containsExactly(1_200L to Action.MUTE, 4_200L to Action.UNMUTE).inOrder()
    }
}
