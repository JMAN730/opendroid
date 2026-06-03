package com.opendroid.ai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * OpenDroid color palette — resolved via CompositionLocal so all screens
 * automatically adapt to the active theme (light / dark).
 */
data class OpenDroidColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentNeonGreen: Color,
    val accentPurple: Color,
    val accentCyan: Color,
    val accentRed: Color,
    val isDark: Boolean
)

// ── Dark palette (the existing one) ─────────────────────────
val DarkPalette = OpenDroidColors(
    background = Color(0xFF080C10),
    surface = Color(0xFF0D1117),
    cardBackground = Color(0xFF161B22),
    borderColor = Color(0xFF30363D),
    textPrimary = Color(0xFFF0F6FC),
    textSecondary = Color(0xFF8B949E),
    accentNeonGreen = Color(0xFF00FF88),
    accentPurple = Color(0xFF8A2BE2),
    accentCyan = Color(0xFF00F0FF),
    accentRed = Color(0xFFFF3B30),
    isDark = true
)

// ── Light palette ───────────────────────────────────────────
val LightPalette = OpenDroidColors(
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    borderColor = Color(0xFFD0D7DE),
    textPrimary = Color(0xFF1F2328),
    textSecondary = Color(0xFF656D76),
    accentNeonGreen = Color(0xFF1A7F37),
    accentPurple = Color(0xFF8250DF),
    accentCyan = Color(0xFF0969DA),
    accentRed = Color(0xFFCF222E),
    isDark = false
)

val LocalOpenDroidColors = compositionLocalOf { DarkPalette }

/** Access the active palette from any @Composable */
object AppTheme {
    val colors: OpenDroidColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenDroidColors.current
}

// ── Legacy top-level aliases ────────────────────────────────
// These keep every existing screen compiling without changes.
// They delegate to the composition-local palette at read-time.

// NOTE: These are static vals used outside @Composable scope.
// For full dynamic theming in screens that read these outside Compose,
// they keep the dark defaults. Inside @Composable, use AppTheme.colors.*
val DarkBackground = DarkPalette.background
val DarkSurface = DarkPalette.surface
val CardBackground = DarkPalette.cardBackground
val BorderColor = DarkPalette.borderColor
val TextPrimary = DarkPalette.textPrimary
val TextSecondary = DarkPalette.textSecondary
val AccentNeonGreen = DarkPalette.accentNeonGreen
val AccentPurple = DarkPalette.accentPurple
val AccentCyan = DarkPalette.accentCyan
val AccentRed = DarkPalette.accentRed
