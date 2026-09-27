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

    private companion object {
        const val NO_ANSWER = "error: the watch didn't answer"
    }
}
