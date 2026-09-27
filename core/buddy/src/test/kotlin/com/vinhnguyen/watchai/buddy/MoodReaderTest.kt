package com.vinhnguyen.watchai.buddy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoodReaderTest {
    private fun said(text: String) = MoodReader.assistant(text)?.mood

    private fun heard(text: String) = MoodReader.user(text)?.mood

    @Test
    fun `what Buddy says sets the obvious mood`() {
        assertThat(said("Done! Your timer is set for three minutes.")).isEqualTo(Mood.PROUD)
        assertThat(said("Haha, that's a good one.")).isEqualTo(Mood.LAUGH)
        assertThat(said("Sorry, I couldn't set the alarm.")).isEqualTo(Mood.OOPS)
        assertThat(said("Sorry, I didn't catch that.")).isEqualTo(Mood.CONFUSED)
        assertThat(said("Congratulations on the new job!")).isEqualTo(Mood.EXCITED)
        assertThat(said("Good night, sleep well.")).isEqualTo(Mood.SLEEPY)
        assertThat(said("Hello! How are you?")).isEqualTo(Mood.GREET)
        assertThat(said("Unfortunately it will rain all day.")).isEqualTo(Mood.SAD)
    }

    @Test
    fun `a question makes Buddy curious when nothing else fits`() {
        assertThat(said("How long should the timer run?")).isEqualTo(Mood.CURIOUS)
        assertThat(said("It's 21 degrees in Aachen.")).isNull()
    }

    @Test
    fun `words only match at a word start`() {
        assertThat(said("The team was upset after the match.")).isNull()
        assertThat(said("It's a lollipop.")).isNull()
    }

    @Test
    fun `German and Vietnamese cues work too`() {
        assertThat(said("Leider regnet es morgen.")).isEqualTo(Mood.SAD)
        assertThat(said("Erledigt, der Timer läuft.")).isEqualTo(Mood.PROUD)
        assertThat(heard("Cảm ơn bạn")).isEqualTo(Mood.LOVE)
        assertThat(heard("Danke dir")).isEqualTo(Mood.LOVE)
    }

    @Test
    fun `thanks and greetings from the user`() {
        assertThat(heard("Thanks a lot!")).isEqualTo(Mood.LOVE)
        assertThat(heard("Hey Buddy, what's up")).isEqualTo(Mood.GREET)
        assertThat(heard("Set a timer for ten minutes")).isNull()
    }

    @Test
    fun `wire names round-trip`() {
        Mood.entries.forEach { assertThat(Mood.of(it.wire)).isEqualTo(it) }
        assertThat(Mood.of("furious")).isNull()
        assertThat(Mood.of(null)).isNull()
    }
}
