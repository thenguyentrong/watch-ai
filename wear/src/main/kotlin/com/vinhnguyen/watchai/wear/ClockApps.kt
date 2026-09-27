package com.vinhnguyen.watchai.wear

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.vinhnguyen.watchai.watchlink.Control

/**
 * Sets timers and alarms in the watch's own clock apps when the phone asks. Android only lets an
 * app open another app's screen while it is on screen itself, which it is during a conversation.
 */
internal class ClockApps(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** A short sentence for the model: "ok: …" or "error: …". */
    @SuppressLint("WearRecents") // another app's screen, started from the app context: it needs a task
    fun set(
        request: Control,
        onScreen: Boolean,
    ): String {
        if (!onScreen) return "error: Watch AI isn't on the watch screen, so it can't open the watch's clock. Ask the user to open it and try again."
        val (intent, done) =
            when (request.type) {
                "timer" -> {
                    val seconds = request.seconds ?: return "error: the timer needs a length"
                    Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds) to "ok: timer set on the watch for $seconds seconds"
                }

                "alarm" -> {
                    val hour = request.hour ?: return "error: the alarm needs an hour"
                    val minute = request.minute ?: 0
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute) to "ok: alarm set on the watch for ${"%02d:%02d".format(hour, minute)}"
                }

                else -> return "error: the watch can't do ${request.type}"
            }
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        request.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return try {
            appContext.startActivity(intent)
            done
        } catch (e: ActivityNotFoundException) {
            "error: the watch has no clock app for that"
        }
    }
}
