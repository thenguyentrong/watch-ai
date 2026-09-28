package com.vinhnguyen.watchai.buddy.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.vinhnguyen.watchai.buddy.Arc
import com.vinhnguyen.watchai.buddy.Badge
import com.vinhnguyen.watchai.buddy.BuddyFrame
import com.vinhnguyen.watchai.buddy.Dot
import com.vinhnguyen.watchai.buddy.Feature
import com.vinhnguyen.watchai.buddy.Genes
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws Buddy the way bloub draws its bot (MIT, github.com/jeremy-prt/bloub, BloubBot.vue): a
 * flat body in [ink] with the eyes and mouth cut out, so they show [paper], the background.
 * The back half of every orbit is drawn first and the body hides it. Eyes and mouth are
 * painted in [paper] (the same as cutting them out, on a plain background) as a rounded rect or
 * one curve under a transform: building outlines point by point cost 10 ms a frame on the Watch5.
 * On a light background the face would show white; [eyes] paints it dark instead (the phone's light
 * theme). Paths are kept in [paths] and reused.
 *
 * Body units: 1 is the radius of the ball at rest, drawn [scale] pixels, centred on [center].
 */
fun DrawScope.drawBuddy(
    frame: BuddyFrame,
    ink: Color,
    accent: Color,
    paper: Color,
    paths: BuddyPaths,
    scale: Float = size.minDimension * BALL,
    center: Offset = this.center,
    eyes: Color = paper,
) {
    val s = Space(center, scale)
    frame.arcs.forEach { drawArc(it, s, paths, front = false) }
    if (frame.dotsBehind) frame.dots.forEach { drawDot(it, s, paths, ink, paper) }
    drawPath(paths.body.apply { smoothLoop(frame.body, s) }, ink)
    frame.features.forEach { drawFeature(it, s, paths, eyes) }
    frame.badge?.let { drawCircle(paper, radius = (it.r + it.gap) * s.k, center = s.p(it.x, it.y)) }
    if (!frame.dotsBehind) frame.dots.forEach { drawDot(it, s, paths, ink, paper) }
    frame.badge?.let { drawBadge(it, s, paths, accent) }
    frame.arcs.forEach { drawArc(it, s, paths, front = true) }
}

/** Always-on screens: only an outline and the face, in grey, so almost every pixel stays off. */
fun DrawScope.drawAmbientBuddy(
    frame: BuddyFrame,
    paths: BuddyPaths,
    scale: Float = size.minDimension * BALL,
) {
    val s = Space(center, scale)
    val grey = Color(0xFF8C8C8C)
    drawPath(paths.body.apply { smoothLoop(frame.body, s) }, grey, style = Stroke(width = scale * 0.035f))
    frame.features.forEach { drawFeature(it, s, paths, grey) }
}

/** The ball's radius as a share of the smaller side: leaves room for the rings (up to 1.3 body units). */
const val BALL: Float = 0.34f

/** Buddy's colours: the body, and a stronger shade of its hue for the badge. */
fun inkOf(genes: Genes): Color = Color(genes.color.body)

fun accentOf(genes: Genes): Color = Color.hsl(genes.color.hue, 0.8f, 0.6f)

/** Paths and a matrix reused from frame to frame. */
class BuddyPaths {
    internal val body = Path()
    internal val shape = Path()
    internal val lens = Path()
    internal val matrix = Matrix()
}

private class Space(
    val c: Offset,
    val k: Float,
) {
    fun x(v: Float) = c.x + v * k

    fun y(v: Float) = c.y + v * k

    fun p(
        x: Float,
        y: Float,
    ) = Offset(x(x), y(y))
}

/** A closed Catmull-Rom curve through the outline points, as cubic Béziers (tension 1/6, like bloub). */
private fun Path.smoothLoop(
    pts: FloatArray,
    s: Space,
) {
    reset()
    val n = pts.size / 2
    fun px(i: Int) = s.x(pts[2 * ((i + n) % n)])
    fun py(i: Int) = s.y(pts[2 * ((i + n) % n) + 1])
    moveTo(px(0), py(0))
    for (i in 0 until n) {
        val c1x = px(i) + (px(i + 1) - px(i - 1)) / 6
        val c1y = py(i) + (py(i + 1) - py(i - 1)) / 6
        val c2x = px(i + 1) - (px(i + 2) - px(i)) / 6
        val c2y = py(i + 1) - (py(i + 2) - py(i)) / 6
        cubicTo(c1x, c1y, c2x, c2y, px(i + 1), py(i + 1))
    }
    close()
}

private fun Path.polygon(
    pts: FloatArray,
    s: Space,
    open: Boolean = false,
) {
    reset()
    moveTo(s.x(pts[0]), s.y(pts[1]))
    var i = 2
    while (i < pts.size) {
        lineTo(s.x(pts[i]), s.y(pts[i + 1]))
        i += 2
    }
    if (!open) close()
}

