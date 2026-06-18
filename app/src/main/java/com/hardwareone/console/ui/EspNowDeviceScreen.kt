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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.EspNowChatLine
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * Per-peer detail, reached by tapping a paired device. Three tabs:
 *  - **Messages** — chat feed (`espnowsend` + reassembled `espnowmessages`).
 *  - **Command** — run any CLI command on the peer (`espnowremote`), result matched by reqId.
 *  - **Manage** — unpair / forget.
 */
@Composable
fun EspNowDeviceScreen(
    deviceName: String,
    mac: String,
    encrypted: Boolean,
    feed: List<EspNowChatLine>,
    onSend: (String) -> Unit,
    remoteBusy: Boolean,
    remoteError: String?,
    remoteResult: String?,
    onRunCommand: (user: String, pass: String, command: String) -> Unit,
    onUnpair: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    var tab by rememberSaveable { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                // Back header.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = hw.onGradient)
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = deviceName.ifEmpty { mac },
                            color = hw.onGradient,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (deviceName.isNotEmpty()) {
                            Text(mac, color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Tabs.
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabSeg("Messages", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    TabSeg("Command", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    TabSeg("Manage", tab == 2, Modifier.weight(1f)) { tab = 2 }
                }

                when (tab) {
                    0 -> MessagesTab(feed, onSend)
                    1 -> CommandTab(encrypted, remoteBusy, remoteError, remoteResult, onRunCommand)
                    else -> ManageTab(mac, encrypted, onUnpair, onForget)
                }
            }
        }
    }
}

@Composable
private fun TabSeg(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) hw.onGradient else hw.cardBg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) androidx.compose.ui.graphics.Color.Black else hw.onGradient,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MessagesTab(feed: List<EspNowChatLine>, onSend: (String) -> Unit) {
    val hw = LocalHwColors.current
    var input by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().fillMaxSize()) {
        val listState = rememberLazyListState()
        LaunchedEffect(feed.size) { if (feed.isNotEmpty()) listState.scrollToItem(feed.lastIndex) }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (feed.isEmpty()) {
                Text(
                    text = "No messages yet.",
                    color = hw.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(feed) { line -> ChatBubble(line) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Message…") },
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                onClick = { if (input.isNotBlank()) { onSend(input.trim()); input = "" } },
                enabled = input.isNotBlank(),
                text = "Send",
            )
        }
    }
}

@Composable
private fun CommandTab(
    encrypted: Boolean,
    busy: Boolean,
    error: String?,
    result: String?,
    onRun: (String, String, String) -> Unit,
) {
    val hw = LocalHwColors.current
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var cmd by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!encrypted) {
            Text(
                text = "Remote commands need ESP-NOW encryption. Set a passphrase on the device " +
                    "(This device ▸ config, or the web), then pair securely.",
                color = hw.danger,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(user, { user = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("peer username") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("peer password") }, visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cmd, { cmd = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("command, e.g. status json") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                onClick = { if (cmd.isNotBlank()) onRun(user.trim(), pass, cmd.trim()) },
                enabled = cmd.isNotBlank() && !busy,
                text = "Run",
            )
            if (busy) Spinner()
        }
        error?.let { Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium) }
        result?.let {
            SectionCard("Result") {
                Text(it, color = hw.onGradient, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ManageTab(mac: String, encrypted: Boolean, onUnpair: () -> Unit, onForget: () -> Unit) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard("Device") {
            InfoRow("MAC", mac)
            InfoRow("Encrypted", yn(encrypted))
        }
        TextButton(onClick = onUnpair) { Text("Unpair device", color = hw.danger) }
        TextButton(onClick = onForget) { Text("Forget crypto identity", color = hw.danger) }
        Text(
            text = "Unpair removes the device from this gateway's peer list. Forget also clears its " +
                "stored crypto identity (re-pair securely to talk again).",
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LocalHwColors.current.onGradient,
    unfocusedTextColor = LocalHwColors.current.onGradient,
    focusedContainerColor = LocalHwColors.current.cardBg,
    unfocusedContainerColor = LocalHwColors.current.cardBg,
    cursorColor = LocalHwColors.current.accent,
    focusedBorderColor = LocalHwColors.current.accent,
    unfocusedBorderColor = LocalHwColors.current.cardBorder,
    focusedPlaceholderColor = LocalHwColors.current.muted,
    unfocusedPlaceholderColor = LocalHwColors.current.muted,
)

@Composable
private fun ChatBubble(line: EspNowChatLine) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (line.outgoing) hw.accent else hw.cardBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!line.outgoing) {
                Text(line.from, color = hw.muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(line.text, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium)
            if (line.outgoing && line.state >= 0) {
                val label = when (line.state) {
                    1 -> "delivered"
                    2 -> "no ack"
                    3 -> "failed"
                    else -> "sending…"
                }
                Text(
                    text = label,
                    color = if (line.state == 2 || line.state == 3) hw.danger else hw.muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}
