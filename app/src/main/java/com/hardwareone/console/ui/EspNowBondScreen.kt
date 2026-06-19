package com.hardwareone.console.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.EspNowBond
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * ESP-NOW Bond view: this (BLE-connected) device and its bonded peer, from `bondstatus json` (the
 * gateway tracks the peer live via the bond heartbeat). Read-only status + the bond controls
 * (connect/disconnect, role, resync). Reached from the "Bond" card on the ESP-NOW page.
 */
@Composable
fun EspNowBondScreen(
    bond: EspNowBond?,
    onSwapRoles: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onResync: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    var confirmSwap by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = hw.onGradient)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Bond", color = hw.onGradient, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        bond == null -> Text("Loading…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                        bond.error != null -> Text("Error: ${bond.error}", color = hw.danger, style = MaterialTheme.typography.bodyMedium)
                        !bond.enabled -> {
                            SectionCard("Bond") {
                                InfoRow("Status", "disabled")
                                InfoRow("This device role", bond.role.ifEmpty { "—" })
                            }
                            Text(
                                "Bond mode is off. Enable and pair it on the device (or the web) — then this " +
                                    "page shows both devices and lets you connect / swap role / resync.",
                                color = hw.muted, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        else -> {
                            SectionCard("This device") {
                                InfoRow("Role", bond.role)
                            }
                            SectionCard("Bonded peer") {
                                InfoRow("Name", bond.peerName.ifEmpty { "—" })
                                if (bond.peer.isNotEmpty()) InfoRow("MAC", bond.peer)
                                if (bond.peerRole.isNotEmpty()) InfoRow("Role", bond.peerRole)
                                InfoRow("Online", yn(bond.online))
                                InfoRow("Sync", bond.syncState)
                            }
                            SectionCard("Link") {
                                InfoRow("Heartbeats", "↑ ${bond.heartbeatsSent}   ↓ ${bond.heartbeatsReceived}")
                                if (bond.online) InfoRow("Last beat", "${bond.lastHeartbeatAgoSec}s ago")
                                // Sync is one-way (worker → master): the master pulls cap+manifest+settings
                                // FROM the worker; the worker only sends. So show each role its own flags —
                                // a master never "sends" settings, a worker never "receives" them.
                                InfoRow("Capabilities", yn(bond.syncCap))
                                if (bond.role == "master") {
                                    InfoRow("Manifest", yn(bond.syncManifest))
                                    InfoRow("Settings received", yn(bond.syncSettingsRx))
                                } else {
                                    InfoRow("Settings sent", yn(bond.syncSettingsTx))
                                }
                            }
                            Text(
                                "Bond sync flows one way: the worker sends its settings to the master.",
                                color = hw.muted, style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton(onClick = onConnect, text = "Connect")
                                PrimaryButton(onClick = onDisconnect, text = "Disconnect")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton(onClick = onResync, text = "Re-sync")
                                PrimaryButton(onClick = { confirmSwap = true }, text = "Swap roles")
                            }
                            Text(
                                "Re-sync re-exchanges capabilities/manifest/settings — use if the peer looks stale.",
                                color = hw.muted, style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmSwap) {
        AlertDialog(
            onDismissRequest = { confirmSwap = false },
            title = { Text("Swap roles?") },
            text = { Text("Swap master/worker on BOTH devices? The peer is switched first, then this device, and the bond re-syncs.") },
            confirmButton = { TextButton(onClick = { confirmSwap = false; onSwapRoles() }) { Text("Swap", color = hw.accent) } },
            dismissButton = { TextButton(onClick = { confirmSwap = false }) { Text("Cancel") } },
        )
    }
}
