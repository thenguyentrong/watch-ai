package com.vinhnguyen.watchai.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What the assistant can do on this phone: notes (kept in this app), calendar events and reminders
 * (the phone's calendar, so they sync to the user's Google or Samsung calendar and show on the
 * watch), timers and alarms (on the device the user talks through, or where they say), and
 * [PhoneControls] that work from a pocket: find my phone, music, volume, ringer, Do Not Disturb,
 * battery. Nothing is deleted or sent anywhere. Every argument is checked; results are short plain
 * sentences for the model.
 */
class PhoneActions(
    context: Context,
    private val notes: NoteStore,
    private val controls: PhoneControls,
    private val phoneClock: Clock,
    /** Set while the user talks through the watch: timers and alarms go there unless they ask for the phone. */
    private val watch: Watch? = null,
    private val logger: BrainLogger = BrainLogger.None,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Toolbox {
    private val appContext = context.applicationContext

    override fun tools(): List<ToolSpec> = SPECS

    fun calendarAllowed(): Boolean = CALENDAR_PERMISSIONS.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        val args = ActionArgs.parse(argumentsJson) ?: return done(name, "invalid", "error: the arguments were not valid JSON")
        return try {
            when (name) {
                ADD_NOTE -> addNote(args)
                LIST_NOTES -> listNotes(args)
                ADD_EVENT -> calendar(name) { addEvent(args) }
                LIST_EVENTS -> calendar(name) { listEvents(args) }
                ADD_REMINDER -> calendar(name) { addReminder(args) }
                SET_TIMER -> setTimer(args)
                SET_ALARM -> setAlarm(args)
                RING_PHONE -> outcome(name, controls.ring(ActionArgs.int(args, "seconds", 5..60) ?: RING_SECONDS))
                STOP_RINGING -> outcome(name, controls.stopRinging())
                MEDIA -> ActionArgs.choice(args, "command", PhoneControls.MEDIA_KEYS.keys)?.let { outcome(name, controls.media(it)) } ?: invalid(name, "command")
                SET_VOLUME -> setVolume(args)
                SET_RINGER -> ActionArgs.choice(args, "mode", PhoneControls.RINGER_MODES.keys)?.let { outcome(name, controls.ringer(it)) } ?: invalid(name, "mode")
                DO_NOT_DISTURB -> ActionArgs.bool(args, "on")?.let { outcome(name, controls.doNotDisturb(it)) } ?: invalid(name, "on")
                DEVICE_STATUS -> outcome(name, listOfNotNull(controls.status(), watch?.battery()).joinToString("; "))
                else -> done(name, "unknown", "error: there is no action called $name")
            }
        } catch (e: SecurityException) {
            done(name, "denied", "error: the phone refused this action")
        }
    }

    private suspend fun addNote(args: JsonObject): String {
        val text = ActionArgs.text(args, "text", NOTE_MAX) ?: return done(ADD_NOTE, "invalid", "error: the note needs text (up to $NOTE_MAX characters)")
        notes.add(text)
        return done(ADD_NOTE, "ok", "ok: note saved")
    }

    private suspend fun listNotes(args: JsonObject): String {
        val limit = ActionArgs.int(args, "limit", 1..20) ?: 10
        val all = notes.list()
        if (all.isEmpty()) return done(LIST_NOTES, "ok", "there are no notes yet")
        val newest = all.takeLast(limit).reversed()
        return done(LIST_NOTES, "ok", "${all.size} notes, newest first: " + newest.joinToString(" | ") { it.text })
    }

    private suspend fun calendar(
        name: String,
        block: suspend () -> String,
    ): String = if (calendarAllowed()) {
        block()
    } else {
        done(name, "denied", "error: calendar access is off. Tell the user to allow it in the Buddy app, under Things it can do.")
    }

    private suspend fun addEvent(args: JsonObject): String {
        val title = ActionArgs.text(args, "title", TITLE_MAX) ?: return done(ADD_EVENT, "invalid", "error: the event needs a title")
        val start = ActionArgs.time(ActionArgs.text(args, "start", 40), zone()) ?: return done(ADD_EVENT, "invalid", "error: start must be a date or date and time like 2026-09-28T15:00")
        val location = ActionArgs.text(args, "location", TITLE_MAX)
        val details = ActionArgs.text(args, "notes", NOTE_MAX)
        return when (start) {
            is ActionArgs.When.Day -> {
                if (!plausible(start.date.atStartOfDay())) return done(ADD_EVENT, "invalid", "error: that date is too far away")
                insertEvent(title, start.date.atStartOfDay(), start.date.plusDays(1).atStartOfDay(), allDay = true, location, details, alertMinutes = null)
                done(ADD_EVENT, "ok", "ok: added \"$title\" as an all-day event on ${ActionArgs.say(start.date)}")
            }

            is ActionArgs.When.At -> {
                if (!plausible(start.time)) return done(ADD_EVENT, "invalid", "error: that time is in the past or too far away")
                val end =
                    (ActionArgs.time(ActionArgs.text(args, "end", 40), zone()) as? ActionArgs.When.At)?.time?.takeIf { it.isAfter(start.time) }
                        ?: start.time.plusMinutes((ActionArgs.int(args, "duration_minutes", 5..1_440) ?: 60).toLong())
                if (Duration.between(start.time, end) > Duration.ofDays(1)) return done(ADD_EVENT, "invalid", "error: events can be at most a day long")
                insertEvent(title, start.time, end, allDay = false, location, details, alertMinutes = ActionArgs.int(args, "alert_minutes_before", 0..10_080))
                done(ADD_EVENT, "ok", "ok: added \"$title\" on ${ActionArgs.say(start.time)} until ${end.toLocalTime()}")
            }
        }
    }

    private suspend fun addReminder(args: JsonObject): String {
        val text = ActionArgs.text(args, "text", TITLE_MAX) ?: return done(ADD_REMINDER, "invalid", "error: the reminder needs text")
        val at = ActionArgs.time(ActionArgs.text(args, "at", 40), zone()) as? ActionArgs.When.At ?: return done(ADD_REMINDER, "invalid", "error: at must be a date and time like 2026-09-28T15:00")
        if (!plausible(at.time)) return done(ADD_REMINDER, "invalid", "error: that time is in the past or too far away")
        insertEvent(text, at.time, at.time.plusMinutes(REMINDER_MINUTES), allDay = false, location = null, details = "Reminder from Buddy", alertMinutes = 0)
        return done(ADD_REMINDER, "ok", "ok: the phone will remind the user on ${ActionArgs.say(at.time)}")
    }

    private suspend fun listEvents(args: JsonObject): String {
        val today = LocalDate.now(zone())
        val from = ActionArgs.time(ActionArgs.text(args, "from", 40), zone()).startTime() ?: today.atStartOfDay()
        val to = ActionArgs.time(ActionArgs.text(args, "to", 40), zone()).endTime()?.takeIf { it.isAfter(from) } ?: from.toLocalDate().plusDays(1).atStartOfDay()
        if (Duration.between(from, to) > Duration.ofDays(LIST_MAX_DAYS)) return done(LIST_EVENTS, "invalid", "error: ask for at most $LIST_MAX_DAYS days at a time")
        val events = withContext(Dispatchers.IO) { queryEvents(from, to) }
        if (events.isEmpty()) return done(LIST_EVENTS, "ok", "no events between ${ActionArgs.say(from)} and ${ActionArgs.say(to)}")
        return done(LIST_EVENTS, "ok", events.joinToString(" | "))
    }

    private suspend fun setTimer(args: JsonObject): String {
        val seconds = ActionArgs.int(args, "seconds", 1..86_400) ?: return done(SET_TIMER, "invalid", "error: seconds must be between 1 and 86400")
        val clock = clockFor(args) ?: return done(SET_TIMER, "invalid", NO_WATCH)
        return outcome(SET_TIMER, clock.setTimer(seconds, ActionArgs.text(args, "label", TITLE_MAX)))
    }

    private suspend fun setAlarm(args: JsonObject): String {
        val hour = ActionArgs.int(args, "hour", 0..23) ?: return done(SET_ALARM, "invalid", "error: hour must be 0 to 23")
        val minute = ActionArgs.int(args, "minute", 0..59) ?: 0
        val clock = clockFor(args) ?: return done(SET_ALARM, "invalid", NO_WATCH)
        return outcome(SET_ALARM, clock.setAlarm(hour, minute, ActionArgs.text(args, "label", TITLE_MAX)))
    }

    /** Where the user said ("on my phone"), else the device they talk through; null if they asked for a watch that isn't in the conversation. */
    private fun clockFor(args: JsonObject): Clock? = when (ActionArgs.choice(args, "on", PLACES)) {
        "phone" -> phoneClock
        "watch" -> watch
        else -> watch ?: phoneClock
    }

    private fun setVolume(args: JsonObject): String {
        val level = ActionArgs.int(args, "level", 0..100)
        val change = ActionArgs.choice(args, "change", setOf("up", "down"))
        if (level == null && change == null) return done(SET_VOLUME, "invalid", "error: give a level from 0 to 100, or change up or down")
        return outcome(SET_VOLUME, controls.volume(level, change?.let { it == "up" }))
    }

    private fun outcome(
        tool: String,
        result: String,
    ) = done(tool, if (result.startsWith("error")) "failed" else "ok", result)

    private fun invalid(
        tool: String,
        key: String,
    ) = done(tool, "invalid", "error: $key is missing or not one of the allowed values")

    private suspend fun insertEvent(
        title: String,
        start: LocalDateTime,
        end: LocalDateTime,
        allDay: Boolean,
        location: String?,
        details: String?,
        alertMinutes: Int?,
    ) = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val calendarId = defaultCalendarId() ?: throw SecurityException("no writable calendar")
        // All-day events are stored at UTC midnight, as the calendar provider expects.
        val eventZone = if (allDay) ZoneOffset.UTC else zone()
        val values =
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.atZone(eventZone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.atZone(eventZone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else eventZone.id)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                details?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw SecurityException("insert refused")
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return@withContext
        if (alertMinutes != null) {
            resolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, alertMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
    }

    /** The user's main calendar: the primary one of a synced account if there is one, else any writable visible one. */
    private fun defaultCalendarId(): Long? {
        val projection =
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        appContext.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            var best: Long? = null
            var bestScore = -1
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = c.getLong(0)
                }
            }
            return best
        }
        return null
    }

    private fun queryEvents(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<String> {
        val begin = from.atZone(zone()).toInstant().toEpochMilli()
        val end = to.atZone(zone()).toInstant().toEpochMilli()
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .appendPath(begin.toString())
                .appendPath(end.toString())
                .build()
        val projection =
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            )
        val out = mutableListOf<String>()
        appContext.contentResolver
            .query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { c ->
                while (c.moveToNext() && out.size < LIST_MAX_EVENTS) {
                    val title = c.getString(0)?.take(TITLE_MAX) ?: "(no title)"
                    val allDay = c.getInt(3) == 1
                    val startsAt =
                        Instant
                            .ofEpochMilli(c.getLong(1))
                            .atZone(if (allDay) ZoneOffset.UTC else zone())
                            .toLocalDateTime()
                    val place = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " at ${it.take(TITLE_MAX)}" }.orEmpty()
                    out += if (allDay) "${ActionArgs.say(startsAt.toLocalDate())}: $title (all day)$place" else "${ActionArgs.say(startsAt)}: $title$place"
                }
            }
        return out
    }

    private fun plausible(time: LocalDateTime): Boolean {
        val now = LocalDateTime.now(zone())
        return time.isAfter(now.minusHours(1)) && time.isBefore(now.plusYears(2))
    }

    private fun ActionArgs.When?.startTime(): LocalDateTime? = when (this) {
        is ActionArgs.When.At -> time
        is ActionArgs.When.Day -> date.atStartOfDay()
        null -> null
    }

    private fun ActionArgs.When?.endTime(): LocalDateTime? = when (this) {
        is ActionArgs.When.At -> time
        is ActionArgs.When.Day -> date.plusDays(1).atStartOfDay()
        null -> null
    }

    private fun done(
        tool: String,
        outcome: String,
        result: String,
    ): String {
        logger.log(LogEvent.ToolUsed(tool, outcome))
        return result
    }

    companion object {
        const val ADD_NOTE = "add_note"
        const val LIST_NOTES = "list_notes"
        const val ADD_EVENT = "add_calendar_event"
        const val LIST_EVENTS = "list_calendar_events"
        const val ADD_REMINDER = "add_reminder"
        const val SET_TIMER = "set_timer"
        const val SET_ALARM = "set_alarm"
        const val RING_PHONE = "ring_phone"
        const val STOP_RINGING = "stop_ringing"
        const val MEDIA = "media_control"
        const val SET_VOLUME = "set_volume"
        const val SET_RINGER = "set_ringer"
        const val DO_NOT_DISTURB = "do_not_disturb"
        const val DEVICE_STATUS = "device_status"

        val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        private const val NOTE_MAX = 1_000
        private const val TITLE_MAX = 200
        private const val REMINDER_MINUTES = 15L
        private const val LIST_MAX_DAYS = 31L
        private const val LIST_MAX_EVENTS = 15
        private const val RING_SECONDS = 30
        private val PLACES = setOf("watch", "phone")
        private const val NO_WATCH = "error: timers and alarms only go on the watch while the user talks through it; offer the phone instead"

        private const val LOCAL_TIME = "Local date and time without a zone, like 2026-09-28T15:00, or just a date for all day."
        private const val ON =
            """"on":{"type":"string","enum":["watch","phone"],"description":"Only when the user says where; otherwise leave it out and it goes to the device they are talking through."}"""
        private const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""

        val SPECS =
            listOf(
                ToolSpec(
                    ADD_NOTE,
                    "Save a note for the user in their Buddy notes (use for 'note that…', 'add … to my notes', shopping items, ideas).",
                    """{"type":"object","properties":{"text":{"type":"string","description":"The note, in the user's words."}},"required":["text"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    LIST_NOTES,
                    "Read the user's newest notes.",
                    """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":20}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    ADD_EVENT,
                    "Add an event to the user's phone calendar (it syncs to their Google or Samsung calendar).",
                    """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string","description":"$LOCAL_TIME"},"end":{"type":"string","description":"$LOCAL_TIME Optional."},"duration_minutes":{"type":"integer","minimum":5,"maximum":1440,"description":"Used when there is no end. Default 60."},"location":{"type":"string"},"notes":{"type":"string"},"alert_minutes_before":{"type":"integer","minimum":0,"maximum":10080}},"required":["title","start"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    LIST_EVENTS,
                    "Read the user's calendar between two times (at most 31 days). Defaults to today.",
                    """{"type":"object","properties":{"from":{"type":"string","description":"$LOCAL_TIME"},"to":{"type":"string","description":"$LOCAL_TIME"}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    ADD_REMINDER,
                    "Remind the user of something at a date and time: the phone notifies them then (and their watch).",
                    """{"type":"object","properties":{"text":{"type":"string"},"at":{"type":"string","description":"Local date and time without a zone, like 2026-09-28T15:00."}},"required":["text","at"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_TIMER,
                    "Start a countdown timer for the length the user said; never guess one (if they didn't say how long, ask). " +
                        "It rings on the device the user is talking through (their watch, or the phone) unless they say where.",
                    """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"maximum":86400},"label":{"type":"string"},$ON},"required":["seconds"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_ALARM,
                    "Set an alarm for the next time it is this hour and minute; never guess a time (if the user didn't say one, ask). " +
                        "It is set on the device the user is talking through (their watch, or the phone) unless they say where.",
                    """{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string"},$ON},"required":["hour"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    RING_PHONE,
                    "Ring the phone loudly, even on silent, so the user can find it ('where's my phone?'). " +
                        "It stops by itself, from its notification, or with stop_ringing.",
                    """{"type":"object","properties":{"seconds":{"type":"integer","minimum":5,"maximum":60,"description":"Default 30."}},"additionalProperties":false}""",
                ),
                ToolSpec(STOP_RINGING, "Stop the phone ringing (after ring_phone).", NO_ARGS),
                ToolSpec(
                    MEDIA,
                    "Control what plays on the phone, also in the user's earbuds (music, podcasts, videos): play, pause, next or previous.",
                    """{"type":"object","properties":{"command":{"type":"string","enum":["play","pause","next","previous"]}},"required":["command"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_VOLUME,
                    "Set the phone's media volume (music, videos, the user's earbuds): a level in percent, or one step up or down.",
                    """{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":100},"change":{"type":"string","enum":["up","down"]}},"additionalProperties":false}""",
                ),
                ToolSpec(
                    SET_RINGER,
                    "Switch the phone's ringer for calls and notifications: normal (sound on), vibrate or silent.",
                    """{"type":"object","properties":{"mode":{"type":"string","enum":["normal","vibrate","silent"]}},"required":["mode"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    DO_NOT_DISTURB,
                    "Turn Do Not Disturb on the phone on or off (a Galaxy watch usually follows the phone).",
                    """{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    DEVICE_STATUS,
                    "Battery of the phone (and of the watch while the user talks through it) and whether it is charging, " +
                        "plus the phone's ringer and Do Not Disturb.",
                    NO_ARGS,
                ),
            )
    }
}
