package com.majk.extractor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NeonGreen = Color(0xFF00FF41)
private val DarkGrey = Color(0xFF1C1C1E)
private val SurfaceVariant = Color(0xFF3A3A3C)
private val OutlineGrey = Color(0xFF5A5A5C)

private val ExtractorDarkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003D14),
    onPrimaryContainer = NeonGreen,
    secondary = NeonGreen,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1A3D24),
    onSecondaryContainer = NeonGreen,
    tertiary = NeonGreen,
    onTertiary = Color.Black,
    background = DarkGrey,
    onBackground = Color.White,
    surface = DarkGrey,
    onSurface = Color.White,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFE0E0E0),
    outline = OutlineGrey,
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = Color(0xFF006622)
)

@Composable
fun ExtractorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ExtractorDarkColors,
        content = content
    )
}
