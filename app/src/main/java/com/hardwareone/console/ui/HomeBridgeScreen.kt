package com.hardwareone.console.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.bridge.BridgeConfig
import com.hardwareone.console.bridge.BridgeStore
import com.hardwareone.console.bridge.HomeBridge
import com.hardwareone.console.bridge.ListenerState
import com.hardwareone.console.bridge.Pem
import com.hardwareone.console.bridge.RelayReachability
import com.hardwareone.console.bridge.ReplyPath
import com.hardwareone.console.bridge.ReplyPathCheck
import com.hardwareone.console.bridge.RequestOutcome
import com.hardwareone.console.ui.theme.LocalHwColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(14.dp)

/**
 * The Home AI bridge page: start/stop, separate hotspot-listener and home-relay status, the exact
 * device settings to apply, and the relay configuration (host, port, imported CA). Settings are
 * editable only while the bridge is stopped so a running listener never silently changes target.
 */
@Composable
fun HomeBridgeScreen(
    state: HomeBridge.UiState,
    store: BridgeStore,
    bleLoggedIn: Boolean,
    /** Largest command the live Bluetooth link accepts, or 0 when not connected. */
    bleCommandLimit: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onSendDeviceCommand: (String) -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) } // bump to re-read the store after edits
    val caPem = remember(revision) { store.loadOrCreateCa()?.certificatePem }
    val configuredUrl = remember(revision, state.deviceUrl) { store.configuredDeviceUrl }
    // Derived here (keyed on revision) and passed down as plain values: a card that only
    // received the store object would be skipped by recomposition after a save.
    val configProblems = remember(revision) { store.config()?.problems() ?: listOf("Configure the home relay first.") }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = hw.onGradient)
                }
                Spacer(Modifier.size(4.dp))
                Text("Home AI bridge", color = hw.onGradient, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (state.probing || state.activeConnections > 0) Spinner()
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(state, configProblems.isEmpty(), store.relayHost, onStart, onStop, onProbe)
                DeviceSetupCard(state, caPem, configuredUrl, bleLoggedIn, bleCommandLimit, onSendDeviceCommand) {
                    store.configuredDeviceUrl = state.deviceUrl
                    revision++
                }
                RelayConfigCard(state.running, store, configProblems) { revision++ }
                AdvancedCard(state, store) { revision++ }
                Text(
                    "Path: glasses ↔ Bluetooth ↔ HardwareOne ↔ phone hotspot ↔ this bridge ↔ WireGuard ↔ home relay. " +
                        "The bridge forwards only POST /v1/chat/completions and /v1/terminal to the configured relay, " +
                        "over the VPN only, with the relay's own size and time limits. Nothing is retried: a request " +
                        "whose outcome is unknown is reported as such, never resent.",
                    color = hw.muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: HomeBridge.UiState, configured: Boolean, relayHost: String, onStart: () -> Unit, onStop: () -> Unit, onProbe: () -> Unit) {
    val hw = LocalHwColors.current
    SectionCard(if (state.running) "Bridge · RUNNING" else "Bridge · OFF") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.running) SecondaryButton(onStop, "STOP") else PrimaryButton(onStart, enabled = configured, text = "START")
            if (state.running) SecondaryButton(onProbe, "TEST RELAY")
        }
        if (!configured && !state.running) {
            Text("Configure the home relay below (host, port and its CA certificate) before starting.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium) }

        Spacer(Modifier.height(6.dp))
        // Bound is not the same as reachable: the kernel may route replies to hotspot clients into
        // the VPN. The reply-path check decides whether a listening socket counts as healthy.
        val repliesBlocked = state.replyPath is ReplyPath.Captured || state.replyPath is ReplyPath.Blocked
        val listenerColor = when (state.listener) {
            is ListenerState.Listening -> if (repliesBlocked) hw.danger else hw.success
            is ListenerState.Failed -> hw.danger
            is ListenerState.Waiting -> Color(0xFFE0A800)
            ListenerState.Stopped -> hw.muted
        }
        Text("Hotspot listener", color = hw.muted, style = MaterialTheme.typography.labelMedium)
        Text(
            when (val l = state.listener) {
                is ListenerState.Listening -> "● Listening on ${l.address}:${l.port}" + (l.interfaceName?.let { " ($it)" } ?: "") +
                    (if (repliesBlocked) " — but unreachable from the hotspot" else "")
                is ListenerState.Waiting -> "◌ Waiting — ${l.detail}"
                is ListenerState.Failed -> "● ${l.detail}"
                ListenerState.Stopped -> "● Off"
            },
            color = listenerColor, style = MaterialTheme.typography.bodyMedium,
        )
        state.replyPath?.let { path ->
            ReplyPathCheck.advice(path, state.hotspotSubnet, relayHost)?.let { Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium) }
        }
        state.lastDeviceAddress?.let {
            InfoRow("Device", it + (state.lastDeviceSeenMs?.let { ms -> " · ${time(ms)}" } ?: ""))
        }
        if (state.activeConnections > 0) InfoRow("In flight", "${state.activeConnections}")

        Spacer(Modifier.height(6.dp))
        val relayColor = when (state.relay.reachability) {
            RelayReachability.REACHABLE -> hw.success
            RelayReachability.UNKNOWN -> hw.muted
            else -> hw.danger
        }
        Text("Home relay", color = hw.muted, style = MaterialTheme.typography.labelMedium)
        Text(
            "● " + HomeBridge.relayWord(state.relay).replaceFirstChar { it.uppercase() } +
                (state.relay.checkedAtMs?.let { " · ${time(it)}" } ?: ""),
            color = relayColor, style = MaterialTheme.typography.bodyMedium,
        )
        state.relay.detail?.let { Text(it, color = hw.onGradient, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            InfoRow(
                "VPN",
                when (state.vpnAvailable) {
                    true -> "available" + (state.vpnInterface?.let { " ($it)" } ?: "")
                    false -> "none visible to this app"
                    null -> "checking…"
                },
            )
        }

        state.lastRequest?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text("Last request", color = hw.muted, style = MaterialTheme.typography.labelMedium)
            val outcomeColor = when (r.outcome) {
                RequestOutcome.COMPLETED -> hw.success
                RequestOutcome.OUTCOME_UNKNOWN, RequestOutcome.CANCELLED_BY_DEVICE -> Color(0xFFE0A800)
                else -> hw.danger
            }
            Text(
                "${r.route?.name?.lowercase() ?: "—"} · ${r.outcome.name.lowercase().replace('_', ' ')}" +
                    (r.httpStatus?.let { " · HTTP $it" } ?: "") + " · ${time(r.startedAtMs)}",
                color = outcomeColor, style = MaterialTheme.typography.bodyMedium,
            )
            r.detail?.let { Text(it, color = hw.onGradient, style = MaterialTheme.typography.bodySmall) }
            InfoRow("Totals", "${state.completed} completed · ${state.failed} not completed")
        }
    }
}

