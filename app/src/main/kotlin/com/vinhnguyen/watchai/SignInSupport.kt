package com.vinhnguyen.watchai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.vinhnguyen.watchai.brain.chatgpt.auth.BrowserLauncher

/** OpenAI's sign-in page in a Custom Tab: the real browser, address bar visible, no WebView. */
class CustomTabLauncher(
    private val activity: Activity,
    private val ephemeral: Boolean = false,
) : BrowserLauncher {
    override fun open(url: String) {
        activity.runOnUiThread {
            val intent =
                CustomTabsIntent
                    .Builder()
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(false)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .setEphemeralBrowsingEnabled(ephemeral)
                    .build()
            intent.launchUrl(activity, url.toUri())
        }
    }
}

/**
 * Keeps the app process alive (for at most ~3 minutes, Android's short-service limit) while the
 * browser is in front, so the sign-in redirect reaches our loopback listener.
 */
class SignInKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification(this))
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "sign_in"
        private const val NOTIFICATION_ID = 4_201

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SignInKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInKeepAliveService::class.java))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_LOW))
            }
            return NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Waiting for ChatGPT sign-in")
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }
    }
}
