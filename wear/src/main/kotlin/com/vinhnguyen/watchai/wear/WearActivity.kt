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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
        if (savedInstanceState == null) talkNow()
        setContent {
            val isAmbient by ambient.collectAsStateWithLifecycle()
            MaterialTheme { WatchScreen(ambient = isAmbient) }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneVoiceLink.get(this).onScreen = true
    }

    override fun onStop() {
        PhoneVoiceLink.get(this).onScreen = false
        super.onStop()
    }

    /** Opened again while already running (single task): talk again, unless a conversation is on. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        talkNow()
    }

    /** Starts a conversation if none is on; without the mic permission the first tap asks for it. */
    private fun talkNow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val link = PhoneVoiceLink.get(this)
        val phase = link.state.value.phase
        if (phase == Phase.IDLE || phase == Phase.ERROR) lifecycleScope.launch { link.start() }
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
    val context = LocalContext.current
    val activity = LocalActivity.current
    val active = state.phase != Phase.IDLE && state.phase != Phase.ERROR
    // The screen stays on for the whole conversation.
    DisposableEffect(active) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggle() }
    val onTap = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.toggle()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Face(
                phase = state.phase,
                level = if (ambient) 0f else state.level,
                ambient = ambient,
                modifier =
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = if (active) "End the conversation" else "Start talking", role = Role.Button, onClick = onTap),
            )
            Text(
                label(state),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
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
            state.roundTripMs?.takeIf { active }?.let {
                Text("link $it ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun label(state: PhoneVoiceLink.State): String = when (state.phase) {
    Phase.IDLE -> "Tap to talk"
    Phase.CONNECTING -> state.detail ?: "Connecting…"
    Phase.LISTENING -> "Listening"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Speaking"
    Phase.ERROR -> state.detail ?: "Something went wrong"
}
