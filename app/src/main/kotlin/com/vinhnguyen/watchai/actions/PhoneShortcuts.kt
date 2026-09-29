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
    private val cards: CardHub? = null,
    /** Buddy only opens the apps the user turned on. */
    private val limits: AppLimits? = null,
    /** Turning an app on waits for the user's yes, like a text. */
    private val pending: Pending? = null,
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
                TURN_ON_APP -> ActionArgs.text(args, "name", NAME_MAX)?.let { turnOn(it) } ?: done(name, "invalid", "error: say which app")
                NAVIGATE -> ActionArgs.text(args, "place", PLACE_MAX)?.let { navigate(it, ActionArgs.choice(args, "mode", MODES.keys)) } ?: done(name, "invalid", "error: say where to")
                FLASHLIGHT -> ActionArgs.bool(args, "on")?.let { flashlight(it) } ?: done(name, "invalid", "error: on must be true or false")
                else -> done(name, "unknown", "error: there is no action called $name")
            }
        } catch (e: SecurityException) {
            done(name, "denied", "error: the phone refused this action")
        }
    }

    /** An app that's off is turned on first, with the user's yes, and then opened. */
    private suspend fun openApp(said: String): String {
        val (pkg, label) = find(said).let { found -> found.singleOrNull() ?: return done(OPEN_APP, "invalid", unclear(said, found)) }
        val limits = limits
        if (limits != null && !limits.allowed(pkg)) {
            val pending = pending ?: return done(OPEN_APP, "refused", "error: $label is off for Buddy; the user can turn it on in Buddy's app, Apps Buddy can use")
            return limits.whenOff(pkg, pending, cards) { open(pkg, label) }.let { done(OPEN_APP, outcome(it), it) }
        }
        return open(pkg, label)
    }

    private suspend fun turnOn(said: String): String {
        val (pkg, label) = find(said).let { found -> found.singleOrNull() ?: return done(TURN_ON_APP, "invalid", unclear(said, found)) }
        val limits = limits ?: return done(TURN_ON_APP, "failed", "error: apps can't be turned on here")
        if (limits.allowed(pkg)) return done(TURN_ON_APP, "ok", "ok: $label is already on for Buddy")
        val pending = pending ?: return done(TURN_ON_APP, "refused", "error: the user can turn $label on in Buddy's app, Apps Buddy can use")
        return limits.whenOff(pkg, pending, cards) { "ok: $label is on for Buddy now; go on with what the user asked" }.let { done(TURN_ON_APP, outcome(it), it) }
    }

    /** The apps on the home screen whose names match what the user said. */
    private fun find(said: String): List<Pair<String, String>> {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps =
            pm
                .queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != appContext.packageName }
        return NameMatch.best(said, apps) { it.second }
    }

    private fun unclear(
        said: String,
        found: List<Pair<String, String>>,
    ) = if (found.isEmpty()) "error: there's no app called $said on the phone" else "several apps match: ${found.take(5).joinToString(", ") { it.second }}; ask which one"

    private fun open(
        pkg: String,
        label: String,
    ): String {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return done(OPEN_APP, "failed", "error: $label can't be opened from here")
        return show(OPEN_APP, intent, "opened $label on the phone").also { if (it.startsWith("ok")) cards?.show(BuddyCard.App(pkg, "Opened $label")) }
    }

    private fun outcome(result: String) = when {
        result.startsWith("not done yet") -> "proposed"
        result.startsWith("error") -> "refused"
        else -> "ok"
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
        return show(NAVIGATE, Intent(Intent.ACTION_VIEW, uri), "directions to $place are open on the phone").also { if (it.startsWith("ok")) cards?.show(BuddyCard.Place(place)) }
    }

    private fun flashlight(on: Boolean): String {
        val cameras = appContext.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return done(FLASHLIGHT, "failed", "error: this phone has no flashlight")
        return try {
            cameras.setTorchMode(id, on)
            cards?.show(BuddyCard.Done(Symbol.LIGHT, if (on) "Flashlight on" else "Flashlight off"))
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
            return done(tool, "denied", "error: Android only lets Buddy open things on the phone with its OK. Tell the user to allow \"Open apps and directions\" in the Buddy phone app: in the menu, What Buddy can do.")
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
        const val TURN_ON_APP = "turn_on_app"
        const val NAVIGATE = "navigate_to"
        const val FLASHLIGHT = "flashlight"

        private const val NAME_MAX = 80
        private const val PLACE_MAX = 200
        private val MODES = mapOf("driving" to "driving", "walking" to "walking", "cycling" to "bicycling", "transit" to "transit")

        val SPECS =
            listOf(
                ToolSpec(
                    OPEN_APP,
                    "Open an app on the user's phone by its name ('open Spotify'). It shows on the phone's screen. To do something in it " +
                        "(find a chat, read the newest message or email, search for something), go on with look_at_screen, then " +
                        "find_on_screen, tap and type_text step by step, and finish with read_screen and an exact instruction. Buddy only " +
                        "uses the apps the user turned on: for one that's off, this reads back turning it on and waits for their yes.",
                    """{"type":"object","properties":{"name":{"type":"string","description":"The app's name as the user said it."}},"required":["name"],"additionalProperties":false}""",
                ),
                ToolSpec(
                    TURN_ON_APP,
                    "Turn an app on for Buddy when the user wants Buddy to use it or read its messages: Buddy only uses the apps the user " +
                        "turned on. Reads back and waits for the user's yes. Banking, payment, password and settings apps can't be turned " +
                        "on this way: the user does that by hand in Buddy's app.",
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
