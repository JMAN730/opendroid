package com.opendroid.ai.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.accentNeonGreen,
    secondary = DarkPalette.accentPurple,
    tertiary = DarkPalette.accentCyan,
    background = DarkPalette.background,
    surface = DarkPalette.surface,
    onPrimary = DarkPalette.background,
    onSecondary = DarkPalette.textPrimary,
    onBackground = DarkPalette.textPrimary,
    onSurface = DarkPalette.textPrimary,
    error = DarkPalette.accentRed
)

private val LightColorScheme = lightColorScheme(
    primary = LightPalette.accentNeonGreen,
    secondary = LightPalette.accentPurple,
    tertiary = LightPalette.accentCyan,
    background = LightPalette.background,
    surface = LightPalette.surface,
    onPrimary = LightPalette.surface,
    onSecondary = LightPalette.textPrimary,
    onBackground = LightPalette.textPrimary,
    onSurface = LightPalette.textPrimary,
    error = LightPalette.accentRed
)

@Composable
fun OpenDroidTheme(
    isDarkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (isDarkTheme) DarkPalette else LightPalette
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.background.toArgb()
            // Light status bar icons for dark theme, dark icons for light theme
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    CompositionLocalProvider(LocalOpenDroidColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
