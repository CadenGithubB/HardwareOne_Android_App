package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.EspNowDeviceInfo
import com.hardwareone.console.ble.EspNowMeshRole
import com.hardwareone.console.ble.EspNowMode
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * Config for THIS (gateway) device's ESP-NOW: enable/disable, mode, mesh role, and identity
 * metadata. Reached by tapping the "This device" card on the ESP-NOW page. Setters are text
 * commands; the page re-reads after each.
 */
@Composable
fun EspNowConfigScreen(
    info: EspNowDeviceInfo?,
    mode: EspNowMode?,
    meshRole: EspNowMeshRole?,
    running: Boolean,
    onSetName: (String) -> Unit,
    onSetFriendlyName: (String) -> Unit,
    onSetRoom: (String) -> Unit,
    onSetZone: (String) -> Unit,
    onSetTags: (String) -> Unit,
    onSetStationary: (Boolean) -> Unit,
    onSetMode: (String) -> Unit,
    onSetMeshRole: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = hw.onGradient)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("This device", color = hw.onGradient, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionCard("ESP-NOW") {
                        // Reflect the RUNNING state (espnowencstatus.running), since the toggle does
                        // openespnow/closeespnow — not the persistent espnowmode.enabled boot setting.
                        SwitchRow("Enabled", running) { onSetEnabled(it) }
                        Spacer(Modifier.height(4.dp))
                        Text("Mode", color = hw.muted, style = MaterialTheme.typography.labelMedium)
                        ChipRow(listOf("direct", "mesh"), mode?.mode ?: "") { onSetMode(it) }
                        Spacer(Modifier.height(4.dp))
                        Text("Mesh role", color = hw.muted, style = MaterialTheme.typography.labelMedium)
                        ChipRow(listOf("worker", "master", "backup"), meshRole?.role ?: info?.meshRole ?: "") { onSetMeshRole(it) }
                    }

                    SectionCard("Identity") {
                        EditRow("Name", info?.name ?: "", onSave = { if (it.isNotBlank()) onSetName(it) })
                        EditRow("Friendly name", info?.friendlyName ?: "", onSave = onSetFriendlyName)
                        EditRow("Room", info?.room ?: "", onSave = onSetRoom)
                        EditRow("Zone", info?.zone ?: "", onSave = onSetZone)
                        EditRow("Tags", info?.tags ?: "", onSave = onSetTags)
                        SwitchRow("Stationary", info?.stationary == true) { onSetStationary(it) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val hw = LocalHwColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = hw.onGradient, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = hw.onGradient, checkedTrackColor = hw.accent,
                uncheckedThumbColor = hw.muted, uncheckedTrackColor = hw.cardBorder,
            ),
        )
    }
}

@Composable
internal fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val hw = LocalHwColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val sel = opt.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) hw.onGradient else hw.cardBg)
                    .border(1.dp, hw.cardBorder, RoundedCornerShape(8.dp))
                    .clickable { if (!sel) onSelect(opt) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(opt, color = if (sel) Color.Black else hw.onGradient, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun EditRow(label: String, initial: String, onSave: (String) -> Unit) {
    val hw = LocalHwColors.current
    var text by remember(initial) { mutableStateOf(initial) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = hw.onGradient, unfocusedTextColor = hw.onGradient,
                focusedContainerColor = hw.cardBg, unfocusedContainerColor = hw.cardBg,
                cursorColor = hw.accent, focusedBorderColor = hw.accent, unfocusedBorderColor = hw.cardBorder,
                focusedLabelColor = hw.muted, unfocusedLabelColor = hw.muted,
            ),
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(onClick = { if (text != initial) onSave(text.trim()) }, enabled = text != initial, text = "Save")
    }
}
