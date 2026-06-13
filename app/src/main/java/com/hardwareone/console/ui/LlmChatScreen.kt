package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.hardwareone.console.R
import com.hardwareone.console.ble.ChatMessage
import com.hardwareone.console.ble.LlmStatus
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

/**
 * On-device LLM chat. Picks/loads a model, streams the assistant reply token-by-token (the
 * ViewModel polls `llmresult json` while generating), and can stop mid-generation.
 */
@Composable
fun LlmChatScreen(
    status: LlmStatus?,
    models: List<String>,
    messages: List<ChatMessage>,
    generating: Boolean,
    onLoadModel: (String) -> Unit,
    onUnload: () -> Unit,
    onSend: (String) -> Unit,
    onDo: (String) -> Unit,
    onRunCommand: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    var input by rememberSaveable { mutableStateOf("") }
    val ready = status?.ready == true

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                        text = "LLM",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!generating && messages.any { it.role == ChatMessage.Role.ASSISTANT }) {
                        OutlinedButton(
                            onClick = onRetry,
                            border = BorderStroke(1.dp, hw.cardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Retry") }
                        Spacer(Modifier.size(6.dp))
                    }
                    if (messages.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClear,
                            border = BorderStroke(1.dp, hw.cardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Clear") }
                    }
                }

                ModelBar(status, models, onLoadModel, onUnload)

                // Conversation.
                val listState = rememberLazyListState()
                val lastLen = messages.lastOrNull()?.text?.length ?: 0
                LaunchedEffect(messages.size, lastLen) {
                    if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (messages.isEmpty()) {
                        Text(
                            text = if (status == null) "On-device LLM isn't available on this device."
                            else if (!status.loaded) "Load a model above, then send a message."
                            else "Ready — send a message.",
                            color = hw.muted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages) { msg -> ChatBubble(msg, generating, onRunCommand) }
                        }
                    }
                }

                // Input.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        enabled = ready && !generating,
                        singleLine = true,
                        shape = CardShape,
                        placeholder = { Text(if (ready) "Ask the model…" else "Load a model first") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = true, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank()) { onSend(input); input = "" }
                        }),
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
                    if (generating) {
                        PrimaryButton(onStop, text = "STOP")
                    } else {
                        // Tap = send chat. Hold + swipe up = "Do:" (ask the LLM for a command).
                        SendDoButton(
                            enabled = ready && input.isNotBlank(),
                            onSend = { if (input.isNotBlank()) { onSend(input); input = "" } },
                            onDo = { if (input.isNotBlank()) { onDo(input); input = "" } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelBar(
    status: LlmStatus?,
    models: List<String>,
    onLoadModel: (String) -> Unit,
    onUnload: () -> Unit,
) {
    val hw = LocalHwColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status?.model?.takeIf { it.isNotEmpty() && status.loaded } ?: "No model loaded",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = llmStateLine(status),
                color = if (status?.errored == true) hw.danger else hw.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (status?.loading == true) {
            CircularProgressIndicator(Modifier.size(18.dp), color = hw.onGradient, strokeWidth = 2.dp)
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                border = BorderStroke(1.dp, hw.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(if (status?.loaded == true) "Model ▾" else "Load ▾") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models found") }, enabled = false, onClick = {})
                } else {
                    models.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { menuOpen = false; onLoadModel(m) })
                    }
                }
                if (status?.loaded == true) {
                    DropdownMenuItem(text = { Text("Unload", color = hw.danger) }, onClick = { menuOpen = false; onUnload() })
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, generating: Boolean, onRunCommand: (String) -> Unit) {
    val hw = LocalHwColors.current
    val isUser = msg.role == ChatMessage.Role.USER

    // `Do:` result — a runnable command suggestion: monospace command + Run button.
    if (msg.command) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(hw.cardBg)
                    .border(1.dp, hw.accent, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = msg.text,
                    color = hw.onGradient,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrimaryButton(onClick = { onRunCommand(msg.text) }, text = "RUN")
            }
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val shown = if (msg.text.isEmpty() && generating) "…" else msg.text
    Row(modifier = Modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) hw.accent else hw.cardBg)
                .border(1.dp, if (isUser) hw.accent else hw.cardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = shown,
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!isUser) Spacer(Modifier.weight(1f))
    }
}

/**
 * Send button with a keyboard-style alternate action: a quick **tap** sends a normal chat
 * message; **press-and-hold, then swipe up** arms the **Do:** action (ask the LLM for a CLI
 * command) — release while armed to fire it. A "Do:" chip pops above the button while held.
 */
@Composable
private fun SendDoButton(enabled: Boolean, onSend: () -> Unit, onDo: () -> Unit) {
    val hw = LocalHwColors.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val sendNow by rememberUpdatedState(onSend)
    val doNow by rememberUpdatedState(onDo)
    var pressing by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center) {
        if (pressing) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(density) { (-52).dp.roundToPx() }),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (armed) hw.accent else hw.cardBg)
                        .border(1.dp, hw.accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Do: ↑",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .clip(CardShape)
                .background(if (!enabled) hw.cardBg else if (armed) hw.cardBg else hw.accent)
                .then(if (armed) Modifier.border(1.dp, hw.accent, CardShape) else Modifier)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val thresholdPx = 44.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Null = released/moved before the long-press fired → treat as a tap.
                        if (awaitLongPressOrCancellation(down.id) == null) {
                            sendNow()
                            return@awaitEachGesture
                        }
                        pressing = true
                        armed = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                ?: ev.changes.firstOrNull() ?: break
                            val nowArmed = (ch.position.y - down.position.y) < -thresholdPx
                            if (nowArmed != armed) {
                                armed = nowArmed
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        val fireDo = armed
                        pressing = false
                        armed = false
                        if (fireDo) doNow() else sendNow()
                    }
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (pressing && armed) "Do:" else "SEND",
                color = if (!enabled) hw.muted else hw.onGradient,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun llmStateLine(status: LlmStatus?): String {
    if (status == null) return "unavailable"
    val s = status.state.lowercase()
    return if (status.tokPerSec > 0 && (status.ready || status.generating)) {
        "$s · %.1f tok/s".format(status.tokPerSec)
    } else {
        s
    }
}
