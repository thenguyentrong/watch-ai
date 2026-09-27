package com.vinhnguyen.watchai.brain

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the phone knows and a model doesn't: the local date, time and time zone. */
public object DeviceContext {
    private val FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    public fun describe(now: ZonedDateTime = ZonedDateTime.now()): String {
        val offset = if (now.offset.id == "Z") "+00:00" else now.offset.id
        return "The user's local time is ${FORMAT.format(now)} (time zone ${now.zone.id}, UTC$offset). " +
            "Use it when asked about the time or date."
    }
}
