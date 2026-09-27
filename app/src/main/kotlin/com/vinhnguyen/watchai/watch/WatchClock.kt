package com.vinhnguyen.watchai.watch

import com.vinhnguyen.watchai.actions.Clock
import com.vinhnguyen.watchai.watchlink.Control

/**
 * Timers and alarms on the watch's own clock during a watch call: they ring on the wrist, and the
 * watch app is on screen, so Android lets it open the clock (the phone in a pocket can't).
 */
class WatchClock(
    private val watch: WatchAudio,
) : Clock {
    override suspend fun setTimer(
        seconds: Int,
        label: String?,
    ): String = watch.ask(Control("timer", seconds = seconds, label = label)) ?: NO_ANSWER

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
    ): String = watch.ask(Control("alarm", hour = hour, minute = minute, label = label)) ?: NO_ANSWER

    private companion object {
        const val NO_ANSWER = "error: the watch didn't answer"
    }
}
