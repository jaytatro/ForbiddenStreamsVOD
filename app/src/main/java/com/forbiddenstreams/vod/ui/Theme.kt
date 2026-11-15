package com.forbiddenstreams.vod.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colors sampled from the Forbidden Streams VOD logo. */
object Brand {
    /** "FORBIDDEN" title green. */
    val ToxicGreen = Color(0xFF6CCD4E)

    /** Brighter tagline green, used for the TV focus ring. */
    val GlowGreen = Color(0xFF90E15D)

    /** The red prohibition ring. */
    val ForbiddenRed = Color(0xFF981517)

    /** Grimy dark teal of the wall and CRT screen. */
    val Background = Color(0xFF0F1A1B)
    val Surface = Color(0xFF152324)
    val SurfaceRaised = Color(0xFF1D2E30)
    val Screen = Color(0xFF223138)

    /** Off-white of the "STREAMS TV VOD" text. */
    val Bone = Color(0xFFF1F1EC)
}

private val VodColors = darkColorScheme(
    primary = Brand.ToxicGreen,
    onPrimary = Color(0xFF0A1408),
    primaryContainer = Color(0xFF244A1A),
    onPrimaryContainer = Brand.GlowGreen,
    secondary = Brand.ForbiddenRed,
    onSecondary = Brand.Bone,
    secondaryContainer = Color(0xFF2E4A24),
    onSecondaryContainer = Brand.GlowGreen,
    tertiary = Brand.GlowGreen,
    onTertiary = Color(0xFF0A1408),
    error = Color(0xFFE5484D),
    onError = Color.White,
    background = Brand.Background,
    onBackground = Brand.Bone,
    surface = Brand.Surface,
    onSurface = Brand.Bone,
    surfaceVariant = Brand.Screen,
    onSurfaceVariant = Color(0xFFA9B9B4),
    surfaceContainerLowest = Color(0xFF0A1314),
    surfaceContainerLow = Brand.Surface,
    surfaceContainer = Color(0xFF192829),
    surfaceContainerHigh = Brand.SurfaceRaised,
    surfaceContainerHighest = Color(0xFF223436),
    outline = Color(0xFF3D5552),
    outlineVariant = Color(0xFF2A3D3D),
)

@Composable
fun VodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VodColors, content = content)
}
