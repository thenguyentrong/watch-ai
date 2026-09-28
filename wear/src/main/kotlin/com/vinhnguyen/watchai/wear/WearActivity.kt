package com.vinhnguyen.watchai.wear

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.ui.BuddySurface
import com.vinhnguyen.watchai.wear.PhoneVoiceLink.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class WearActivity : ComponentActivity() {
    private val ambient = MutableStateFlow(false)

    private val ambientObserver =
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    ambient.value = true
                }

                override fun onExitAmbient() {
                    ambient.value = false
                }
            },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wrist down: stay on the face in ambient mode instead of going back to the watch face.
        lifecycle.addObserver(ambientObserver)
        // Opening the app is the "press to talk": from the launcher, the tile or the side button.
        // Tests open it with "quiet" to only switch "Hey Buddy" back on after an install.
        if (savedInstanceState == null && !intent.getBooleanExtra(QUIET, false)) talkNow()
        setContent {
            val isAmbient by ambient.collectAsStateWithLifecycle()
            MaterialTheme { WatchScreen(ambient = isAmbient) }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneVoiceLink.get(this).onScreen = true
        CallService.faceShown(this)
        // "Hey Buddy" is on but not running (the watch restarted, or Android stopped it): the app is on screen, so it can start.
        if (WakeSetting.isOn(this) && !CallService.armed.value && micAllowed()) CallService.wakeOn(this)
    }

    override fun onStop() {
        PhoneVoiceLink.get(this).onScreen = false
        super.onStop()
    }

    /** Opened again while already running (single task): talk again, unless a conversation is on. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.getBooleanExtra(QUIET, false)) talkNow()
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts a conversation if none is on; without the mic permission the first tap asks for it. */
    private fun talkNow() {
        if (!micAllowed()) return
        val link = PhoneVoiceLink.get(this)
        val phase = link.state.value.phase
        if (phase == Phase.IDLE || phase == Phase.ERROR) lifecycleScope.launch { link.start() }
    }

    private companion object {
        const val QUIET = "quiet"
    }
}

class WatchViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val link = PhoneVoiceLink.get(application)

    fun toggle() {
        viewModelScope.launch {
            val phase = link.state.value.phase
            if (phase == Phase.IDLE || phase == Phase.ERROR) link.start() else link.stop()
        }
    }

    fun setWake(on: Boolean) {
        if (on) CallService.wakeOn(getApplication()) else CallService.wakeOff(getApplication())
    }

    val always = MutableStateFlow(WakeSetting.isAlways(application))

    fun setAlways(on: Boolean) {
        always.value = on
        CallService.listenAlways(getApplication(), on)
    }

    // No onCleared clean-up: a conversation outlives the screen (the call service keeps it) and
    // ends from the face, the notification's End action, or the phone.
}

/** The whole watch app for now: the face. Tap it to start or end a conversation with the phone. */
@Composable
private fun WatchScreen(
    ambient: Boolean,
    vm: WatchViewModel = viewModel(),
) {
    val state by vm.link.state.collectAsStateWithLifecycle()
    val wakeOn by CallService.armed.collectAsStateWithLifecycle()
    val wakeListening by WakeListener.listening.collectAsStateWithLifecycle()
    val always by vm.always.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val taught by remember { WakeSetting.taught(context) }.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val active = state.phase != Phase.IDLE && state.phase != Phase.ERROR
    // The screen stays on for the whole conversation.
    DisposableEffect(active) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggle() }
    // Notifications only bring the face up over the watch face; "Hey Buddy" works without them.
    val wakePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.setWake(true)
        }
    val onTap = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.toggle()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val genes by vm.link.genes.collectAsStateWithLifecycle()
    val screen = LocalConfiguration.current
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // One screen: Buddy and a line of text. The one setting is a scroll away (bezel or swipe).
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().height(screen.screenHeightDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val shownAct = act(state.phase, wakeOn && wakeListening)
            BuddySurface(
                genes = genes,
                act = shownAct,
                reaction = state.reaction,
                reactionId = state.reactionId,
                level = if (ambient) 0f else state.level,
                paper = Color.Black,
                ambient = ambient,
                // Every frame costs the watch's small cores: 10 a second, 15 while Buddy talks (the mouth), 24 while a reaction plays.
                fps = if (shownAct == Act.SPEAK) 15 else 10,
                busyFps = 24,
                modifier =
                Modifier
                    .size((screen.screenWidthDp * 0.74f).dp)
                    .clickable(onClickLabel = if (active) "End the conversation" else "Start talking", role = Role.Button, onClick = onTap),
            )
            Text(
                label(state, wakeOn && wakeListening),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            val earbuds =
                when {
                    !active -> null
                    !state.micOnWatch -> "Using your earbuds"
                    !state.answersOnWatch -> "Answers in your earbuds"
                    else -> null
                }
            earbuds?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!active && !ambient) {
            SwitchButton(
                checked = wakeOn,
                onCheckedChange = { on ->
                    val missing = WAKE_PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (on && missing.isNotEmpty()) wakePermission.launch(missing.toTypedArray()) else vm.setWake(on)
                },
                label = { Text("Hey Buddy") },
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            if (wakeOn) {
                Spacer(Modifier.height(4.dp))
                // Without it "Hey Buddy" needs a raised wrist first; with it there's no hand needed at all.
                SwitchButton(
                    checked = always,
                    onCheckedChange = vm::setAlways,
                    label = { Text("Always listening") },
                    secondaryLabel = { Text("Uses more battery") },
                    modifier = Modifier.padding(horizontal = 36.dp),
                )
                Spacer(Modifier.height(4.dp))
                // When "Hey Buddy" misses the user: five sentences read out teach it their voice.
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, TeachActivity::class.java)) },
                    label = { Text("Learn my voice") },
                    secondaryLabel = { Text(if (taught == true) "Learned" else "Read 5 sentences") },
                    modifier = Modifier.padding(horizontal = 36.dp).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** What the conversation looks like on Buddy. */
private fun act(
    phase: Phase,
    wakeListening: Boolean,
): Act = when (phase) {
    Phase.IDLE -> if (wakeListening) Act.AWAKE else Act.REST
    Phase.CONNECTING -> Act.CONNECT
    Phase.LISTENING -> Act.LISTEN
    Phase.THINKING -> Act.THINK
    Phase.SPEAKING -> Act.SPEAK
    Phase.ERROR -> Act.ERROR
}

private val WAKE_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

private fun label(
    state: PhoneVoiceLink.State,
    wakeListening: Boolean,
): String = when (state.phase) {
    Phase.IDLE -> if (wakeListening) "Tap or say \"Hey Buddy\"" else "Tap to talk"
    Phase.CONNECTING -> state.detail ?: "Connecting…"
    Phase.LISTENING -> "Listening"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Speaking"
    Phase.ERROR -> state.detail ?: "Something went wrong"
}
