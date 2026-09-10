package com.example.smartmarkerfrontend.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SmartMarkerColorScheme = lightColorScheme(
    primary = SmColors.accent,
    onPrimary = SmColors.bg,
    secondary = SmColors.accent700,
    onSecondary = SmColors.bg,
    tertiary = SmColors.accent400,
    background = SmColors.bg,
    onBackground = SmColors.ink,
    surface = SmColors.bg,
    onSurface = SmColors.ink,
    surfaceVariant = SmColors.neutral100,
    onSurfaceVariant = SmColors.neutral700,
    outline = SmColors.divider,
    error = SmColors.bad,
    onError = Color.White
)

@Composable
fun SmartMarkerFrontendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartMarkerColorScheme,
        typography = Typography,
        content = content
    )
}
