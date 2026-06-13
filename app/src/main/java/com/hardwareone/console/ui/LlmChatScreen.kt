package com.hardwareone.console.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                            items(messages) { msg -> ChatBubble(msg, generating) }
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
                        PrimaryButton(
                            onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                            enabled = ready && input.isNotBlank(),
                            text = "SEND",
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
private fun ChatBubble(msg: ChatMessage, generating: Boolean) {
    val hw = LocalHwColors.current
    val isUser = msg.role == ChatMessage.Role.USER
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
                color = if (isUser) hw.onGradient else hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!isUser) Spacer(Modifier.weight(1f))
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
