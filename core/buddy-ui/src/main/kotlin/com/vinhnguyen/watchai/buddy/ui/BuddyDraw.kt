package com.vinhnguyen.watchai.buddy.ui

import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.vinhnguyen.watchai.buddy.Arms
import com.vinhnguyen.watchai.buddy.Effect
import com.vinhnguyen.watchai.buddy.EyeShape
import com.vinhnguyen.watchai.buddy.Frame
import com.vinhnguyen.watchai.buddy.Genes
import com.vinhnguyen.watchai.buddy.Item
import com.vinhnguyen.watchai.buddy.Outfit
import com.vinhnguyen.watchai.buddy.Profile
import com.vinhnguyen.watchai.buddy.Slot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws one frame of Buddy: [genes] give the body, colour, eyes and top; [outfit] the items;
 * [frame] the pose. The still parts (body, marks, top, clothes, neck and head items) come from
 * [layer], drawn once; each frame only moves that picture and draws the face, arms, props and
 * effects over it. Drawing everything every frame took about 25% CPU on the Watch5 (27.09).
 */
fun DrawScope.drawBuddy(
    frame: Frame,
    genes: Genes,
    outfit: Outfit,
    layer: BuddyLayer,
) {
    val rest = restSpace()
    val body = layer.body(genes)
    val look = Palette(Color(genes.color.body), Color(genes.color.shade))
    val face = FaceSpots(genes, frame.gazeX, frame.gazeY)
    val bottom = rest.p(0f, -1f)
    val sx = 1f + frame.squash
    withTransform({
        translate(frame.dx * rest.r, -frame.dy * rest.r)
        rotate(frame.tilt, bottom)
        scale(sx, 1f / sx, bottom)
    }) {
        drawArms(rest, body, frame, look)
        drawImage(layer.image(this, genes, outfit))
        drawFace(rest, face, frame, genes)
        outfit[Slot.FACE]?.let { drawFaceItem(rest, it, face) }
        if (frame.arms == Arms.REST) outfit[Slot.HAND]?.let { drawHandItem(rest, it, hand(rest, body)) }
    }
    drawEffect(Space(rest.cx, rest.cy, rest.r, frame.dx, frame.dy), body, frame)
}

/** A Buddy's still parts drawn once into a picture, until the genes, outfit or size change. */
class BuddyLayer {
    private var genes: Genes? = null
    private var profile: Profile? = null
    private var key: Any? = null
    private var picture: ImageBitmap? = null

    internal fun body(genes: Genes): Profile = profile?.takeIf { this.genes == genes } ?: Profile.of(genes).also {
        this.genes = genes
        profile = it
    }

    internal fun image(
        scope: DrawScope,
        genes: Genes,
        outfit: Outfit,
    ): ImageBitmap {
        val k = Triple(genes, outfit, scope.size)
        picture?.takeIf { key == k }?.let { return it }
        val bitmap = ImageBitmap(scope.size.width.toInt().coerceAtLeast(1), scope.size.height.toInt().coerceAtLeast(1))
        CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(bitmap), scope.size) { drawStill(genes, outfit, body(genes)) }
        key = k
        picture = bitmap
        return bitmap
    }
}

private fun DrawScope.restSpace() = Space(size.width / 2, size.height * 0.58f, size.minDimension * 0.36f, 0f, 0f)

/** Everything that doesn't move on its own: drawn once per Buddy and outfit. */
private fun DrawScope.drawStill(
    genes: Genes,
    outfit: Outfit,
    body: Profile,
) {
    val s = restSpace()
    val look = Palette(Color(genes.color.body), Color(genes.color.shade))
    val face = FaceSpots(genes, 0f, 0f)
    if (outfit[Slot.HEAD] == null) drawTop(s, body, genes.top, look)
    val path = bodyPath(s, body)
    drawPath(path, look.body)
    clipPath(path) {
        // Soft light from the top left, a little shade underneath.
        val light = s.p(-0.35f, 0.45f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), center = light, radius = s.r * 0.8f), radius = s.r * 0.8f, center = light)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, look.shade.copy(alpha = 0.55f)), startY = s.y(-0.2f), endY = s.y(-1.05f)))
        drawMark(s, genes)
        outfit[Slot.BODY]?.let { drawBodyItem(s, it, genes) }
    }
    outfit[Slot.NECK]?.let { drawNeckItem(s, it, face) }
    outfit[Slot.HEAD]?.let { drawHeadItem(s, it, body, face) }
}

/** The always-on screen: the outline, resting eyes and a small smile. No fills, no motion (power, burn-in). */
fun DrawScope.drawAmbientBuddy(genes: Genes) {
    val s = Space(size.width / 2, size.height * 0.58f, size.minDimension * 0.36f, 0f, 0f)
    val body = Profile.of(genes)
    val line = Color.White.copy(alpha = 0.6f)
    val stroke = Stroke(width = s.r * 0.035f, cap = StrokeCap.Round)
    drawPath(bodyPath(s, body), line, style = stroke)
    val eyeV = 0.1f - genes.eyeHeight
    listOf(-1f, 1f).forEach { side ->
        drawArc(line, 20f, 140f, false, topLeft = s.p(side * genes.eyeGap - 0.08f, eyeV + 0.03f), size = Size(s.r * 0.16f, s.r * 0.08f), style = stroke)
    }
    val mouth = Path().apply {
        moveTo(s.x(-0.07f), s.y(eyeV - 0.26f))
        quadraticTo(s.x(0f), s.y(eyeV - 0.3f), s.x(0.07f), s.y(eyeV - 0.26f))
    }
    drawPath(mouth, line, style = stroke)
}

