package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.actions.MessageInbox
import kotlinx.coroutines.delay

/**
 * Exactly what Buddy has from the phone's notifications right now: the chat messages it keeps in
 * memory, with who, which app, when and the text. Updates as messages come and go. Only on this screen.
 */
@Composable
fun InboxScreen(inbox: MessageInbox) {
    val p = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(inbox) {
        while (true) {
            now = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }
    val messages = remember(now) { inbox.recent(now) }

    Text(
        "Everything Buddy has from your notifications right now. Nothing else: no older messages, nothing from other apps.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    if (messages.isEmpty()) {
        Group {
            Item("Nothing right now", subtitle = "New chat messages show up here as they arrive.", last = true)
        }
    } else {
        Group("${messages.size} ${if (messages.size == 1) "message" else "messages"}") {
            messages.forEachIndexed { i, m ->
                Item(
                    listOfNotNull(m.from, m.chat?.let { "in $it" }).joinToString(" "),
                    subtitle = "${m.app} · ${ago(now - m.at)}\n${m.text}",
                    painter = painterResource(R.drawable.sym_forum),
                    last = i == messages.lastIndex,
                )
            }
        }
    }
    Group(
        "How it works",
        footer = "Only chat messages that arrive while this is allowed (WhatsApp, Signal, Telegram, SMS and similar), never banking, " +
            "payment, password or authenticator apps. Kept in memory only: at most 40, for at most 6 hours, and each one goes as " +
            "soon as its notification does (for example when you open the chat). Nothing is saved or sent anywhere until you ask " +
            "Buddy about your messages; with \"Private things stay on this phone\" only Gemma on this phone reads them.",
    ) {
        Item(
            "Forget them now",
            subtitle = "Buddy keeps noticing new messages; turn it off in Android's notification access to stop that",
            danger = true,
            last = true,
            onClick = {
                inbox.clear()
                now = System.currentTimeMillis()
            },
        )
    }
}

private const val REFRESH_MS = 2_000L

private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ago"
    }
}
