package com.hardwareone.console.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hardwareone.console.security.SavedLog
import com.hardwareone.console.ui.theme.LocalHwColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun SavedLogsScreen(
    logs: List<SavedLog>,
    storageLocation: String,
    onOpen: (SavedLog) -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    var showInfo by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                        text = "Saved logs",
                        color = hw.onGradient,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "About saved logs",
                            tint = hw.onGradient,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))

                if (logs.isEmpty()) {
                    Text(
                        text = "No saved logs yet. Use SAVE on the console, or enable " +
                            "auto-save in Settings.",
                        color = hw.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(logs, key = { it.fileName }) { log ->
                            SavedLogRow(log, onOpen = { onOpen(log) })
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        val totalBytes = logs.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
            title = { Text("About saved logs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Saved logs live in this app's private storage:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = storageLocation,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "That folder isn't visible in a file manager (it's app-private, " +
                            "so no other app can reach it) and each file is encrypted at rest — " +
                            "its contents are AES-encrypted with a key wrapped by the Android " +
                            "Keystore, on top of the OS's file-based encryption. To read one, " +
                            "open it here (you'll be asked to authenticate); use a viewer's " +
                            "Export for a plaintext copy.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Total: ${formatSize(totalBytes)} across ${logs.size} " +
                            if (logs.size == 1) "file" else "files",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
        )
    }
}

@Composable
private fun SavedLogRow(log: SavedLog, onOpen: () -> Unit) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTimestamp(log.lastModified) + if (log.isAuto) "  ·  auto" else "",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = formatSize(log.sizeBytes),
                color = hw.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text("›", color = hw.muted, style = MaterialTheme.typography.titleLarge)
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
