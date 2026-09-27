package com.vinhnguyen.watchai.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Screens that show answers, device codes or account details: no screenshots, no screen recording,
 * no thumbnail in the recent-apps switcher, and no overlays drawn on top.
 */
fun Activity.protectScreen(enabled: Boolean) {
    if (enabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!enabled)
    window.setHideOverlayWindows(enabled)
}
