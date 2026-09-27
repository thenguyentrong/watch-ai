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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** Always-on screen: outline only, no fills or motion, to save power and avoid burn-in. */
    ambient: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    // Face and features use a colour role and its "on" pair, so the eyes stay readable in every state.
    val (skinTarget, featuresTarget) =
        when (phase) {
            Phase.IDLE -> colors.surfaceContainerHigh to colors.onSurface
            Phase.ERROR -> colors.errorContainer to colors.onErrorContainer
            Phase.SPEAKING -> colors.tertiary to colors.onTertiary
            Phase.THINKING, Phase.CONNECTING -> colors.secondary to colors.onSecondary
            Phase.LISTENING -> colors.primary to colors.onPrimary
        }
    val skin by animateColorAsState(skinTarget, label = "skin")
    val features by animateColorAsState(featuresTarget, label = "features")
    val talk = remember { Animatable(0f) }
    LaunchedEffect(level) { talk.animateTo(level.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessMedium)) }
    val eyesOpen by animateFloatAsState(if (phase == Phase.IDLE) 0.15f else 1f, label = "eyesOpen")
    val lookUp by animateFloatAsState(if (phase == Phase.THINKING || phase == Phase.CONNECTING) 1f else 0f, label = "lookUp")
    // Blinking and breathing only on the full screen; the always-on screen draws no motion.
    val blink =
        if (ambient) {
            1f
        } else {
            rememberInfiniteTransition(label = "blink")
                .animateFloat(
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
                ).value
        }
    val breathe =
        if (ambient) {
            1f
        } else {
            rememberInfiniteTransition(label = "breathe")
                .animateFloat(
                    initialValue = 0.97f,
                    targetValue = 1.03f,
                    animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
                    label = "breatheValue",
                ).value
        }

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        if (ambient) {
            val outline = Color.White.copy(alpha = 0.7f)
            drawCircle(outline, radius = r * 0.86f, style = Stroke(width = r * 0.03f))
            listOf(-1f, 1f).forEach { side ->
                drawLine(outline, Offset(c.x + side * r * 0.3f - r * 0.07f, c.y - r * 0.18f), Offset(c.x + side * r * 0.3f + r * 0.07f, c.y - r * 0.18f), strokeWidth = r * 0.04f)
            }
            drawLine(outline, Offset(c.x - r * 0.15f, c.y + r * 0.28f), Offset(c.x + r * 0.15f, c.y + r * 0.28f), strokeWidth = r * 0.04f)
            return@Canvas
        }
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