@Composable
private fun DeviceSetupCard(
    state: HomeBridge.UiState,
    caPem: String?,
    configuredUrl: String?,
    bleLoggedIn: Boolean,
    bleCommandLimit: Int,
    onSend: (String) -> Unit,
    onMarkConfigured: () -> Unit,
) {
    val hw = LocalHwColors.current
    val context = LocalContext.current
    var showPem by remember { mutableStateOf(false) }
    val url = state.deviceUrl ?: configuredUrl
    val urlCommand = url?.let { "llmhttpurl $it" }
    val caCommand = caPem?.let { "llmhttpca " + Pem.escapedForDeviceCommand(it) }
    SectionCard("Device settings (HardwareOne)") {
        Text(
            "Point the device's HTTP agent at this phone instead of the relay. Its token and model stay as they are; " +
                "the bridge passes the token through to the relay unchanged.",
            color = hw.muted, style = MaterialTheme.typography.bodyMedium,
        )
        InfoRow("llmhttpurl", url ?: "(appears when the hotspot listener is up)")
        if (url != null && configuredUrl != null && url != configuredUrl) {
            Text(
                "The hotspot address changed since the device was last configured ($configuredUrl). Update llmhttpurl on the device.",
                color = Color(0xFFE0A800), style = MaterialTheme.typography.bodyMedium,
            )
        }
        InfoRow("llmhttpca", if (caPem != null) "bridge CA · ${Pem.sha256Fingerprint(Pem.decodeCertificates(caPem).first()).take(23)}…" else "unavailable")
        Text(
            "Set llmhttpca once (the bridge's own CA, below). llmhttpurl must be updated whenever the hotspot address changes. " +
                "Keep llmhttpplaintext 0 and llmhttpenabled 1.",
            color = hw.muted, style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton({
                val text = listOfNotNull(urlCommand, caCommand, "llmhttpplaintext 0", "llmhttpenabled 1").joinToString("\n")
                copy(context, "HardwareOne bridge commands", text)
            }, "COPY COMMANDS")
            TextButton(onClick = { showPem = !showPem }) { Text(if (showPem) "Hide CA" else "Show CA", color = hw.onGradient) }
        }
        if (bleLoggedIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (urlCommand != null) SecondaryButton({ onSend(urlCommand); onMarkConfigured() }, "SEND URL VIA BT")
                if (caCommand != null && caCommand.length <= bleCommandLimit) SecondaryButton({ onSend(caCommand) }, "SEND CA VIA BT")
            }
            if (caCommand != null && caCommand.length > bleCommandLimit) {
                Text(
                    "The CA command is ${caCommand.length} bytes; this Bluetooth link takes at most $bleCommandLimit per command. " +
                        "Paste it into the device web CLI or serial console instead (Copy commands).",
                    color = hw.muted, style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Text(
                "Log in over Bluetooth (server mode) to push these to the device, then switch it back to glasses mode with `blemode client`.",
                color = hw.muted, style = MaterialTheme.typography.labelSmall,
            )
        }
        if (url != null && url != configuredUrl) {
            TextButton(onClick = onMarkConfigured) { Text("Mark this URL as configured on the device", color = hw.onGradient) }
        }
        if (showPem && caPem != null) {
            SelectionContainer {
                Text(caPem, color = hw.onGradient, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
private fun RelayConfigCard(running: Boolean, store: BridgeStore, problems: List<String>, onChanged: () -> Unit) {
    val hw = LocalHwColors.current
    val context = LocalContext.current
    var host by remember { mutableStateOf(store.relayHost) }
    var port by remember { mutableStateOf(store.relayPort.toString()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var caRevision by remember { mutableIntStateOf(0) }
    val relayCa = remember(caRevision) { store.relayCaPem?.let { runCatching { Pem.decodeCertificates(it).first() }.getOrNull() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { bytes ->
                require(bytes.size <= 16 * 1024) { "file too large" }
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        val certs = text?.let { runCatching { Pem.decodeCertificates(it) }.getOrNull() }
        if (certs.isNullOrEmpty()) {
            importError = "That file is not a PEM certificate. Choose the relay's ca-cert.pem."
        } else {
            store.relayCaPem = text
            importError = null
            caRevision++
            onChanged()
        }
    }
    val dirty = host != store.relayHost || port != store.relayPort.toString()
    SectionCard("Home relay") {
        if (running) {
            Text("Stop the bridge to change these settings.", color = hw.muted, style = MaterialTheme.typography.labelSmall)
        }
        Field("Relay host (IP or name, as in its certificate)", host, enabled = !running, keyboard = KeyboardType.Uri) { host = it }
        Field("Relay port", port, enabled = !running, keyboard = KeyboardType.Number) { port = it.filter { c -> c.isDigit() }.take(5) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                onClick = {
                    store.relayHost = host
                    store.relayPort = port.toIntOrNull() ?: BridgeConfig.DEFAULT_PORT
                    onChanged()
                },
                enabled = !running && dirty && host.isNotBlank(),
                text = "SAVE",
            )
            if (!running) SecondaryButton({ picker.launch("*/*") }, if (relayCa == null) "IMPORT CA" else "REPLACE CA")
        }
        if (relayCa != null) {
            InfoRow("CA", relayCa.subjectX500Principal.name)
            InfoRow("Fingerprint", Pem.sha256Fingerprint(relayCa).take(47) + "…")
            InfoRow("Valid until", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(relayCa.notAfter))
        } else {
            Text("Import the relay's public ca-cert.pem (not a key or token). The bridge trusts only this CA for the home leg.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        }
        importError?.let { Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium) }
        problems.forEach { Text(it, color = hw.danger, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun AdvancedCard(state: HomeBridge.UiState, store: BridgeStore, onChanged: () -> Unit) {
    val hw = LocalHwColors.current
    var listenPort by remember { mutableStateOf(store.listenPort.toString()) }
    var override by remember { mutableStateOf(store.listenAddressOverride) }
    var menu by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    val running = state.running
    SectionCard("Advanced") {
        Field("Listen port on the hotspot", listenPort, enabled = !running, keyboard = KeyboardType.Number) { listenPort = it.filter { c -> c.isDigit() }.take(5) }
        Text("Listen address", color = hw.muted, style = MaterialTheme.typography.labelMedium)
        Box {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !running) { menu = true }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(override ?: "Automatic (detect the hotspot interface)", color = hw.onGradient, style = MaterialTheme.typography.bodyMedium)
                Text("▾", color = hw.muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = { override = null; menu = false })
                state.hotspotCandidates.forEach { label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { override = label.substringBefore(' '); menu = false })
                }
                if (state.hotspotCandidates.isEmpty()) {
                    DropdownMenuItem(text = { Text("No candidates while stopped or without a hotspot") }, onClick = { menu = false }, enabled = false)
                }
            }
        }
        val dirty = listenPort != store.listenPort.toString() || override != store.listenAddressOverride
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                onClick = {
                    store.listenPort = listenPort.toIntOrNull() ?: BridgeConfig.DEFAULT_PORT
                    store.listenAddressOverride = override
                    onChanged()
                },
                enabled = !running && dirty,
                text = "SAVE",
            )
            if (!running) TextButton(onClick = { confirmRegenerate = true }) { Text("Regenerate bridge CA", color = hw.danger) }
        }
        Text(
            "Android may pick a new hotspot subnet (10.x, 172.16.x or 192.168.x) after a reboot; the bridge follows it and " +
                "reissues its certificate automatically, but the device's llmhttpurl must be updated to the new address.",
            color = hw.muted, style = MaterialTheme.typography.labelSmall,
        )
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate the bridge CA?") },
            text = { Text("Every HardwareOne that trusts the current certificate will stop connecting until you set the new llmhttpca on it.", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton(onClick = { store.regenerateCa(); confirmRegenerate = false; onChanged() }) { Text("Regenerate") } },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val hw = LocalHwColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = CardShape,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = hw.onGradient, unfocusedTextColor = hw.onGradient, disabledTextColor = hw.muted,
            focusedContainerColor = hw.cardBg, unfocusedContainerColor = hw.cardBg, disabledContainerColor = hw.cardBg,
            cursorColor = hw.accent, focusedBorderColor = hw.accent, unfocusedBorderColor = hw.cardBorder, disabledBorderColor = hw.cardBorder,
            focusedLabelColor = hw.muted, unfocusedLabelColor = hw.muted, disabledLabelColor = hw.muted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun copy(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
