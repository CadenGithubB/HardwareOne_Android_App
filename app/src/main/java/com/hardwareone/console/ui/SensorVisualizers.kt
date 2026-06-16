package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * Live gamepad redraw (Seesaw gamepad), mirroring how the device's own OLED/G2 surfaces draw it.
 * Data is `{x, y, buttons}` from `sensors json` — joystick 0–1023 (centre ~512); `buttons` is a
 * raw **active-low** bitmask (bit 0 ⇒ pressed): SELECT=0, B=1, Y=2, A=5, X=6, START=16.
 * Physical face layout: X top, Y left, A right, B bottom.
 */
@Composable
fun GamepadView(x: Double?, y: Double?, buttons: Long) {
    val nx = (((x ?: 512.0) - 512.0) / 512.0).coerceIn(-1.0, 1.0).toFloat()
    // Y is inverted relative to screen space: stick up should move the dot up (negative bias),
    // so flip the sign of the raw 0–1023 reading.
    val ny = (((512.0 - (y ?: 512.0)) / 512.0)).coerceIn(-1.0, 1.0).toFloat()
    fun pressed(bit: Int) = (buttons shr bit) and 1L == 0L

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JoystickWell(nx, ny)
            ButtonDiamond(
                xTop = pressed(6),
                yLeft = pressed(2),
                aRight = pressed(5),
                bBottom = pressed(1),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniButton("SELECT", pressed(0))
            MiniButton("START", pressed(16))
        }
    }
}

@Composable
private fun JoystickWell(nx: Float, ny: Float) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(hw.terminalBg)
            .border(1.5.dp, hw.cardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(4.dp).clip(CircleShape).background(hw.cardBorder)) // centre reference
        Box(
            modifier = Modifier
                .align(BiasAlignment(nx, ny))
                .size(26.dp)
                .clip(CircleShape)
                .background(hw.accent),
        )
    }
}

@Composable
private fun ButtonDiamond(xTop: Boolean, yLeft: Boolean, aRight: Boolean, bBottom: Boolean) {
    Box(modifier = Modifier.size(120.dp)) {
        FaceButton("X", xTop, BiasAlignment(0f, -1f))
        FaceButton("Y", yLeft, BiasAlignment(-1f, 0f))
        FaceButton("A", aRight, BiasAlignment(1f, 0f))
        FaceButton("B", bBottom, BiasAlignment(0f, 1f))
    }
}

@Composable
private fun BoxScope.FaceButton(label: String, pressed: Boolean, alignment: Alignment) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .align(alignment)
            .size(36.dp)
            .clip(CircleShape)
            .background(if (pressed) hw.accent else hw.cardBg)
            .border(1.5.dp, if (pressed) hw.accent else hw.cardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (pressed) hw.onGradient else hw.muted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniButton(label: String, pressed: Boolean) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) hw.accent else hw.cardBg)
            .border(1.dp, if (pressed) hw.accent else hw.cardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = if (pressed) hw.onGradient else hw.muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
