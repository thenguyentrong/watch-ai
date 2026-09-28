package com.vinhnguyen.watchai.buddy.ui

import android.provider.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Director
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.Scene
import kotlinx.coroutines.delay

/**
 * A living Buddy: the [Director] sampled on the view's own clock, drawn by [drawBuddy]. Callers
 * say what Buddy is doing ([act], and a [reaction] each time [reactionId] goes up) and the voice
 * [level]; the view notes when each change happened on its clock. [paper] is the colour behind
 * the view: the eyes and mouth show it.
 *
 * [fps] caps how often it redraws, [busyFps] while a reaction plays (rings, bursts, the comet).
 * For the phone and for frozen screenshots: on the watch [BuddySurface] draws the same Buddy for
 * about half the CPU. [frozenAt] stops the clock at that many seconds into the act and reaction,
 * for screenshots and tests. With the system's animations off Buddy holds still.
 */
@Composable
fun BuddyView(
    genes: Genes,
    act: Act,
    reaction: Reaction?,
    reactionId: Int,
    level: Float,
    paper: Color,
    modifier: Modifier = Modifier,
    fps: Int = 60,
    busyFps: Int = fps,
    frozenAt: Double? = null,
    ambient: Boolean = false,
) {
    val context = LocalContext.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val director = remember(genes, still) { Director(genes, still) }
    val paths = remember { BuddyPaths() }
    val ink = remember(genes) { inkOf(genes) }
    val accent = remember(genes) { accentOf(genes) }
    var t by remember { mutableDoubleStateOf(frozenAt ?: 0.0) }
    var scene by remember { mutableStateOf(Scene(act, reaction = reaction)) }
    // Stamp changes with the clock as it is now; a frozen view keeps everything at time 0.
    LaunchedEffect(act) { if (scene.act != act) scene = scene.copy(act = act, actAt = if (frozenAt == null) t else 0.0) }
    LaunchedEffect(reactionId) { scene = scene.copy(reaction = reaction, reactionAt = if (frozenAt == null) t else 0.0) }
    val calmRate by rememberUpdatedState(if (still) 4 else fps)
    val busyRate by rememberUpdatedState(if (still) 4 else busyFps)
    LaunchedEffect(frozenAt, ambient) {
        if (frozenAt != null || ambient) {
            t = frozenAt ?: t
            return@LaunchedEffect
        }
        val start = System.nanoTime() - (t * 1e9).toLong()
        while (true) {
            // Read here, not in composition, so the clock ticking never recomposes anything.
            val rate = if (scene.reaction != null && t - scene.reactionAt < REACTION_S) busyRate else calmRate
            if (rate >= 60) withFrameNanos { } else delay(1_000L / rate.coerceAtLeast(1))
            t = (System.nanoTime() - start) / 1e9
        }
    }
    // Its own layer: a tick re-records only Buddy, not the screen around it.
    Canvas(modifier.graphicsLayer()) {
        val frame = director.frame(scene, level, t, blend = frozenAt == null && !ambient)
        if (ambient) drawAmbientBuddy(frame, paths) else drawBuddy(frame, ink, accent, paper, paths)
    }
}

/** Long enough for the longest reaction to play out. */
private const val REACTION_S = 3.5
