package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.actions.ActionLogStore
import com.vinhnguyen.watchai.actions.ConversationActions
import com.vinhnguyen.watchai.actions.PhoneActions
import com.vinhnguyen.watchai.actions.PhoneShortcuts
import com.vinhnguyen.watchai.actions.ReachActions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * What Buddy did, newest first: the kind of action and how it ended. Nothing of what was in it is
 * kept (no message text, no names), only for 30 days, encrypted on this phone.
 */
@Composable
fun ActivityScreen(log: ActionLogStore) {
    val p = LocalPalette.current
    val items by log.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(log) { log.load() }

    Text(
        "Only the kind of action and how it ended, never what was in it. Kept 30 days, encrypted on this phone.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    if (items.isEmpty()) {
        Group { Item("Nothing yet", subtitle = "Ask Buddy for something, on your watch or here.", last = true) }
        return
    }
    items.asReversed().take(SHOWN).groupBy { day(it.at) }.forEach { (day, entries) ->
        Group(day) {
            entries.forEachIndexed { i, e ->
                val (title, icon) = describe(e)
                Item(title, subtitle = "${time(e.at)} · ${outcome(e)}", painter = painterResource(icon), last = i == entries.lastIndex)
            }
        }
    }
    Group {
        Item("Clear the list", danger = true, last = true, onClick = { scope.launch { log.clear() } })
    }
}

private const val SHOWN = 200

private fun describe(e: ActionLogStore.Item): Pair<String, Int> = when (e.tool) {
    ReachActions.READ_MESSAGES -> "Read your messages" to R.drawable.sym_forum
    PhoneActions.LIST_NOTES -> "Read your notes" to R.drawable.sym_sticky_note_2
    PhoneActions.LIST_EVENTS -> "Read your calendar" to R.drawable.sym_event
    PhoneActions.ADD_NOTE -> "Added a note" to R.drawable.sym_sticky_note_2
    PhoneActions.ADD_EVENT -> "Added to your calendar" to R.drawable.sym_event
    PhoneActions.ADD_REMINDER -> "Added a reminder" to R.drawable.sym_event
    PhoneActions.SET_TIMER -> "Set a timer" to R.drawable.sym_timer
    PhoneActions.SET_ALARM -> "Set an alarm" to R.drawable.sym_alarm
    PhoneActions.RING_PHONE, PhoneActions.STOP_RINGING -> "Rang your phone" to R.drawable.sym_ring_volume
    PhoneActions.MEDIA, PhoneActions.SET_VOLUME -> "Music and volume" to R.drawable.sym_music_note
    PhoneActions.SET_RINGER, PhoneActions.DO_NOT_DISTURB -> "Ringer or Do Not Disturb" to R.drawable.sym_ring_volume
    PhoneActions.DEVICE_STATUS -> "Checked the battery" to R.drawable.sym_bolt
    PhoneShortcuts.OPEN_APP -> "Opened an app" to R.drawable.sym_bolt
    PhoneShortcuts.NAVIGATE -> "Opened directions" to R.drawable.sym_directions
    PhoneShortcuts.FLASHLIGHT -> "Flashlight" to R.drawable.sym_flashlight_on
    ReachActions.SEND_TEXT -> "A text" to R.drawable.sym_chat_bubble
    ReachActions.REPLY -> "A reply" to R.drawable.sym_chat_bubble
    ReachActions.CALL -> "A call" to R.drawable.sym_call
    ReachActions.CONFIRM -> if (e.level == "OUTBOUND") "Your yes" to R.drawable.sym_check_circle else "Your no" to R.drawable.sym_cancel
    ConversationActions.END -> "Conversation ended" to R.drawable.sym_graphic_eq
    else -> e.tool.replace('_', ' ').replaceFirstChar { it.uppercase() } to R.drawable.sym_bolt
}

private fun outcome(e: ActionLogStore.Item): String = when (e.outcome) {
    "PROPOSED" -> "read back, waiting for a yes"
    "FAILED" -> "didn't work"
    "REFUSED" -> "held back by the safety rules"
    else -> if (e.tool == ReachActions.CONFIRM && e.level == "OUTBOUND") "went out" else "done"
}

private fun time(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

private fun day(at: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.sameDay(o: Calendar) = get(Calendar.YEAR) == o.get(Calendar.YEAR) && get(Calendar.DAY_OF_YEAR) == o.get(Calendar.DAY_OF_YEAR)
    return when {
        then.sameDay(today) -> "Today"
        then.sameDay(yesterday) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
    }
}
