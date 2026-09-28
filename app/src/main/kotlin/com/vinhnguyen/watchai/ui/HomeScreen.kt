package com.vinhnguyen.watchai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import com.vinhnguyen.watchai.voice.VoicePhase

/**
 * The phone's home, like the watch: the user's own Buddy on black and one line of text. Tap Buddy
 * to talk, tap again (or say bye) to stop. Everything else is behind the menu.
 */
@Composable
fun HomeScreen(
    talk: TalkViewModel,
    onMenu: () -> Unit,
    onSignIn: () -> Unit,
) {
    val state by talk.state.collectAsStateWithLifecycle()
    val level by talk.level.collectAsStateWithLifecycle()
    val auth by talk.auth.collectAsStateWithLifecycle()
    val seed by produceState<Long?>(null) { value = talk.buddySeed() }
    val context = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) talk.tap(micAllowed = true) }
    LifecycleStartEffect(talk) {
        talk.onForeground()
        onStopOrDispose { talk.onBackground() }
    }
    val onTap = {
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when (talk.tap(micAllowed)) {
            TalkViewModel.Tap.NEEDS_SIGN_IN -> onSignIn()
            TalkViewModel.Tap.NEEDS_MIC -> mic.launch(Manifest.permission.RECORD_AUDIO)
            else -> Unit
        }
    }
    val talking = state.phase != VoicePhase.IDLE && state.phase != VoicePhase.ERROR

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            seed?.let {
                BuddyView(
                    genes = remember(it) { Genes.of(it) },
                    act = act(state.phase),
                    reaction = state.reaction,
                    reactionId = state.reactionId,
                    level = level,
                    paper = Color.Black,
                    fps = 30,
                    busyFps = 60,
                    modifier =
                    Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 460.dp)
                        .aspectRatio(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = if (talking || state.onWatch) "End the conversation" else "Talk to Buddy",
                            onClick = onTap,
                        ),
                )
            } ?: Spacer(Modifier.fillMaxWidth(0.9f).widthIn(max = 460.dp).aspectRatio(1f))
            Spacer(Modifier.height(20.dp))
            Text(label(state), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center)
            hint(state, auth)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.weight(1.3f))
        }
        IconButton(onClick = onMenu, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

private fun act(phase: VoicePhase): Act = when (phase) {
    VoicePhase.IDLE -> Act.REST
    VoicePhase.CONNECTING -> Act.CONNECT
    VoicePhase.LISTENING -> Act.LISTEN
    VoicePhase.THINKING -> Act.THINK
    VoicePhase.SPEAKING -> Act.SPEAK
    VoicePhase.ERROR -> Act.ERROR
}

private fun label(state: TalkViewModel.State): String = when (state.phase) {
    VoicePhase.IDLE -> "Tap to talk"
    VoicePhase.CONNECTING -> state.detail ?: "Connecting…"
    VoicePhase.LISTENING -> "Listening"
    VoicePhase.THINKING -> "Thinking…"
    VoicePhase.SPEAKING -> "Speaking"
    VoicePhase.ERROR -> state.detail ?: "Something went wrong"
}

private fun hint(
    state: TalkViewModel.State,
    auth: AuthState,
): String? = when {
    state.onWatch -> "On your watch · tap to end"
    state.phase == VoicePhase.IDLE && auth is AuthState.SignedOut -> "Sign in with ChatGPT first"
    state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.SPEAKING -> "Say \"bye\" or tap to finish"
    else -> null
}
