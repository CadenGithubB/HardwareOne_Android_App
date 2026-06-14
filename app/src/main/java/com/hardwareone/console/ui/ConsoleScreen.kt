package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import kotlinx.coroutines.launch
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ui.theme.HwColors
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    widthSizeClass: WindowWidthSizeClass,
    foldPosture: FoldPosture,
    onSelectPage: (AppPage) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenFiles: () -> Unit,
    onLoginButton: () -> Unit,
) {
    val hw = LocalHwColors.current
    val state by vm.connectionState.collectAsState()
    val log by vm.log.collectAsState()
    val logTotal by vm.logTotal.collectAsState()
    val commandHistory by vm.commandHistory.collectAsState()
    val authenticated by vm.authenticated.collectAsState()
    val currentUser by vm.currentUser.collectAsState()

    // rememberSaveable so a fold/unfold (or rotation) keeps the typed line.
    var input by rememberSaveable { mutableStateOf("") }

    val ready = state is ConnectionState.Ready
    val isCompact = widthSizeClass == WindowWidthSizeClass.Compact

    val saveLog: (() -> Unit)? = if (vm.canSaveLogs) ({ vm.saveCurrentLog() }) else null
    val header: @Composable () -> Unit = {
        Header(
            state = state,
            authenticated = authenticated,
            user = currentUser,
            onSelectPage = onSelectPage,
            onSyncClock = vm::syncClock,
            onOpenStatus = onOpenStatus,
            onOpenSensors = onOpenSensors,
            onOpenLlm = onOpenLlm,
            onOpenFiles = onOpenFiles,
            onClear = vm::clearLog,
            onSaveLog = saveLog,
            onOpenSettings = onOpenSettings,
        )
    }
    val logView: @Composable (Modifier) -> Unit = { m -> LogView(log, logTotal, m) }
    val inputBar: @Composable () -> Unit = {
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = ready,
            authenticated = authenticated,
            history = commandHistory,
            onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
            onLogin = onLoginButton,
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
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
                        logView(Modifier.weight(1f).fillMaxWidth())
                        inputBar()
                    }
                }
            }
        }
    }

}

@Composable
private fun Header(
    state: ConnectionState,
    authenticated: Boolean,
    user: String?,
    onSelectPage: (AppPage) -> Unit,
    onSyncClock: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenFiles: () -> Unit,
    onClear: () -> Unit,
    onSaveLog: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: all controls (toggle, menus, settings) — evenly distributed so the gaps
        // between every control (and the edges) match instead of clustering left.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PageToggle(AppPage.CONSOLE, onSelectPage)
            ConsoleMenu(onSaveLog, onClear)
            DeviceMenu(
                state = state,
                onSyncClock = onSyncClock,
                onOpenStatus = onOpenStatus,
                onOpenSensors = onOpenSensors,
                onOpenLlm = onOpenLlm,
                onOpenFiles = onOpenFiles,
                onConnect = { onSelectPage(AppPage.DEVICES) },
            )
            GearButton(onOpenSettings)
        }
        // Row 2: connection / login status, always on its own line.
        Text(
            text = statusLabel(state, authenticated, user),
            color = statusColor(state, hw),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        )
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

/** "Device ▾" menu: device actions when connected; otherwise jump to the Devices page. */
@Composable
private fun DeviceMenu(
    state: ConnectionState,
    onSyncClock: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenFiles: () -> Unit,
    onConnect: () -> Unit,
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
                    text = { Text("LLM chat") },
                    onClick = { expanded = false; onOpenLlm() },
                )
                DropdownMenuItem(
                    text = { Text("Files") },
                    onClick = { expanded = false; onOpenFiles() },
                )
                DropdownMenuItem(
                    text = { Text("Sync clock") },
                    onClick = { expanded = false; onSyncClock() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Connect…") },
                    onClick = { expanded = false; onConnect() },
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { OutlinedText("$label ▾", color = hw.onGradient) }
}

@Composable
private fun LogView(log: List<LogEntry>, logTotal: Long, modifier: Modifier = Modifier) {
    val hw = LocalHwColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    // Scroll lock: when the user scrolls up, freeze a SNAPSHOT of the log so the buffer trimming
    // (oldest lines dropping as new arrive) doesn't shift the text under them. New output keeps
    // flowing into `log` behind the snapshot; returning to the bottom resumes live tailing.
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }
    var frozenTotal by remember { mutableStateOf(0L) }
    LaunchedEffect(atBottom) {
        if (atBottom) frozen = null else { frozen = log; frozenTotal = logTotal }
    }
    val shown = frozen ?: log
    // Key on the displayed list (not its size): once the log hits its cap the size stays constant
    // while content changes, so a size-keyed effect would stop following.
    LaunchedEffect(shown) {
        if (atBottom && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    // Lines between the user's current view and the live tail: grows as new output arrives,
    // shrinks as they scroll down — a live sense of position while scroll-locked.
    val logTotalState = rememberUpdatedState(logTotal)
    val linesBelow by remember {
        derivedStateOf {
            val snap = frozen ?: return@derivedStateOf 0L
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: snap.lastIndex
            val belowInSnapshot = (snap.lastIndex - lastVisible).coerceAtLeast(0).toLong()
            belowInSnapshot + (logTotalState.value - frozenTotal).coerceAtLeast(0)
        }
    }

    // NOT inside a SelectionContainer: the console stays read-only on screen (FLAG_SECURE); the
    // one sanctioned plaintext egress is the gated decrypted-log export.
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clip(CardShape)
                .background(hw.terminalBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            items(shown) { entry ->
                Text(
                    text = entry.text,
                    color = colorFor(entry.kind, hw),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
        if (frozen != null) {
            Surface(
                onClick = {
                    scope.launch {
                        frozen = null
                        if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
                    }
                },
                shape = RoundedCornerShape(50),
                color = hw.accent,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                Text(
                    text = if (linesBelow > 0) "↓ Jump to latest · $linesBelow below" else "↓ Jump to latest",
                    color = hw.onGradient,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    authenticated: Boolean,
    history: List<String>,
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
            trailingIcon = if (history.isEmpty()) null else {
                {
                    var menu by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A divider then a history glyph at the right edge, near SEND: "| ↑".
                        Box(Modifier.width(2.dp).height(22.dp).background(hw.cardBorder))
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_up),
                                    contentDescription = "Command history",
                                    tint = hw.onGradient,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                history.forEach { cmd ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                cmd,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = { menu = false; onValueChange(cmd) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
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
internal fun LoginDialog(
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

private fun colorFor(kind: LogEntry.Kind, hw: HwColors): Color = when (kind) {
    LogEntry.Kind.OUTGOING -> hw.logOut
    LogEntry.Kind.INCOMING -> hw.logIn
    LogEntry.Kind.INFO -> hw.logInfo
    LogEntry.Kind.ERROR -> hw.logError
}
