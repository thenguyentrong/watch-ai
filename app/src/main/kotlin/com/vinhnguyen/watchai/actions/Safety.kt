package com.vinhnguyen.watchai.actions

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.vinhnguyen.watchai.brain.guard.Level
import com.vinhnguyen.watchai.brain.guard.Presence

/** How much each of Buddy's actions can do (see docs/security/agent-safety.md). A new tool isn't offered until it's in here. */
object Safety {
    val LEVELS: Map<String, Level> =
        mapOf(
            PhoneActions.DEVICE_STATUS to Level.LOOKUP,
            ConversationActions.END to Level.LOOKUP,
            PhoneActions.ADD_NOTE to Level.LOCAL,
            PhoneActions.ADD_EVENT to Level.LOCAL,
            PhoneActions.ADD_REMINDER to Level.LOCAL,
            PhoneActions.SET_TIMER to Level.LOCAL,
            PhoneActions.SET_ALARM to Level.LOCAL,
            PhoneActions.RING_PHONE to Level.LOCAL,
            PhoneActions.STOP_RINGING to Level.LOCAL,
            PhoneActions.MEDIA to Level.LOCAL,
            PhoneActions.SET_VOLUME to Level.LOCAL,
            PhoneActions.SET_RINGER to Level.LOCAL,
            PhoneActions.DO_NOT_DISTURB to Level.LOCAL,
            PhoneShortcuts.OPEN_APP to Level.LOCAL,
            PhoneShortcuts.NAVIGATE to Level.LOCAL,
            PhoneShortcuts.FLASHLIGHT to Level.LOCAL,
            PhoneActions.LIST_NOTES to Level.PRIVATE,
            PhoneActions.LIST_EVENTS to Level.PRIVATE,
            ReachActions.READ_MESSAGES to Level.PRIVATE,
            // These only read a text or call back; it goes out with the yes below.
            ReachActions.SEND_TEXT to Level.OUTBOUND,
            ReachActions.REPLY to Level.OUTBOUND,
            ReachActions.CALL to Level.OUTBOUND,
            // A no is harmless; the Guard checks a yes as outbound.
            ReachActions.CONFIRM to Level.LOCAL,
        )

    /** What the phone's own model is told it's reading, per tool. */
    val READABLE: Map<String, String> =
        mapOf(
            ReachActions.READ_MESSAGES to "messages people sent",
            PhoneActions.LIST_NOTES to "notes",
            PhoneActions.LIST_EVENTS to "calendar events",
        )
}

/**
 * Whether the owner is there for a yes: the phone unlocked, earbuds or headphones on the phone, or
 * (in a watch call) the watch unlocked, which it only is on the wrist when it has a screen lock.
 * [watchUnlocked] is null when the watch can't say.
 */
class OwnerPresence(
    context: Context,
    private val watchUnlocked: suspend () -> Boolean?,
) : Presence {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    override suspend fun check(): Boolean? = when {
        !keyguard.isDeviceLocked -> true
        earbudsIn() -> true
        else -> watchUnlocked()
    }

    private fun earbudsIn(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
        device.type in PERSONAL_OUTPUTS && device.productName?.contains("watch", ignoreCase = true) != true
    }

    private companion object {
        val PERSONAL_OUTPUTS =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
    }
}

/**
 * Apps whose notifications Buddy never reads, even with notification access: banking, payments,
 * password managers, authenticators and crypto wallets. Codes from ordinary text messages are
 * masked on the way to the model instead.
 */
object SensitiveApps {
    private val PACKAGES =
        setOf(
            // Authenticators and password managers.
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.x8bit.bitwarden",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "com.keepersecurity",
            "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free",
            "com.samsung.android.samsungpassautofill",
            // Payments and wallets.
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay",
            "com.klarna.android",
            // Banks.
            "de.number26.android",
            "com.revolut.revolut",
            "com.starfinanz.smob.android.sfinanzstatus",
            "com.starfinanz.smob.android.sbanking",
            "de.dkb.portalapp",
            "de.comdirect.android",
            "de.commerzbanking.mobil",
            "com.db.pwcc.dbmobile",
            "de.ingdiba.bankingapp",
            "de.consorsbank",
            "de.postbank.finanzassistent",
            "com.c24.bankapp",
            "de.traderepublic.app",
            "com.wise.android",
            // Crypto.
            "com.coinbase.android",
            "com.binance.dev",
            "com.kraken.trade",
            "com.krakenfutures",
        )
    private val WORDS = listOf("bank", "authenticator", "password", "passwort", "wallet", "crypto", "finanz", "sparkasse", "volksbank", "raiffeisen")

    fun hidden(packageName: String): Boolean = packageName in PACKAGES || WORDS.any { packageName.contains(it, ignoreCase = true) }
}
