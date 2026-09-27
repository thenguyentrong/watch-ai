package com.vinhnguyen.watchai.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/** Where timers and alarms ring. Results are short sentences for the model: "ok: …" or "error: …". */
interface Clock {
    suspend fun setTimer(
        seconds: Int,
        label: String?,
    ): String

    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
    ): String
}

/**
 * The phone's clock app, opened with an activity intent. Android silently drops those from apps in
 * the background (a watch call with the phone in a pocket: the timer "succeeded" and never
 * appeared, 27.09), so this only tries while the app is on screen.
 */
class PhoneClock(
    context: Context,
    private val onScreen: () -> Boolean,
) : Clock {
    private val appContext = context.applicationContext

    override suspend fun setTimer(
        seconds: Int,
        label: String?,
    ): String {
        if (!onScreen()) return IN_BACKGROUND
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        return if (open(intent, label)) "ok: timer set for $seconds seconds" else "error: no clock app took the timer"
    }

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        label: String?,
    ): String {
        if (!onScreen()) return IN_BACKGROUND
        val intent =
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        return if (open(intent, label)) "ok: alarm set for ${"%02d:%02d".format(hour, minute)}" else "error: no clock app took the alarm"
    }

    private fun open(
        intent: Intent,
        label: String?,
    ): Boolean = try {
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        appContext.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private companion object {
        const val IN_BACKGROUND = "error: the phone can't open its clock app while Watch AI isn't on its screen. Ask the user to open the app and try again."
    }
}
