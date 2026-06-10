package com.hardwareone.console.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// Terminal palette: phosphor green on near-black.
val TerminalGreen = Color(0xFF39FF14)
val TerminalDim = Color(0xFF7CFC9A)
val TerminalAmber = Color(0xFFFFB000)
val TerminalRed = Color(0xFFFF5555)
val TerminalBg = Color(0xFF0B0B0B)
val TerminalSurface = Color(0xFF141414)
val TerminalOnSurface = Color(0xFFD6FFD6)

private val DarkColors = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = TerminalBg,
    secondary = TerminalDim,
    background = TerminalBg,
    onBackground = TerminalOnSurface,
    surface = TerminalSurface,
    onSurface = TerminalOnSurface,
    surfaceVariant = TerminalSurface,
    error = TerminalRed,
)

/** App theme. Always dark — it's a terminal. */
@Composable
fun HardwareOneTheme(content: @Composable () -> Unit) {
    val monoBody = TextStyle(fontFamily = FontFamily.Monospace)
    val typography = Typography(
        bodyLarge = monoBody,
        bodyMedium = monoBody,
        bodySmall = monoBody,
        labelLarge = monoBody,
    )
    MaterialTheme(
        colorScheme = DarkColors,
        typography = typography,
        content = content,
    )
}
