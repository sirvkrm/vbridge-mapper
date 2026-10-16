package com.vbridge.mapper.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CyberCyan = Color(0xFF00E5FF)
val ElectricViolet = Color(0xFF7C4DFF)
val NeonAmber = Color(0xFFFFD600)
val NeonEmerald = Color(0xFF00E676)
val NeonCrimson = Color(0xFFFF1744)

val DarkObsidian = Color(0xFF0F141C)
val SurfaceDark = Color(0xFF18202C)
val CardDark = Color(0xFF1E293B)
val BorderDark = Color(0xFF334155)
val TextMuted = Color(0xFF94A3B8)

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = CyberCyan,
    secondary = ElectricViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF321B66),
    onSecondaryContainer = ElectricViolet,
    tertiary = NeonAmber,
    background = DarkObsidian,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextMuted,
    outline = BorderDark
)

@Composable
fun VBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
