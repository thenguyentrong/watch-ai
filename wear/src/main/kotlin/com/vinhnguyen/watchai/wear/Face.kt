package com.vinhnguyen.watchai.wear

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme
import com.vinhnguyen.watchai.wear.PhoneVoiceLink.Phase

/**
 * Placeholder mascot until the real one is designed: a round face whose eyes and mouth show what
 * the conversation is doing. Asleep when idle, eyes open and a ring that follows your voice while
 * listening, eyes looking up while thinking, a mouth that moves with the answer while speaking.
 */
@Composable
fun Face(
    phase: Phase,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val skin by animateColorAsState(
        when (phase) {
            Phase.IDLE -> colors.surfaceContainerHigh
            Phase.ERROR -> colors.errorContainer
            Phase.SPEAKING -> colors.tertiary
            Phase.THINKING, Phase.CONNECTING -> colors.secondary
            Phase.LISTENING -> colors.primary
        },
        label = "skin",
    )
    val features = if (phase == Phase.IDLE) colors.onSurface else Color.White
    val talk = remember { Animatable(0f) }
    LaunchedEffect(level) { talk.animateTo(level.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMedium)) }
    val eyesOpen by animateFloatAsState(if (phase == Phase.IDLE) 0.15f else 1f, label = "eyesOpen")
    val lookUp by animateFloatAsState(if (phase == Phase.THINKING || phase == Phase.CONNECTING) 1f else 0f, label = "lookUp")
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec =
        infiniteRepeatable(
            keyframes {
                durationMillis = 4_000
                1f at 3_700
                0.1f at 3_800
                1f at 3_900
            },
            RepeatMode.Restart,
        ),
        label = "blinkValue",
    )
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
        label = "breatheValue",
    )

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        val ring = if (phase == Phase.LISTENING) 1f + 0.12f * talk.value else 1f
        drawCircle(skin.copy(alpha = 0.25f), radius = r * ring.coerceAtMost(1f))
        drawCircle(skin, radius = r * 0.86f * (if (phase == Phase.IDLE) breathe else 1f))

        // Eyes: squeezed when asleep, blinking now and then, raised while thinking.
        val eyeW = r * 0.16f
        val eyeH = r * 0.24f * eyesOpen * (if (phase == Phase.IDLE) 1f else blink)
        val eyeY = c.y - r * 0.18f - lookUp * r * 0.08f
        listOf(-1f, 1f).forEach { side ->
            val x = c.x + side * r * 0.3f
            drawRoundRect(
                features,
                topLeft = Offset(x - eyeW / 2, eyeY - eyeH / 2),
                size = Size(eyeW, eyeH.coerceAtLeast(r * 0.03f)),
                cornerRadius = CornerRadius(eyeW / 2),
            )
        }

        // Mouth: a small smile, opening with the answer's loudness while speaking.
        val mouthW = r * 0.42f
        val open = if (phase == Phase.SPEAKING) (0.06f + 0.3f * talk.value) else 0.05f
        val mouthH = r * open
        drawRoundRect(
            features,
            topLeft = Offset(c.x - mouthW / 2, c.y + r * 0.28f - mouthH / 2),
            size = Size(mouthW, mouthH),
            cornerRadius = CornerRadius(mouthH / 2),
        )
    }
}
