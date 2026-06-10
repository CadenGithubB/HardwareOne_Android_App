package com.hardwareone.console.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.DiscoveredDevice
import com.hardwareone.console.ui.theme.TerminalAmber
import com.hardwareone.console.ui.theme.TerminalDim
import com.hardwareone.console.ui.theme.TerminalGreen
import com.hardwareone.console.ui.theme.TerminalRed

@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    onScanClicked: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    foldPosture: FoldPosture,
) {
    val state by vm.connectionState.collectAsState()
    val log by vm.log.collectAsState()
    val devices by vm.scanResults.collectAsState()
    val authenticated by vm.authenticated.collectAsState()

    // rememberSaveable so a fold/unfold (or rotation) keeps the typed line and dialog.
    var input by rememberSaveable { mutableStateOf("") }
    var showLogin by rememberSaveable { mutableStateOf(false) }

    val ready = state is ConnectionState.Ready
    val connecting = state is ConnectionState.Connecting ||
        state is ConnectionState.DiscoveringServices ||
        state is ConnectionState.NegotiatingMtu ||
        state is ConnectionState.EnablingNotifications
    val showDevices = !ready && !connecting && devices.isNotEmpty()

    // Reusable UI slots so the stacked and tabletop layouts share one source of truth.
    val header: @Composable () -> Unit = {
        Header(
            state = state,
            authenticated = authenticated,
            onScan = onScanClicked,
            onStopScan = vm::stopScan,
            onDisconnect = vm::disconnect,
            onReconnect = vm::reconnect,
            onStatus = vm::readStatus,
            onClear = vm::clearLog,
        )
    }
    val divider: @Composable () -> Unit = {
        HorizontalDivider(color = TerminalDim.copy(alpha = 0.3f))
    }
    val deviceList: @Composable () -> Unit = { DeviceList(devices, vm::connect) }
    val logView: @Composable (Modifier) -> Unit = { m -> LogView(log, m) }
    val inputBar: @Composable () -> Unit = {
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = ready,
            authenticated = authenticated,
            onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
            onLogin = { showLogin = true },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // On the unfolded inner screen (and tablets/landscape) keep lines readable by
            // constraining width and centring, instead of stretching edge-to-edge.
            val isCompact = widthSizeClass == WindowWidthSizeClass.Compact
            val columnModifier =
                if (isCompact) Modifier.fillMaxSize().padding(horizontal = 12.dp)
                else Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = 16.dp)

            when (foldPosture) {
                is FoldPosture.Tabletop -> {
                    // Half-open, horizontal hinge: log on the propped-up top panel,
                    // controls on the flat bottom panel, with a gap for the hinge.
                    val hingeGap = with(LocalDensity.current) {
                        foldPosture.hingeThicknessPx.toDp()
                    }
                    Column(columnModifier) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            header()
                            divider()
                            logView(Modifier.weight(1f).fillMaxWidth())
                        }
                        Spacer(Modifier.height(hingeGap))
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (showDevices) {
                                deviceList()
                                divider()
                            }
                            Spacer(Modifier.weight(1f))
                            divider()
                            inputBar()
                        }
                    }
                }

                else -> {
                    // Flat / cover screen / book posture: one scrolling column.
                    Column(columnModifier) {
                        header()
                        divider()
                        if (showDevices) {
                            deviceList()
                            divider()
                        }
                        logView(Modifier.weight(1f).fillMaxWidth())
                        divider()
                        inputBar()
                    }
                }
            }
        }
    }

    if (showLogin) {
        LoginDialog(
            onDismiss = { showLogin = false },
            onSubmit = { user, pass ->
                vm.login(user, pass)
                showLogin = false
            },
        )
    }
}

@Composable
private fun Header(
    state: ConnectionState,
    authenticated: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onStatus: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "HardwareOne",
                color = TerminalGreen,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel(state, authenticated),
                color = statusColor(state),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ConnectionState.Scanning -> {
                    OutlinedButton(onClick = onStopScan) { Text("STOP") }
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TerminalGreen,
                        strokeWidth = 2.dp,
                    )
                }
                is ConnectionState.Ready -> {
                    OutlinedButton(onClick = onStatus) { Text("STATUS") }
                    OutlinedButton(onClick = onDisconnect) { Text("DISCONNECT") }
                }
                is ConnectionState.Connecting,
                is ConnectionState.DiscoveringServices,
                is ConnectionState.NegotiatingMtu,
                is ConnectionState.EnablingNotifications -> {
                    OutlinedButton(onClick = onDisconnect) { Text("CANCEL") }
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TerminalGreen,
                        strokeWidth = 2.dp,
                    )
                }
                else -> { // Disconnected / Failed
                    Button(onClick = onScan) { Text("SCAN") }
                    if (state is ConnectionState.Failed) {
                        OutlinedButton(onClick = onReconnect) { Text("RECONNECT") }
                    }
                }
            }
            TextButton(onClick = onClear) { Text("CLEAR") }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<DiscoveredDevice>,
    onConnect: (DiscoveredDevice) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "Devices (${devices.size}) — tap to connect:",
            color = TerminalDim,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            items(devices, key = { it.address }) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(device) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${device.name}  ${device.address}",
                        color = TerminalGreen,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${device.rssi} dBm",
                        color = TerminalDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogView(log: List<LogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.scrollToItem(log.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        items(log) { entry ->
            Text(
                text = entry.text,
                color = colorFor(entry.kind),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    authenticated: Boolean,
    onSend: () -> Unit,
    onLogin: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            placeholder = {
                Text(
                    if (enabled) "type a command (e.g. help)" else "connect to begin",
                    color = TerminalDim.copy(alpha = 0.6f),
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        if (enabled && !authenticated) {
            OutlinedButton(onClick = onLogin) { Text("LOGIN") }
        }
        Button(onClick = onSend, enabled = enabled && value.isNotBlank()) { Text("SEND") }
    }
}

@Composable
private fun LoginDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log in", color = TerminalGreen) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(user, pass) }) { Text("LOG IN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}

// --- small helpers ----------------------------------------------------------------

private fun colorFor(kind: LogEntry.Kind): Color = when (kind) {
    LogEntry.Kind.OUTGOING -> TerminalAmber
    LogEntry.Kind.INCOMING -> TerminalGreen
    LogEntry.Kind.INFO -> TerminalDim
    LogEntry.Kind.ERROR -> TerminalRed
}

private fun statusLabel(state: ConnectionState, authenticated: Boolean): String = when (state) {
    is ConnectionState.Disconnected -> "● disconnected"
    is ConnectionState.Scanning -> "◌ scanning"
    is ConnectionState.Connecting -> "◌ connecting"
    is ConnectionState.DiscoveringServices -> "◌ discovering"
    is ConnectionState.NegotiatingMtu -> "◌ mtu"
    is ConnectionState.EnablingNotifications -> "◌ subscribing"
    is ConnectionState.Ready ->
        if (authenticated) "● online (mtu ${state.mtu})" else "● connected — login required"
    is ConnectionState.Failed -> "● ${state.reason}"
}

private fun statusColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Ready -> TerminalGreen
    is ConnectionState.Failed -> TerminalRed
    is ConnectionState.Disconnected -> TerminalDim
    else -> TerminalAmber
}
