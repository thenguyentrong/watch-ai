package com.vinhnguyen.watchai.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val openSettings = { intent: Intent -> runCatching { context.startActivity(intent) } }
    val appSettings = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
    var askedAt by remember { mutableLongStateOf(0L) }
    // The OKs Android didn't give when asked here: from then on, their button opens Buddy's settings.
    var viaSettings by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.refresh()
            if (result.values.all { it }) return@rememberLauncherForActivityResult
            viaSettings = viaSettings + result.keys
            // No at once means Android didn't show its question at all (a no before, or a restricted setting):
            // only Buddy's page in the phone's settings can allow it now, so go there.
            if (SystemClock.elapsedRealtime() - askedAt < NO_QUESTION_MS) appSettings()
        }
    val askFor = { permissions: Array<String> ->
        if (permissions.any { it in viaSettings }) {
            appSettings()
        } else {
            askedAt = SystemClock.elapsedRealtime()
            ask.launch(permissions)
        }
        Unit
    }
    val label = { permissions: Array<String> -> if (permissions.any { it in viaSettings }) "Settings" else "Allow" }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }
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

    val reach = label(ReachActions.PERMISSIONS)
    Group("People", footer = if (reach == "Settings" && !allowed.textsAndCalls) SETTINGS_HINT else null) {
        Ability(R.drawable.sym_chat_bubble, "Text someone", "\"Text Anna I'm running late\"", allowed.textsAndCalls, allowLabel = reach, onAllow = { askFor(ReachActions.PERMISSIONS) })
        Ability(R.drawable.sym_call, "Call someone", "\"Call Jan\"", allowed.textsAndCalls, allowLabel = reach, onAllow = { askFor(ReachActions.PERMISSIONS) })
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
        Ability(
            R.drawable.sym_event,
            "Calendar and reminders",
            "\"Remind me at six to call Mum\"",
            allowed.calendar,
            last = true,
            allowLabel = label(PhoneActions.CALENDAR_PERMISSIONS),
            onAllow = { askFor(PhoneActions.CALENDAR_PERMISSIONS) },
        )
    }
    Group("Phone") {
        Ability(
            R.drawable.sym_directions,
            "Open apps and directions",
            "\"Open Spotify\" · \"Take me to the station\"",
            allowed.fromPocket,
            onAllow = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))) },
        )
        Ability(R.drawable.sym_music_note, "Music, volume and flashlight", "\"Pause the music\" · \"Flashlight on\"", allowed = null)
        Ability(
            R.drawable.sym_ring_volume,
            "Find my phone, silent mode",
            "\"Where's my phone?\" · \"Silence my phone\"",
            allowed.doNotDisturb,
            last = true,
            onAllow = { openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        )
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

/**
 * One thing Buddy does; [allowed] null means it needs no OK. [onAllow] comes last, so a trailing
 * lambda is the Allow button (with [onSee] last, Allow did nothing until 29.09).
 */
@Composable
private fun Ability(
    icon: Int,
    title: String,
    example: String,
    allowed: Boolean?,
    last: Boolean = false,
    allowLabel: String = "Allow",
    /** Once allowed: show what it can see. */
    onSee: (() -> Unit)? = null,
    onAllow: () -> Unit = {},
) {
    Item(
        title,
        subtitle = example,
        painter = painterResource(icon),
        last = last,
        trailing = {
            when {
                allowed == false -> Pill(allowLabel, onClick = onAllow)
                allowed == true && onSee != null -> Pill("See", onClick = onSee, filled = false)
            }
        },
    )
}

/** Android answers at once, without a question on screen, when it won't ask the user anymore. */
private const val NO_QUESTION_MS = 400L

private const val SETTINGS_HINT =
    "Android didn't let Buddy ask here. In Buddy's settings, open Permissions and allow Contacts, Phone and SMS. If it says " +
        "the setting is restricted, tap ⋮ at the top of Buddy's settings and allow restricted settings first."
