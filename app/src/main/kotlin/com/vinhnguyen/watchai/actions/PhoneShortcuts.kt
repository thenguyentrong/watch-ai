package com.vinhnguyen.watchai.actions

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.ToolSpec
import com.vinhnguyen.watchai.brain.Toolbox

/**
 * Things that show on the phone: open an app, directions in Maps, and the flashlight. Opening
 * something from a pocket needs the user's "Appear on top" OK (Android blocks it otherwise), and
 * a locked phone shows it once unlocked.
 */
class PhoneShortcuts(
    context: Context,
    private val logger: BrainLogger = BrainLogger.None,
    /** The Buddy app is on screen: Android lets it open things then without the OK. */
    private val foreground: () -> Boolean = { false },
) : Toolbox {
    private val appContext = context.applicationContext

    override fun tools(): List<ToolSpec> = SPECS

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        val args = ActionArgs.parse(argumentsJson) ?: return done(name, "invalid", "error: the arguments were not valid JSON")
        return try {
            when (name) {
                OPEN_APP -> ActionArgs.text(args, "name", NAME_MAX)?.let { openApp(it) } ?: done(name, "invalid", "error: say which app")
                NAVIGATE -> ActionArgs.text(args, "place", PLACE_MAX)?.let { navigate(it, ActionArgs.choice(args, "mode", MODES.keys)) } ?: done(name, "invalid", "error: say where to")
                FLASHLIGHT -> ActionArgs.bool(args, "on")?.let { flashlight(it) } ?: done(name, "invalid", "error: on must be true or false")
                else -> done(name, "unknown", "error: there is no action called $name")
            }
        } catch (e: SecurityException) {
            done(name, "denied", "error: the phone refused this action")
        }
    }

    private fun openApp(said: String): String {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps =
            pm
                .queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != appContext.packageName }
        val found = NameMatch.best(said, apps) { it.second }
        return when {
            found.isEmpty() -> done(OPEN_APP, "invalid", "error: there's no app called $said on the phone")

            found.size > 1 -> done(OPEN_APP, "invalid", "several apps match: ${found.take(5).joinToString(", ") { it.second }}; ask which one")

            else -> {
                val (pkg, label) = found.first()
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return done(OPEN_APP, "failed", "error: $label can't be opened from here")
                show(OPEN_APP, intent, "opened $label on the phone")
            }
        }
    }

    private fun navigate(
        place: String,
        mode: String?,
    ): String {
        // Google's own directions link: the Maps app takes it, or the browser if there's none.
        val uri =
            Uri
                .parse("https://www.google.com/maps/dir/")
                .buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", place)
                .appendQueryParameter("travelmode", MODES[mode ?: "driving"])
                .appendQueryParameter("dir_action", "navigate")
                .build()
        return show(NAVIGATE, Intent(Intent.ACTION_VIEW, uri), "directions to $place are open on the phone")
    }

    private fun flashlight(on: Boolean): String {
        val cameras = appContext.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return done(FLASHLIGHT, "failed", "error: this phone has no flashlight")
        return try {
            cameras.setTorchMode(id, on)
            done(FLASHLIGHT, "ok", "ok: flashlight ${if (on) "on" else "off"}")
        } catch (e: CameraAccessException) {
            done(FLASHLIGHT, "failed", "error: the camera is busy, so the flashlight can't be switched now")
        }
    }

    /** Opens [intent] on the phone, or says why it can't. */
    private fun show(
        tool: String,
        intent: Intent,
        what: String,
    ): String {
        if (!foreground() && !Settings.canDrawOverlays(appContext)) {
            return done(tool, "denied", "error: Android only lets Buddy open things on the phone with its OK. Tell the user to allow \"Phone clock from your pocket\" in the Buddy app, under Things it can do.")
        }
        return try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val locked = appContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            done(tool, "ok", "ok: $what" + if (locked) "; the phone is locked, so it shows once the user unlocks it" else "")
        } catch (e: ActivityNotFoundException) {
            done(tool, "failed", "error: nothing on the phone can open that")
        }
    }

    private fun done(
        tool: String,
        outcome: String,
        result: String,
    ): String {
        logger.log(LogEvent.ToolUsed(tool, outcome))
        return result
    }

    companion object {
        const val OPEN_APP = "open_app"
        const val NAVIGATE = "navigate_to"
        const val FLASHLIGHT = "flashlight"

        private const val NAME_MAX = 80
        private const val PLACE_MAX = 200
        private val MODES = mapOf("driving" to "driving", "walking" to "walking", "cycling" to "bicycling", "transit" to "transit")

        val SPECS =
            listOf(
                ToolSpec(
                    OPEN_APP,
                    "Open an app on the user's phone by its name ('open Spotify'). It shows on the phone's screen.",
                    """{"type":"object","properties":{"name":{"type":"string","description":"The app's name as the user said it."}},"required":["name"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    NAVIGATE,
                    "Start directions on the phone in Google Maps to a place or address the user said.",
                    """{"type":"object","properties":{"place":{"type":"string"},"mode":{"type":"string","enum":["driving","walking","cycling","transit"],"description":"Only if the user said how; default driving."}},"required":["place"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    FLASHLIGHT,
                    "Switch the phone's flashlight on or off.",
                    """{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"],"additionalProperties":false}""",
                ),
            )
    }
}
