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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.BatteryInfo
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.DeviceInfo
import com.hardwareone.console.ble.DiscoveredDevice
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Device pairing & selection — split out of the console. Scan, pick a device, connect/disconnect,
 * and see the live connection state. Flips to the Console via the header [PageToggle].
 */
@Composable
fun DevicesScreen(
    vm: ConsoleViewModel,
    battery: BatteryInfo?,
    onScanClicked: () -> Unit,
    onLogin: () -> Unit,
    onSelectPage: (AppPage) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hw = LocalHwColors.current
    val state by vm.connectionState.collectAsState()
    val devices by vm.scanResults.collectAsState()
    val authenticated by vm.authenticated.collectAsState()
    val currentUser by vm.currentUser.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()

    val ready = state is ConnectionState.Ready
    val scanning = state is ConnectionState.Scanning
    val connecting = state is ConnectionState.Connecting ||
        state is ConnectionState.DiscoveringServices ||
        state is ConnectionState.NegotiatingMtu ||
        state is ConnectionState.EnablingNotifications ||
        state is ConnectionState.Securing

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header: toggle + settings.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageToggle(AppPage.DEVICES, onSelectPage)
                    Spacer(Modifier.weight(1f))
                    GearButton(onOpenSettings)
                }

                // Only show the connection card once there's actually a device in play —
                // a fresh launch (idle/disconnected) shows just the action buttons.
                if (deviceInfo != null) {
                    // Connected but not logged in → tap the card to log in (biometric or dialog,
                    // same as the console LOGIN button).
                    ConnectionCard(
                        state, authenticated, currentUser, deviceInfo, battery,
                        onLogin = if (ready && !authenticated) onLogin else null,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryConnectionButton(state, onScanClicked, vm::stopScan, vm::disconnect)
                    if (!ready && !scanning && !connecting) {
                        SecondaryButton(vm::reconnect, "RECONNECT")
                    }
                }

                when {
                    !ready && devices.isNotEmpty() -> DeviceList(devices, vm::connect)
                    scanning -> Text("Scanning…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    !ready && !connecting -> Text(
                        text = "Tap SCAN to find HardwareOne devices nearby.",
                        color = hw.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    authenticated: Boolean,
    user: String?,
    deviceInfo: DeviceInfo?,
    battery: BatteryInfo?,
    onLogin: (() -> Unit)? = null,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .then(if (onLogin != null) Modifier.clickable { onLogin() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Connection", color = hw.muted, style = MaterialTheme.typography.labelMedium)
        Text(
            text = statusLabel(state, authenticated, user),
            color = statusColor(state, hw),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (onLogin != null) {
            Text(
                text = "Tap to log in",
                color = hw.accent,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (battery != null && battery.available) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Battery", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatteryGlyph(battery.percentage, battery.charging)
                    Text(
                        text = if (battery.percentage in 0..100) "${battery.percentage}%" else "—",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (deviceInfo != null) {
            InfoRow("Name", deviceInfo.name)
            InfoRow("Address", deviceInfo.address)
            if (deviceInfo.mtu > 0) InfoRow("MTU", deviceInfo.mtu.toString())
            InfoRow("Secure", if (deviceInfo.secure) "yes" else "no")
            deviceInfo.firmware?.let { InfoRow("Firmware", it) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Single state-driven connection button (SCAN / STOP / CANCEL / DISCONNECT). */
@Composable
private fun PrimaryConnectionButton(
    state: ConnectionState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    when (state) {
        is ConnectionState.Scanning -> {
            SecondaryButton(onStopScan, "STOP")
            Spinner()
        }
        is ConnectionState.Connecting,
        is ConnectionState.DiscoveringServices,
        is ConnectionState.NegotiatingMtu,
        is ConnectionState.EnablingNotifications,
        is ConnectionState.Securing -> {
            SecondaryButton(onDisconnect, "CANCEL")
            Spinner()
        }
        is ConnectionState.Ready -> SecondaryButton(onDisconnect, "DISCONNECT")
        else -> PrimaryButton(onScan, text = "SCAN")
    }
}

@Composable
private fun DeviceList(
    devices: List<DiscoveredDevice>,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Devices (${devices.size}) — tap to connect",
            color = hw.muted,
            style = MaterialTheme.typography.labelMedium,
        )
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(devices, key = { it.address }) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(device) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${device.name}  ·  ${device.address}",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${device.rssi} dBm",
                        color = hw.muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
