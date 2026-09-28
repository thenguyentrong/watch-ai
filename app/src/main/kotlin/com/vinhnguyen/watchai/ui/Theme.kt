package com.vinhnguyen.watchai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vinhnguyen.watchai.R

/**
 * Buddy's look on the phone: calm neutrals, one typeface (Inter), and the user's own Buddy as the
 * only colour. Light and dark follow the system. Material components pick these up, so they come
 * out monochrome too.
 */
@Immutable
data class Palette(
    val background: Color,
    /** Grouped rows, the chat composer. */
    val surface: Color,
    /** Pressed rows, the user's chat bubbles. */
    val surfaceHigh: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val danger: Color,
    /** What glass is tinted with over this background. */
    val glass: Color,
    /** Buddy's face: dark on a light background, cut out (the background) on a dark one. */
    val eyes: Color,
    val dark: Boolean,
)

private val Light =
    Palette(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF4F4F5),
        surfaceHigh = Color(0xFFE9E9EC),
        text = Color(0xFF0A0A0B),
        textSecondary = Color(0xFF65656D),
        textTertiary = Color(0xFFA1A1AA),
        separator = Color(0xFFE4E4E7),
        danger = Color(0xFFD92D20),
        glass = Color(0x8CFFFFFF),
        eyes = Color(0xFF121214),
        dark = false,
    )

private val Dark =
    Palette(
        background = Color(0xFF0B0B0D),
        surface = Color(0xFF17171A),
        surfaceHigh = Color(0xFF232327),
        text = Color(0xFFF4F4F5),
        textSecondary = Color(0xFFA1A1AA),
        textTertiary = Color(0xFF6E6E78),
        separator = Color(0xFF26262B),
        danger = Color(0xFFF97066),
        glass = Color(0x8C1C1C20),
        eyes = Color(0xFF0B0B0D),
        dark = true,
    )

val LocalPalette = staticCompositionLocalOf { Light }

private fun inter(
    weight: Int,
    opticalSize: Float,
) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
)

val Inter = FontFamily(listOf(400, 500, 600, 700).map { inter(it, 14f) })

/** Inter's display cut (its optical size axis): tighter and finer for titles. */
private val InterDisplay = FontFamily(listOf(500, 600, 700).map { inter(it, 28f) })

private fun style(
    size: Int,
    weight: Int,
    line: Int,
    tracking: Double = 0.0,
    family: FontFamily = Inter,
) = TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = tracking.em)

private val Type =
    Typography(
        displaySmall = style(34, 600, 40, -0.012, InterDisplay),
        headlineMedium = style(30, 600, 36, -0.01, InterDisplay),
        headlineSmall = style(24, 600, 30, -0.008, InterDisplay),
        titleLarge = style(20, 600, 26, -0.006, InterDisplay),
        titleMedium = style(17, 500, 24, -0.01),
        titleSmall = style(15, 600, 20, -0.006),
        bodyLarge = style(16, 400, 24, -0.006),
        bodyMedium = style(15, 400, 21, -0.004),
        bodySmall = style(13, 400, 18),
        labelLarge = style(15, 500, 20, -0.004),
        labelMedium = style(13, 500, 18),
        labelSmall = style(12, 500, 16),
    )

@Composable
fun WatchAiTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme =
        (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
            primary = p.text,
            onPrimary = p.background,
            primaryContainer = p.surfaceHigh,
            onPrimaryContainer = p.text,
            secondary = p.textSecondary,
            onSecondary = p.background,
            secondaryContainer = p.surfaceHigh,
            onSecondaryContainer = p.text,
            tertiary = p.textSecondary,
            background = p.background,
            onBackground = p.text,
            surface = p.background,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.textSecondary,
            surfaceContainerLowest = p.background,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surfaceHigh,
            surfaceContainerHighest = p.surfaceHigh,
            outline = p.separator,
            outlineVariant = p.separator,
            error = p.danger,
            onError = Color.White,
            scrim = Color.Black,
        )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = Type) {
            // Pages aren't on a Material surface: text with no colour of its own takes the theme's.
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
