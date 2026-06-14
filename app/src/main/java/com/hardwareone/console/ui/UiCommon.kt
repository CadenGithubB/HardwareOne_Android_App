package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ui.theme.HwColors
import com.hardwareone.console.ui.theme.LocalHwColors

/** The two top-level pages the header toggle flips between. */
enum class AppPage { CONSOLE, DEVICES }

/** Compact segmented toggle for flipping between the Console and Devices pages. */
@Composable
fun PageToggle(active: AppPage, onSelect: (AppPage) -> Unit) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
    ) {
        PageToggleSegment("Devices", active == AppPage.DEVICES) { onSelect(AppPage.DEVICES) }
        PageToggleSegment("Console", active == AppPage.CONSOLE) { onSelect(AppPage.CONSOLE) }
    }
}

@Composable
private fun PageToggleSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) hw.onGradient else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (selected) {
            // Selected side has a white pill background → plain black text reads cleanly.
            Text(
                text = label,
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            // Unselected sits on the gradient → white text with a black outline for readability.
            OutlinedText(
                text = label,
                color = hw.onGradient,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Text with a thin black outline (a stroked copy behind the filled text) — improves readability
 * of the header controls on the light gradient. Near-invisible on the dark theme, by design.
 */
@Composable
internal fun OutlinedText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    outline: Color = Color.Black,
) {
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Box(modifier) {
        Text(
            text = text,
            color = outline,
            fontWeight = fontWeight,
            style = style.copy(drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round)),
        )
        Text(text = text, color = color, fontWeight = fontWeight, style = style)
    }
}

@Composable
internal fun GearButton(onOpenSettings: () -> Unit) {
    IconButton(onClick = onOpenSettings) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = "Settings",
            tint = LocalHwColors.current.onGradient,
        )
    }
}

/** Solid light button with accent text — the primary action, high-contrast on the gradient. */
@Composable
internal fun PrimaryButton(onClick: () -> Unit, enabled: Boolean = true, text: String) {
    val hw = LocalHwColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = hw.onGradient,
            contentColor = hw.accent,
            disabledContainerColor = hw.cardBg,
            disabledContentColor = hw.muted,
        ),
    ) {
        // Plain black text on the light button (not accent/"purple"). Disabled inherits the
        // button's muted content colour.
        Text(text, color = if (enabled) Color.Black else Color.Unspecified)
    }
}

/** Frosted outlined button — secondary actions. */
@Composable
internal fun SecondaryButton(onClick: () -> Unit, text: String) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, hw.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text(text) }
}

@Composable
internal fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = LocalHwColors.current.onGradient,
        strokeWidth = 2.dp,
    )
}

internal fun statusLabel(
    state: ConnectionState,
    authenticated: Boolean,
    user: String? = null,
): String = when (state) {
    is ConnectionState.Disconnected -> "● disconnected"
    is ConnectionState.Scanning -> "◌ scanning"
    is ConnectionState.Connecting -> "◌ connecting"
    is ConnectionState.DiscoveringServices -> "◌ discovering"
    is ConnectionState.NegotiatingMtu -> "◌ mtu"
    is ConnectionState.EnablingNotifications -> "◌ subscribing"
    is ConnectionState.Securing -> "◌ securing"
    is ConnectionState.Ready ->
        if (authenticated) "● online${if (!user.isNullOrBlank()) " as $user" else ""}"
        else "● connected — login required"
    is ConnectionState.Failed -> "● ${state.reason}"
}

/** A small battery glyph filled to [percent], colour-coded, with a charging bolt overlay. */
@Composable
internal fun BatteryGlyph(percent: Int, charging: Boolean) {
    val hw = LocalHwColors.current
    val frac = percent.coerceIn(0, 100) / 100f
    val fill = when {
        charging -> hw.success
        percent < 0 -> hw.muted
        percent < 15 -> hw.danger
        percent < 40 -> Color(0xFFE0A800) // amber
        else -> hw.success
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.5.dp, hw.onGradient, RoundedCornerShape(4.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (percent >= 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(frac)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fill),
                )
            }
            if (charging) {
                // Yellow bolt with an even thin black outline — a single drawable whose path has
                // a yellow fill + black stroke (rendered untinted so both colours show).
                Image(
                    painter = painterResource(R.drawable.ic_bolt_charging),
                    contentDescription = "charging",
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 1.dp)
                .size(width = 2.5.dp, height = 7.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(hw.onGradient),
        )
    }
}

internal fun statusColor(state: ConnectionState, hw: HwColors): Color = when (state) {
    is ConnectionState.Ready -> hw.success
    is ConnectionState.Failed -> hw.danger
    is ConnectionState.Disconnected -> hw.muted
    else -> hw.onGradient
}
