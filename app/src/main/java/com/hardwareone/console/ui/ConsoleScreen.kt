package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ble.DiscoveredDevice
import com.hardwareone.console.ui.theme.HwColors
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    onScanClicked: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    foldPosture: FoldPosture,
    onOpenSettings: () -> Unit,
) {
    val hw = LocalHwColors.current
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
            onOpenSettings = onOpenSettings,
        )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isCompact = widthSizeClass == WindowWidthSizeClass.Compact
            val columnModifier =
                if (isCompact) Modifier.fillMaxSize().padding(horizontal = 12.dp)
                else Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = 16.dp)

            when (foldPosture) {
                is FoldPosture.Tabletop -> {
                    val hingeGap = with(androidx.compose.ui.platform.LocalDensity.current) {
                        foldPosture.hingeThicknessPx.toDp()
                    }
                    Column(columnModifier) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            header()
                            logView(Modifier.weight(1f).fillMaxWidth())
                        }
                        Spacer(Modifier.height(hingeGap))
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (showDevices) deviceList()
                            Spacer(Modifier.weight(1f))
                            inputBar()
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = columnModifier,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        header()
                        if (showDevices) deviceList()
                        logView(Modifier.weight(1f).fillMaxWidth())
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
    onOpenSettings: () -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HardwareOne",
                color = hw.onGradient,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = statusLabel(state, authenticated),
                color = statusColor(state, hw),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = hw.onGradient,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ConnectionState.Scanning -> {
                    SecondaryButton(onStopScan, "STOP")
                    Spinner()
                }
                is ConnectionState.Ready -> {
                    SecondaryButton(onStatus, "STATUS")
                    SecondaryButton(onDisconnect, "DISCONNECT")
                }
                is ConnectionState.Connecting,
                is ConnectionState.DiscoveringServices,
                is ConnectionState.NegotiatingMtu,
                is ConnectionState.EnablingNotifications -> {
                    SecondaryButton(onDisconnect, "CANCEL")
                    Spinner()
                }
                else -> { // Disconnected / Failed
                    PrimaryButton(onScan, text = "SCAN")
                    if (state is ConnectionState.Failed) SecondaryButton(onReconnect, "RECONNECT")
                }
            }
            SecondaryButton(onClear, "CLEAR")
        }
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
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            items(devices, key = { it.address }) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(device) }
                        .padding(vertical = 8.dp),
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

@Composable
private fun LogView(log: List<LogEntry>, modifier: Modifier = Modifier) {
    val hw = LocalHwColors.current
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.scrollToItem(log.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .clip(CardShape)
            .background(hw.terminalBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        items(log) { entry ->
            Text(
                text = entry.text,
                color = colorFor(entry.kind, hw),
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
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            shape = CardShape,
            placeholder = { Text(if (enabled) "type a command (e.g. help)" else "connect to begin") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = hw.onGradient,
                unfocusedTextColor = hw.onGradient,
                disabledTextColor = hw.muted,
                focusedContainerColor = hw.cardBg,
                unfocusedContainerColor = hw.cardBg,
                disabledContainerColor = hw.cardBg,
                cursorColor = hw.accent,
                focusedBorderColor = hw.accent,
                unfocusedBorderColor = hw.cardBorder,
                disabledBorderColor = hw.cardBorder,
                focusedPlaceholderColor = hw.muted,
                unfocusedPlaceholderColor = hw.muted,
                disabledPlaceholderColor = hw.muted,
            ),
            modifier = Modifier.weight(1f),
        )
        if (enabled && !authenticated) PrimaryButton(onLogin, text = "LOGIN")
        PrimaryButton(onSend, enabled = enabled && value.isNotBlank(), text = "SEND")
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
        title = { Text("Log in") },
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
        confirmButton = { TextButton(onClick = { onSubmit(user, pass) }) { Text("LOG IN") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

// --- shared button styles ---------------------------------------------------------

/** Solid light button with accent text — the primary action, high-contrast on the gradient. */
@Composable
private fun PrimaryButton(onClick: () -> Unit, enabled: Boolean = true, text: String) {
    val hw = LocalHwColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = hw.onGradient,
            contentColor = hw.accent,
            disabledContainerColor = hw.cardBg,
            disabledContentColor = hw.muted,
        ),
    ) { Text(text) }
}

/** Frosted outlined button — secondary actions. */
@Composable
private fun SecondaryButton(onClick: () -> Unit, text: String) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, hw.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text(text) }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = LocalHwColors.current.onGradient,
        strokeWidth = 2.dp,
    )
}

// --- small helpers ----------------------------------------------------------------

private fun colorFor(kind: LogEntry.Kind, hw: HwColors): Color = when (kind) {
    LogEntry.Kind.OUTGOING -> hw.logOut
    LogEntry.Kind.INCOMING -> hw.logIn
    LogEntry.Kind.INFO -> hw.logInfo
    LogEntry.Kind.ERROR -> hw.logError
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

private fun statusColor(state: ConnectionState, hw: HwColors): Color = when (state) {
    is ConnectionState.Ready -> hw.success
    is ConnectionState.Failed -> hw.danger
    is ConnectionState.Disconnected -> hw.muted
    else -> hw.onGradient
}
