package com.vinhnguyen.watchai.wear

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.vinhnguyen.watchai.wear.PhoneVoiceLink.Phase
import kotlinx.coroutines.launch

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchScreen() } }
    }
}

class WatchViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val link = PhoneVoiceLink(application)

    fun toggle() {
        viewModelScope.launch {
            val phase = link.state.value.phase
            if (phase == Phase.IDLE || phase == Phase.ERROR) link.start() else link.stop()
        }
    }

    override fun onCleared() {
        // viewModelScope is gone; the link cleans up on its own scope.
        link.release()
    }
}

/** The whole watch app for now: the face. Tap it to start or end a conversation with the phone. */
@Composable
private fun WatchScreen(vm: WatchViewModel = viewModel()) {
    val state by vm.link.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val active = state.phase != Phase.IDLE && state.phase != Phase.ERROR
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
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
                level = state.level,
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
