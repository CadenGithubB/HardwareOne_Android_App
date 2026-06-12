package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
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
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onLogin: (username: String, password: String, remember: Boolean) -> Unit,
    onLoginButton: () -> Unit,
) {
    val hw = LocalHwColors.current
    val state by vm.connectionState.collectAsState()
    val log by vm.log.collectAsState()
    val devices by vm.scanResults.collectAsState()
    val authenticated by vm.authenticated.collectAsState()
    val savedUsername by vm.savedUsername.collectAsState()
    val canRememberCreds = vm.canUseCredentialStore
    val showLogin by vm.loginDialogVisible.collectAsState()

    // rememberSaveable so a fold/unfold (or rotation) keeps the typed line.
    var input by rememberSaveable { mutableStateOf("") }

    val ready = state is ConnectionState.Ready
    val connecting = state is ConnectionState.Connecting ||
        state is ConnectionState.DiscoveringServices ||
        state is ConnectionState.NegotiatingMtu ||
        state is ConnectionState.EnablingNotifications ||
        state is ConnectionState.Securing
    val showDevices = !ready && !connecting && devices.isNotEmpty()

    // Compact = narrow portrait phone / folded cover screen. Wider (landscape, unfolded,
    // tablet) gets a single-row header.
    val isCompact = widthSizeClass == WindowWidthSizeClass.Compact

    // Reusable UI slots so the stacked and tabletop layouts share one source of truth.
    val saveLog: (() -> Unit)? = if (vm.canSaveLogs) ({ vm.saveCurrentLog() }) else null
    val header: @Composable () -> Unit = {
        Header(
            state = state,
            authenticated = authenticated,
            compact = isCompact,
            onScan = onScanClicked,
            onStopScan = vm::stopScan,
            onDisconnect = vm::disconnect,
            onReconnect = vm::reconnect,
            onReadStatus = vm::readStatus,
            onSyncClock = vm::syncClock,
            onOpenStatus = onOpenStatus,
            onOpenSensors = onOpenSensors,
            onClear = vm::clearLog,
            onSaveLog = saveLog,
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
            onLogin = onLoginButton,
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
            initialUsername = savedUsername ?: "",
            canRemember = canRememberCreds,
            onDismiss = { vm.hideLoginDialog() },
            onSubmit = { user, pass, remember ->
                onLogin(user, pass, remember)
                vm.hideLoginDialog()
            },
        )
    }
}

@Composable
private fun Header(
    state: ConnectionState,
    authenticated: Boolean,
    compact: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onReadStatus: () -> Unit,
    onSyncClock: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onClear: () -> Unit,
    onSaveLog: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    val controls: @Composable () -> Unit = {
        PrimaryConnectionButton(state, onScan, onStopScan, onDisconnect)
        ConsoleMenu(onSaveLog, onClear)
        DeviceMenu(state, onReadStatus, onSyncClock, onOpenStatus, onOpenSensors, onReconnect)
    }
    if (compact) {
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TitleStatus(state, authenticated)
                GearButton(onOpenSettings)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { controls() }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleStatus(state, authenticated)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { controls() }
            Spacer(Modifier.width(8.dp))
            GearButton(onOpenSettings)
        }
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

/** "Console ▾" menu: save / clear the log. */
@Composable
private fun ConsoleMenu(onSaveLog: (() -> Unit)?, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MenuButton("Console") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onSaveLog != null) {
                DropdownMenuItem(
                    text = { Text("Save log") },
                    onClick = { expanded = false; onSaveLog() },
                )
            }
            DropdownMenuItem(
                text = { Text("Clear log") },
                onClick = { expanded = false; onClear() },
            )
        }
    }
}

/** "Device ▾" menu: device actions only (status page / read status / sync clock / reconnect). */
@Composable
private fun DeviceMenu(
    state: ConnectionState,
    onReadStatus: () -> Unit,
    onSyncClock: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onReconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MenuButton("Device") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state is ConnectionState.Ready) {
                DropdownMenuItem(
                    text = { Text("Status page") },
                    onClick = { expanded = false; onOpenStatus() },
                )
                DropdownMenuItem(
                    text = { Text("Sensors") },
                    onClick = { expanded = false; onOpenSensors() },
                )
                DropdownMenuItem(
                    text = { Text("Read status") },
                    onClick = { expanded = false; onReadStatus() },
                )
                DropdownMenuItem(
                    text = { Text("Sync clock") },
                    onClick = { expanded = false; onSyncClock() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Reconnect") },
                    onClick = { expanded = false; onReconnect() },
                )
            }
        }
    }
}

/** Outlined button with a dropdown caret, styled for the gradient. */
@Composable
private fun MenuButton(label: String, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, hw.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text("$label ▾") }
}

@Composable
private fun RowScope.TitleStatus(state: ConnectionState, authenticated: Boolean) {
    val hw = LocalHwColors.current
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
}

@Composable
private fun GearButton(onOpenSettings: () -> Unit) {
    IconButton(onClick = onOpenSettings) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = "Settings",
            tint = LocalHwColors.current.onGradient,
        )
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
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send,
            ),
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
    initialUsername: String,
    canRemember: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, Boolean) -> Unit,
) {
    var user by remember { mutableStateOf(initialUsername) }
    var pass by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
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
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                )
                if (canRemember) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { remember = !remember },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text("Remember (biometric / PIN)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(user, pass, remember) }) { Text("LOG IN") } },
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
    is ConnectionState.Securing -> "◌ securing"
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
