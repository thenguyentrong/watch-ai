package com.vinhnguyen.watchai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Puts the phone in "call" audio mode for a voice session: the echo canceller works on the mic,
 * the answer plays on a headset if one is connected (else the loudspeaker, not the earpiece), and
 * music or videos pause until the session ends.
 */
internal class CallAudio(
    context: Context,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var active = false

    /** Where the answer plays, e.g. "loudspeaker" (for the lab screen). */
    var output: String = "phone"
        private set

    /** What Android offered for this call, e.g. "loudspeaker, Bluetooth headset (Galaxy Watch5)" (for the lab screen). */
    var offered: String = ""
        private set

    @Synchronized
    fun enter() {
        if (active) return
        active = true
        focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(SPEECH)
                .build()
                .also { audioManager.requestAudioFocus(it) }
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = audioManager.availableCommunicationDevices
        offered = devices.joinToString { d -> d.label() + (d.productName?.toString()?.takeIf { it.isNotBlank() && d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && d.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { " ($it)" } ?: "") }
        val device = PREFERRED.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
        if (device != null && audioManager.setCommunicationDevice(device)) output = device.label()
    }

    @Synchronized
    fun exit() {
        if (!active) return
        active = false
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = previousMode
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun AudioDeviceInfo.label(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "loudspeaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        else -> "device type $type"
    }

    companion object {
        val SPEECH: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        private val PREFERRED =
            listOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
    }
}
