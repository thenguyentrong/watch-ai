package com.vinhnguyen.watchai.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vinhnguyen.watchai.watchlink.Wav
import java.io.File

/**
 * Debug builds only: plays a WAV (16 kHz, mono, 16-bit) to "Hey Buddy" as if the watch heard it,
 * to test the wake word without talking. Use synthetic speech only; "Hey Buddy" must be on.
 *
 *   adb push hey.wav /sdcard/Android/data/com.vinhnguyen.watchai/files/hey.wav
 *   adb shell am broadcast -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.HearWav --es file hey.wav
 */
class HearWav : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, intent.getStringExtra("file") ?: return)
        if (file.parentFile?.canonicalPath != dir.canonicalPath || !file.isFile) {
            Log.w(TAG, "no such file in ${dir.path}")
            return
        }
        val pcm = Wav.read16kMono(file.readBytes())
        if (pcm == null) {
            Log.w(TAG, "not a 16 kHz mono 16-bit WAV")
            return
        }
        Log.i(TAG, if (CallService.testWakeWord(pcm)) "playing ${pcm.size / 16} ms of ${file.name} to the wake word" else "\"Hey Buddy\" is off")
    }

    private companion object {
        const val TAG = "HearWav"
    }
}
