package com.vinhnguyen.watchai.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ActionArgsTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `local times, times with a zone, and plain dates are understood`() {
        assertThat(ActionArgs.time("2026-09-28T15:00", berlin)).isEqualTo(ActionArgs.When.At(LocalDateTime.of(2026, 9, 28, 15, 0)))
        assertThat(ActionArgs.time("2026-09-28 15:00:30", berlin)).isEqualTo(ActionArgs.When.At(LocalDateTime.of(2026, 9, 28, 15, 0, 30)))
        // 13:00 UTC is 15:00 in Berlin in summer.
        assertThat(ActionArgs.time("2026-09-28T13:00Z", berlin)).isEqualTo(ActionArgs.When.At(LocalDateTime.of(2026, 9, 28, 15, 0)))
        assertThat(ActionArgs.time("2026-09-28", berlin)).isEqualTo(ActionArgs.When.Day(LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `anything else is not a time`() {
        listOf(null, "", "tomorrow at 3", "2026-13-01", "15:00", "2026-09-28T25:00").forEach {
            assertThat(ActionArgs.time(it, berlin)).isNull()
        }
    }

    @Test
    fun `text is trimmed, cleaned of control characters and length-checked`() {
        val args = ActionArgs.parse("""{"a":"  buy milk\u0007  ","b":"","c":12,"d":"line one\nline two"}""")!!
        assertThat(ActionArgs.text(args, "a", 100)).isEqualTo("buy milk")
        assertThat(ActionArgs.text(args, "b", 100)).isNull()
        assertThat(ActionArgs.text(args, "c", 100)).isNull()
        assertThat(ActionArgs.text(args, "d", 100)).isEqualTo("line one\nline two")
        assertThat(ActionArgs.text(args, "a", 3)).isNull()
        assertThat(ActionArgs.text(args, "missing", 100)).isNull()
    }

    @Test
    fun `numbers must be whole and in range`() {
        val args = ActionArgs.parse("""{"s":90,"big":100000,"f":1.5,"t":"60"}""")!!
        assertThat(ActionArgs.int(args, "s", 1..86_400)).isEqualTo(90)
        assertThat(ActionArgs.int(args, "big", 1..86_400)).isNull()
        assertThat(ActionArgs.int(args, "f", 1..86_400)).isNull()
        assertThat(ActionArgs.int(args, "t", 1..86_400)).isEqualTo(60)
    }

    @Test
    fun `arguments that are not a JSON object are rejected`() {
        assertThat(ActionArgs.parse("not json")).isNull()
        assertThat(ActionArgs.parse("[1,2]")).isNull()
    }

    @Test
    fun `times are said the way people say them`() {
        assertThat(ActionArgs.say(LocalDateTime.of(2026, 9, 28, 15, 0))).isEqualTo("Monday 28 September at 15:00")
        assertThat(ActionArgs.say(LocalDate.of(2026, 9, 28))).isEqualTo("Monday 28 September")
    }
}