/** Body units to pixels: (0, 0) is the body's centre, radius 1 its size at rest, y up. */
private class Space(
    val cx: Float,
    val cy: Float,
    val r: Float,
    val dx: Float,
    val dy: Float,
) {
    fun x(u: Float) = cx + (u + dx) * r

    fun y(v: Float) = cy - (v + dy) * r

    fun p(
        u: Float,
        v: Float,
    ) = Offset(x(u), y(v))
}

private class Palette(
    val body: Color,
    val shade: Color,
)

/** Where the face sits for these genes and this gaze, in body units. */
private class FaceSpots(
    genes: Genes,
    gazeX: Float,
    gazeY: Float,
) {
    val eyeV = 0.1f - genes.eyeHeight + 0.06f * gazeY
    val eyeGap = genes.eyeGap
    val gazeU = 0.07f * gazeX
    val mouthV = 0.1f - genes.eyeHeight - 0.27f
    val neckV = mouthV - 0.2f
    val size = genes.eyeSize
}

private fun bodyPath(
    s: Space,
    body: Profile,
): Path {
    val n = Profile.SAMPLES
    val pts = List(n) { i -> outline(s, body, Profile.angle(i), body.radii[i]) }
    return Path().apply {
        val first = mid(pts[n - 1], pts[0])
        moveTo(first.x, first.y)
        for (i in 0 until n) {
            val m = mid(pts[i], pts[(i + 1) % n])
            quadraticTo(pts[i].x, pts[i].y, m.x, m.y)
        }
        close()
    }
}

private fun outline(
    s: Space,
    body: Profile,
    angle: Float,
    radius: Float = body.radiusAt(angle),
) = s.p(cos(angle) * radius, sin(angle) * radius)

private fun mid(
    a: Offset,
    b: Offset,
) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)

private fun rad(degrees: Float) = degrees * PI.toFloat() / 180f

