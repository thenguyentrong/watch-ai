package com.vinhnguyen.watchai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.actions.PhoneActions
import com.vinhnguyen.watchai.ui.VoiceLabViewModel.Engine
import com.vinhnguyen.watchai.ui.VoiceLabViewModel.ModelWarmth
import com.vinhnguyen.watchai.voice.ChatGptRealtimeSession
import com.vinhnguyen.watchai.voice.VoicePhase
import com.vinhnguyen.watchai.voice.VoiceState
import com.vinhnguyen.watchai.watch.Route
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/** Debug builds only: talk to either voice engine and see how fast it answers. */
@Composable
fun VoiceLabScreen(vm: VoiceLabViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = LocalActivity.current
    val running = state.active != null

    // Gemma stays loaded while this screen is open. Leaving the tab ends the conversation; leaving
    // the app (e.g. the clock app opening for a timer) ends it after a short grace period.
    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        vm.onForeground()
        onStopOrDispose {
            vm.setVisible(false)
            vm.onBackground()
        }
    }
    DisposableEffect(vm) { onDispose { vm.stop() } }
    DisposableEffect(running) {
        view.keepScreenOn = running
        // The volume keys should change the answer's volume, not the ringer.
        activity?.volumeControlStream = if (running) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
        onDispose {
            view.keepScreenOn = false
            activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingAction.value?.invoke()
            pendingAction.value = null
        }
    val withMic: (() -> Unit) -> Unit = { action ->
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAction.value = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val vs = state.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EnginePicker(state.engine, enabled = !running, onPick = vm::setEngine)
        Text(
            engineLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        TalkButton(
            phase = if (running) vs.phase else null,
            level = vm.level,
            onClick = { if (running) vm.stop() else withMic { vm.toggle() } },
        )
        Column(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(headline(running, vs), style = MaterialTheme.typography.headlineSmall)
            vs.detail?.takeIf { (running || vs.phase == VoicePhase.ERROR) && it != headline(running, vs) }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vs.phase == VoicePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (state.engine == Engine.CHATGPT && !running) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatGptRealtimeSession.VOICES.forEach { v ->
                    FilterChip(selected = state.voice == v, onClick = { vm.setVoice(v) }, label = { Text(v) })
                }
            }
        }

        Captions(vs)
        Timings(vs, state.savedTo)
        WatchCard(vm)
        ActionsCard(vm)
        ProbeCard(state, onRun = { withMic { vm.runProbe() } })
    }
}

@Composable
private fun EnginePicker(
    selected: Engine,
    enabled: Boolean,
    onPick: (Engine) -> Unit,
) {
    val options = listOf(Engine.CHATGPT to "ChatGPT voice", Engine.ON_DEVICE to "On this phone")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (engine, label) ->
            SegmentedButton(
                selected = selected == engine,
                onClick = { onPick(engine) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label) },
            )
        }
    }
}

/**
 * The one control: tap to start or stop. Its ring follows whoever is talking and breathes while
 * connecting or thinking. The level is only read while drawing, so it never recomposes the screen.
 */
@Composable
private fun TalkButton(
    phase: VoicePhase?,
    level: StateFlow<Float>,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (target, onTarget) =
        when (phase) {
            VoicePhase.ERROR -> colors.error to colors.onError
            VoicePhase.SPEAKING -> colors.tertiary to colors.onTertiary
            VoicePhase.CONNECTING, VoicePhase.THINKING -> colors.secondary to colors.onSecondary
            else -> colors.primary to colors.onPrimary
        }
    val fill by animateColorAsState(target, label = "talkFill")
    val onFill by animateColorAsState(onTarget, label = "talkOnFill")

    val ring = remember { Animatable(0f) }
    LaunchedEffect(level) {
        level.collectLatest { ring.animateTo(it.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMediumLow)) }
    }
    // Only composed while waiting, so an idle screen draws no frames.
    val breath: State<Float>? =
        if (phase == VoicePhase.CONNECTING || phase == VoicePhase.THINKING) {
            rememberInfiniteTransition(label = "breath").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "breathValue",
            )
        } else {
            null
        }

    val label = if (phase == null) "Start talking" else "Stop talking"
    Box(
        Modifier
            .size(220.dp)
            .drawBehind {
                val base = size.minDimension / 2f * 0.72f
                val grow = breath?.let { 0.04f + 0.10f * it.value } ?: (0.30f * ring.value)
                if (grow > 0.01f) {
                    drawCircle(fill.copy(alpha = 0.16f), radius = base * (1f + grow))
                    drawCircle(fill.copy(alpha = 0.30f), radius = base * (1f + grow * 0.5f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(158.dp)
                .clip(CircleShape)
                .drawBehind { drawCircle(fill) }
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics { stateDescription = phase?.name?.lowercase() ?: "idle" },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (phase == null) "Talk" else "Stop", style = MaterialTheme.typography.headlineMedium, color = onFill)
        }
    }
}

@Composable
private fun Captions(vs: VoiceState) {
    val user = vs.lastUserText?.takeIf { it.isNotBlank() }
    val ai = vs.lastAssistantText?.takeIf { it.isNotBlank() }
    if (user == null && ai == null) return
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            user?.let { Caption("You", it) }
            ai?.let { Caption("AI", it) }
        }
    }
}

