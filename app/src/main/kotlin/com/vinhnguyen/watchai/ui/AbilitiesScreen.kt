package com.vinhnguyen.watchai.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vinhnguyen.watchai.AppGraph
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
fun AbilitiesScreen(vm: AbilitiesViewModel) {
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text(
            "Just ask, on your watch or here. Messages and calls are read back to you and only go out after your yes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        Section("People")
        Ability("Text someone", "\"Text Anna I'm running late\"", allowed.textsAndCalls) { ask.launch(ReachActions.PERMISSIONS) }
        Ability("Call someone", "\"Call Jan\"", allowed.textsAndCalls) { ask.launch(ReachActions.PERMISSIONS) }
        Ability("Read and answer messages", "\"Any messages?\" · \"Tell Jan I'm on my way\" · WhatsApp, Signal, SMS and more", allowed.messages) {
            val listener = ComponentName(context, BuddyNotificationListener::class.java).flattenToString()
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener))
                .onFailure { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        Section("Time")
        Ability("Timers and alarms", "\"Timer for ten minutes\" · on the device you talk through", allowed = null)
        Ability("Calendar and reminders", "\"Remind me at six to call Mum\" · \"What's on tomorrow?\"", allowed.calendar) { ask.launch(PhoneActions.CALENDAR_PERMISSIONS) }

        Section("Phone")
        Ability("Open apps, clock and maps from your pocket", "\"Open Spotify\" · \"Take me to the station\"", allowed.fromPocket) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)))
        }
        Ability("Find my phone, music, volume, flashlight, battery", "\"Where's my phone?\" · \"Pause the music\"", allowed = null)
        Ability("Do Not Disturb and silent mode", "\"Silence my phone\"", allowed.doNotDisturb) { openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }

        Section("Notes")
        ListItem(
            headlineContent = { Text("Your notes") },
            supportingContent = { Text(if (notes.isEmpty()) "\"Add milk to my notes\" · kept encrypted on this phone" else "${notes.size} · kept encrypted on this phone") },
            trailingContent = { if (notes.isNotEmpty()) TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide" else "Show") } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (showNotes) {
            notes.asReversed().take(20).forEach { note ->
                ListItem(
                    headlineContent = { Text(note.text) },
                    trailingContent = { TextButton(onClick = { vm.deleteNote(note.id) }) { Text("Delete") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        Section("On your watch")
        Text(
            "Scroll down on Buddy's face on the watch: \"Hey Buddy\" wakes it when you raise your wrist, \"Always listening\" " +
                "needs no wrist at all (uses more battery), and \"Learn my voice\" helps it hear you. Say \"bye\" to end a conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One thing Buddy does; [allowed] null means it needs no OK. */
@Composable
private fun Ability(
    title: String,
    example: String,
    allowed: Boolean?,
    onAllow: () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (allowed == false) "$example · needs your OK" else example) },
        trailingContent = { if (allowed == false) FilledTonalButton(onClick = onAllow) { Text("Allow") } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