private fun DrawScope.drawTop(
    s: Space,
    body: Profile,
    top: Genes.Top,
    look: Palette,
) {
    val tip = outline(s, body, rad(90f))
    val r = s.r
    when (top) {
        Genes.Top.NONE -> Unit

        Genes.Top.LOOP -> drawCircle(look.shade, radius = r * 0.11f, center = Offset(tip.x, tip.y - r * 0.08f), style = Stroke(width = r * 0.05f))

        Genes.Top.SPROUT -> {
            drawLine(LEAF_DARK, tip, Offset(tip.x, tip.y - r * 0.16f), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
            listOf(-1f, 1f).forEach { side ->
                rotate(side * 35f, Offset(tip.x, tip.y - r * 0.15f)) {
                    drawOval(LEAF, topLeft = Offset(tip.x - r * 0.06f + side * r * 0.08f, tip.y - r * 0.2f), size = Size(r * 0.13f, r * 0.08f))
                }
            }
        }

        Genes.Top.ANTENNA -> {
            drawLine(look.shade, tip, Offset(tip.x + r * 0.05f, tip.y - r * 0.24f), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
            drawCircle(ANTENNA_BALL, radius = r * 0.065f, center = Offset(tip.x + r * 0.05f, tip.y - r * 0.27f))
        }

        Genes.Top.EARS ->
            listOf(62f, 118f).forEach { deg ->
                val base = outline(s, body, rad(deg))
                val out = Offset(cos(rad(deg)), -sin(rad(deg)))
                val ear = Path().apply {
                    moveTo(base.x - out.y * r * 0.14f - out.x * r * 0.05f, base.y + out.x * r * 0.14f + r * 0.05f)
                    lineTo(base.x + out.x * r * 0.24f, base.y + out.y * r * 0.24f)
                    lineTo(base.x + out.y * r * 0.14f - out.x * r * 0.05f, base.y - out.x * r * 0.14f + r * 0.05f)
                    close()
                }
                drawPath(ear, look.body)
                drawPath(ear, look.shade, style = Stroke(width = r * 0.02f))
            }

        Genes.Top.TUFT -> {
            val curl = Path().apply {
                moveTo(tip.x - r * 0.02f, tip.y + r * 0.01f)
                cubicTo(tip.x - r * 0.05f, tip.y - r * 0.18f, tip.x + r * 0.16f, tip.y - r * 0.2f, tip.x + r * 0.1f, tip.y - r * 0.08f)
            }
            drawPath(curl, look.shade, style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
        }

        Genes.Top.NUBS ->
            listOf(72f, 108f).forEach { deg ->
                drawCircle(look.shade, radius = r * 0.08f, center = outline(s, body, rad(deg), body.radiusAt(rad(deg)) + 0.02f))
            }
    }
}

private fun DrawScope.drawMark(
    s: Space,
    genes: Genes,
) {
    when (genes.mark) {
        Genes.Mark.NONE -> Unit

        Genes.Mark.BELLY -> drawOval(Color.White.copy(alpha = 0.3f), topLeft = s.p(-0.42f, -0.32f), size = Size(s.r * 0.84f, s.r * 0.72f))

        Genes.Mark.SPOTS ->
            listOf(Triple(-0.62f, 0.3f, 0.09f), Triple(-0.5f, 0.52f, 0.06f), Triple(0.6f, -0.35f, 0.08f)).forEach { (u, v, r) ->
                drawCircle(Color(genes.color.shade).copy(alpha = 0.55f), radius = s.r * r, center = s.p(u, v))
            }
    }
}

private fun DrawScope.drawFace(
    s: Space,
    face: FaceSpots,
    frame: Frame,
    genes: Genes,
) {
    val r = s.r
    if (genes.cheeks || frame.blush > 0.5f) {
        val blush = (if (genes.cheeks) 0.35f else 0f).coerceAtLeast(frame.blush)
        listOf(-1f, 1f).forEach { side ->
            drawOval(BLUSH.copy(alpha = 0.6f * blush), topLeft = s.p(side * (face.eyeGap + 0.12f) - 0.1f, face.eyeV - 0.12f), size = Size(r * 0.2f, r * 0.1f))
        }
    }
    val (w0, h0) =
        when (genes.eyes) {
            Genes.EyeStyle.CAPSULE -> 0.15f to 0.24f
            Genes.EyeStyle.ROUND -> 0.19f to 0.19f
            Genes.EyeStyle.DOT -> 0.13f to 0.13f
            Genes.EyeStyle.BEAN -> 0.21f to 0.15f
        }
    val w = w0 * face.size * r
    val h = h0 * face.size * r
    val stroke = r * 0.055f
    listOf(-1f, 1f).forEach { side ->
        val c = s.p(side * face.eyeGap + face.gazeU, face.eyeV)
        when (frame.eyes) {
            EyeShape.OPEN, EyeShape.WIDE -> {
                val grow = if (frame.eyes == EyeShape.WIDE) 1.25f else 1f
                val ew = w * grow
                val eh = (h * grow * frame.eyeOpen).coerceAtLeast(r * 0.02f)
                drawRoundRect(INK, topLeft = Offset(c.x - ew / 2, c.y - eh / 2), size = Size(ew, eh), cornerRadius = CornerRadius(ew / 2, eh / 2))
                if (frame.eyeOpen > 0.45f && genes.eyes != Genes.EyeStyle.DOT) {
                    drawCircle(Color.White, radius = ew * 0.2f, center = Offset(c.x + ew * 0.17f, c.y - eh * 0.2f))
                    if (frame.eyes == EyeShape.WIDE) drawCircle(Color.White, radius = ew * 0.09f, center = Offset(c.x - ew * 0.15f, c.y + eh * 0.2f))
                }
            }

            EyeShape.HAPPY -> drawArc(INK, 200f, 140f, false, topLeft = Offset(c.x - w * 0.75f, c.y - h * 0.3f), size = Size(w * 1.5f, h * 0.9f), style = Stroke(width = stroke, cap = StrokeCap.Round))

            EyeShape.CLOSED -> drawArc(INK, 20f, 140f, false, topLeft = Offset(c.x - w * 0.75f, c.y - h * 0.45f), size = Size(w * 1.5f, h * 0.6f), style = Stroke(width = stroke, cap = StrokeCap.Round))

            EyeShape.SQUINT -> {
                val d = w * 0.7f
                val tip = c.x - side * d * 0.45f
                val back = c.x + side * d * 0.55f
                drawLine(INK, Offset(back, c.y - d * 0.65f), Offset(tip, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(INK, Offset(tip, c.y), Offset(back, c.y + d * 0.65f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            EyeShape.HEARTS -> drawPath(heart(c, w * 1.6f), HEART)
        }
    }
    // Mouth: a curve that smiles or frowns; talking, laughing and surprise open it.
    val mw = (0.09f + 0.05f * frame.smile.coerceAtLeast(0f)) * r
    val y = s.y(face.mouthV)
    val mx = s.x(0f + face.gazeU * 0.4f)
    val curve = 0.06f * frame.smile * r
    if (frame.mouthOpen < 0.08f) {
        val mouth = Path().apply {
            moveTo(mx - mw, y - curve * 0.3f)
            quadraticTo(mx, y + curve, mx + mw, y - curve * 0.3f)
        }
        drawPath(mouth, INK, style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
    } else {
        val depth = (0.03f + 0.1f * frame.mouthOpen) * r
        val open = Path().apply {
            moveTo(mx - mw, y - curve * 0.2f)
            quadraticTo(mx, y + curve * 0.3f, mx + mw, y - curve * 0.2f)
            quadraticTo(mx, y + curve * 0.3f + depth * 2f, mx - mw, y - curve * 0.2f)
            close()
        }
        drawPath(open, INK)
        drawOval(TONGUE, topLeft = Offset(mx - mw * 0.5f, y + depth * 0.55f), size = Size(mw, depth * 0.6f))
    }
}

/** Little arms on the sides of the outline, in the body's shade: hanging, waving or cheering. */
private fun DrawScope.drawArms(
    s: Space,
    body: Profile,
    frame: Frame,
    look: Palette,
) {
    // Raised arms are longer, so they show past the body instead of hiding behind it.
    val len = s.r * if (frame.arms == Arms.REST) 0.3f else 0.4f
    val thick = s.r * 0.14f
    fun arm(
        side: Float,
        out: Float,
    ) {
        val pivot = shoulder(s, body, side)
        rotate(-side * out, pivot) {
            drawRoundRect(look.shade, topLeft = Offset(pivot.x - thick / 2, pivot.y - thick / 2), size = Size(thick, len), cornerRadius = CornerRadius(thick / 2))
        }
    }
    val pi = PI.toFloat()
    when (frame.arms) {
        Arms.REST -> listOf(-1f, 1f).forEach { arm(it, 22f) }

        Arms.UP -> listOf(-1f, 1f).forEach { arm(it, 122f + 10f * sin(frame.armT * 4 * pi)) }

        Arms.WAVE -> {
            arm(-1f, 22f)
            arm(1f, 118f + 25f * sin(frame.armT * 6 * pi))
        }
    }
}

private fun shoulder(
    s: Space,
    body: Profile,
    side: Float,
): Offset {
    val a = rad(if (side > 0) -22f else 202f)
    return outline(s, body, a, body.radiusAt(a) * 0.93f)
}

/** Where a hanging right hand holds things. */
private fun hand(
    s: Space,
    body: Profile,
): Offset {
    val pivot = shoulder(s, body, 1f)
    val a = rad(22f)
    return Offset(pivot.x + sin(a) * s.r * 0.3f, pivot.y + cos(a) * s.r * 0.3f)
}

private fun DrawScope.drawBodyItem(
    s: Space,
    item: Item,
    genes: Genes,
) {
    val r = s.r
    val neck = 0.1f - genes.eyeHeight - 0.47f
    when (item) {
        Item.JERSEY -> {
            drawRect(JERSEY, topLeft = s.p(-1.2f, neck), size = Size(r * 2.4f, r * 2f))
            drawRect(Color.White, topLeft = s.p(-1.2f, neck), size = Size(r * 2.4f, r * 0.05f))
            drawRect(Color.White, topLeft = s.p(-0.07f, neck - 0.18f), size = Size(r * 0.05f, r * 0.24f))
            drawRect(Color.White, topLeft = s.p(0.05f, neck - 0.18f), size = Size(r * 0.05f, r * 0.24f))
        }

        Item.LAB_COAT -> {
            val coat = Path().apply {
                moveTo(s.x(-1.2f), s.y(neck))
                lineTo(s.x(-0.18f), s.y(neck))
                lineTo(s.x(0f), s.y(neck - 0.3f))
                lineTo(s.x(0.18f), s.y(neck))
                lineTo(s.x(1.2f), s.y(neck))
                lineTo(s.x(1.2f), s.y(-1.2f))
                lineTo(s.x(-1.2f), s.y(-1.2f))
                close()
            }
            drawPath(coat, COAT)
            drawPath(coat, COAT_LINE, style = Stroke(width = r * 0.025f))
            drawLine(COAT_LINE, s.p(0f, neck - 0.3f), s.p(0f, -1.2f), strokeWidth = r * 0.02f)
            listOf(neck - 0.45f, neck - 0.62f).forEach { v -> drawCircle(COAT_LINE, radius = r * 0.03f, center = s.p(0.08f, v)) }
        }

        Item.SAFETY_VEST -> {
            listOf(-1f, 1f).forEach { side -> drawRect(VEST, topLeft = s.p(if (side < 0) -1.2f else 0.12f, neck), size = Size(r * 1.08f, r * 2f)) }
            drawRect(SILVER, topLeft = s.p(-1.2f, neck - 0.2f), size = Size(r * 2.4f, r * 0.07f))
        }

        Item.APRON -> {
            drawRoundRect(Color.White, topLeft = s.p(-0.36f, neck + 0.02f), size = Size(r * 0.72f, r * 1.5f), cornerRadius = CornerRadius(r * 0.08f))
            drawRoundRect(APRON_POCKET, topLeft = s.p(-0.18f, neck - 0.28f), size = Size(r * 0.36f, r * 0.18f), cornerRadius = CornerRadius(r * 0.04f))
        }

        else -> Unit
    }
}

private fun DrawScope.drawFaceItem(
    s: Space,
    item: Item,
    face: FaceSpots,
) {
    val r = s.r
    val stroke = r * 0.035f
    listOf(-1f, 1f).forEach { side ->
        val c = s.p(side * face.eyeGap + face.gazeU, face.eyeV)
        when (item) {
            Item.ROUND_GLASSES -> drawCircle(INK, radius = r * 0.15f * face.size, center = c, style = Stroke(width = stroke))

            Item.SUNGLASSES -> drawRoundRect(SHADES, topLeft = Offset(c.x - r * 0.17f, c.y - r * 0.1f), size = Size(r * 0.34f, r * 0.2f), cornerRadius = CornerRadius(r * 0.08f))

            else -> Unit
        }
    }
    val inner = face.eyeGap - 0.15f * face.size
    drawLine(INK, s.p(-inner + face.gazeU, face.eyeV), s.p(inner + face.gazeU, face.eyeV), strokeWidth = stroke)
}

private fun DrawScope.drawNeckItem(
    s: Space,
    item: Item,
    face: FaceSpots,
) {
    val r = s.r
    val v = face.neckV
    when (item) {
        Item.BOW_TIE -> {
            listOf(-1f, 1f).forEach { side ->
                val wing = Path().apply {
                    moveTo(s.x(0f), s.y(v))
                    lineTo(s.x(side * 0.2f), s.y(v + 0.1f))
                    lineTo(s.x(side * 0.2f), s.y(v - 0.1f))
                    close()
                }
                drawPath(wing, BOW)
            }
            drawCircle(BOW_KNOT, radius = r * 0.05f, center = s.p(0f, v))
        }

        Item.SCARF -> {
            drawRoundRect(SCARF, topLeft = s.p(-0.62f, v + 0.07f), size = Size(r * 1.24f, r * 0.14f), cornerRadius = CornerRadius(r * 0.07f))
            drawRoundRect(SCARF, topLeft = s.p(0.22f, v), size = Size(r * 0.14f, r * 0.34f), cornerRadius = CornerRadius(r * 0.05f))
        }

        Item.NECKTIE -> {
            drawCircle(TIE, radius = r * 0.055f, center = s.p(0f, v))
            val tie = Path().apply {
                moveTo(s.x(-0.05f), s.y(v - 0.03f))
                lineTo(s.x(0.05f), s.y(v - 0.03f))
                lineTo(s.x(0.09f), s.y(v - 0.34f))
                lineTo(s.x(0f), s.y(v - 0.44f))
                lineTo(s.x(-0.09f), s.y(v - 0.34f))
                close()
            }
            drawPath(tie, TIE)
        }

        Item.STETHOSCOPE -> {
            val tube = Path().apply {
                moveTo(s.x(-0.42f), s.y(v + 0.12f))
                quadraticTo(s.x(-0.35f), s.y(v - 0.3f), s.x(0.05f), s.y(v - 0.24f))
            }
            drawPath(tube, STETHOSCOPE, style = Stroke(width = r * 0.04f, cap = StrokeCap.Round))
            drawCircle(SILVER, radius = r * 0.075f, center = s.p(0.1f, v - 0.24f))
            drawCircle(STETHOSCOPE, radius = r * 0.075f, center = s.p(0.1f, v - 0.24f), style = Stroke(width = r * 0.02f))
        }

        else -> Unit
    }
}

private fun DrawScope.drawHeadItem(
    s: Space,
    item: Item,
    body: Profile,
    face: FaceSpots,
) {
    val r = s.r
    val topV = body.radiusAt(rad(90f))
    val tip = s.p(0f, topV)
    when (item) {
        Item.CAP -> {
            drawArc(CAP_RED, 180f, 180f, true, topLeft = Offset(tip.x - r * 0.55f, tip.y - r * 0.22f), size = Size(r * 1.1f, r * 0.62f))
            drawRoundRect(CAP_RED_DARK, topLeft = Offset(tip.x + r * 0.2f, tip.y + r * 0.02f), size = Size(r * 0.55f, r * 0.1f), cornerRadius = CornerRadius(r * 0.05f))
            drawCircle(Color.White, radius = r * 0.05f, center = Offset(tip.x, tip.y - r * 0.22f))
        }

        Item.BEANIE -> {
            drawArc(BEANIE, 180f, 180f, true, topLeft = Offset(tip.x - r * 0.62f, tip.y - r * 0.3f), size = Size(r * 1.24f, r * 0.8f))
            drawRoundRect(BEANIE_FOLD, topLeft = Offset(tip.x - r * 0.64f, tip.y + r * 0.02f), size = Size(r * 1.28f, r * 0.2f), cornerRadius = CornerRadius(r * 0.08f))
            drawCircle(Color.White, radius = r * 0.1f, center = Offset(tip.x, tip.y - r * 0.32f))
        }

        Item.CHEF_HAT -> {
            drawRoundRect(HAT_SHADE, topLeft = Offset(tip.x - r * 0.42f, tip.y - r * 0.1f), size = Size(r * 0.84f, r * 0.22f), cornerRadius = CornerRadius(r * 0.06f))
            listOf(-0.26f, 0f, 0.26f).forEach { fx -> drawCircle(Color.White, radius = r * 0.24f, center = Offset(tip.x + r * fx, tip.y - r * 0.32f)) }
            drawRect(Color.White, topLeft = Offset(tip.x - r * 0.4f, tip.y - r * 0.34f), size = Size(r * 0.8f, r * 0.26f))
        }

        Item.HARD_HAT -> {
            drawArc(HARD_HAT, 180f, 180f, true, topLeft = Offset(tip.x - r * 0.56f, tip.y - r * 0.28f), size = Size(r * 1.12f, r * 0.74f))
            drawRoundRect(HARD_HAT_DARK, topLeft = Offset(tip.x - r * 0.72f, tip.y + r * 0.05f), size = Size(r * 1.44f, r * 0.09f), cornerRadius = CornerRadius(r * 0.045f))
            drawRect(HARD_HAT_DARK, topLeft = Offset(tip.x - r * 0.04f, tip.y - r * 0.28f), size = Size(r * 0.08f, r * 0.34f))
        }

        Item.PARTY_HAT ->
            rotate(12f, Offset(tip.x, tip.y)) {
                val cone = Path().apply {
                    moveTo(tip.x - r * 0.3f, tip.y + r * 0.08f)
                    lineTo(tip.x, tip.y - r * 0.62f)
                    lineTo(tip.x + r * 0.3f, tip.y + r * 0.08f)
                    close()
                }
                drawPath(cone, PARTY_A)
                clipPath(cone) {
                    listOf(0.05f, -0.2f, -0.45f).forEach { dy -> drawRect(PARTY_B, topLeft = Offset(tip.x - r * 0.5f, tip.y + dy * r), size = Size(r, r * 0.09f)) }
                }
                drawCircle(POM, radius = r * 0.09f, center = Offset(tip.x, tip.y - r * 0.64f))
            }

        Item.GRAD_CAP -> {
            drawRect(CAP_BLACK, topLeft = Offset(tip.x - r * 0.36f, tip.y - r * 0.08f), size = Size(r * 0.72f, r * 0.18f))
            val board = Path().apply {
                moveTo(tip.x, tip.y - r * 0.3f)
                lineTo(tip.x + r * 0.72f, tip.y - r * 0.1f)
                lineTo(tip.x, tip.y + r * 0.08f)
                lineTo(tip.x - r * 0.72f, tip.y - r * 0.1f)
                close()
            }
            drawPath(board, CAP_BLACK)
            drawLine(TASSEL, Offset(tip.x, tip.y - r * 0.1f), Offset(tip.x + r * 0.5f, tip.y - r * 0.02f), strokeWidth = r * 0.03f)
            drawLine(TASSEL, Offset(tip.x + r * 0.5f, tip.y - r * 0.02f), Offset(tip.x + r * 0.5f, tip.y + r * 0.26f), strokeWidth = r * 0.03f)
            drawCircle(TASSEL, radius = r * 0.045f, center = Offset(tip.x + r * 0.5f, tip.y + r * 0.28f))
        }

        Item.CROWN -> {
            val crown = Path().apply {
                moveTo(tip.x - r * 0.4f, tip.y + r * 0.06f)
                lineTo(tip.x - r * 0.4f, tip.y - r * 0.26f)
                lineTo(tip.x - r * 0.2f, tip.y - r * 0.08f)
                lineTo(tip.x, tip.y - r * 0.34f)
                lineTo(tip.x + r * 0.2f, tip.y - r * 0.08f)
                lineTo(tip.x + r * 0.4f, tip.y - r * 0.26f)
                lineTo(tip.x + r * 0.4f, tip.y + r * 0.06f)
                close()
            }
            drawPath(crown, GOLD)
            listOf(-0.2f, 0f, 0.2f).forEachIndexed { i, fx -> drawCircle(if (i == 1) GEM_RED else GEM_BLUE, radius = r * 0.045f, center = Offset(tip.x + r * fx, tip.y - r * 0.04f)) }
        }

        Item.HEADPHONES -> {
            val w = body.radiusAt(0f)
            drawArc(HEADSET, 180f, 180f, false, topLeft = s.p(-w * 0.95f, topV + 0.14f), size = Size(r * w * 1.9f, r * (topV + 0.14f - face.eyeV) * 2f), style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
            listOf(-1f, 1f).forEach { side ->
                drawRoundRect(HEADSET, topLeft = s.p(side * w * 0.95f - 0.12f, face.eyeV + 0.16f), size = Size(r * 0.24f, r * 0.34f), cornerRadius = CornerRadius(r * 0.1f))
                drawRoundRect(GAMER_LIGHT, topLeft = s.p(side * w * 0.95f - 0.03f, face.eyeV + 0.08f), size = Size(r * 0.06f, r * 0.18f), cornerRadius = CornerRadius(r * 0.03f))
            }
        }

        else -> Unit
    }
}

private fun DrawScope.drawHandItem(
    s: Space,
    item: Item,
    at: Offset,
) {
    val r = s.r
    when (item) {
        Item.BASKETBALL -> {
            val br = r * 0.2f
            val c = Offset(at.x + br * 0.4f, at.y)
            drawCircle(BALL, radius = br, center = c)
            val line = Stroke(width = r * 0.02f)
            drawCircle(INK, radius = br, center = c, style = line)
            drawLine(INK, Offset(c.x - br, c.y), Offset(c.x + br, c.y), strokeWidth = line.width)
            drawLine(INK, Offset(c.x, c.y - br), Offset(c.x, c.y + br), strokeWidth = line.width)
        }

        Item.CONTROLLER -> {
            val w = r * 0.46f
            val h = r * 0.24f
            val tl = Offset(at.x - w * 0.3f, at.y - h / 2)
            drawRoundRect(CONTROLLER, topLeft = tl, size = Size(w, h), cornerRadius = CornerRadius(h / 2))
            drawCircle(GAMER_LIGHT, radius = h * 0.13f, center = Offset(tl.x + w * 0.72f, tl.y + h * 0.38f))
            drawCircle(PARTY_A, radius = h * 0.13f, center = Offset(tl.x + w * 0.84f, tl.y + h * 0.62f))
            drawLine(Color.White, Offset(tl.x + w * 0.18f, tl.y + h / 2), Offset(tl.x + w * 0.36f, tl.y + h / 2), strokeWidth = h * 0.12f)
            drawLine(Color.White, Offset(tl.x + w * 0.27f, tl.y + h * 0.3f), Offset(tl.x + w * 0.27f, tl.y + h * 0.7f), strokeWidth = h * 0.12f)
        }

        Item.COFFEE -> {
            val tl = Offset(at.x - r * 0.08f, at.y - r * 0.14f)
            drawRoundRect(CUP, topLeft = tl, size = Size(r * 0.22f, r * 0.26f), cornerRadius = CornerRadius(r * 0.04f))
            drawRect(CUP_SLEEVE, topLeft = Offset(tl.x, tl.y + r * 0.08f), size = Size(r * 0.22f, r * 0.09f))
            drawRoundRect(CUP_LID, topLeft = Offset(tl.x - r * 0.02f, tl.y - r * 0.04f), size = Size(r * 0.26f, r * 0.06f), cornerRadius = CornerRadius(r * 0.03f))
        }

        Item.BOOK -> {
            val tl = Offset(at.x - r * 0.1f, at.y - r * 0.14f)
            drawRoundRect(BOOK, topLeft = tl, size = Size(r * 0.26f, r * 0.32f), cornerRadius = CornerRadius(r * 0.03f))
            drawRect(Color.White, topLeft = Offset(tl.x + r * 0.2f, tl.y + r * 0.02f), size = Size(r * 0.04f, r * 0.28f))
        }

        else -> Unit
    }
}

private fun DrawScope.drawEffect(
    s: Space,
    body: Profile,
    frame: Frame,
) {
    val r = s.r
    val t = frame.effectT
    val pi = PI.toFloat()
    val m = (t / 1.2f).coerceIn(0f, 1f)
    val fade = 1f - m
    val corner = s.p(0.62f, body.radiusAt(rad(60f)) * 0.9f + 0.1f)
    when (frame.effect) {
        Effect.NONE -> Unit

        Effect.SPARKLES -> {
            val count = 3 + (frame.intensity * 3).toInt()
            repeat(count) { i ->
                val a = i * 2 * pi / count + pi / 2
                val dist = 1.25f + 0.2f * m
                sparkle(s.p(cos(a) * dist, sin(a) * dist * 0.95f), r * 0.1f * sin(m * pi), SPARK)
            }
        }

        Effect.HEARTS ->
            repeat(3) { i ->
                val p = s.p((i - 1) * 0.55f, body.radiusAt(rad(90f)) + 0.1f + m * 0.5f + i * 0.06f)
                drawPath(heart(p, r * (0.16f + 0.05f * i)), HEART.copy(alpha = fade.coerceAtLeast(0.15f)))
            }

        Effect.ZZZ ->
            repeat(3) { i ->
                val p = ((t / 1.6f) % 1f * 1.4f - i * 0.2f).coerceIn(0f, 1f)
                if (p > 0f) zee(Offset(corner.x + i * r * 0.14f, corner.y - p * r * 0.4f - i * r * 0.08f), r * (0.07f + 0.025f * i), ACCENT.copy(alpha = 1f - p * 0.7f))
            }

        Effect.QUESTION -> question(Offset(corner.x, corner.y - r * 0.12f), r * (0.14f + 0.05f * m), ACCENT)

        Effect.EXCLAIM -> exclaim(Offset(corner.x, corner.y - r * 0.14f), r * (0.16f + 0.06f * sin(m * pi)), SPARK)

        Effect.SWEAT -> {
            // Just outside the outline, so it shows on any body colour.
            val at = outline(s, body, rad(38f), body.radiusAt(rad(38f)) + 0.12f)
            val drip = drop(Offset(at.x, at.y + r * 0.1f * m), r * 0.16f)
            drawPath(drip, SWEAT)
            drawPath(drip, Color.White.copy(alpha = 0.8f), style = Stroke(width = r * 0.02f))
        }

        Effect.DOTS ->
            repeat(3) { i ->
                val on = ((t * 2.5f) % 3f).toInt() == i
                drawCircle(ACCENT.copy(alpha = if (on) 1f else 0.35f), radius = r * 0.06f, center = Offset(corner.x - r * 0.1f + i * r * 0.17f, corner.y - r * 0.1f))
            }
    }
}

private fun DrawScope.sparkle(
    c: Offset,
    r: Float,
    color: Color,
) {
    if (r <= 0f) return
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(star, color)
}

private fun heart(
    c: Offset,
    size: Float,
): Path {
    val s = size / 2
    return Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.4f, c.y - s * 0.1f, c.x - s * 0.6f, c.y - s * 1.1f, c.x, c.y - s * 0.4f)
        cubicTo(c.x + s * 0.6f, c.y - s * 1.1f, c.x + s * 1.4f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
}

private fun drop(
    c: Offset,
    size: Float,
): Path {
    val s = size / 2
    return Path().apply {
        moveTo(c.x, c.y - s * 1.2f)
        cubicTo(c.x + s * 0.2f, c.y - s * 0.6f, c.x + s, c.y, c.x, c.y + s * 0.9f)
        cubicTo(c.x - s, c.y, c.x - s * 0.2f, c.y - s * 0.6f, c.x, c.y - s * 1.2f)
        close()
    }
}

/** A "z" drawn with lines, so the renderer needs no fonts. */
private fun DrawScope.zee(
    c: Offset,
    h: Float,
    color: Color,
) {
    val z = Path().apply {
        moveTo(c.x - h / 2, c.y - h / 2)
        lineTo(c.x + h / 2, c.y - h / 2)
        lineTo(c.x - h / 2, c.y + h / 2)
        lineTo(c.x + h / 2, c.y + h / 2)
    }
    drawPath(z, color, style = Stroke(width = h * 0.22f, cap = StrokeCap.Round))
}

private fun DrawScope.question(
    c: Offset,
    h: Float,
    color: Color,
) {
    drawArc(color, 180f, 270f, false, topLeft = Offset(c.x - h * 0.3f, c.y - h * 0.5f), size = Size(h * 0.6f, h * 0.55f), style = Stroke(width = h * 0.16f, cap = StrokeCap.Round))
    drawLine(color, Offset(c.x, c.y + h * 0.05f), Offset(c.x, c.y + h * 0.2f), strokeWidth = h * 0.16f, cap = StrokeCap.Round)
    drawCircle(color, radius = h * 0.09f, center = Offset(c.x, c.y + h * 0.45f))
}

private fun DrawScope.exclaim(
    c: Offset,
    h: Float,
    color: Color,
) {
    drawLine(color, Offset(c.x, c.y - h * 0.5f), Offset(c.x, c.y + h * 0.15f), strokeWidth = h * 0.2f, cap = StrokeCap.Round)
    drawCircle(color, radius = h * 0.11f, center = Offset(c.x, c.y + h * 0.45f))
}

private val INK = Color(0xFF2A2640)
private val BLUSH = Color(0xFFFF8FAB)
private val TONGUE = Color(0xFFFF7A95)
private val ACCENT = Color(0xFFB9A8FF)
private val HEART = Color(0xFFFF5C8A)
private val SPARK = Color(0xFFFFD166)
private val SWEAT = Color(0xFF7FD1FF)
private val LEAF = Color(0xFF7BC96F)
private val LEAF_DARK = Color(0xFF4E9A48)
private val ANTENNA_BALL = Color(0xFFFF6F91)
private val JERSEY = Color(0xFFE8452C)
private val CAP_RED = Color(0xFFE8452C)
private val CAP_RED_DARK = Color(0xFFB8321F)
private val BEANIE = Color(0xFF3F7CE8)
private val BEANIE_FOLD = Color(0xFF2E5FB8)
private val BALL = Color(0xFFF28C28)
private val COAT = Color(0xFFF7F8FB)
private val COAT_LINE = Color(0xFFB9C0CE)
private val STETHOSCOPE = Color(0xFF3A3F4B)
private val SILVER = Color(0xFFC3CAD4)
private val HAT_SHADE = Color(0xFFE3E3EA)
private val SCARF = Color(0xFFE63946)
private val HEADSET = Color(0xFF2F2B3A)
private val GAMER_LIGHT = Color(0xFF3DDC97)
private val CONTROLLER = Color(0xFF3B3650)
private val CAP_BLACK = Color(0xFF1F1D2B)
private val TASSEL = Color(0xFFFFC23D)
private val VEST = Color(0xFFFF7A1A)
private val HARD_HAT = Color(0xFFFFC21A)
private val HARD_HAT_DARK = Color(0xFFE0A800)
private val TIE = Color(0xFF2E4A9E)
private val BOW = Color(0xFFE63970)
private val BOW_KNOT = Color(0xFFB82A57)
private val PARTY_A = Color(0xFFFF5C8A)
private val PARTY_B = Color(0xFF5CC8FF)
private val POM = Color(0xFFFFD166)
private val GOLD = Color(0xFFFFC53D)
private val GEM_RED = Color(0xFFE63946)
private val GEM_BLUE = Color(0xFF3F7CE8)
private val SHADES = Color(0xFF1D1B26)
private val APRON_POCKET = Color(0xFFE6E6EE)
private val CUP = Color(0xFFFFFFFF)
private val CUP_SLEEVE = Color(0xFFB07A4F)
private val CUP_LID = Color(0xFF5A4636)
private val BOOK = Color(0xFF7A5CE0)
