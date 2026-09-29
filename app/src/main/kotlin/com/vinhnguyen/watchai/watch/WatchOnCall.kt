package com.vinhnguyen.watchai.watch

import com.vinhnguyen.watchai.actions.Watch
import com.vinhnguyen.watchai.watchlink.Control

/**
 * The watch during a watch call, asked over the link: timers and alarms on its own clock (they
 * ring on the wrist, and the watch app is on screen, so Android lets it open the clock), and its
 * battery.
 */
class WatchOnCall(
    private val watch: WatchAudio,
) : Watch {
    override suspend fun setTimer(
        seconds: Int,
        label: String?,
    ): String = watch.ask(Control("timer", seconds = seconds, label = label)) ?: NO_ANSWER

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
    ): String = watch.ask(Control("alarm", hour = hour, minute = minute, label = label)) ?: NO_ANSWER

    override suspend fun battery(): String = watch.ask(Control("battery")) ?: NO_ANSWER

    /** Whether the watch is unlocked, so on the wrist; null when it has no screen lock or doesn't answer. */
    suspend fun unlocked(): Boolean? = when (watch.ask(Control("locked"))) {
        "unlocked" -> true
        "locked" -> false
        else -> null
    }

    private companion object {
        const val NO_ANSWER = "error: the watch didn't answer"
    }
}
