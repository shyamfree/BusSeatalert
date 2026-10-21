package com.busseatalert.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kerala RTC style: red bars and buttons on white. */
val KsrtcRed = Color(0xFFD71920)
private val DarkRed = Color(0xFF8E0E12)
private val LightRed = Color(0xFFFFE3E3)
private val Blush = Color(0xFFFFF5F5)

private val Colors = lightColorScheme(
    primary = KsrtcRed,
    onPrimary = Color.White,
    primaryContainer = LightRed,
    onPrimaryContainer = DarkRed,
    secondary = DarkRed,
    onSecondary = Color.White,
    secondaryContainer = LightRed,
    onSecondaryContainer = DarkRed,
    tertiary = Color(0xFF5D4037),
    background = Color.White,
    onBackground = Color(0xFF1C1B1B),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1B),
    surfaceVariant = Blush,
    onSurfaceVariant = Color(0xFF5A4444),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Blush,
    surfaceContainer = Blush,
    surfaceContainerHigh = Color(0xFFFFEFEF),
    surfaceContainerHighest = Color(0xFFFFEBEB),
    outline = Color(0xFF9C8585),
    outlineVariant = Color(0xFFF0D0D0),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)
