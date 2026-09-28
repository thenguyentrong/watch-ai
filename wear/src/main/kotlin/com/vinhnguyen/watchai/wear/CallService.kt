package com.vinhnguyen.watchai.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The watch app's microphone service. It keeps a conversation going while the screen is off or
 * another app is in front: without it Android freezes the app and the audio stalls (a 30 s round
 * trip in the 27.09 test). With "Hey Buddy" on it also stays between conversations, so the wake
 * word can listen when the wrist comes up: Android only lets a microphone service start while the
 * app is on screen, so it couldn't be started later, when the phrase is heard.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: WakeListener? = null
    private var talking = false
    private var teaching = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        wake?.stop()
        wake = null
        _armed.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_END -> scope.launch { PhoneVoiceLink.get(this@CallService).stop() }

            ACTION_WAKE_OFF -> wakeOff()

            ACTION_WAKE_ON -> {
                goForeground()
                wakeOn()
            }

            else -> {
                // Can race "Hey Buddy" being switched on, which starts the service too: the mic is the conversation's.
                synchronized(this) {
                    talking = true
                    wake?.pause()
                }
                goForeground()
            }
        }
        return START_NOT_STICKY
    }

    /** A conversation starts (from the screen or "Hey Buddy"): the microphone is its. */
    @Synchronized
    private fun onTalk() {
        talking = true
        wake?.pause()
        refresh()
    }

    /** The conversation ended: back to listening for "Hey Buddy", or done. */
    @Synchronized
    private fun onTalked() {
        talking = false
        val listener = wake
        if (listener == null) {
            stopSelf()
            return
        }
        refresh()
        if (!teaching) listener.resume()
    }

    /** Teaching has the mic, and saying "Hey Buddy" then mustn't start a conversation. */
    @Synchronized
    private fun onTeach(on: Boolean) {
        if (teaching == on) return
        teaching = on
        if (on) {
            wake?.pause()
        } else if (!talking) {
            wake?.resume()
        }
    }

    @Synchronized
    private fun wakeOn() {
        WakeSetting.set(this, true)
        if (wake != null) return
        wake = WakeListener(this) { heard() }.also { it.start() }
        if (talking) wake?.pause()
        _armed.value = true
        refresh()
    }

    @Synchronized
    private fun onAlways(on: Boolean) {
        wake?.listenAlways(on)
        if (wake != null) refresh()
    }

    @Synchronized
    private fun wakeOff() {
        WakeSetting.set(this, false)
        wake?.stop()
        wake = null
        _armed.value = false
        if (talking) refresh() else stopSelf()
    }

    /** "Hey Buddy" was heard: a tick on the wrist, the face, and talk. */
    @SuppressLint("WearRecents") // started from a service, so it needs its own task
    private fun heard() {
        runCatching { getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
        if (dryWake) {
            Log.i(TAG, "heard \"Hey Buddy\" (dry run, no call)")
            return
        }
        // Works while the app is on screen. Over the watch face Android blocks it (27.09), so the face comes up
        // through a full-screen notification, like an incoming call; the conversation runs either way.
        runCatching { startActivity(Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        showFace()
        scope.launch { PhoneVoiceLink.get(this@CallService).start() }
    }

    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled
    private fun showFace() {
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) return
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(FACE_CHANNEL, "Hey Buddy", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val face = PendingIntent.getActivity(this, 3, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            NotificationCompat
                .Builder(this, FACE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Listening")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(face)
                .setFullScreenIntent(face, true)
                .setAutoCancel(true)
                .setTimeoutAfter(FACE_TIMEOUT_MS)
                .build()
        notifications.notify(FACE_NOTIFICATION, notification)
    }

    private fun goForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Conversation", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** New notification text for the running service (no second start: that isn't allowed from the background). */
    @SuppressLint("MissingPermission") // checked: areNotificationsEnabled
    private fun refresh() {
        val notifications = NotificationManagerCompat.from(this)
        if (notifications.areNotificationsEnabled()) notifications.notify(NOTIFICATION_ID, notification())
    }

    private fun notification() = NotificationCompat
        .Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setCategory(if (talking) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE),
        ).apply {
            if (talking) {
                setContentTitle("Talking with Buddy")
                setContentText("Your phone is listening through the watch")
                addAction(0, "End", PendingIntent.getService(this@CallService, 1, Intent(this@CallService, CallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
            } else {
                setContentTitle("\"Hey Buddy\" is on")
                setContentText(if (WakeSetting.isAlways(this@CallService)) "Say it any time" else "Raise your wrist and say it")
                addAction(0, "Turn off", PendingIntent.getService(this@CallService, 2, Intent(this@CallService, CallService::class.java).setAction(ACTION_WAKE_OFF), PendingIntent.FLAG_IMMUTABLE))
            }
        }.build()

    companion object {
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION_ID = 1
        private const val FACE_CHANNEL = "hey_buddy"
        private const val FACE_NOTIFICATION = 2
        private const val FACE_TIMEOUT_MS = 15_000L
        private const val ACTION_TALK = "com.vinhnguyen.watchai.wear.TALK"
        private const val ACTION_END = "com.vinhnguyen.watchai.wear.END"
        private const val ACTION_WAKE_ON = "com.vinhnguyen.watchai.wear.WAKE_ON"
        private const val ACTION_WAKE_OFF = "com.vinhnguyen.watchai.wear.WAKE_OFF"

        private const val TAG = "CallService"

        @Volatile private var instance: CallService? = null

        /** Debug builds, testing with a speaker next to the mic: a heard "Hey Buddy" only ticks. */
        @Volatile internal var dryWake = false

        private val _armed = MutableStateFlow(false)

        /** "Hey Buddy" is switched on and the service is there to listen. */
        val armed: StateFlow<Boolean> = _armed.asStateFlow()

        /**
         * A conversation starts. With the service already running (for "Hey Buddy") it's told directly;
         * otherwise it's started, which Android allows only while the app is on screen.
         */
        fun talk(context: Context) {
            val running = instance
            if (running != null) running.onTalk() else context.startForegroundService(Intent(context, CallService::class.java).setAction(ACTION_TALK))
        }

        fun talked() {
            instance?.onTalked()
        }

        /** The face is up: the full-screen notification that brought it has done its job. */
        fun faceShown(context: Context) {
            NotificationManagerCompat.from(context).cancel(FACE_NOTIFICATION)
        }

        /** Switches "Hey Buddy" on. Call while the app is on screen. */
        fun wakeOn(context: Context) {
            context.startForegroundService(Intent(context, CallService::class.java).setAction(ACTION_WAKE_ON))
        }

        fun wakeOff(context: Context) {
            val running = instance
            if (running != null) running.wakeOff() else WakeSetting.set(context, false)
        }

        /** "Hey Buddy" with the screen off too, not only after a raised wrist. */
        fun listenAlways(
            context: Context,
            on: Boolean,
        ) {
            WakeSetting.setAlways(context, on)
            instance?.onAlways(on)
        }

        /** Debug builds: [pcm] goes to the wake word as if heard. False if "Hey Buddy" is off. */
        fun testWakeWord(pcm: ShortArray): Boolean {
            val listener = instance?.wake ?: return false
            listener.test(pcm)
            return true
        }

        /** The wake word model (waits for it to load), or null if "Hey Buddy" is off. */
        internal suspend fun wakeModel(): WakeWord? = instance?.wake?.model()

        /** The teach screen is recording takes: "Hey Buddy" waits meanwhile. */
        fun teaching(on: Boolean) {
            instance?.onTeach(on)
        }

        /** The user's own spellings from teaching, or null to go back to the default phrase. */
        fun learned(
            context: Context,
            keywords: String?,
        ) {
            WakeSetting.setKeywords(context, keywords)
            instance?.wake?.useKeywords(keywords)
        }
    }
}
