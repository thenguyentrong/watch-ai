package com.vinhnguyen.watchai.buddy.ui

import android.graphics.PixelFormat
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import com.vinhnguyen.watchai.buddy.Act
import com.vinhnguyen.watchai.buddy.Director
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Reaction
import com.vinhnguyen.watchai.buddy.Scene
import kotlin.concurrent.thread

/**
 * The same Buddy as [BuddyView], drawn on its own surface by its own thread with a software
 * canvas. A Compose canvas that ticks makes Android redraw the whole window each frame, and on
 * the Watch5 that alone cost about 2% CPU per frame per second (release, 28.09: an empty canvas
 * at 20 fps took 39%, Buddy 48%). Here a frame is only Buddy, drawn and handed over: 25% at
 * 20 fps, 15% at 10 (the compositor's share, 15 to 25%, is the same either way).
 *
 * The thread keeps the clock and stamps changes of [act] and [reaction] (each time [reactionId]
 * goes up) on it. It stops with the surface; [ambient] draws one still picture.
 */
@Composable
fun BuddySurface(
    genes: Genes,
    act: Act,
    reaction: Reaction?,
    reactionId: Int,
    level: Float,
    paper: Color,
    modifier: Modifier = Modifier,
    fps: Int = 30,
    busyFps: Int = fps,
    ambient: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val input = remember { SurfaceInput() }
    val currentGenes by rememberUpdatedState(genes)
    input.act = act
    input.reaction = reaction
    input.reactionId = reactionId
    input.level = level
    input.fps = if (still) 4 else fps
    input.busyFps = if (still) 4 else busyFps
    input.ambient = ambient
    input.paper = paper
    val renderer = remember { SurfaceRenderer(input, { currentGenes }, still) }
    renderer.density = density
    renderer.direction = direction
    DisposableEffect(renderer) { onDispose { renderer.stop() } }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFormat(PixelFormat.OPAQUE)
                holder.addCallback(renderer)
            }
        },
        modifier = modifier,
    )
}

/** What the composition hands the render thread; written on the main thread, read on the render thread. */
private class SurfaceInput {
    @Volatile var act: Act = Act.REST

    @Volatile var reaction: Reaction? = null

    @Volatile var reactionId: Int = 0

    @Volatile var level: Float = 0f

    @Volatile var fps: Int = 30

    @Volatile var busyFps: Int = 30

    @Volatile var ambient: Boolean = false

    @Volatile var paper: Color = Color.Black
}

private class SurfaceRenderer(
    private val input: SurfaceInput,
    private val genes: () -> Genes,
    private val still: Boolean,
) : SurfaceHolder.Callback {
    @Volatile var density: androidx.compose.ui.unit.Density = androidx.compose.ui.unit.Density(1f)

    @Volatile var direction: androidx.compose.ui.unit.LayoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        stop()
        running = true
        worker = thread(name = "buddy-surface", priority = Thread.NORM_PRIORITY) { loop(holder, width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    fun stop() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(500) }
        worker = null
    }

    private fun loop(
        holder: SurfaceHolder,
        width: Int,
        height: Int,
    ) {
        var shownGenes = genes()
        var director = Director(shownGenes, still)
        val paths = BuddyPaths()
        val draw = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())
        val start = System.nanoTime()
        var scene = Scene(input.act, reaction = input.reaction)
        var reactionId = input.reactionId
        var ambientShown = false
        while (running) {
            val t = (System.nanoTime() - start) / 1e9
            if (genes() != shownGenes) {
                shownGenes = genes()
                director = Director(shownGenes, still)
            }
            // Changes are stamped with this thread's clock, as they arrive.
            if (input.act != scene.act) scene = scene.copy(act = input.act, actAt = t)
            if (input.reactionId != reactionId) {
                reactionId = input.reactionId
                scene = scene.copy(reaction = input.reaction, reactionAt = t)
            }
            val ambient = input.ambient
            if (!(ambient && ambientShown)) {
                val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: break
                try {
                    val frame = director.frame(scene, input.level, t, blend = !ambient)
                    draw.draw(density, direction, Canvas(canvas), size) {
                        drawRect(input.paper.takeUnless { ambient } ?: Color.Black)
                        if (ambient) drawAmbientBuddy(frame, paths) else drawBuddy(frame, inkOf(shownGenes), accentOf(shownGenes), input.paper, paths)
                    }
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
                ambientShown = ambient
            }
            val playing = scene.reaction != null && t - scene.reactionAt < 3.5
            val rate = if (ambient) 2 else if (playing) input.busyFps else input.fps
            val next = start + ((t + 1.0 / rate.coerceAtLeast(1)) * 1e9).toLong()
            val wait = (next - System.nanoTime()) / 1_000_000
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }
}
