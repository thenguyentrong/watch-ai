package com.vinhnguyen.watchai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.brain.chatgpt.auth.AuthState
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.ui.BuddyView
import com.vinhnguyen.watchai.buddy.ui.accentOf
import com.vinhnguyen.watchai.voice.VoicePhase

/**
 * The phone's home: the user's own Buddy, a soft glow in its colour that breathes while it listens
 * and speaks, and one line of text. Tap Buddy to talk, tap again (or say bye) to stop. Glass floats
 * over it: the menu and the chat.
 */
@Composable
fun HomeScreen(
    talk: TalkViewModel,
    genes: Genes?,
    backdrop: LayerBackdrop,
    onMenu: () -> Unit,
    onChat: () -> Unit,
    onSignIn: () -> Unit,
) {
    val p = LocalPalette.current
    val state by talk.state.collectAsStateWithLifecycle()
    val level by talk.level.collectAsStateWithLifecycle()
    val auth by talk.auth.collectAsStateWithLifecycle()
    val accent = remember(genes) { genes?.let { accentOf(it) } ?: p.textSecondary }
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

    Box(Modifier.fillMaxSize().background(p.background)) {
        // Everything the glass floats over.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(p.background)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .widthIn(max = 440.dp)
                        .aspectRatio(1f)
                        .glow(accent, talking, level, strong = p.dark),
                ) {
                    genes?.let {
                        BuddyView(
                            genes = it,
                            act = act(state.phase),
                            reaction = state.reaction,
                            reactionId = state.reactionId,
                            level = level,
                            paper = p.background,
                            eyes = p.eyes,
                            fps = 30,
                            busyFps = 60,
                            modifier =
                            Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClickLabel = if (talking || state.onWatch) "End the conversation" else "Talk to Buddy",
                                    onClick = onTap,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(label(state), style = MaterialTheme.typography.titleMedium, color = p.text, textAlign = TextAlign.Center)
                hint(state, auth)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.weight(1.25f))
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            GlassCircle(backdrop, painterResource(R.drawable.sym_menu), "Menu", onMenu)
            Spacer(Modifier.weight(1f))
            GlassCircle(backdrop, painterResource(R.drawable.sym_chat_bubble), "Chat", onChat)
        }
    }
}

/** A soft glow in Buddy's colour behind it; it breathes, and swells with the voice, while talking. */
@Composable
private fun Modifier.glow(
    color: Color,
    talking: Boolean,
    level: Float,
    strong: Boolean,
): Modifier {
    val breath by rememberInfiniteTransition(label = "glow").animateFloat(0f, 1f, infiniteRepeatable(tween(2_800), RepeatMode.Reverse), label = "breath")
    val on by animateFloatAsState(if (talking) 1f else 0f, tween(600), label = "on")
    val voice by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "voice")
    val rest = if (strong) 0.2f else 0.16f
    return drawBehind {
        val alpha = rest + on * (0.08f + 0.06f * breath + 0.22f * voice)
        val radius = size.minDimension * (0.62f + on * (0.04f * breath + 0.1f * voice))
        drawCircle(Brush.radialGradient(0f to color.copy(alpha = alpha), 1f to Color.Transparent, center = center, radius = radius), radius = radius, center = center)
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
