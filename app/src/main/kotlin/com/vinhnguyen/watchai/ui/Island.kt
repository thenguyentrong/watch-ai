package com.vinhnguyen.watchai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.vinhnguyen.watchai.R
import com.vinhnguyen.watchai.actions.BuddyCard
import com.vinhnguyen.watchai.actions.Symbol
import com.vinhnguyen.watchai.voice.VoicePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the pop-up at the top of the home screen shows. */
sealed interface Island {
    data object Hidden : Island

    /** A conversation is on: a small capsule with what Buddy is doing. */
    data class Live(
        val phase: VoicePhase,
        val onWatch: Boolean,
    ) : Island

    /** Something Buddy did or found, or a text or call waiting for a yes. */
    data class Showing(
        val card: BuddyCard,
    ) : Island
}

private val Ink = Color(0xFF0A0A0B)
private val OnInk = Color(0xFFF7F7F8)
private val OnInkQuiet = Color(0xB3F7F7F8)

/**
 * Buddy's pop-up, like the Dynamic Island: a dark glass capsule at the top that grows into a card
 * for what just happened (a timer, directions with a map, an app, messages) and shrinks back.
 * A text or a call waiting for a yes gets its buttons here: a tap is as good as saying it.
 */
@Composable
fun BuddyIsland(
    island: Island,
    backdrop: Backdrop,
    accent: Color,
    level: Float,
    onDismiss: () -> Unit,
    onDecide: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rim = if (LocalPalette.current.dark) Color.White.copy(alpha = 0.12f) else Color.Transparent
    AnimatedContent(
        targetState = island,
        contentKey = { it::class },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f)) },
        modifier = modifier,
        label = "island",
    ) { state ->
        when (state) {
            Island.Hidden -> Spacer(Modifier.height(1.dp))

            is Island.Live ->
                Row(
                    Modifier
                        .height(40.dp)
                        .glass(backdrop, Ink.copy(alpha = 0.86f))
                        .border(0.5.dp, rim, Capsule())
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Bars(accent, if (state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.SPEAKING) level else 0f)
                    Text(liveText(state), style = MaterialTheme.typography.labelLarge, color = OnInk, maxLines = 1)
                }

            is Island.Showing ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .widthIn(max = 520.dp)
                        .glass(backdrop, Ink.copy(alpha = 0.9f), corner = 30.dp)
                        .border(0.5.dp, rim, RoundedRectangle(30.dp))
                        .clip(RoundedRectangle(30.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Close",
                            onClick = { if (state.card !is BuddyCard.Ask) onDismiss() },
                        ).animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                        .padding(14.dp),
                ) {
                    Card(state.card, accent, onDecide)
                }
        }
    }
}

@Composable
private fun Card(
    card: BuddyCard,
    accent: Color,
    onDecide: (Boolean) -> Unit,
) {
    when (card) {
        is BuddyCard.Done -> Header({ Symbol(card.symbol, if (card.symbol == Symbol.CANCEL) Color(0xFFF97066) else accent) }, card.title, card.detail)

        is BuddyCard.App -> Header({ AppIcon(card.packageName) }, card.title, null)

        is BuddyCard.Ask -> {
            Header({ card.packageName?.let { AppIcon(it) } ?: Symbol(Symbol.PHONE, accent) }, card.title, card.detail, detailLines = 4)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IslandButton("Cancel", filled = false, modifier = Modifier.weight(1f)) { onDecide(false) }
                IslandButton(card.confirm, filled = true, modifier = Modifier.weight(1f)) { onDecide(true) }
            }
        }

        is BuddyCard.Place -> {
            Header({ Symbol(null, accent, R.drawable.sym_directions) }, "Directions", card.name)
            Map(card.name, accent)
        }

        is BuddyCard.Messages -> {
            card.lines.take(3).forEachIndexed { i, line ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Header({ AppIcon(line.packageName) }, line.from, line.text, detailLines = 2)
            }
        }
    }
}

@Composable
private fun Header(
    leading: @Composable () -> Unit,
    title: String,
    detail: String?,
    detailLines: Int = 2,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OnInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = OnInkQuiet, maxLines = detailLines, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Symbol(
    symbol: Symbol?,
    tint: Color,
    icon: Int = SYMBOLS[symbol] ?: R.drawable.sym_check_circle,
) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun Map(
    place: String,
    accent: Color,
) {
    val density = LocalDensity.current
    val width = with(density) { 480.dp.roundToPx() }
    val height = with(density) { 200.dp.roundToPx() }
    val map by produceState<ImageBitmap?>(null, place) { value = PlaceMap.render(place, width, height) }
    val links = LocalUriHandler.current
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f))) {
        map?.let {
            Image(it, contentDescription = "Map of $place", contentScale = ContentScale.Crop, colorFilter = NightMap, modifier = Modifier.fillMaxWidth().aspectRatio(2.4f))
            // The place: a dot in Buddy's colour with a white ring, like a map pin seen from above.
            Box(Modifier.align(Alignment.Center).size(18.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(accent))
        }
        Text(
            "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = OnInkQuiet,
            modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ink.copy(alpha = 0.6f))
                .clickable(role = Role.Button, onClickLabel = "Map licence") { links.openUri("https://www.openstreetmap.org/copyright") }
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun IslandButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(42.dp)
            .clip(CircleShape)
            .background(if (filled) OnInk else Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) Ink else OnInk)
    }
}

/** Three bars that move with the voice. */
@Composable
private fun Bars(
    color: Color,
    level: Float,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.55f, 1f, 0.7f).forEach { weight ->
            val h = 6.dp + 12.dp * (level.coerceIn(0f, 1f) * weight)
            Box(Modifier.width(3.dp).height(h).clip(CircleShape).background(color))
        }
    }
}

private fun liveText(state: Island.Live): String = when (state.phase) {
    VoicePhase.CONNECTING -> "Connecting"
    VoicePhase.LISTENING -> if (state.onWatch) "Listening on your watch" else "Listening"
    VoicePhase.THINKING -> "Thinking"
    VoicePhase.SPEAKING -> "Speaking"
    VoicePhase.ERROR -> "Something went wrong"
    VoicePhase.IDLE -> "Buddy"
}

private val SYMBOLS =
    mapOf(
        Symbol.TIMER to R.drawable.sym_timer,
        Symbol.ALARM to R.drawable.sym_alarm,
        Symbol.NOTE to R.drawable.sym_sticky_note_2,
        Symbol.CALENDAR to R.drawable.sym_event,
        Symbol.MUSIC to R.drawable.sym_music_note,
        Symbol.LIGHT to R.drawable.sym_flashlight_on,
        Symbol.PHONE to R.drawable.sym_ring_volume,
        Symbol.CHECK to R.drawable.sym_check_circle,
        Symbol.CANCEL to R.drawable.sym_cancel,
        Symbol.PRIVATE to R.drawable.sym_lock,
    )

/**
 * OpenStreetMap's light map made dark for the island: brightness inverted, then hues turned half
 * way round so parks stay green and water blue, a little muted.
 */
private val NightMap =
    ColorFilter.colorMatrix(
        ColorMatrix().apply {
            // Invert.
            val invert = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            // Hue rotation by 180 degrees (the usual luminance-preserving matrix).
            val hue = ColorMatrix(floatArrayOf(-0.574f, 1.43f, 0.144f, 0f, 0f, 0.426f, 0.43f, 0.144f, 0f, 0f, 0.426f, 1.43f, -0.856f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            val calm = ColorMatrix().apply { setToSaturation(0.7f) }
            set(invert)
            timesAssign(hue)
            timesAssign(calm)
        },
    )
