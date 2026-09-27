package com.vinhnguyen.watchai.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads and checks what the model passed to an action. Model output is untrusted: anything odd is rejected. */
object ActionArgs {
    private val json = Json { ignoreUnknownKeys = true }
    private const val CHOICE_MAX = 40

    fun parse(arguments: String): JsonObject? = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()

    /** Trimmed text of at most [max] characters, control characters removed; null if missing or empty. */
    fun text(
        args: JsonObject,
        key: String,
        max: Int,
    ): String? {
        val raw = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val clean = raw.filter { it == '\n' || !it.isISOControl() }.trim()
        return clean.takeIf { it.isNotEmpty() && it.length <= max }
    }

    fun int(
        args: JsonObject,
        key: String,
        range: IntRange,
    ): Int? = (args[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range }

    fun bool(
        args: JsonObject,
        key: String,
    ): Boolean? = (args[key] as? JsonPrimitive)?.booleanOrNull

    /** One of [allowed] (any case), lower-cased; null if missing or anything else. */
    fun choice(
        args: JsonObject,
        key: String,
        allowed: Set<String>,
    ): String? = text(args, key, CHOICE_MAX)?.lowercase(Locale.ROOT)?.takeIf { it in allowed }

    sealed interface When {
        data class At(
            val time: LocalDateTime,
        ) : When

        data class Day(
            val date: LocalDate,
        ) : When
    }

    /** "2026-09-28T15:00", "2026-09-28 15:00:00", "2026-09-28T15:00+02:00" (moved to [zone]) or a date "2026-09-28". */
    fun time(
        value: String?,
        zone: ZoneId,
    ): When? {
        val v = value?.trim()?.replace(' ', 'T') ?: return null
        runCatching { return When.At(LocalDateTime.parse(v)) }
        runCatching { return When.At(OffsetDateTime.parse(v).atZoneSameInstant(zone).toLocalDateTime()) }
        runCatching { return When.Day(LocalDate.parse(v)) }
        return null
    }

    private val SPOKEN_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private val SPOKEN_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun say(time: LocalDateTime): String = "${SPOKEN_DAY.format(time)} at ${SPOKEN_TIME.format(time)}"

    fun say(date: LocalDate): String = SPOKEN_DAY.format(date)
}
