package com.vinhnguyen.watchai.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vinhnguyen.watchai.watchlink.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: "Learn my voice" from WAV takes (16 kHz mono, synthetic voices reading the
 * teach screen's sentences) instead of the mic, and logs what it learned. With --ez apply true the lesson is used, as the teach screen
 * does; --ez forget true goes back to the default phrase. "Hey Buddy" must be on.
 *
 *   adb shell am broadcast -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.TeachWav --es files a.wav,b.wav --ez apply true
 */
class TeachWav : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(null) ?: return
        val names = intent.getStringExtra("files")?.split(",")?.map { it.trim() }.orEmpty()
        val apply = intent.getBooleanExtra("apply", false)
        if (intent.getBooleanExtra("forget", false)) {
            CallService.learned(app, null)
            Log.i(TAG, "back to the default phrase")
            return
        }
        // Longer than a receiver may take; the call service keeps the process alive meanwhile.
        scope.launch {
            val takes =
                names.mapNotNull { name ->
                    File(dir, name).takeIf { it.parentFile?.canonicalPath == dir.canonicalPath && it.isFile }?.let { Wav.read16kMono(it.readBytes()) }
                }
            val word = CallService.wakeModel()
            if (takes.isEmpty() || word == null) {
                Log.w(TAG, if (word == null) "\"Hey Buddy\" is off" else "no takes in ${dir.path}")
                return@launch
            }
            val teacher = VoiceTeacher(app)
            try {
                val lesson = teacher.learn(takes, takes.map { teacher.spelling(teacher.phrase(it)) }, word)
                Log.i(TAG, "heard ${lesson.before}/${lesson.takes} before, ${lesson.after} after${if (apply) ", in use now" else ""}")
                if (apply) CallService.learned(app, lesson.keywords)
            } finally {
                teacher.release()
            }
        }
    }

    private companion object {
        const val TAG = "TeachWav"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
