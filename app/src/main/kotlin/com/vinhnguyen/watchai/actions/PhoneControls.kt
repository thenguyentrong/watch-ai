package com.vinhnguyen.watchai.actions

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vinhnguyen.watchai.WatchAiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone controls that work with the phone in a pocket. Nothing here opens another app (Android
 * blocks that from the background): ringing to find the phone, the media keys, volume, ringer,
 * Do Not Disturb and the battery. One instance for the app, so a ring started from the watch can
 * be stopped from the phone's notification or in the next conversation.
 */
class PhoneControls(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
    private val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator

    // Main thread only.
    private var player: MediaPlayer? = null
    private var alarmVolumeBefore: Int? = null
    private var ringEnds: Job? = null

    fun doNotDisturbAllowed(): Boolean = notifications.isNotificationPolicyAccessGranted

    /** Rings with the alarm sound at full alarm volume, and vibrates, for [seconds]; silent mode doesn't stop it. */
    suspend fun ring(seconds: Int): String = withContext(Dispatchers.Main) {
        stopRing()
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            alarmVolumeBefore = before
            player =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(appContext, sound)
                    isLooping = true
                    prepare()
                    start()
                }
        } catch (e: Exception) {
            stopRing()
            return@withContext "error: the phone couldn't play a sound"
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        showRinging()
        ringEnds =
            scope.launch(Dispatchers.Main) {
                delay(seconds * 1_000L)
                stopRing()
            }
        "ok: the phone is ringing for $seconds seconds"
    }

    suspend fun stopRinging(): String = withContext(Dispatchers.Main) { if (stopRing()) "ok: the phone stopped ringing" else "the phone wasn't ringing" }

    /** Stops a ring and puts the alarm volume back. True if it was ringing. */
    @MainThread
    fun stopRing(): Boolean {
        ringEnds?.cancel()
        ringEnds = null
        val p = player
        player = null
        if (p != null) {
            runCatching { p.stop() }
            p.release()
            vibrator?.cancel()
            notifications.cancel(RING_NOTIFICATION)
        }
        alarmVolumeBefore?.let { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        alarmVolumeBefore = null
        return p != null
    }

    /** A notification with Stop, for when the phone turns up; the sound is the ring itself. */
    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled
    private fun showRinging() {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(RING_CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val stop = PendingIntent.getBroadcast(appContext, 0, Intent(appContext, StopRingingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat
                .Builder(appContext, RING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Watch AI is ringing your phone")
                .setContentText("Tap Stop when you've found it")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setDeleteIntent(stop)
                .addAction(0, "Stop", stop)
                .build()
        NotificationManagerCompat.from(appContext).notify(RING_NOTIFICATION, notification)
    }

    /** Presses a media key for whatever plays on the phone, or played last (music, podcasts, videos). */
    fun media(command: String): String {
        val key = MEDIA_KEYS[command] ?: return "error: the media commands are ${MEDIA_KEYS.keys.joinToString()}"
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "ok: pressed $command for the phone's music or video app"
    }

    /** Media volume to [percent], or one step of about 15 % [up] or down. Reports what the phone actually set. */
    fun volume(
        percent: Int?,
        up: Boolean?,
    ): String {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val step = maxOf(1, max * VOLUME_STEP_PERCENT / 100)
        val now = audio.getStreamVolume(stream)
        val target =
            when {
                percent != null -> (percent * max + 50) / 100
                up == true -> minOf(max, now + step)
                up == false -> maxOf(0, now - step)
                else -> return "error: give a level from 0 to 100, or up or down"
            }
        audio.setStreamVolume(stream, target, 0)
        return "ok: the phone's media volume is ${audio.getStreamVolume(stream) * 100 / max}%"
    }

    fun ringer(mode: String): String {
        val ringer = RINGER_MODES[mode] ?: return "error: the ringer is ${RINGER_MODES.keys.joinToString()}"
        return try {
            audio.ringerMode = ringer
            if (audio.ringerMode == ringer) "ok: the phone's ringer is on $mode" else "error: the phone didn't switch to $mode"
        } catch (e: SecurityException) {
            NEEDS_DND_ACCESS
        }
    }

    fun doNotDisturb(on: Boolean): String {
        if (!doNotDisturbAllowed()) return NEEDS_DND_ACCESS
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return when {
            doNotDisturbOn() == on -> if (on) "ok: Do Not Disturb is on" else "ok: Do Not Disturb is off"

            on -> "error: Do Not Disturb didn't turn on"

            // Since Android 15 an app only switches off what it switched on itself.
            else -> "error: Do Not Disturb stays on: it was switched on on the phone or by a schedule, so it has to be switched off there"
        }
    }

    fun status(): String {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val ringer = RINGER_MODES.entries.firstOrNull { it.value == audio.ringerMode }?.key ?: "normal"
        val charging = if (battery.isCharging) ", charging" else ""
        return "phone: battery ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%$charging, ringer $ringer, " +
            "Do Not Disturb ${if (doNotDisturbOn()) "on" else "off"}"
    }

    private fun doNotDisturbOn(): Boolean = notifications.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    companion object {
        val MEDIA_KEYS =
            mapOf(
                "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
                "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
                "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
                "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            )
        val RINGER_MODES =
            mapOf(
                "normal" to AudioManager.RINGER_MODE_NORMAL,
                "vibrate" to AudioManager.RINGER_MODE_VIBRATE,
                "silent" to AudioManager.RINGER_MODE_SILENT,
            )
        private const val VOLUME_STEP_PERCENT = 15
        private const val RING_CHANNEL = "find_phone"
        private const val RING_NOTIFICATION = 8
        private const val NEEDS_DND_ACCESS =
            "error: the user has to allow Watch AI to change Do Not Disturb first, once, in the Watch AI phone app (Voice tab)"
    }
}

/** Stop on the ringing notification (or swiping it away). */
class StopRingingReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        (context.applicationContext as WatchAiApp).graph.controls.stopRing()
    }
}
