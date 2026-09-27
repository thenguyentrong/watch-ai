package com.vinhnguyen.watchai.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceLogicTest {
    private fun run(
        timer: TurnTimer,
        from: Long,
        to: Long,
        user: Double,
        assistant: Double,
    ): List<TurnTimer.Event> = (from until to step 50).mapNotNull { timer.sample(it, user, assistant) }

    @Test
    fun `turn latency runs from the end of the user's speech to the first sound of the answer`() {
        val timer = TurnTimer()
        val events =
            run(timer, 0, 1_000, user = 0.3, assistant = 0.0) +
                run(timer, 1_000, 1_800, user = 0.0, assistant = 0.0) +
                run(timer, 1_800, 2_500, user = 0.0, assistant = 0.4)
        assertThat(events).containsExactly(TurnTimer.Event.Turn(800))
    }

    @Test
    fun `talking over the answer is measured until the answer goes quiet`() {
        val timer = TurnTimer()
        run(timer, 0, 500, user = 0.3, assistant = 0.0)
        run(timer, 500, 1_000, user = 0.0, assistant = 0.0)
        run(timer, 1_000, 2_000, user = 0.0, assistant = 0.4)
        // User starts talking at 2000 while the answer still plays; the answer stops at 2300.
        val events =
            run(timer, 2_000, 2_300, user = 0.3, assistant = 0.4) +
                run(timer, 2_300, 3_000, user = 0.3, assistant = 0.0)
        assertThat(events).containsExactly(TurnTimer.Event.Interrupt(300))
    }

    @Test
    fun `quiet noise never counts as a turn`() {
        val timer = TurnTimer()
        assertThat(run(timer, 0, 3_000, user = 0.01, assistant = 0.0)).isEmpty()
    }

    @Test
    fun `answers are spoken sentence by sentence`() {
        val splitter = SentenceSplitter()
        val pieces = mutableListOf<String>()
        "Red, yellow, and blue. Those are the primary colours! Want more?".chunked(7).forEach { pieces += splitter.add(it) }
        pieces += listOfNotNull(splitter.flush())
        assertThat(pieces).containsExactly("Red, yellow, and blue.", "Those are the primary colours!", "Want more?").inOrder()
    }

    @Test
    fun `very short sentences wait for more text`() {
        val splitter = SentenceSplitter()
        assertThat(splitter.add("Yes. ")).isEmpty()
        assertThat(splitter.add("It is prime because nothing divides it. ")).containsExactly("Yes. It is prime because nothing divides it.")
    }

    @Test
    fun `a long first sentence starts speaking at its first comma`() {
        val splitter = SentenceSplitter()
        val pieces = mutableListOf<String>()
        "The capital of Australia is Canberra, not Sydney, which many people think. Sydney is bigger, though."
            .chunked(5)
            .forEach { pieces += splitter.add(it) }
        pieces += listOfNotNull(splitter.flush())
        assertThat(pieces)
            .containsExactly("The capital of Australia is Canberra,", "not Sydney, which many people think.", "Sydney is bigger, though.")
            .inOrder()
    }

    @Test
    fun `numbers with thousands separators are not cut`() {
        val splitter = SentenceSplitter()
        val out = splitter.add("The stadium holds exactly 100,000 people on match days. ")
        assertThat(out).containsExactly("The stadium holds exactly 100,000 people on match days.")
    }

    @Test
    fun `long runs without punctuation are cut at a comma`() {
        val splitter = SentenceSplitter(maxChars = 40)
        val out = splitter.add("one two three four five six, seven eight nine ten eleven twelve")
        assertThat(out.first()).isEqualTo("one two three four five six,")
    }
}
