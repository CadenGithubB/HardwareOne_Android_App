package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import androidx.compose.ui.graphics.Color
import com.hardwareone.console.ble.BatteryInfo
import com.hardwareone.console.ble.DeviceStatus
import com.hardwareone.console.ble.I2cDevice
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Device status / health page. Reads the firmware's `status json` snapshot (captured off the
 * console) and renders it as cards. Polling is driven by the caller via [onRefresh].
 */
@Composable
fun StatusScreen(
    status: DeviceStatus?,
    error: String?,
    i2cDevices: List<com.hardwareone.console.ble.I2cDevice>?,
    i2cLoading: Boolean,
    onLoadI2cDevices: () -> Unit,
    battery: BatteryInfo?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                // Top bar: back + title + refresh
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
                        text = "Device status",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    // Spinner only during the first load (no panels yet); once data is in, the
                    // refresh icon stays put so the silent auto-refresh doesn't flash it.
                    IconButton(onClick = onRefresh, enabled = status != null) {
                        if (status == null) {
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

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        // Loaded — render the cards (silent auto-refresh updates them in place).
                        status != null -> Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusBody(status, error, i2cDevices, i2cLoading, onLoadI2cDevices, battery)
                            Spacer(Modifier.height(8.dp))
                        }
                        // Couldn't load anything yet — surface the error.
                        error != null -> Box(
                            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) { Banner(error) }
                        // First load with nothing yet — show the loading animation.
                        else -> Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(color = hw.onGradient, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Loading device status…",
                                color = hw.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBody(
    s: DeviceStatus,
    staleError: String?,
    i2cDevices: List<com.hardwareone.console.ble.I2cDevice>?,
    i2cLoading: Boolean,
    onLoadI2cDevices: () -> Unit,
    battery: BatteryInfo?,
) {
    // A non-fatal error while we still have a previous snapshot (e.g. a transient OOM).
    if (staleError != null) Banner(staleError)

    SectionCard("System") {
        InfoRow("Firmware", s.firmware.ifEmpty { "—" })
        InfoRow("Board", s.board.ifEmpty { "—" })
        InfoRow("Uptime", s.uptime.ifEmpty { "—" })
        InfoRow("Device time", s.systemTime.ifEmpty { "— (not synced)" })
    }

    // Battery card — only when real battery hardware is present.
    if (battery?.available == true) BatteryCard(battery)

    SectionCard("Health") {
        s.mem?.let {
            InfoRow("Heap free", "${it.heapFreeKb} / ${it.heapTotalKb} KB")
            if (it.psramTotalKb > 0) InfoRow("PSRAM free", "${it.psramFreeKb} / ${it.psramTotalKb} KB")
        }
        s.storage?.let {
            InfoRow("Flash used", "${it.usedKb} / ${it.totalKb} KB (${it.freeKb} free)")
            it.sd?.let { sd -> InfoRow("SD used", "${sd.usedMb} / ${sd.totalMb} MB (${sd.freeMb} free)") }
        }
        InfoRow("Last reset", s.resetReason.ifEmpty { "—" })
        InfoRow("Crash count", s.crashCount.toString())
    }

    s.net?.let { net ->
        SectionCard("Network") {
            if (net.connected) {
                InfoRow("SSID", net.ssid.ifEmpty { "—" })
                InfoRow("IP", net.ip)
                InfoRow("Signal", "${net.rssi} dBm")
                if (net.channel > 0) InfoRow("Channel", net.channel.toString())
            } else {
                InfoRow("Wi-Fi", "disconnected")
            }
            InfoRow("MAC", net.mac.ifEmpty { "—" })
        }
    }

    val c = s.connectivity ?: return
    c.bluetooth?.let {
        SectionCard("Bluetooth") {
            InfoRow("State", it.state.ifEmpty { "—" })
            InfoRow("Mode", it.mode.ifEmpty { "—" })
            InfoRow("Running", yn(it.running))
            if (it.client) InfoRow("G2 glasses", yn(it.g2Connected))
        }
    }
    c.espnow?.let {
        SectionCard("ESP-NOW") {
            InfoRow("Enabled", yn(it.enabled))
            InfoRow("Running", yn(it.running))
            if (it.deviceName.isNotEmpty()) InfoRow("Name", it.deviceName)
            InfoRow("Mesh", yn(it.mesh))
            InfoRow("Encrypted", yn(it.encrypted))
        }
    }
    c.mqtt?.let {
        SectionCard("MQTT") {
            InfoRow("Enabled", yn(it.enabled))
            InfoRow("Connected", yn(it.connected))
            if (it.host.isNotEmpty()) InfoRow("Host", it.host)
        }
    }
    c.webserver?.let { ws ->
        SectionCard("Web server") {
            InfoRow("Running", yn(ws.running))
            // The webserver JSON has no host — build the URL from the device's IP (net.ip).
            // Omit the port when it's the scheme default (80/443) for a cleaner address.
            val scheme = if (ws.https) "https" else "http"
            val host = s.net?.ip?.takeIf { it.isNotEmpty() }
            val portPart = if ((ws.https && ws.port == 443) || (!ws.https && ws.port == 80)) "" else ":${ws.port}"
            val url = if (host != null) "$scheme://$host$portPart" else "$scheme://(no IP)$portPart"
            InfoRow("URL", url)
            InfoRow("Sessions", "${ws.sessions} / ${ws.maxSessions}")
        }
    }
    c.i2c?.let { I2cCard(it, i2cDevices, i2cLoading, onLoadI2cDevices) }
    c.llm?.let {
        SectionCard("On-device LLM") {
            InfoRow("State", it.state.ifEmpty { "—" })
            if (it.model.isNotEmpty()) InfoRow("Model", it.model)
            if (it.psramKb > 0) InfoRow("PSRAM", "${it.psramKb} KB")
            if (it.tokPerSec > 0) InfoRow("Speed", "${it.tokPerSec} tok/s")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = hw.onGradient,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun BatteryCard(b: BatteryInfo) {
    val hw = LocalHwColors.current
    SectionCard("Battery") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatteryGlyph(b.percentage, b.charging)
            Text(
                text = if (b.percentage in 0..100) "${b.percentage}%" else "—",
                color = hw.onGradient,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (b.charging) "Charging" else b.status.ifEmpty { "—" },
                color = if (b.charging) hw.success else hw.muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        InfoRow("Voltage", "%.2f V".format(b.voltage))
        InfoRow("Source", batterySource(b))
        b.ratePctPerHr?.takeIf { it != 0.0 }?.let { InfoRow("Rate", "%+.1f %%/hr".format(it)) }
        b.etaMinutes?.takeIf { it > 0 }?.let {
            InfoRow(if (b.charging) "Until full" else "Time left", formatEta(it))
        }
        if (b.usbPresent) InfoRow("USB", "connected")
    }
}

private fun batterySource(b: BatteryInfo): String = when {
    b.estimated -> "ADC (estimated)"
    b.backend.equals("fuelgauge", ignoreCase = true) -> "Fuel gauge"
    b.backend.isNotEmpty() -> b.backend
    else -> "—"
}

private fun formatEta(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * I²C card with a lazily-loaded device list. The count comes from the status poll; tapping
 * the "Devices" row sends `devices json` once (via [onLoad]) and reveals the full list.
 */
@Composable
private fun I2cCard(
    i2c: DeviceStatus.Connectivity.I2c,
    devices: List<I2cDevice>?,
    loading: Boolean,
    onLoad: () -> Unit,
) {
    val hw = LocalHwColors.current
    var expanded by remember { mutableStateOf(false) }
    // "found" comes from the honest detected list (devices json) when we have it — the
    // status-json scalar can over-count from a stale device registry. "active" can never
    // exceed what's actually detected.
    val foundCount = devices?.size ?: i2c.devices
    val activeCount = if (devices != null) i2c.activeDevices.coerceAtMost(foundCount) else i2c.activeDevices
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "I²C",
            color = hw.onGradient,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        InfoRow("Enabled", yn(i2c.enabled))
        InfoRow("Pins", "SDA ${i2c.sdaPin} · SCL ${i2c.sclPin}")

        // Tappable "Devices" row — expands to the full list (loaded on first tap).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && devices == null && !loading) onLoad()
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Devices",
                color = hw.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "$activeCount active / $foundCount found",
                    color = hw.onGradient,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(if (expanded) "▴" else "▾", color = hw.muted)
            }
        }

        if (expanded) {
            when {
                loading && devices == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = hw.onGradient,
                        strokeWidth = 2.dp,
                    )
                    Text("Reading device list…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                }
                devices.isNullOrEmpty() -> Text(
                    text = "No I²C devices detected.",
                    color = hw.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> devices.forEach { d ->
                    InfoRow(d.name, "${d.addrHex} · bus ${d.bus}")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = hw.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = hw.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun yn(b: Boolean): String = if (b) "yes" else "no"
