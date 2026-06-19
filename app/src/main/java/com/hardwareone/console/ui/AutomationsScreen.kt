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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.Automation
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * Automations page (Tier 1: read + control). Lists `/system/automations.json` via
 * `automationlist json`, with per-item Run / Trigger / Enable-Disable / Delete and a global
 * system on/off. Creating/editing is a future tier.
 */
@Composable
fun AutomationsScreen(vm: ConsoleViewModel, nav: HeaderNav) {
    val hw = LocalHwColors.current
    val list by vm.automations.collectAsState()
    val busy by vm.automationsBusy.collectAsState()
    val systemOn by vm.automationsSystemOn.collectAsState()
    val actionStatus by vm.automationStatus.collectAsState()
    var confirmDelete by remember { mutableStateOf<Automation?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = busy, onRefresh = { vm.loadAutomations() })
                Spacer(Modifier.height(8.dp))
                SectionCard("Automation system") {
                    SwitchRow("Enabled", systemOn == true) { vm.setAutomationsSystem(it) }
                }
                Spacer(Modifier.height(10.dp))

                actionStatus?.let {
                    Text(
                        it,
                        color = if (it.startsWith("Error:")) hw.danger else hw.muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                val autos = list?.automations.orEmpty()
                list?.error?.let { Text("Error: $it", color = hw.danger, style = MaterialTheme.typography.bodyMedium) }
                if (!busy && list?.error == null && autos.isEmpty()) {
                    Text("No automations.", color = hw.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(autos) { a ->
                        AutomationRow(
                            a = a,
                            onRun = { vm.runAutomation(a.id) },
                            onTrigger = { vm.triggerAutomation(a.id) },
                            onToggle = { vm.setAutomationEnabled(a.id, !a.enabled) },
                            onDelete = { confirmDelete = a },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete automation?") },
            text = { Text("Delete \"${a.name}\" (id ${a.id})? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAutomation(a.id); confirmDelete = null }) {
                    Text("Delete", color = hw.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AutomationRow(
    a: Automation,
    onRun: () -> Unit,
    onTrigger: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val hw = LocalHwColors.current
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(a.name, color = hw.onGradient, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(a.triggerSummary, color = hw.muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (a.enabled) "on" else "off",
                color = if (a.enabled) hw.accent else hw.muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Text("⋮", color = hw.onGradient, style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Run now") }, onClick = { menu = false; onRun() })
                    DropdownMenuItem(text = { Text("Arm timer") }, onClick = { menu = false; onTrigger() })
                    DropdownMenuItem(text = { Text(if (a.enabled) "Disable" else "Enable") }, onClick = { menu = false; onToggle() })
                    DropdownMenuItem(text = { Text("Delete", color = hw.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (a.commands.isNotEmpty()) {
            Text(
                a.commands.joinToString("; "),
                color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            )
        }
        if (a.condition.isNotEmpty()) {
            Text("if ${a.condition}", color = hw.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
