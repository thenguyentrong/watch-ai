package com.vinhnguyen.watchai.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.actions.BuddyNotificationListener
import com.vinhnguyen.watchai.actions.MessageInbox
import com.vinhnguyen.watchai.actions.NoteStore
import com.vinhnguyen.watchai.actions.PhoneActions
import com.vinhnguyen.watchai.actions.ReachActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of Buddy's actions the user has allowed, and the notes it keeps. */
class AbilitiesViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    data class Allowed(
        val calendar: Boolean,
        val textsAndCalls: Boolean,
        val messages: Boolean,
        val fromPocket: Boolean,
        val doNotDisturb: Boolean,
    )

    private val _allowed = MutableStateFlow(read())
    val allowed: StateFlow<Allowed> = _allowed.asStateFlow()
    val notes: StateFlow<List<NoteStore.Note>> = graph.notes.notes

    init {
        viewModelScope.launch { runCatching { graph.notes.list() } }
    }

    /** The answers come back from Android's own screens: read them again whenever this one returns. */
    fun refresh() {
        _allowed.value = read()
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { runCatching { graph.notes.delete(id) } }
    }

    private fun read() = Allowed(
        calendar = graph.actions.calendarAllowed(),
        textsAndCalls = ReachActions.PERMISSIONS.all { graph.appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        messages = MessageInbox.allowed(graph.appContext),
        fromPocket = graph.phoneClock.worksFromPocket(),
        doNotDisturb = graph.controls.doNotDisturbAllowed(),
    )
}

/**
 * What Buddy can do, in plain words with an example each, and the OKs some of it needs. The same
 * on the watch and in chat. Messages and calls always wait for the user's yes.
 */
@Composable
fun AbilitiesScreen(
    vm: AbilitiesViewModel,
    onSeeMessages: () -> Unit,
) {
    val p = LocalPalette.current
    val allowed by vm.allowed.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val openSettings = { intent: Intent -> runCatching { context.startActivity(intent) } }
    val askMessages = {
        val listener = ComponentName(context, BuddyNotificationListener::class.java).flattenToString()
        openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener))
            .onFailure { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Unit
    }

    Text(
        "Just ask, on your watch or here. Messages and calls are read back to you and only go out after your yes.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )

    Group("People") {
        Ability(R.drawable.sym_chat_bubble, "Text someone", "\"Text Anna I'm running late\"", allowed.textsAndCalls) { ask.launch(ReachActions.PERMISSIONS) }
        Ability(R.drawable.sym_call, "Call someone", "\"Call Jan\"", allowed.textsAndCalls) { ask.launch(ReachActions.PERMISSIONS) }
        Ability(
            R.drawable.sym_forum,
            "Read and answer messages",
            "\"Any messages?\" · WhatsApp, Signal, SMS and more",
            allowed.messages,
            last = true,
            onAllow = askMessages,
            onSee = onSeeMessages,
        )
    }
    Group("Time") {
        Ability(R.drawable.sym_timer, "Timers and alarms", "\"Timer for ten minutes\"", allowed = null)
        Ability(R.drawable.sym_event, "Calendar and reminders", "\"Remind me at six to call Mum\"", allowed.calendar, last = true) { ask.launch(PhoneActions.CALENDAR_PERMISSIONS) }
    }
    Group("Phone") {
        Ability(R.drawable.sym_directions, "Open apps and directions", "\"Open Spotify\" · \"Take me to the station\"", allowed.fromPocket) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)))
        }
        Ability(R.drawable.sym_music_note, "Music, volume and flashlight", "\"Pause the music\" · \"Flashlight on\"", allowed = null)
        Ability(R.drawable.sym_ring_volume, "Find my phone, silent mode", "\"Where's my phone?\" · \"Silence my phone\"", allowed.doNotDisturb, last = true) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }
    Group("Notes", footer = "Kept encrypted on this phone.") {
        Item(
            "Your notes",
            painter = painterResource(R.drawable.sym_sticky_note_2),
            subtitle = if (notes.isEmpty()) "\"Add milk to my notes\"" else "${notes.size} saved",
            last = !showNotes || notes.isEmpty(),
            onClick = if (notes.isEmpty()) null else ({ showNotes = !showNotes }),
            trailing = { if (notes.isNotEmpty()) Text(if (showNotes) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = p.textSecondary) },
        )
        if (showNotes) {
            val shown = notes.asReversed().take(20)
            shown.forEachIndexed { i, note ->
                Item(
                    note.text,
                    last = i == shown.lastIndex,
                    trailing = { Pill("Delete", onClick = { vm.deleteNote(note.id) }, filled = false) },
                )
            }
        }
    }
    Group("On your watch") {
        Item(
            "Hey Buddy",
            painter = painterResource(R.drawable.sym_watch),
            subtitle = "Raise your wrist and say it. \"Always listening\" needs no wrist (more battery), \"Learn my voice\" helps it hear you. Say \"bye\" to finish.",
            last = true,
        )
    }
}

/** One thing Buddy does; [allowed] null means it needs no OK. */
@Composable
private fun Ability(
    icon: Int,
    title: String,
    example: String,
    allowed: Boolean?,
    last: Boolean = false,
    onAllow: () -> Unit = {},
    /** Once allowed: show what it can see. */
    onSee: (() -> Unit)? = null,
) {
    Item(
        title,
        subtitle = example,
        painter = painterResource(icon),
        last = last,
        trailing = {
            when {
                allowed == false -> Pill("Allow", onClick = onAllow)
                allowed == true && onSee != null -> Pill("See", onClick = onSee, filled = false)
            }
        },
    )
}
