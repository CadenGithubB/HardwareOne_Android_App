package com.hardwareone.console.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Palette copied verbatim from the HardwareOne web UI shared CSS
 * (firmware: components/hardwareone/WebServer_Utils.h -> streamCommonCSS()).
 *
 * The web look is "glassmorphism": a vivid gradient backdrop with frosted translucent
 * cards, plus a dark terminal panel for console/code output. We reproduce that here.
 */

// Brand accent (--accent)
private val Indigo = Color(0xFF667EEA)        // light
private val IndigoDark = Color(0xFF818CF8)    // dark
private val Purple = Color(0xFF764BA2)        // gradient end (light) / secondary
private val VioletDark = Color(0xFFA78BFA)    // dark secondary (--warning-fg/info-fg dark)

// Background gradients (--bg)
private val LightGradStart = Color(0xFF667EEA)
private val LightGradEnd = Color(0xFF764BA2)
private val DarkGradStart = Color(0xFF07070B)
private val DarkGradEnd = Color(0xFF151520)

// Terminal/console panel — identical in both themes (--terminal-bg / --terminal-fg)
private val TerminalBg = Color(0xFF12121C)
private val TerminalFg = Color(0xFFD4D4D4)

// Console line colours, tuned to read on the always-dark terminal panel.
private val LogOut = Color(0xFF8AB4FF)    // your command (--link)
private val LogIn = TerminalFg            // device reply (--terminal-fg)
private val LogInfo = Color(0xFF9AA0A6)   // info (muted grey)
private val LogError = Color(0xFFFF6B78)  // error (--danger, brightened for dark bg)

/** Web design tokens that don't map cleanly onto a Material [androidx.compose.material3.ColorScheme]. */
@Immutable
data class HwColors(
    val gradient: List<Color>,
    val onGradient: Color,   // --fg
    val muted: Color,        // --muted
    val cardBg: Color,       // --card-bg (frosted)
    val cardBorder: Color,   // --card-border
    val accent: Color,       // --accent
    val link: Color,         // --link
    val danger: Color,       // --danger
    val success: Color,      // --success
    val terminalBg: Color,   // --terminal-bg
    val terminalFg: Color,   // --terminal-fg
    val logOut: Color,
    val logIn: Color,
    val logInfo: Color,
    val logError: Color,
)

private val LightHw = HwColors(
    gradient = listOf(LightGradStart, LightGradEnd),
    onGradient = Color(0xFFFFFFFF),
    muted = Color(0xBFFFFFFF),       // rgba(255,255,255,.75)
    cardBg = Color(0x1AFFFFFF),      // rgba(255,255,255,.10)
    cardBorder = Color(0x38FFFFFF),  // rgba(255,255,255,.22)
    accent = Indigo,
    link = Color(0xFFBCD0FF),
    danger = Color(0xFFDC3545),
    success = Color(0xFF28A745),
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
    logOut = LogOut, logIn = LogIn, logInfo = LogInfo, logError = LogError,
)

private val DarkHw = HwColors(
    gradient = listOf(DarkGradStart, DarkGradEnd),
    onGradient = Color(0xFFF2F2F7),
    muted = Color(0xB8F2F2F7),       // rgba(242,242,247,.72)
    cardBg = Color(0x0AFFFFFF),      // rgba(255,255,255,.04)
    cardBorder = Color(0x1FFFFFFF),  // rgba(255,255,255,.12)
    accent = IndigoDark,
    link = Color(0xFF8AB4FF),
    danger = Color(0xFFFF5A6A),
    success = Color(0xFF4ADE80),
    terminalBg = TerminalBg,
    terminalFg = TerminalFg,
    logOut = LogOut, logIn = LogIn, logInfo = LogInfo, logError = LogError,
)

// Material scheme drives buttons, text fields, and dialogs (readable solid surfaces).
private val LightScheme = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    secondary = Purple, onSecondary = Color.White,
    background = LightGradStart, onBackground = Color.White,
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F2430),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF4A4F5A),
    error = Color(0xFFDC3545), onError = Color.White,
    outline = Color(0xFFC4C9D4), outlineVariant = Color(0xFFDFE3EA),
)

private val DarkScheme = darkColorScheme(
    primary = IndigoDark, onPrimary = Color(0xFF0B0B14),
    secondary = VioletDark, onSecondary = Color(0xFF0B0B14),
    background = DarkGradStart, onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF15151F), onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1E1E2A), onSurfaceVariant = Color(0xFFC7C7D1),
    error = Color(0xFFFF5A6A), onError = Color(0xFF0B0B14),
    outline = Color(0xFF2F2F3D), outlineVariant = Color(0xFF24242F),
)

/** Access the web design tokens for the current theme. */
val LocalHwColors = staticCompositionLocalOf { LightHw }

/** App theme — follows the system light/dark setting, mirroring the web UI's two modes. */
@Composable
fun HardwareOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val hw = if (darkTheme) DarkHw else LightHw
    CompositionLocalProvider(LocalHwColors provides hw) {
        // Default (sans-serif) typography — no global monospace; the log opts in itself.
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
