package com.vinhnguyen.watchai.brain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DeviceContextTest {
    @Test
    fun `describes local time, date and zone`() {
        val now = ZonedDateTime.of(2026, 9, 27, 16, 40, 0, 0, ZoneId.of("Europe/Berlin"))
        assertThat(DeviceContext.describe(now))
            .startsWith("The user's local time is Sunday 27 September 2026, 16:40 (time zone Europe/Berlin, UTC+02:00).")
    }

    @Test
    fun `utc is written as an offset`() {
        val now = ZonedDateTime.of(2026, 1, 5, 9, 5, 0, 0, ZoneId.of("Z"))
        assertThat(DeviceContext.describe(now)).contains("(time zone Z, UTC+00:00)")
    }

    @Test
    fun `the system prompt carries the context after the style`() {
        val request = ChatRequest("c", emptyList(), "What time is it?", style = ReplyStyle.SPOKEN, context = "It is noon.")
        assertThat(PromptStyle.system(request)).isEqualTo(PromptStyle.SPOKEN_SYSTEM + "\nIt is noon.")
        assertThat(PromptStyle.system(request.copy(context = null))).isEqualTo(PromptStyle.SPOKEN_SYSTEM)
    }
}
