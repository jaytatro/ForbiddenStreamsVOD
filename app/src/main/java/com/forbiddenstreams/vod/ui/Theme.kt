package com.forbiddenstreams.vod.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VodColors = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1414),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFFF8A65),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4A1C1C),
    onSecondaryContainer = Color.White,
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF141418),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFB4B4BE),
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF16161A),
    surfaceContainer = Color(0xFF1A1A1F),
    surfaceContainerHigh = Color(0xFF1F1F25),
    surfaceContainerHighest = Color(0xFF24242B),
    outline = Color(0xFF4A4A55),
)

@Composable
fun VodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VodColors, content = content)
}
