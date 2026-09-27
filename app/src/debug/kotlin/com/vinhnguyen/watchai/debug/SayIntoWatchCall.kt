package com.vinhnguyen.watchai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vinhnguyen.watchai.WatchAiApp
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val pcm = readWav(file.readBytes())
        if (pcm == null) {
            Timber.tag(TAG).w("not a 16 kHz mono 16-bit WAV")
            return
        }
        val saying = (context.applicationContext as WatchAiApp).graph.watchCalls.say(pcm)
        Timber.tag(TAG).i(if (saying) "saying ${pcm.size / 16} ms of ${file.name}" else "no watch call to say it into")
    }

    /** The samples of a plain PCM WAV, or null if it isn't 16 kHz mono 16-bit. */
    private fun readWav(bytes: ByteArray): ShortArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var at = 12
        var formatOk = false
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4)
            val size = b.getInt(at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> formatOk = b.getShort(body).toInt() == 1 && b.getShort(body + 2).toInt() == 1 && b.getInt(body + 4) == 16_000 && b.getShort(body + 14).toInt() == 16

                "data" -> {
                    if (!formatOk) return null
                    val n = minOf(size, bytes.size - body) / 2
                    return ShortArray(n) { b.getShort(body + it * 2) }
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }

    private companion object {
        const val TAG = "SayIntoWatchCall"
    }
}
