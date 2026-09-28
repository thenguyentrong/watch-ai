package com.vinhnguyen.watchai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.vinhnguyen.watchai.R

/*
 * The few pieces every screen is made of. Glass is Kyant's Liquid Glass for Compose (Apache-2.0):
 * an Android take on Apple's material (blur, vibrancy, a little refraction at the edge), not
 * Apple's own. Used sparingly, for controls that float over content.
 */

/** Liquid Glass over [backdrop], tinted for the theme (or [tint]). */
fun Modifier.glass(
    backdrop: Backdrop,
    tint: Color,
    corner: Dp? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { if (corner == null) Capsule() else RoundedRectangle(corner) },
    effects = {
        vibrancy()
        blur(10.dp.toPx())
        lens(10.dp.toPx(), 20.dp.toPx())
    },
    onDrawSurface = { drawRect(tint) },
)

/** A round glass button floating over content: the menu, back, a new chat. */
@Composable
fun GlassCircle(
    backdrop: Backdrop,
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .size(size)
            .glass(backdrop, p.glass)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = p.text, modifier = Modifier.size(22.dp))
    }
}

/** A titled group of rows on a rounded card, like a settings list. */
@Composable
fun Group(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        title?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = p.textSecondary, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface), content = content)
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

/** One row in a [Group]. [last] rows have no separator under them. */
@Composable
fun Item(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    painter: Painter? = null,
    danger: Boolean = false,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            painter?.let {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(it, contentDescription = null, tint = if (danger) p.danger else p.text, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) p.danger else p.text)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary) }
            }
            trailing?.let {
                Spacer(Modifier.width(12.dp))
                it()
            }
        }
        if (!last) {
            Box(Modifier.padding(start = if (painter != null) 62.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(p.separator))
        }
    }
}

/** A small rounded button: [filled] for the one thing to do, otherwise quiet. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    danger: Boolean = false,
) {
    val p = LocalPalette.current
    val background = when {
        danger -> p.danger
        filled -> p.text
        else -> p.surfaceHigh
    }
    val foreground = if (danger || filled) p.background else p.text
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground, maxLines = 1)
    }
}

/**
 * A page opened from the menu, with a glass back button floating over it. Pages that [scrolls]
 * get a large title that scrolls away (a small one takes its place); the others (the chat) get the
 * small title, or [center], from the start and run under the bar: [content] gets the height to
 * leave free at the top. Content fades out under the bar instead of meeting a hard edge.
 */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    scrolls: Boolean = true,
    center: (@Composable () -> Unit)? = null,
    action: (@Composable (Backdrop) -> Unit)? = null,
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    content: @Composable ColumnScope.(top: Dp) -> Unit,
) {
    val p = LocalPalette.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val bar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BAR_HEIGHT
    val titlePx = with(density) { 56.dp.toPx() }
    val scrolled by remember(scroll) { derivedStateOf { scroll.value > titlePx } }
    val under by remember(scroll) { derivedStateOf { scroll.value > 0 } }
    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(p.background)
                .then(if (scrolls) Modifier.verticalScroll(scroll) else Modifier)
                .navigationBarsPadding(),
        ) {
            if (scrolls) {
                Spacer(Modifier.height(bar))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = p.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp))
                content(0.dp)
                Spacer(Modifier.height(32.dp))
            } else {
                content(bar)
            }
        }
        EdgeFade(visible = !scrolls || under, height = bar + 20.dp)
        PageBar(backdrop, title, showTitle = !scrolls || scrolled, center = center, action = action, onBack = onBack)
    }
}

private val BAR_HEIGHT = 60.dp

/** Like iOS's scroll edge: the page's own colour, fading out, so text never runs into the bar. */
@Composable
private fun BoxScope.EdgeFade(
    visible: Boolean,
    height: Dp,
) {
    val p = LocalPalette.current
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "edge")
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { this.alpha = alpha }
            .background(Brush.verticalGradient(0f to p.background, 0.55f to p.background.copy(alpha = 0.9f), 1f to p.background.copy(alpha = 0f))),
    )
}

@Composable
private fun BoxScope.PageBar(
    backdrop: Backdrop,
    title: String,
    showTitle: Boolean,
    center: (@Composable () -> Unit)?,
    action: (@Composable (Backdrop) -> Unit)?,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().height(BAR_HEIGHT).padding(horizontal = 12.dp)) {
        GlassCircle(backdrop, painterResource(R.drawable.sym_arrow_back), "Back", onBack, Modifier.align(Alignment.CenterStart))
        Box(Modifier.align(Alignment.Center).padding(horizontal = 56.dp)) {
            if (center != null) {
                center()
            } else {
                AnimatedVisibility(showTitle, enter = fadeIn(), exit = fadeOut()) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        action?.let { Box(Modifier.align(Alignment.CenterEnd)) { it(backdrop) } }
    }
}
