package com.vinhnguyen.watchai.buddy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Director
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Outfit
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.Scene
import kotlinx.coroutines.delay

/**
 * A living Buddy: the [Director] sampled on the view's own clock, drawn by [drawBuddy]. Callers
 * say what Buddy is doing ([act], and a [reaction] each time [reactionId] goes up); the view
 * notes when each change happened on its clock.
 *
 * [fps] caps how often it redraws: 60 on a phone; a watch passes less while nothing much moves,
 * since every frame costs its small cores (a 60 fps loop took 94% CPU on the Watch5). [frozenAt]
 * stops the clock at that many seconds into the act and reaction, for screenshots and tests.
 */
@Composable
fun BuddyView(
    genes: Genes,
    outfit: Outfit,
    act: Act,
    reaction: Reaction?,
    reactionId: Int,
    level: Float,
    modifier: Modifier = Modifier,
    fps: Int = 60,
    frozenAt: Double? = null,
    ambient: Boolean = false,
) {
    val director = remember(genes) { Director(genes) }
    val layer = remember { BuddyLayer() }
    var t by remember { mutableDoubleStateOf(frozenAt ?: 0.0) }
    var scene by remember { mutableStateOf(Scene(act, reaction = reaction)) }
    // Stamp changes with the clock as it is now; a frozen view keeps everything at time 0.
    LaunchedEffect(act) { if (scene.act != act) scene = scene.copy(act = act, actAt = if (frozenAt == null) t else 0.0) }
    LaunchedEffect(reactionId) { scene = scene.copy(reaction = reaction, reactionAt = if (frozenAt == null) t else 0.0) }
    val currentFps by rememberUpdatedState(fps)
    LaunchedEffect(frozenAt, ambient) {
        if (frozenAt != null || ambient) {
            t = frozenAt ?: t
            return@LaunchedEffect
        }
        val start = System.nanoTime() - (t * 1e9).toLong()
        while (true) {
            if (currentFps >= 60) withFrameNanos { } else delay(1_000L / currentFps.coerceAtLeast(1))
            t = (System.nanoTime() - start) / 1e9
        }
    }
    Canvas(modifier) {
        if (ambient) drawAmbientBuddy(genes) else drawBuddy(director.frame(scene, level, t, blend = frozenAt == null), genes, outfit, layer)
    }
}
