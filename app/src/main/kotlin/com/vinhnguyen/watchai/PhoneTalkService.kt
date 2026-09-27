package com.vinhnguyen.watchai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import timber.log.Timber

/**
 * Keeps a conversation on the phone going when the user leaves the app: a microphone service
 * with an ongoing notification and an End button. Android lets it start only while the app is on
 * screen, so it starts with the conversation and stops with it; the conversation still hangs up
 * by itself after a quiet while.
 */
class PhoneTalkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_END) {
            onEnd?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            running = true
        } catch (e: RuntimeException) {
            // No mic permission or started from the background: the conversation stops when the app is left, as before.
            Timber.w("phone talk service refused: %s", e::class.simpleName)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification() = NotificationCompat
        .Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
        .setContentTitle("Talking with Buddy")
        .setContentText("Still listening while you use other apps")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "End", PendingIntent.getService(this, 1, Intent(this, PhoneTalkService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val CHANNEL = "phone_talk"
        private const val NOTIFICATION_ID = 8
        private const val ACTION_END = "com.vinhnguyen.watchai.PHONE_TALK_END"

        /** Whether the service holds the mic in the foreground right now. */
        @Volatile var running: Boolean = false
            private set

        /** What the notification's End button does; set by the conversation that runs, cleared when it stops. */
        @Volatile private var onEnd: (() -> Unit)? = null

        fun start(
            context: Context,
            onEnd: () -> Unit,
        ) {
            this.onEnd = onEnd
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Conversations on the phone", NotificationManager.IMPORTANCE_LOW))
            try {
                context.startForegroundService(Intent(context, PhoneTalkService::class.java))
            } catch (e: IllegalStateException) {
                Timber.w("phone talk service not started: %s", e::class.simpleName)
            }
        }

        fun stop(context: Context) {
            onEnd = null
            context.stopService(Intent(context, PhoneTalkService::class.java))
        }
    }
}
