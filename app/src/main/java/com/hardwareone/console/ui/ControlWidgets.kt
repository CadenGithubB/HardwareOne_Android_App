package com.hardwareone.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.ControlEntry
import com.hardwareone.console.ui.theme.LocalHwColors
import kotlin.math.roundToInt

// Shared renderers for a firmware `controls json <module>` descriptor. One control per row,
// two-way-bound to its live value; a change emits the `<key> <token>` the firmware expects
// (case-insensitive on the device). Used by both the per-sensor Settings panel (SensorsScreen)
// and the general device-settings page (DeviceSettingsScreen), so the two never drift.

/** One control entry → the right editor for its type (read-only row / toggle / select / slider /
 *  text). [onSet] receives the pre-formatted token to append after the entry's key. */
@Composable
internal fun ControlEntryRow(entry: ControlEntry, onSet: (token: String) -> Unit) {
    when {
        entry.readOnly -> ReadingRow(entry.label, entry.displayValue, 0.dp)
        entry.type == ControlEntry.Type.BOOL ->
            ControlToggle(entry.label, entry.boolValue ?: false) { on -> onSet(if (on) "on" else "off") }
        // Options outrank min/max and type: an enum-valued int (microphoneBitDepth is 16 or 32,
        // but reports min 16 / max 32) must be a select, not a number input offering the 15
        // values in between that the device rejects.
        entry.options.isNotEmpty() ->
            ControlSelect(entry.label, entry.options, entry.displayValue, onSet)
        (entry.type == ControlEntry.Type.INT || entry.type == ControlEntry.Type.FLOAT) &&
            entry.min != null && entry.max != null -> ControlSlider(entry, onSet)
        entry.type == ControlEntry.Type.STRING -> ControlText(entry.label, entry.displayValue, onSet)
        else -> ReadingRow(entry.label, entry.displayValue, 0.dp)
    }
}

@Composable
private fun ControlSlider(entry: ControlEntry, onSet: (String) -> Unit) {
    val hw = LocalHwColors.current
    val min = entry.min!!.toFloat()
    val max = entry.max!!.toFloat()
    val isInt = entry.type == ControlEntry.Type.INT
    var pos by remember(entry.numberValue, entry.key) {
        mutableStateOf((entry.numberValue?.toFloat() ?: min).coerceIn(min, max))
    }
    val shown = if (isInt) pos.roundToInt().toString() else trimFloat(pos)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(entry.label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
            Text(shown, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            valueRange = min..max,
            onValueChangeFinished = { onSet(if (isInt) pos.roundToInt().toString() else trimFloat(pos)) },
            colors = SliderDefaults.colors(
                thumbColor = hw.accent,
                activeTrackColor = hw.accent,
                inactiveTrackColor = hw.cardBorder,
            ),
        )
    }
}

@Composable
private fun ControlToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val hw = LocalHwColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = hw.onGradient,
                checkedTrackColor = hw.accent,
                uncheckedThumbColor = hw.muted,
                uncheckedTrackColor = hw.cardBg,
            ),
        )
    }
}

@Composable
private fun ControlSelect(
    label: String,
    options: List<com.hardwareone.console.ble.ControlOption>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val hw = LocalHwColors.current
    var open by remember { mutableStateOf(false) }
    val matched = options.firstOrNull { it.value == current }
    // A stored value outside the published set (older config, or firmware/app version skew) is
    // carried as an extra entry rather than dropped. Snapping the display to the first option
    // would misreport the device, and would write a value the user never chose the moment they
    // touched anything else.
    val shown = if (matched != null || current.isBlank()) options else {
        listOf(com.hardwareone.console.ble.ControlOption(current, "$current (current)")) + options
    }
    val currentLabel = matched?.label ?: current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                text = "$currentLabel ▾",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                shown.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.label) },
                        // Send the VALUE, never the label — the device's set command takes the
                        // left-hand side of the `value|label` pair.
                        onClick = { open = false; onSelect(opt.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlText(label: String, current: String, onSet: (String) -> Unit) {
    val hw = LocalHwColors.current
    var text by remember(current) { mutableStateOf(current) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSet(text) }) { Text("Set") }
    }
}

/** A label→value row (read-only). Shared by control descriptors and the sensor reading tree. */
@Composable
internal fun ReadingRow(label: String, value: String, indent: Dp) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

internal fun trimFloat(f: Float): String = "%.3f".format(f).trimEnd('0').trimEnd('.')
