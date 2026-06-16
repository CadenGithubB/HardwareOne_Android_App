package com.hardwareone.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.EspNowChatLine
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * ESP-NOW per-peer message feed: incoming messages (and relayed results) from one paired device,
 * plus a box to send it a message (`espnowsend`). A drill-down off the ESP-NOW page, so it keeps a
 * back arrow rather than the switcher.
 */
@Composable
fun EspNowDeviceScreen(
    deviceName: String,
    mac: String,
    feed: List<EspNowChatLine>,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    var input by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                // Back header (drill-down, not a switcher page).
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

                // Feed (auto-scrolls to newest).
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
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(feed) { line -> ChatBubble(line) }
                        }
                    }
                }

                // Input bar.
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = hw.onGradient,
                            unfocusedTextColor = hw.onGradient,
                            focusedContainerColor = hw.cardBg,
                            unfocusedContainerColor = hw.cardBg,
                            cursorColor = hw.accent,
                            focusedBorderColor = hw.accent,
                            unfocusedBorderColor = hw.cardBorder,
                            focusedPlaceholderColor = hw.muted,
                            unfocusedPlaceholderColor = hw.muted,
                        ),
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
    }
}

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
        }
    }
}
