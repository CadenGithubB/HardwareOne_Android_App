package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.ControlEntry
import com.hardwareone.console.ble.ControlsModule
import com.hardwareone.console.ble.ReadingNode
import com.hardwareone.console.ble.SensorEntry
import com.hardwareone.console.ui.theme.LocalHwColors
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Sensors page: live readings (`sensors json`) + control (`features <id> on/off`). Each sensor
 * is a card with a state chip row, an enable toggle, and a generic recursive renderer for its
 * native `data`. Polling is driven by the caller via [onRefresh].
 */
@Composable
fun SensorsScreen(
    sensors: List<SensorEntry>?,
    loading: Boolean,
    error: String?,
    controlModules: Set<String>,
    controls: Map<String, ControlsModule>,
    controlsLoading: Set<String>,
    onToggle: (id: String, enable: Boolean) -> Unit,
    onLoadControls: (id: String) -> Unit,
    onSetControl: (moduleId: String, key: String, token: String) -> Unit,
    onAction: (command: String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = hw.onGradient,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "Sensors",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = hw.onGradient,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh",
                                tint = hw.onGradient,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        sensors == null && error != null -> Banner(error)
                        sensors == null -> Banner(if (loading) "Reading sensors…" else "No sensor data yet.")
                        sensors.isEmpty() -> Banner("No sensors compiled into this device.")
                        else -> sensors.forEach { entry ->
                            SensorCard(
                                entry = entry,
                                hasControls = entry.id in controlModules,
                                controls = controls[entry.id],
                                controlsLoading = entry.id in controlsLoading,
                                onToggle = onToggle,
                                onLoadControls = onLoadControls,
                                onSetControl = onSetControl,
                                onAction = onAction,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorCard(
    entry: SensorEntry,
    hasControls: Boolean,
    controls: ControlsModule?,
    controlsLoading: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onLoadControls: (String) -> Unit,
    onSetControl: (moduleId: String, key: String, token: String) -> Unit,
    onAction: (command: String) -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: name + enable toggle.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = hw.onGradient,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Chip(entry.kind.name.lowercase(), active = false)
                    Chip(if (entry.connected) "connected" else "absent", active = entry.connected)
                }
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = { onToggle(entry.id, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = hw.onGradient,
                    checkedTrackColor = hw.accent,
                    uncheckedThumbColor = hw.muted,
                    uncheckedTrackColor = hw.cardBg,
                ),
            )
        }

        // Body.
        when {
            entry.kind == SensorEntry.Kind.STREAM ->
                Note("Live view isn't available over Bluetooth — use the web UI or OLED.")
            !entry.connected -> Note("Not detected on the bus.")
            !entry.enabled -> Note("Disabled — toggle on to read.")
            !entry.dataValid -> Note("Reading unavailable.")
            entry.readings.isEmpty() -> Note("No readings yet.")
            else -> ReadingNodes(entry.readings, indent = 0.dp)
        }

        // Live action controls (tune/volume/mute/seek/reads/sync), shown when the sensor is on.
        val actions = if (entry.enabled && entry.connected) actionsFor(entry) else emptyList()
        if (actions.isNotEmpty()) {
            Text("Controls", color = hw.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            actions.filterNot { it is SensorAction.Button }.forEach { a ->
                when (a) {
                    is SensorAction.Slider -> ActionSlider(a, onAction)
                    is SensorAction.Toggle -> ActionToggle(a, onAction)
                    else -> Unit
                }
            }
            val buttons = actions.filterIsInstance<SensorAction.Button>()
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    buttons.forEach { b -> ActionButton(b.label) { onAction(b.command) } }
                }
            }
        }

        // Adjustable settings (controls json), lazily loaded when expanded.
        if (hasControls) {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded && controls == null && !controlsLoading) onLoadControls(entry.id)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = hw.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "▴" else "▾", color = hw.muted)
            }
            if (expanded) {
                when {
                    controlsLoading && controls == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = hw.onGradient, strokeWidth = 2.dp)
                        Text("Loading controls…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    controls == null || controls.entries.isEmpty() ->
                        Note("No adjustable settings for this sensor.")
                    else -> controls.entries.forEach { ce ->
                        ControlEntryRow(ce) { token -> onSetControl(entry.id, ce.key, token) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlEntryRow(entry: ControlEntry, onSet: (token: String) -> Unit) {
    when {
        entry.readOnly -> ReadingRow(entry.label, entry.displayValue, 0.dp)
        entry.type == ControlEntry.Type.BOOL ->
            ControlToggle(entry.label, entry.boolValue ?: false) { on -> onSet(if (on) "on" else "off") }
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
private fun ControlSelect(label: String, options: List<String>, current: String, onSelect: (String) -> Unit) {
    val hw = LocalHwColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                text = "$current ▾",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSelect(opt) })
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

private fun trimFloat(f: Float): String = "%.3f".format(f).trimEnd('0').trimEnd('.')

// --- Live action controls (per-sensor verbs) -------------------------------------

@Composable
private fun ActionSlider(a: SensorAction.Slider, onAction: (String) -> Unit) {
    val hw = LocalHwColors.current
    var pos by remember(a.current, a.commandPrefix) {
        mutableStateOf((a.current ?: a.min).coerceIn(a.min, a.max))
    }
    val shown = if (a.isInt) pos.roundToInt().toString() else trimFloat(pos)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(a.label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
            Text(shown, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            valueRange = a.min..a.max,
            onValueChangeFinished = { onAction("${a.commandPrefix} ${if (a.isInt) pos.roundToInt() else trimFloat(pos)}") },
            colors = SliderDefaults.colors(
                thumbColor = hw.accent,
                activeTrackColor = hw.accent,
                inactiveTrackColor = hw.cardBorder,
            ),
        )
    }
}

@Composable
private fun ActionToggle(a: SensorAction.Toggle, onAction: (String) -> Unit) {
    val hw = LocalHwColors.current
    var checked by remember(a.current) { mutableStateOf(a.current ?: false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = { checked = it; onAction(if (it) a.onCommand else a.offCommand) },
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
private fun ActionButton(label: String, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(1.dp, hw.cardBorder),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun ReadingNodes(nodes: List<ReadingNode>, indent: Dp) {
    val hw = LocalHwColors.current
    nodes.forEach { node ->
        when (node) {
            is ReadingNode.Leaf -> ReadingRow(node.label, node.value, indent)
            is ReadingNode.Group -> {
                Text(
                    text = node.label,
                    color = hw.muted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = indent, top = 2.dp),
                )
                ReadingNodes(node.children, indent + 12.dp)
            }
        }
    }
}

@Composable
private fun ReadingRow(label: String, value: String, indent: Dp) {
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

@Composable
private fun Chip(text: String, active: Boolean) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(hw.cardBg)
            .border(1.dp, if (active) hw.success else hw.cardBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = if (active) hw.success else hw.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = LocalHwColors.current.muted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Banner(text: String) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
    }
}
