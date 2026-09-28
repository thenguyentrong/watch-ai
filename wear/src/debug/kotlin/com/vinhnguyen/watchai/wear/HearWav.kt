package com.vinhnguyen.watchai.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vinhnguyen.watchai.watchlink.Wav
import java.io.File

/**
 * Debug builds only: plays a WAV (16 kHz, mono, 16-bit) to "Hey Buddy" as if the watch heard it,
 * to test the wake word without talking. Use synthetic speech only; "Hey Buddy" must be on. A heard
 * phrase only ticks and logs ("dry", on unless --ez dry false) instead of calling the phone.
 *
 *   adb push hey.wav /sdcard/Android/data/com.vinhnguyen.watchai/files/hey.wav
 *   adb shell am broadcast -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.HearWav --es file hey.wav
 *
 * --ef level 0.02 plays it as loud as speech reaches a watch mic (its loudest 100 ms at that RMS).
 */
class HearWav : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        CallService.dryWake = intent.getBooleanExtra("dry", true)
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, intent.getStringExtra("file") ?: return)
        if (file.parentFile?.canonicalPath != dir.canonicalPath || !file.isFile) {
            Log.w(TAG, "no such file in ${dir.path}")
            return
        }
        val clip = Wav.read16kMono(file.readBytes())
        val level = intent.getFloatExtra("level", 0f)
        val pcm = if (clip == null || level <= 0f) clip else scaled(clip, level / WakeWord.peakLevel(clip))
        if (pcm == null) {
            Log.w(TAG, "not a 16 kHz mono 16-bit WAV")
            return
        }
        Log.i(TAG, if (CallService.testWakeWord(pcm)) "playing ${pcm.size / 16} ms of ${file.name} to the wake word" else "\"Hey Buddy\" is off")
    }

    private fun scaled(
        pcm: ShortArray,
        by: Float,
    ) = ShortArray(pcm.size) { (pcm[it] * by).toInt().coerceIn(-32_768, 32_767).toShort() }

    private companion object {
        const val TAG = "HearWav"
    }
}
