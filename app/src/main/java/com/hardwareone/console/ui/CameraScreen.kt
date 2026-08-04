package com.hardwareone.console.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.CameraStatus
import com.hardwareone.console.ble.ControlsModule
import com.hardwareone.console.ble.ImageItem
import com.hardwareone.console.ble.MediaBlob
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Camera page. Live preview isn't possible over BLE (frames are HTTP-only), so this drives
 * capture-to-file + tuning + a saved-photo browser: `camerasave` writes a JPEG on the device, and
 * the app pulls it back over the windowed binary `fileread` to display it. The viewer is inline
 * (not a Dialog) so it stays inside the app's FLAG_SECURE window.
 */
@Composable
fun CameraScreen(
    status: CameraStatus?,
    gallery: List<ImageItem>?,
    viewedImage: MediaBlob?,
    controls: ControlsModule?,
    controlsLoading: Boolean,
    mediaBusy: Boolean,
    error: String?,
    onToggleCamera: (Boolean) -> Unit,
    onCapture: () -> Unit,
    onLoadControls: () -> Unit,
    onSetControl: (key: String, token: String) -> Unit,
    onViewImage: (ImageItem) -> Unit,
    onDeleteImage: (ImageItem) -> Unit,
    onCloseImage: () -> Unit,
    onRefresh: () -> Unit,
    nav: HeaderNav,
) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = mediaBusy, onRefresh = onRefresh)
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (viewedImage != null) ImageViewerCard(viewedImage, onCloseImage)
                    error?.let { MediaBanner(it) }

                    when {
                        status == null -> MediaBanner("Reading camera… (needs a connected, logged-in device)")
                        !status.connected -> MediaBanner("No camera detected on this device.")
                        else -> {
                            StatusCard(status, onToggleCamera, onCapture, mediaBusy)
                            if (status.enabled) {
                                TuningPanel(controls, controlsLoading, onLoadControls, onSetControl)
                            }
                        }
                    }

                    GallerySection(gallery, onViewImage, onDeleteImage)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ImageViewerCard(blob: MediaBlob, onClose: () -> Unit) {
    val hw = LocalHwColors.current
    val bitmap = remember(blob.bytes) {
        runCatching { BitmapFactory.decodeByteArray(blob.bytes, 0, blob.bytes.size)?.asImageBitmap() }.getOrNull()
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(blob.name, color = hw.onGradient, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("✕ close", color = hw.accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { onClose() })
        }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = blob.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
        } else {
            MediaNote("Couldn't decode this image (${blob.bytes.size} bytes).")
        }
        Text("${blob.bytes.size / 1024} KB · ${blob.path}", color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusCard(status: CameraStatus, onToggle: (Boolean) -> Unit, onCapture: () -> Unit, busy: Boolean) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.model, color = hw.onGradient, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    MediaChip(status.resolution, false)
                    MediaChip(if (status.psram) "psram" else "no-psram", status.psram)
                    MediaChip(if (status.enabled) "on" else "off", status.enabled)
                }
            }
            Switch(
                checked = status.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = hw.onGradient, checkedTrackColor = hw.accent, uncheckedThumbColor = hw.muted, uncheckedTrackColor = hw.cardBg),
            )
        }
        if (status.enabled) {
            PrimaryButton(onClick = onCapture, enabled = !busy, text = if (busy) "Working…" else "Take photo")
        } else {
            MediaNote("Turn the camera on to capture photos.")
        }
    }
}

@Composable
private fun TuningPanel(controls: ControlsModule?, loading: Boolean, onLoad: () -> Unit, onSet: (String, String) -> Unit) {
    val hw = LocalHwColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape)
            .clickable { expanded = !expanded; if (expanded && controls == null && !loading) onLoad() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Image tuning", color = hw.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "▴" else "▾", color = hw.muted)
        }
        if (expanded) {
            when {
                loading && controls == null -> LoadingRow()
                controls == null -> MediaNote("No reply — needs an admin login.")
                controls.entries.isEmpty() -> MediaNote("No adjustable settings.")
                else -> controls.entries.forEach { ce -> ControlEntryRow(ce) { token -> onSet(ce.key, token) } }
            }
        }
    }
}

@Composable
private fun GallerySection(gallery: List<ImageItem>?, onView: (ImageItem) -> Unit, onDelete: (ImageItem) -> Unit) {
    val hw = LocalHwColors.current
    MediaSectionLabel("Saved photos" + (gallery?.let { " (${it.size})" } ?: ""))
    when {
        gallery == null -> MediaNote("Loading gallery…")
        gallery.isEmpty() -> MediaNote("No photos yet — take one above.")
        else -> gallery.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape)
                    .clickable { onView(item) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.filename, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.size / 1024} KB · ${item.location}", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("view", color = hw.accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Text("🗑", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onDelete(item) }.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    val hw = LocalHwColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = hw.onGradient, strokeWidth = 2.dp)
        Text("Loading…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- small shared presentational helpers (local to the media screens) ------------------

@Composable
internal fun MediaChip(text: String, active: Boolean) {
    val hw = LocalHwColors.current
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(hw.cardBg).border(1.dp, if (active) hw.success else hw.cardBorder, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = if (active) hw.success else hw.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun MediaSectionLabel(text: String) {
    Text(text, color = LocalHwColors.current.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun MediaNote(text: String) {
    Text(text, color = LocalHwColors.current.muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun MediaBanner(text: String) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
    }
}
