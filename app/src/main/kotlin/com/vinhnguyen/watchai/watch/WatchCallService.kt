package com.vinhnguyen.watchai.watch

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
import androidx.core.content.IntentCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import com.vinhnguyen.watchai.MainActivity
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.WatchAiApp
import com.vinhnguyen.watchai.watchlink.WatchLink
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Google Play services wakes this when the watch app opens its voice channel, even with the phone
 * app closed; it hands the call to [WatchCallService] to keep it running.
 */
class WatchListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchLink.VOICE_PATH) return
        try {
            WatchCallService.start(this, channel)
        } catch (e: IllegalStateException) {
            // Android refused a background start; the watch times out and says so.
            Timber.w("watch call refused in background: %s", e::class.simpleName)
        }
    }
}

/**
 * Keeps a watch conversation running with the phone in a pocket: a foreground service for the
 * watch link, with an ongoing notification and an End action. Started only by the watch calling.
 */
class WatchCallService : Service() {
    private val calls get() = (application as WatchAiApp).graph.watchCalls

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val graph = (application as WatchAiApp).graph
        if (intent?.action == ACTION_END) {
            graph.scope.launch { calls.hangUp() }
            return START_NOT_STICKY
        }
        // Microphone too: with earbuds the phone records itself, and Android hands a background app
        // without a microphone service only silence (earbud calls with the phone locked, 28.09).
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: SecurityException) {
            Timber.w("watch call without the phone's microphone: %s", e::class.simpleName)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        val channel = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CHANNEL, ChannelClient.Channel::class.java) }
        if (channel == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        graph.scope.launch {
            runCatching { calls.answer(channel) { stopSelf() } }.onFailure { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun notification() = NotificationCompat
        .Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_buddy)
        .setContentTitle("Talking through your watch")
        .setContentText("ChatGPT voice on your plan")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "End", PendingIntent.getService(this, 1, Intent(this, WatchCallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val CHANNEL = "watch_call"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_END = "com.vinhnguyen.watchai.watch.END"
        private const val EXTRA_CHANNEL = "channel"

        fun start(
            context: Context,
            channel: ChannelClient.Channel,
        ) {
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Watch conversations", NotificationManager.IMPORTANCE_LOW))
            context.startForegroundService(Intent(context, WatchCallService::class.java).putExtra(EXTRA_CHANNEL, channel))
        }
    }
}
