package com.hardwareone.console.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.FilePerm
import com.hardwareone.console.ui.theme.LocalHwColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(14.dp)

/**
 * BLE file browser. Lists directories, shows storage, views text files, uploads from the phone,
 * and manages files (new folder/file, rename, delete). All actions are gated by the per-entry /
 * per-directory permission bitmask reported by the firmware (admin + secure channel required).
 */
@Composable
fun FilesScreen(vm: ConsoleViewModel, nav: HeaderNav) {
    val hw = LocalHwColors.current
    val path by vm.filesPath.collectAsState()
    val listing by vm.fileListing.collectAsState()
    val stats by vm.fileStats.collectAsState()
    val busy by vm.filesBusy.collectAsState()
    val viewer by vm.fileViewer.collectAsState()
    val transfer by vm.fileTransfer.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = queryDisplayName(context, uri)
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) withContext(Dispatchers.Main) { vm.uploadFile(vm.joinPath(path, name), bytes) }
        }
    }

    // Download: once a file has been read off the device, the system "Save as" picker opens;
    // on a chosen destination we write the bytes there.
    val downloadReady by vm.downloadReady.collectAsState()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = vm.downloadReady.value
        if (uri != null && pending != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second) } }
        }
        vm.clearDownload()
    }
    LaunchedEffect(downloadReady) {
        downloadReady?.let { saver.launch(it.first) }
    }

    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val canCreate = listing?.canDir(FilePerm.CREATE) == true
    val canImport = listing?.canDir(FilePerm.IMPORT) == true

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header: switcher + refresh (spinner while busy).
                AppHeader(nav, busy = busy, onRefresh = { vm.loadFiles(path) })

                // Path + storage.
                Text(path, color = hw.muted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                stats?.let { StorageBar(it) }

                // Toolbar.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (path != "/") FileToolButton("Up") { vm.navigateUp() }
                    if (canCreate) {
                        FileToolButton("+ Folder") { newFolder = true }
                        FileToolButton("+ File") { newFile = true }
                    }
                    if (canImport) FileToolButton("Upload") { picker.launch("*/*") }
                }

                listing?.error?.let {
                    Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }

                val entries = listing?.entries.orEmpty()
                if (listing?.success == true && entries.isEmpty()) {
                    Text("Empty folder.", color = hw.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries) { e ->
                        FileRow(
                            entry = e,
                            onOpen = {
                                if (e.isDir) vm.openDir(e.name)
                                else if (e.can(FilePerm.READ)) vm.openFile(e.name)
                            },
                            onDownload = if (!e.isDir && e.can(FilePerm.READ)) ({ vm.downloadFile(e.name) }) else null,
                            onRename = if (e.can(FilePerm.RENAME)) ({ renameTarget = e.name }) else null,
                            onDelete = if (e.can(FilePerm.DELETE)) ({ deleteTarget = e.name }) else null,
                        )
                    }
                }
            }
        }

        viewer?.let { FileViewerOverlay(it.path, it.text, it.binary, it.size) { vm.closeFileViewer() } }
        transfer?.let {
            TransferDialog(
                it.name, it.done, it.total, it.finished, it.error, it.upload,
                onStop = { vm.cancelTransfer() },
                onDismiss = { vm.dismissTransfer() },
            )
        }
    }

    if (newFolder) TextInputDialog("New folder", "name", "CREATE", { newFolder = false }) { vm.makeDir(it); newFolder = false }
    if (newFile) TextInputDialog("New file", "name", "CREATE", { newFile = false }) { vm.createFile(it); newFile = false }
    renameTarget?.let { old ->
        TextInputDialog("Rename", "new name", "RENAME", { renameTarget = null }, initial = old) { vm.renameFile(old, it); renameTarget = null }
    }
    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete") },
            text = { Text("Delete \"$name\"? This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteFile(name); deleteTarget = null }) { Text("DELETE") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun StorageBar(stats: com.hardwareone.console.ble.FileStats) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage", color = hw.muted, style = MaterialTheme.typography.labelMedium)
            Text(
                "${humanBytes(stats.used)} / ${humanBytes(stats.total)} (${stats.usagePercent}%)",
                color = hw.onGradient, style = MaterialTheme.typography.bodySmall,
            )
        }
        ProgressBar(stats.usagePercent / 100f, hw.accent, hw.cardBorder)
    }
}

@Composable
private fun FileRow(
    entry: com.hardwareone.console.ble.FileEntry,
    onOpen: () -> Unit,
    onDownload: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val hw = LocalHwColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape).clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (entry.isDir) "📁" else "📄", modifier = Modifier.padding(end = 10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name + if (entry.isDir) "/" else "",
                color = hw.onGradient, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(entry.sizeLabel, color = hw.muted, style = MaterialTheme.typography.labelSmall)
        }
        if (onDownload != null || onRename != null || onDelete != null) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Text("⋮", color = hw.onGradient, style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onDownload != null) DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
                    if (onRename != null) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    if (onDelete != null) DropdownMenuItem(text = { Text("Delete", color = hw.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FileViewerOverlay(path: String, text: String, binary: Boolean, size: Long, onClose: () -> Unit) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_arrow_back), "Close", tint = hw.onGradient)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(path.substringAfterLast('/'), color = hw.onGradient, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${humanBytes(size)}", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (binary) {
                Text(
                    "Binary file (${humanBytes(size)}) — not viewable as text.",
                    color = hw.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                // Pretty-print JSON (the firmware stores it minified, so it renders as one long
                // wrapping blob otherwise). Falls back to the raw text for non-JSON / invalid JSON.
                val display = remember(path, text) { prettyJson(path, text) ?: text }
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = display.ifEmpty { "(empty file)" },
                        color = hw.onGradient,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferDialog(
    name: String,
    done: Long,
    total: Long,
    finished: Boolean,
    error: String?,
    upload: Boolean,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = if (upload) "Upload" else "Download"
    AlertDialog(
        onDismissRequest = { if (finished) onDismiss() },
        title = { Text(if (error != null) "$verb failed" else if (finished) "${verb}ed" else "${verb}ing…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Use theme-surface colours (the dialog sits on the Material surface, not the
                // app gradient) so the text stays readable in both light and dark themes.
                Text(
                    name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    ProgressBar(
                        if (total > 0) done.toFloat() / total else 0f,
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${humanBytes(done)} / ${humanBytes(total)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            if (finished) TextButton(onClick = onDismiss) { Text("CLOSE") }
            else TextButton(onClick = onStop) { Text("STOP") }
        },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    confirmText: String,
    onDismiss: () -> Unit,
    initial: String = "",
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/** Plain fill bar (no Material3 stop-indicator dot). A 0 fraction renders nothing. */
@Composable
private fun ProgressBar(fraction: Float, color: Color, track: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(track),
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(modifier = Modifier.fillMaxWidth(f).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun FileToolButton(text: String, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, hw.cardBorder),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "upload.bin"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { name = it }
            }
        }
    }
    return name
}

/** Pretty-print JSON (2-space indent) for the viewer; null if it isn't JSON or won't parse. */
private fun prettyJson(path: String, text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val looksJson = path.endsWith(".json", ignoreCase = true) || t.startsWith("{") || t.startsWith("[")
    if (!looksJson) return null
    return runCatching { org.json.JSONObject(t).toString(2) }.getOrNull()
        ?: runCatching { org.json.JSONArray(t).toString(2) }.getOrNull()
}

private fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024))
}
