package com.vinhnguyen.watchai.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vinhnguyen.watchai.watchlink.Wav
import java.io.File

/**
 * Debug builds only: says a WAV (16 kHz, mono, 16-bit) out of the watch speaker, so "Hey Buddy"
 * can be tested through the real mic, timing included. Synthetic speech only. While testing, a
 * heard phrase only ticks and logs ("dry", on unless --ez dry false) instead of calling the phone.
 *
 *   adb shell am broadcast -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.SpeakWav --es file hey.wav
 */
class SpeakWav : BroadcastReceiver() {
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
        val pcm = Wav.read16kMono(file.readBytes())
        if (pcm == null) {
            Log.w(TAG, "not a 16 kHz mono 16-bit WAV")
            return
        }
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Log.i(TAG, "saying ${file.name} (${pcm.size / 16} ms)${if (CallService.dryWake) ", dry" else ""}")
        Handler(Looper.getMainLooper()).postDelayed({ track.release() }, pcm.size / 16L + 500)
    }

    private companion object {
        const val TAG = "SpeakWav"
        const val SAMPLE_RATE = 16_000
    }
}
