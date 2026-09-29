package com.vinhnguyen.watchai.actions

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vinhnguyen.watchai.WatchAiApp

/**
 * Messages the user got, as their notifications show them, from any messaging app (WhatsApp,
 * Signal, Telegram, Messages…), so Buddy can read them out and reply through the app's own reply
 * button, like a car or a watch does. Only with the user's OK (notification access). Kept in memory
 * for a few hours at most, never stored, never logged; gone once the notification is.
 */
class MessageInbox {
    data class Message(
        val key: String,
        val packageName: String,
        val app: String,
        val from: String,
        /** The chat's name when it's a group. */
        val chat: String?,
        val text: String,
        val at: Long,
        val reply: Notification.Action?,
    )

    private val messages = ArrayList<Message>()

    @Synchronized
    fun add(message: Message) {
        messages.removeAll { it.key == message.key }
        messages += message
        val oldest = message.at - KEEP_MS
        messages.removeAll { it.at < oldest }
        while (messages.size > MAX) messages.removeAt(0)
    }

    @Synchronized
    fun remove(key: String) {
        messages.removeAll { it.key == key }
    }

    /** Forgets every message now (the user asked). New ones are still noticed. */
    @Synchronized
    fun clear() {
        messages.clear()
    }

    /** Newest first. */
    @Synchronized
    fun recent(now: Long): List<Message> = messages.filter { now - it.at < KEEP_MS }.sortedByDescending { it.at }

    /** Answers through the app's own reply button; false if it has none (or it's gone). */
    fun reply(
        context: Context,
        message: Message,
        text: String,
    ): Boolean {
        val action = message.reply ?: return false
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent) }.isSuccess
    }

    companion object {
        private const val MAX = 40
        private const val KEEP_MS = 6 * 60 * 60 * 1_000L

        fun allowed(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}

/** Fills [MessageInbox] from the phone's notifications (the user turns it on in Android's settings). */
class BuddyNotificationListener : NotificationListenerService() {
    private val graph get() = (application as WatchAiApp).graph
    private val inbox get() = graph.inbox

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Password managers, authenticators, payment and system apps: never kept, whatever they post. The rest waits
        // in memory; only the apps the user turned on are read (ReachActions).
        if (!graph.limits.keepsMessages(sbn.packageName)) return
        val n = sbn.notification
        // A group summary repeats the chats inside it.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style == null && n.category != Notification.CATEGORY_MESSAGE) return
        val last = style?.messages?.lastOrNull()
        val from = (last?.person?.name ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.takeIf { it.isNotBlank() } ?: return
        val text = (last?.text ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.takeIf { it.isNotBlank() } ?: return
        val chat = style?.conversationTitle?.toString()?.takeIf { style.isGroupConversation && it != from }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        inbox.add(MessageInbox.Message(sbn.key, sbn.packageName, app, from, chat, text.take(TEXT_MAX), sbn.postTime, reply))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        inbox.remove(sbn.key)
    }

    private companion object {
        const val TEXT_MAX = 1_000
    }
}
