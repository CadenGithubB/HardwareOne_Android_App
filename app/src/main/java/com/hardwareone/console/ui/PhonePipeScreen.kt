package com.hardwareone.console.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.pipe.PhonePipe
import com.hardwareone.console.pipe.PipeSecret
import com.hardwareone.console.pipe.PipeTarget
import com.hardwareone.console.ui.theme.LocalHwColors

private val PipeCardShape = RoundedCornerShape(14.dp)

/**
 * The BLE relay pipe page: start/stop, the pipe passphrase and the one relay destination, what
 * background operation needs, and endpoint-free diagnostics. The phone only forwards opaque TLS
 * bytes; HardwareOne keeps its own URL, certificate checks and token, so nothing here configures
 * TLS or HTTP. Wording never claims anything about what HardwareOne stored (R12).
 */
@Composable
fun PhonePipeScreen(
    state: PhonePipe.UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    /** Normalised secret bytes from [PipeSecret.normalize]; the receiver wipes them. */
    onSaveSecret: (ByteArray) -> Unit,
    onSaveTarget: (String) -> Unit,
    onForget: () -> Unit,
    onAllowBackground: () -> Unit,
    /** Exports the qualification log (JSON lines keyed by corr and stream id, no endpoints). */
    onExportLog: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = hw.onGradient)
                }
                Spacer(Modifier.size(4.dp))
                Text("BLE relay pipe", color = hw.onGradient, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (state.stats.liveStreams > 0) Spinner()
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PipeStatusCard(state, onStart, onStop)
                SetupCard(state, onSaveSecret, onSaveTarget, onForget)
                BackgroundCard(state, onAllowBackground)
                if (state.running) DiagnosticsCard(state)
                QualificationLogCard(onExportLog, onClearLog)
                Text(
                    "Path: HardwareOne (TLS) ↔ Bluetooth, encrypted with the pipe passphrase ↔ this phone ↔ VPN ↔ home " +
                        "relay. The phone never decrypts TLS, sees no HTTP or token, and connects only to the one approved " +
                        "destination, only through the VPN. A failed stream is never reconnected or replayed.",
                    color = hw.muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PipeStatusCard(state: PhonePipe.UiState, onStart: () -> Unit, onStop: () -> Unit) {
    val hw = LocalHwColors.current
    SectionCard(if (state.running) "Pipe · RUNNING" else "Pipe · OFF") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.running) SecondaryButton(onStop, "STOP") else PrimaryButton(onStart, enabled = state.isConfigured, text = "START")
        }
        Text(PhonePipe.summary(state), color = hw.onGradient, style = MaterialTheme.typography.bodyLarge)
        // While stopped, the summary line above already is the error.
        if (state.running) state.error?.let { Text(it, color = hw.danger, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            InfoRow("Bluetooth", if (state.bluetoothOn == true) "on" else "off")
            InfoRow("Advertising", if (state.advertising) "yes" else (state.advertiseError ?: "no"))
            InfoRow("Session", stageLabel(state.stats.stage))
            // Android cannot tell which app provides the VPN; this only says one covers this app.
            InfoRow("VPN covering this app", when (state.vpnAvailable) { true -> "yes"; false -> "NO — relay connections refused"; null -> "—" })
            if (state.networkPermission == false) {
                Text(
                    "This app has no Network permission, so every relay connection is refused. On GrapheneOS: " +
                        "Settings → Apps → HardwareOne → Permissions → Network.",
                    color = hw.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.coolingDownSeconds > 0) InfoRow("Authentication failed or timed out", "retry in ${state.coolingDownSeconds} s")
        } else if (!state.isConfigured) {
            Text("Set the passphrase and the destination below before starting.", color = hw.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun stageLabel(stage: String): String = when (stage) {
    "handshake" -> "authenticating"
    "secure" -> "secure channel up"
    "ready" -> "ready (phone side)"
    "ending" -> "ending"
    else -> "idle"
}

@Composable
private fun SetupCard(
    state: PhonePipe.UiState,
    onSaveSecret: (ByteArray) -> Unit,
    onSaveTarget: (String) -> Unit,
    onForget: () -> Unit,
) {
    val hw = LocalHwColors.current
    var confirmForget by remember { mutableStateOf(false) }
    var changingSecret by rememberSaveable { mutableStateOf(false) }
    var changingTarget by rememberSaveable { mutableStateOf(false) }
    val configured = state.configured
    SectionCard("Pipe setup") {
        if (configured?.hasSecret == true && !changingSecret) {
            InfoRow("Passphrase", "set")
            SecondaryButton({ changingSecret = true }, "CHANGE PASSPHRASE")
        } else {
            if (state.secretUnreadable) {
                Text("The stored passphrase can no longer be read on this phone. Enter it again.", color = hw.danger, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Enter the passphrase set on HardwareOne with its serial phonepipesecret command (never over Bluetooth), " +
                    "following the rules below. A secret stored or restored on HardwareOne any other way may not be enterable " +
                    "here. It never leaves this phone. Changing it ends the current session.",
                color = hw.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            PassphraseForm(onSave = { secret -> changingSecret = false; onSaveSecret(secret) })
            if (configured?.hasSecret == true) SecondaryButton({ changingSecret = false }, "KEEP CURRENT PASSPHRASE")
        }
        val target = configured?.target
        if (target != null && !changingTarget) {
            InfoRow("Relay destination", target)
            SecondaryButton({ changingTarget = true }, "CHANGE DESTINATION")
        } else {
            Text(
                "Enter HardwareOne's AI base URL exactly as configured on it (https://…/v1). Only that host and port will be " +
                    "reachable. Changing it ends streams to the old destination; the secure session stays up.",
                color = hw.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            UrlForm(label = "APPROVE DESTINATION") { url -> changingTarget = false; onSaveTarget(url) }
            if (target != null) SecondaryButton({ changingTarget = false }, "KEEP DESTINATION")
        }
        val last = configured?.lastAuthenticatedAt ?: 0L
        InfoRow(
            "Last authenticated pipe",
            if (last <= 0L) "never" else DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
        if (configured != null) SecondaryButton({ confirmForget = true }, "FORGET")
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget the pipe settings?") },
            text = {
                Text(
                    "Deletes this phone's pipe passphrase and approved destination, and stops the pipe. Nothing stored on " +
                        "HardwareOne changes: clear phonepipesecret there if you no longer want it.",
                )
            },
            confirmButton = { TextButton({ confirmForget = false; onForget() }) { Text("FORGET") } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("CANCEL") } },
        )
    }
}

/**
 * A masked passphrase field with the firmware's rules checked as you type (Q6). The text lives
 * only in this composable's memory (never in saved instance state) and is cleared on save.
 */
@Composable
private fun PassphraseForm(onSave: (ByteArray) -> Unit) {
    val hw = LocalHwColors.current
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val check = remember(text) { if (text.isEmpty()) null else PipeSecret.normalize(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Pipe passphrase") },
        singleLine = true,
        shape = PipeCardShape,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        colors = fieldColors(),
        trailingIcon = {
            TextButton({ visible = !visible }) { Text(if (visible) "HIDE" else "SHOW", color = hw.muted) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    when (check) {
        is PipeSecret.Result.Invalid -> Text(check.reason, color = hw.danger, style = MaterialTheme.typography.bodySmall)
        is PipeSecret.Result.Ok -> Text("${check.bytes.size} bytes", color = hw.muted, style = MaterialTheme.typography.labelSmall)
        null -> {}
    }
    PrimaryButton(
        onClick = {
            (check as? PipeSecret.Result.Ok)?.let { ok ->
                text = ""
                onSave(ok.bytes.copyOf())
                ok.bytes.fill(0)
            }
        },
        enabled = check is PipeSecret.Result.Ok,
        text = "SAVE PASSPHRASE",
    )
}

/** A device base URL, its canonical destination preview, and one submit button. */
@Composable
private fun UrlForm(label: String, onSubmit: (String) -> Unit) {
    val hw = LocalHwColors.current
    var url by rememberSaveable { mutableStateOf("") }
    val parsed = remember(url) {
        if (url.isBlank()) null else runCatching { Result.success(PipeTarget.parseUrl(url.trim())) }.getOrElse { Result.failure(it) }
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Device base URL") },
        singleLine = true,
        shape = PipeCardShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    parsed?.onSuccess { target ->
        val kind = when (target.type) { PipeTarget.IPV4 -> "IPv4"; PipeTarget.IPV6 -> "IPv6"; else -> "DNS name, resolved through the VPN" }
        InfoRow("Will approve", target.display())
        Text(kind, color = hw.muted, style = MaterialTheme.typography.labelSmall)
    }
    parsed?.onFailure { e ->
        Text((e as? PipeTarget.Invalid)?.reason ?: "Not a valid URL.", color = hw.danger, style = MaterialTheme.typography.bodySmall)
    }
    PrimaryButton(onClick = { onSubmit(url.trim()); url = "" }, enabled = parsed?.isSuccess == true, text = label)
}

@Composable
private fun fieldColors() = LocalHwColors.current.let { hw ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = hw.onGradient, unfocusedTextColor = hw.onGradient, disabledTextColor = hw.muted,
        focusedContainerColor = hw.cardBg, unfocusedContainerColor = hw.cardBg, disabledContainerColor = hw.cardBg,
        cursorColor = hw.accent, focusedBorderColor = hw.accent, unfocusedBorderColor = hw.cardBorder, disabledBorderColor = hw.cardBorder,
        focusedLabelColor = hw.muted, unfocusedLabelColor = hw.muted, disabledLabelColor = hw.muted,
    )
}

/** What automatic recovery needs from Android (FW-1); the battery step is the only one the app can prompt for. */
@Composable
private fun BackgroundCard(state: PhonePipe.UiState, onAllowBackground: () -> Unit) {
    val hw = LocalHwColors.current
    SectionCard("Background operation") {
        InfoRow(
            "Battery",
            when {
                state.backgroundRestricted == true -> "Restricted — the pipe cannot run in the background"
                state.batteryUnrestricted == true -> "Unrestricted"
                state.batteryUnrestricted == false -> "Optimized"
                else -> "—"
            },
        )
        if (state.batteryUnrestricted != true) SecondaryButton(onAllowBackground, "ALLOW BACKGROUND USE")
        Text(
            "So the pipe comes back on its own after a crash, an update, Bluetooth off/on or a VPN drop: set battery to " +
                "Unrestricted, turn on Always-on VPN for your VPN app, and leave GrapheneOS's Bluetooth timeout off. After a " +
                "reboot, unlock the phone once. Force stop turns the pipe off until you open the app.",
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** The per-session and per-stream record the firmware's F3 log lines up with (row 10). */
@Composable
private fun QualificationLogCard(onExport: () -> Unit, onClear: () -> Unit) {
    val hw = LocalHwColors.current
    var confirmClear by remember { mutableStateOf(false) }
    SectionCard("Qualification log") {
        Text(
            "One line per session and per stream, keyed by the same session id (corr) HardwareOne logs, plus counters " +
                "at export. Times, sizes and codes only: no destinations, addresses, passphrase or content.",
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(onExport, "EXPORT LOG")
            SecondaryButton({ confirmClear = true }, "CLEAR LOG")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the qualification log?") },
            text = { Text("Deletes the pipe's session and stream records on this phone, so a new test run starts empty.") },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text("CLEAR") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun DiagnosticsCard(state: PhonePipe.UiState) {
    val s = state.stats
    SectionCard("Diagnostics") {
        // The same session id HardwareOne prints on line 1 of `phonepipe stats`.
        InfoRow("Session id (corr)", s.corr ?: "—")
        InfoRow("ATT MTU / inner frame", if (s.mtu > 0) "${s.mtu} / ${s.frame}" else "—")
        InfoRow("Sessions", s.sessions.toString())
        InfoRow("Live streams", s.liveStreams.toString())
        InfoRow("OPENs this session / connected", "${s.opens} / ${s.opened}")
        InfoRow("Frames in / out", "${s.framesIn} / ${s.framesOut}")
        InfoRow("Bytes to relay / to device", "${s.bytesDown} / ${s.bytesUp}")
        s.lastOpenOkMs?.let { InfoRow("Last OPEN → OPEN_OK", "$it ms") }
        if (s.refused.isNotEmpty()) InfoRow("Refused (OPEN_ERR)", s.refused.entries.joinToString { "${it.key.name.lowercase()}×${it.value}" })
        if (s.resets.isNotEmpty()) InfoRow("Stream resets", s.resets.entries.joinToString { "${it.key.name.lowercase()}×${it.value}" })
        s.lastEnd?.let { kind ->
            InfoRow("Last session end", kind.name.lowercase() + (s.lastError?.let { " · ${it.name.lowercase()} (${it.code})" } ?: ""))
        }
        s.lastOpenErrMs?.let { InfoRow("Last OPEN → OPEN_ERR", "$it ms") }
        if (s.peerResets > 0 || s.peerCloses > 0) InfoRow("Ended by HardwareOne (RESET / CLOSE)", "${s.peerResets} / ${s.peerCloses}")
        if (s.staleCallbacks > 0 || state.droppedCallbacks > 0) {
            InfoRow("Stale callbacks dropped (engine / retired server)", "${s.staleCallbacks} / ${state.droppedCallbacks}")
        }
    }
}
