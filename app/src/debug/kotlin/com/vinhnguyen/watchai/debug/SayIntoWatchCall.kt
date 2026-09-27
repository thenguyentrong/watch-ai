package com.vinhnguyen.watchai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vinhnguyen.watchai.WatchAiApp
import com.vinhnguyen.watchai.watchlink.Wav
import timber.log.Timber
import java.io.File

/**
 * Debug builds only: plays a WAV (16 kHz, mono, 16-bit) into the current watch call as if it was
 * said on the watch, to test calls end to end without talking. Use synthetic speech only.
 *
 *   adb push say.wav /sdcard/Android/data/com.vinhnguyen.watchai/files/say.wav
 *   adb shell am broadcast -n com.vinhnguyen.watchai/.debug.SayIntoWatchCall --es file say.wav
 */
class SayIntoWatchCall : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, intent.getStringExtra("file") ?: return)
        if (file.parentFile?.canonicalPath != dir.canonicalPath || !file.isFile) {
            Timber.tag(TAG).w("no such file in ${dir.path}")
            return
        }
        val pcm = Wav.read16kMono(file.readBytes())
        if (pcm == null) {
            Timber.tag(TAG).w("not a 16 kHz mono 16-bit WAV")
            return
        }
        val saying = (context.applicationContext as WatchAiApp).graph.watchCalls.say(pcm)
        Timber.tag(TAG).i(if (saying) "saying ${pcm.size / 16} ms of ${file.name}" else "no watch call to say it into")
    }

    private companion object {
        const val TAG = "SayIntoWatchCall"
    }
}