@Composable
private fun Caption(
    who: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(who, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Timings(
    vs: VoiceState,
    savedTo: String?,
) {
    val turns = vs.metrics.turnLatenciesMs
    val interrupts = vs.metrics.interruptLatenciesMs
    var details by rememberSaveable { mutableStateOf(false) }
    if (turns.isEmpty() && interrupts.isEmpty() && savedTo == null) return
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Timings", style = MaterialTheme.typography.titleMedium)
            if (turns.isNotEmpty()) {
                Metric("Answer starts after", "${seconds(turns.sorted()[turns.size / 2])} (median of ${turns.size})")
                Metric("Last answers", turns.takeLast(5).joinToString(" · ") { seconds(it) })
            }
            if (interrupts.isNotEmpty()) Metric("Quiet after you interrupt", interrupts.takeLast(5).joinToString(" · ") { seconds(it) })
            savedTo?.let { Metric("Saved", it) }
            TextButton(onClick = { details = !details }, modifier = Modifier.align(Alignment.End)) {
                Text(if (details) "Hide details" else "Show details")
            }
            if (details) {
                vs.metrics.notes.takeLast(20).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** Talking from the watch: the call in progress, run by the phone's watch service whether the app is open or not. */
@Composable
private fun WatchCard(vm: VoiceLabViewModel) {
    val call by vm.watchCall.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Watch", style = MaterialTheme.typography.titleMedium)
            val current = call
            if (current != null) {
                Text("Talking through ${current.watch} · ${current.voice.phase.name.lowercase()}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (current.route) {
                        Route.WATCH -> "The watch is the microphone and speaker."
                        Route.HEADPHONES -> "Answers play in your headphones; the watch is the microphone."
                        Route.HEADSET -> "Your headset is the microphone and speaker; the watch shows the face."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = vm::endWatchCall) { Text("End") }
            } else {
                Text(
                    "Open Buddy on the watch and talk; the phone can stay in your pocket. Tip: set the watch's side button to open it (Settings, Advanced features, Customize buttons).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the assistant can do on this phone, the calendar permission, and the notes it keeps. */
@Composable
private fun ActionsCard(vm: VoiceLabViewModel) {
    val calendarAllowed by vm.calendarAllowed.collectAsStateWithLifecycle()
    val clockFromPocket by vm.clockFromPocket.collectAsStateWithLifecycle()
    val doNotDisturbAllowed by vm.doNotDisturbAllowed.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val calendarPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermissions() }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Things it can do on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ask with ChatGPT voice or in Chat, for example \"add milk to my notes\", \"remind me at six to call Mum\", " +
                    "\"where's my phone?\", \"pause the music\" or \"what's on my calendar tomorrow?\". " +
                    "What you ask to add or read goes to ChatGPT on your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow("Calendar and reminders", if (calendarAllowed) "Allowed" else "Needs your OK") {
                if (!calendarAllowed) {
                    Button(onClick = { calendarPermission.launch(PhoneActions.CALENDAR_PERMISSIONS) }) { Text("Allow") }
                }
            }
            ActionRow("Timers and alarms", "On the device you talk through, or where you say")
            ActionRow("Phone clock from your pocket", if (clockFromPocket) "Allowed" else "Off: the phone's Clock only opens while this app is on screen") {
                if (!clockFromPocket) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)))
                    }) { Text("Allow") }
                }
            }
            ActionRow("Find my phone, music, volume, battery", "Work with the phone in your pocket")
            ActionRow("Do Not Disturb and silent mode", if (doNotDisturbAllowed) "Allowed" else "Needs your OK") {
                if (!doNotDisturbAllowed) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("Allow") }
                }
            }
            ActionRow("Notes", if (notes.isEmpty()) "None yet · kept encrypted in this app" else "${notes.size} · kept encrypted in this app") {
                if (notes.isNotEmpty()) {
                    TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide" else "Show") }
                }
            }
            if (showNotes) {
                notes.asReversed().take(20).forEach { note ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(note.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { vm.deleteNote(note.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    status: String,
    action: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun ProbeCard(
    state: VoiceLabViewModel.State,
    onRun: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Experiment: Gemma hears your voice directly", style = MaterialTheme.typography.titleMedium)
            Text(
                "Records 4 seconds and sends the audio itself to Gemma, with no speech recogniser in between.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRun, enabled = !state.probeRunning && state.active == null) {
                Text(if (state.probeRunning) "Recording / thinking…" else "Record 4 s and ask")
            }
            state.probe?.let { p ->
                Text(
                    if (p.error != null) {
                        "Result: ${p.error} (audio input supported: ${p.supportsAudio})"
                    } else {
                        "Load ${seconds(p.loadMs)} · first word ${p.firstTokenMs?.let(::seconds) ?: "-"} · total ${seconds(p.totalMs)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (p.answer.isNotEmpty()) Caption("Gemma", p.answer)
            }
        }
    }
}

private fun engineLine(state: VoiceLabViewModel.State): String = when (state.engine) {
    Engine.CHATGPT -> "Speech to speech on your ChatGPT plan · voice ${state.voice}"

    Engine.ON_DEVICE ->
        "Gemma on this phone, nothing leaves it · " +
            when (state.model) {
                ModelWarmth.READY -> "model loaded"
                ModelWarmth.LOADING -> "loading the model…"
                ModelWarmth.MISSING -> "model not downloaded (Brains tab)"
                ModelWarmth.COLD -> "model loads when you start"
            }
}

private fun headline(
    running: Boolean,
    vs: VoiceState,
): String = when {
    vs.phase == VoicePhase.ERROR -> "Something went wrong"

    !running -> "Tap to talk"

    else ->
        when (vs.phase) {
            VoicePhase.CONNECTING -> "Connecting…"
            VoicePhase.LISTENING -> "Listening"
            VoicePhase.THINKING -> "Thinking…"
            VoicePhase.SPEAKING -> "Speaking"
            VoicePhase.IDLE, VoicePhase.ERROR -> "Tap to talk"
        }
}

private fun seconds(ms: Long): String = "%.1f s".format(ms / 1000.0)
