package com.hardwareone.console.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.EspNowDeviceInfo
import com.hardwareone.console.ble.EspNowEnc
import com.hardwareone.console.ble.EspNowMeshRole
import com.hardwareone.console.ble.EspNowMeshStatus
import com.hardwareone.console.ble.EspNowMode
import com.hardwareone.console.ble.EspNowPaired
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * ESP-NOW page (Phase 1: read-only). Shows the device's ESP-NOW config + encryption + mesh role,
 * its own identity, the paired-device list, and live mesh peers / discovered devices. All driven
 * by the `... json` reader commands; control (enable, pair, set role, …) comes in a later phase.
 * Every block is presence-gated — absent/error replies just don't render their card.
 */
@Composable
fun EspNowScreen(
    nav: HeaderNav,
    mode: EspNowMode?,
    enc: EspNowEnc?,
    meshRole: EspNowMeshRole?,
    deviceInfo: EspNowDeviceInfo?,
    paired: EspNowPaired?,
    mesh: EspNowMeshStatus?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpenDevice: (mac: String, name: String) -> Unit,
    onOpenConfig: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = loading, onRefresh = onRefresh)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Status: mode + encryption + mesh role.
                    SectionCard("ESP-NOW") {
                        mode?.let { m ->
                            if (m.error != null) {
                                InfoRow("Status", m.error)
                            } else {
                                // "Enabled" = live running state (espnowencstatus.running), NOT
                                // espnowmode.enabled (the persistent boot setting), which reads false
                                // when ESP-NOW was started at runtime. Matches the Configure page.
                                InfoRow("Enabled", yn(enc?.running == true))
                                if (m.mode.isNotEmpty()) InfoRow("Mode", m.mode)
                            }
                        }
                        enc?.let { e ->
                            if (e.error == null) {
                                InfoRow("Encrypted", yn(e.encrypted))
                                if (e.encrypted) {
                                    InfoRow("Passphrase set", yn(e.passphraseSet))
                                    if (e.keyFingerprint.isNotEmpty()) InfoRow("Key", e.keyFingerprint)
                                }
                            }
                        }
                        meshRole?.let { r ->
                            if (r.error == null && r.role.isNotEmpty()) {
                                InfoRow("Mesh role", r.role)
                                if (r.masterMac.isNotEmpty()) InfoRow("Master", r.masterMac)
                                if (r.backupEnabled && r.backupMac.isNotEmpty()) InfoRow("Backup", r.backupMac)
                            }
                        }
                    }

                    // This device's identity/metadata — tap to configure.
                    deviceInfo?.let { d ->
                        if (d.error == null && (d.name.isNotEmpty() || d.mac.isNotEmpty())) {
                            Box(modifier = Modifier.clickable { onOpenConfig() }) {
                                SectionCard("This device") {
                                    if (d.name.isNotEmpty()) InfoRow("Name", d.name)
                                    if (d.friendlyName.isNotEmpty()) InfoRow("Friendly name", d.friendlyName)
                                    if (d.room.isNotEmpty()) InfoRow("Room", d.room)
                                    if (d.zone.isNotEmpty()) InfoRow("Zone", d.zone)
                                    if (d.tags.isNotEmpty()) InfoRow("Tags", d.tags)
                                    if (d.stationary) InfoRow("Stationary", "yes")
                                    if (d.mac.isNotEmpty()) InfoRow("MAC", d.mac)
                                    InfoRow("", "Configure ›")
                                }
                            }
                        }
                    }

                    // Paired devices — tap a row to open its message feed.
                    paired?.let { p ->
                        SectionCard("Paired devices (${p.devices.size})") {
                            when {
                                p.error != null -> InfoRow("Status", p.error)
                                p.devices.isEmpty() ->
                                    Text("None paired.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                                else -> p.devices.forEach { d ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenDevice(d.mac, d.name) }
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                d.name.ifEmpty { "(unnamed)" },
                                                color = hw.onGradient,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(
                                                d.mac + if (d.encrypted) "  🔒" else "",
                                                color = hw.muted,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        Text("›", color = hw.muted, style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                    }

                    // Live mesh peers + discovered (only render when there's something to show).
                    mesh?.let { ms ->
                        if (ms.error == null) {
                            if (ms.peers.isNotEmpty()) {
                                SectionCard("Mesh peers (${ms.peers.size})") {
                                    ms.peers.forEach { peer ->
                                        InfoRow(
                                            peer.name.ifEmpty { peer.mac },
                                            (if (peer.alive) "online" else "offline") + " · ${peer.secondsSinceHeartbeat}s",
                                        )
                                    }
                                }
                            }
                            if (ms.unpaired.isNotEmpty()) {
                                SectionCard("Discovered (${ms.unpaired.size})") {
                                    ms.unpaired.forEach { u ->
                                        InfoRow(u.name.ifEmpty { u.mac }, "${u.rssi} dBm · ${u.secondsSinceLastSeen}s")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
