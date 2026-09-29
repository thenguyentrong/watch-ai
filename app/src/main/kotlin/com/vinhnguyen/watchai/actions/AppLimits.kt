package com.vinhnguyen.watchai.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.vinhnguyen.watchai.brain.guard.LocalJudge

/**
 * The apps Buddy may use: only the ones the user turned on ("Apps Buddy can use"). It opens those,
 * uses them on the screen and reads their messages; every other app is off. Some can't be turned
 * on at all, by what the phone itself says about them (there's no list of apps or words here: no
 * list covers every country's banks):
 *
 * - whatever opens the phone's settings or a store link (settings, app stores): a tap there can
 *   change security or spend money;
 * - password managers and passkey providers (they fill in passwords for other apps), authenticators
 *   (they open otpauth links, the standard for sign-in codes), apps that pay by tapping the phone;
 * - anything that can't be opened from the home screen (system screens, installers).
 *
 * The user can also turn an app on in a conversation, with their yes ([whenOff]), unless the phone's
 * own model takes it for a money or secrets app: those only by hand, in the list.
 */
class AppLimits(
    context: Context,
    private val judge: LocalJudge?,
) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val verdicts = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val choices = appContext.getSharedPreferences(CHOICES_PREFS, Context.MODE_PRIVATE)

    @Volatile private var known: Known? = null

    private class Known(
        val at: Long,
        val openable: Set<String>,
        val never: Set<String>,
    )

    /** Whether Buddy may open and use [packageName]: the user turned it on, and it isn't one that's always off. */
    fun allowed(packageName: String): Boolean = !alwaysOff(packageName) && on(packageName)

    /** The phone's settings, stores, passwords, sign-in codes, tap-to-pay, anything without an icon: never on. */
    fun alwaysOff(packageName: String): Boolean {
        val k = known()
        return packageName == appContext.packageName || packageName !in k.openable || packageName in k.never
    }

    /** Whether Buddy reads [packageName]'s messages: only an app the user turned on. */
    fun readsMessages(packageName: String): Boolean = packageName !in known().never && on(packageName)

    /** Kept from notifications at all: everything but the apps that are always off, so turning one on later has its messages. */
    fun keepsMessages(packageName: String): Boolean = packageName !in known().never

    enum class Status { ON, OFF, ALWAYS_OFF }

    data class App(
        val packageName: String,
        val label: String,
        val status: Status,
    )

    /** The apps on the home screen and whether Buddy may use each, by name. */
    fun apps(): List<App> {
        val k = known()
        return (k.openable - appContext.packageName)
            .map { pkg ->
                val status =
                    when {
                        pkg in k.never -> Status.ALWAYS_OFF
                        on(pkg) -> Status.ON
                        else -> Status.OFF
                    }
                App(pkg, label(pkg), status)
            }.sortedBy { it.label.lowercase() }
    }

    fun choose(
        packageName: String,
        on: Boolean,
    ) {
        choices.edit().putBoolean(packageName, on).apply()
    }

    /**
     * What a tool answers when the user wants Buddy in [packageName] and it's off: a proposal to turn
     * it on, which waits for their yes like a text does (by voice, or the pop-up's button), then runs
     * [then]; or why it can't be. A money or secrets app, as the phone's model sees it, or one it can't
     * tell about, is only turned on by hand in the list: no text in an app can talk Buddy into it.
     */
    suspend fun whenOff(
        packageName: String,
        pending: Pending,
        cards: CardHub?,
        then: suspend () -> String,
    ): String {
        val name = label(packageName)
        if (alwaysOff(packageName)) {
            return "error: $name is an app Buddy never uses (the phone's settings, a store, passwords, sign-in codes or payments). " +
                "The user has to do this themselves."
        }
        if (verdict(packageName) != false) {
            return "error: $name is off for Buddy, and it looks like a banking, payment or password app, so only the user can turn it " +
                "on, by hand in Buddy's app (Apps Buddy can use). Tell them."
        }
        cards?.show(BuddyCard.Ask("Let Buddy use $name?", "It can then open it, use it for you and read its messages. Turn it off any time.", "Turn on", packageName))
        return pending.propose("Turn on $name for Buddy (only apps the user turns on are used), so it can open it, use it and read its messages.") {
            choose(packageName, true)
            then()
        }
    }

    fun label(packageName: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

    /** For "Delete everything": the model's answers and the user's choices. */
    fun clear() {
        verdicts.edit().clear().apply()
        choices.edit().clear().apply()
    }

    private fun on(packageName: String): Boolean = choices.getBoolean(packageName, false)

    /** True for a money or secrets app, as the phone's model sees it; asked once per version of the app. */
    private suspend fun verdict(packageName: String): Boolean? {
        stored(packageName)?.let { return it }
        val version = version(packageName) ?: return null
        val kind = judge?.pick(question(label(packageName), packageName), CHOICES) ?: return null
        val yes = kind != OTHER
        verdicts.edit().putString(packageName, "$version:${if (yes) 1 else 0}").apply()
        return yes
    }

    /** The model's answer for this version of the app; a new version is asked again. */
    private fun stored(packageName: String): Boolean? {
        val saved = verdicts.getString(packageName, null) ?: return null
        if (saved.substringBefore(':') != version(packageName)?.toString()) return null
        return saved.endsWith(":1")
    }

    private fun known(): Known {
        known?.takeIf { SystemClock.elapsedRealtime() - it.at < FRESH_MS }?.let { return it }
        val openable = activities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        val never =
            buildSet {
                addAll(activities(Intent(Settings.ACTION_SETTINGS)))
                addAll(activities(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${appContext.packageName}"))))
                addAll(services(Intent(AUTOFILL)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) addAll(services(Intent(CREDENTIALS)))
                addAll(activities(Intent(Intent.ACTION_VIEW, Uri.parse("otpauth://totp/Buddy?secret=BUDDY"))))
                addAll(services(Intent(TAP_TO_PAY)))
            } - appContext.packageName
        return Known(SystemClock.elapsedRealtime(), openable, never).also { known = it }
    }

    private fun activities(intent: Intent): Set<String> = runCatching { pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet() }.getOrDefault(emptySet())

    private fun services(intent: Intent): Set<String> = runCatching { pm.queryIntentServices(intent, 0).map { it.serviceInfo.packageName }.toSet() }.getOrDefault(emptySet())

    private fun version(packageName: String): Long? = try {
        pm.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    internal companion object {
        private const val PREFS = "app_limits"
        private const val CHOICES_PREFS = "app_limits_user"
        private const val FRESH_MS = 60_000L
        private const val AUTOFILL = "android.service.autofill.AutofillService"
        private const val CREDENTIALS = "android.service.credentials.CredentialProviderService"
        private const val TAP_TO_PAY = "android.nfc.cardemulation.action.HOST_APDU_SERVICE"

        /** What the phone's model sorts an app into; only an [OTHER] app can be turned on in a conversation. */
        const val OTHER = "other"
        val CHOICES = listOf("money", "secrets", OTHER)

        /**
         * What the phone's model is asked about an app, once per version of it. The examples are made
         * up; they only show where the line is.
         */
        fun question(
            label: String,
            packageName: String,
        ) = "Say what the main job of a phone app is: money (banking, a wallet, paying people, investing, trading, crypto), secrets " +
            "(passwords, passkeys, sign-in codes, ID documents) or other (anything else, also an app that sells things or chats and " +
            "can take a payment).\n" +
            "Examples:\n" +
            "\"Northwind Bank\" (com.northwind.mobilebanking): money\n" +
            "\"ChatNow\" (com.chatnow.messenger): other\n" +
            "\"PocketPay\" (com.pocketpay.wallet): money\n" +
            "\"ShopMart\" (com.shopmart.android): other\n" +
            "\"KeyVault\" (com.keyvault.passwords): secrets\n" +
            "\"RideGo\" (com.ridego.passenger): other\n" +
            "\"CoinStack\" (com.coinstack.exchange): money\n" +
            "\"TuneBox\" (com.tunebox.music): other\n" +
            "Now:\n\"$label\" ($packageName):"
    }
}
