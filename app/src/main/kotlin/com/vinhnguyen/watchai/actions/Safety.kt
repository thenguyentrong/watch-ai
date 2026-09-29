package com.vinhnguyen.watchai.actions

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.vinhnguyen.watchai.actions.screen.ScreenActions
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
            // Only reads back; the app is turned on with the yes below.
            PhoneShortcuts.TURN_ON_APP to Level.LOCAL,
            PhoneShortcuts.NAVIGATE to Level.LOCAL,
            PhoneShortcuts.FLASHLIGHT to Level.LOCAL,
            // Using an app: looking at its controls, tapping, typing and scrolling. A tap that sends, pays,
            // posts or deletes waits for a yes (the phone's model judges it), and apps Buddy stays out of are refused (AppLimits).
            ScreenActions.LOOK to Level.LOCAL,
            ScreenActions.FIND to Level.LOCAL,
            ScreenActions.TAP to Level.LOCAL,
            ScreenActions.TYPE to Level.LOCAL,
            ScreenActions.SCROLL to Level.LOCAL,
            ScreenActions.BACK to Level.LOCAL,
            ScreenActions.READ to Level.PRIVATE,
            PhoneActions.LIST_NOTES to Level.PRIVATE,
            PhoneActions.LIST_EVENTS to Level.PRIVATE,
            ReachActions.READ_MESSAGES to Level.PRIVATE,
            // These only read a text or call back; it goes out with the yes below.
            ReachActions.SEND_TEXT to Level.OUTBOUND,
            ReachActions.REPLY to Level.OUTBOUND,
            ReachActions.CALL to Level.OUTBOUND,
            // A no is harmless; the Guard checks a yes as outbound.
            ReachActions.CONFIRM to Level.LOCAL,
            // Buddy's memory, on the phone: only what the user just said is kept (MemoryActions), and the
            // conversations it finds were ChatGPT's own already.
            MemoryActions.REMEMBER to Level.LOCAL,
            MemoryActions.FORGET to Level.LOCAL,
            MemoryActions.RECALL to Level.LOCAL,
        )

    /** What the phone's own model is told it's reading, per tool. */
    val READABLE: Map<String, String> =
        mapOf(
            ReachActions.READ_MESSAGES to "messages people sent",
            PhoneActions.LIST_NOTES to "notes",
            PhoneActions.LIST_EVENTS to "calendar events",
            ScreenActions.READ to "phone screen (an app the user opened: their chats, emails, whatever it shows)",
        )

    /**
     * Private tools whose data may go to ChatGPT (cleaned) when the user turns "Private on the phone"
     * off in Settings. What's on an app's screen isn't one of them: it always stays on the phone.
     */
    val MAY_GO_TO_CLOUD: Set<String> = setOf(ReachActions.READ_MESSAGES, PhoneActions.LIST_NOTES, PhoneActions.LIST_EVENTS)
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