/** An eye or the mouth in its own frame, mapped onto the body by the feature's matrix. */
private fun DrawScope.drawFeature(
    f: Feature,
    s: Space,
    paths: BuddyPaths,
    color: Color,
) {
    val alpha = f.alpha.coerceIn(0f, 1f)
    if (alpha < 0.01f) return
    val m = paths.matrix.apply {
        reset()
        values[Matrix.ScaleX] = f.a * s.k
        values[Matrix.SkewY] = f.b * s.k
        values[Matrix.SkewX] = f.c * s.k
        values[Matrix.ScaleY] = f.d * s.k
        values[Matrix.TranslateX] = s.x(f.e)
        values[Matrix.TranslateY] = s.y(f.f)
    }
    withTransform({ transform(m) }) {
        val band = f.band
        if (band == null) {
            val r = min(f.w, f.h) / 2
            drawRoundRect(color, Offset(-f.w / 2, -f.h / 2), Size(f.w, f.h), CornerRadius(r, r), alpha = alpha)
            return@withTransform
        }
        // The line: a parabola, which one quadratic curve draws exactly, with round ends.
        val hw = band.w / 2
        val bend = 2 * band.curve * hw
        drawPath(paths.shape.apply { reset(); moveTo(-hw, 0f); quadraticTo(0f, bend, hw, 0f) }, color, alpha = alpha, style = Stroke(width = band.thick, cap = StrokeCap.Round))
        if (band.open > 0.004f) {
            // Opened: the space down to a round "U" (and up to another for an "o", by [Mouth.up]).
            // A cubic with upright ends reaches 3/4 of its control depth half-way.
            val low = (band.curve * hw + band.open * (1 - band.up)) * 4 / 3
            val high = (band.curve * hw - band.open * band.up) * 4 / 3
            val lens = paths.lens.apply {
                reset()
                moveTo(-hw, 0f)
                if (band.up > 0f) cubicTo(-hw, high, hw, high, hw, 0f) else quadraticTo(0f, bend, hw, 0f)
                cubicTo(hw, low, -hw, low, -hw, 0f)
                close()
            }
            drawPath(lens, color, alpha = alpha)
        }
    }
}

private fun DrawScope.drawArc(
    arc: Arc,
    s: Space,
    paths: BuddyPaths,
    front: Boolean,
) {
    val runs = if (front) arc.front else arc.back
    if (runs.isEmpty()) return
    val brush = Brush.linearGradient(arc.colors.map { Color(it) }, s.p(arc.x1, arc.y1), s.p(arc.x2, arc.y2))
    val stroke = Stroke(width = arc.width * s.k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    runs.forEach { run -> drawPath(paths.shape.apply { polygon(run, s, open = true) }, brush, alpha = arc.opacity.coerceIn(0f, 1f), style = stroke) }
}

private fun DrawScope.drawDot(
    dot: Dot,
    s: Space,
    paths: BuddyPaths,
    ink: Color,
    paper: Color,
) {
    // Particles far away fade into the background.
    val color = if (dot.depth >= 1f) ink else lerp(paper, ink, dot.depth.coerceIn(0f, 1f))
    val alpha = dot.opacity.coerceIn(0f, 1f)
    val shape = dot.shape
    if (shape == null) {
        drawCircle(color, radius = dot.r * s.k, center = s.p(dot.x, dot.y), alpha = alpha)
        return
    }
    val rad = Math.toRadians(dot.rot.toDouble())
    val c = cos(rad).toFloat()
    val n = sin(rad).toFloat()
    val placed = FloatArray(shape.size)
    var i = 0
    while (i < shape.size) {
        placed[i] = dot.x + shape[i] * c - shape[i + 1] * n
        placed[i + 1] = dot.y + shape[i] * n + shape[i + 1] * c
        i += 2
    }
    drawPath(paths.shape.apply { polygon(placed, s) }, color, alpha = alpha)
}

private fun DrawScope.drawBadge(
    badge: Badge,
    s: Space,
    paths: BuddyPaths,
    accent: Color,
) {
    if (!badge.heart) {
        drawCircle(accent, radius = badge.r * s.k, center = s.p(badge.x, badge.y))
        return
    }
    // A heart the size of the badge: two lobes and a point.
    val r = badge.r * s.k
    val cx = s.x(badge.x)
    val cy = s.y(badge.y)
    paths.shape.apply {
        reset()
        moveTo(cx, cy + r * 0.95f)
        cubicTo(cx - r * 1.35f, cy + r * 0.05f, cx - r * 0.85f, cy - r * 1.05f, cx, cy - r * 0.45f)
        cubicTo(cx + r * 0.85f, cy - r * 1.05f, cx + r * 1.35f, cy + r * 0.05f, cx, cy + r * 0.95f)
        close()
    }
    drawPath(paths.shape, accent)
}
