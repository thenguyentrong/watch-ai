package com.vinhnguyen.watchai.ondevice

import android.content.Context
import androidx.core.content.edit

/** Is the app on screen right now? Gemini Nano only answers while it is. */
fun interface AppForeground {
    fun isForeground(): Boolean
}

/** Non-secret switches for the on-device brains. */
class OnDeviceSettings(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("ondevice", Context.MODE_PRIVATE)

    /** Gemini Nano runs through ML Kit, which sends Google diagnostics - so it's opt-in. */
    var nanoOptIn: Boolean
        get() = prefs.getBoolean(KEY_NANO, false)
        set(value) = prefs.edit { putBoolean(KEY_NANO, value) }

    var modelId: String
        get() = prefs.getString(KEY_MODEL, ModelCatalog.DEFAULT_ID) ?: ModelCatalog.DEFAULT_ID
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    /** Remembered per phone model + engine version + model file, so a broken GPU isn't retried every time. */
    fun isGpuBroken(key: String): Boolean = prefs.getBoolean("gpu_broken_$key", false)

    fun markGpuBroken(key: String) = prefs.edit { putBoolean("gpu_broken_$key", true) }

    fun clearAll() = prefs.edit { clear() }

    private companion object {
        const val KEY_NANO = "nano_opt_in"
        const val KEY_MODEL = "model_id"
    }
}
