package com.hardwareone.console.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.ControlsModule
import com.hardwareone.console.ble.MediaBlob
import com.hardwareone.console.ble.MicStatus
import com.hardwareone.console.ble.RecordingItem
import com.hardwareone.console.ui.theme.LocalHwColors
import java.io.File

private val CardShape = RoundedCornerShape(14.dp)

/**
 * Microphone page. The live VU meter IS real over BLE (`miclevel json` polled). Audio itself can't
 * stream over BLE, so recordings are captured to a WAV on the device (`micrecord`), pulled back via
 * the windowed binary `fileread`, then played locally with [MediaPlayer].
 */
@Composable
fun MicScreen(
    status: MicStatus?,
    level: Int,
    recordings: List<RecordingItem>?,
    audioClip: MediaBlob?,
    controls: ControlsModule?,
    controlsLoading: Boolean,
    mediaBusy: Boolean,
    error: String?,
    onToggleMic: (Boolean) -> Unit,
    onStartRec: () -> Unit,
    onStopRec: () -> Unit,
    onLoadControls: () -> Unit,
    onSetControl: (key: String, token: String) -> Unit,
    onPlay: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onClearClip: () -> Unit,
    onRefresh: () -> Unit,
    nav: HeaderNav,
) {
    val hw = LocalHwColors.current
    val context = LocalContext.current

    // One reused player; auto-plays each freshly-pulled clip, released when the screen leaves.
    val player = remember { MediaPlayer() }
    var nowPlaying by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(audioClip) {
        val clip = audioClip ?: return@LaunchedEffect
        runCatching {
            val f = File(context.cacheDir, "hw1_clip.wav").apply { writeBytes(clip.bytes) }
            player.reset()
            player.setDataSource(f.absolutePath)
            player.prepare()
            player.start()
            nowPlaying = clip.name
            player.setOnCompletionListener { nowPlaying = null }
        }.onFailure { nowPlaying = null }
    }
    fun stopPlayback() {
        runCatching { if (player.isPlaying) player.stop(); player.reset() }
        nowPlaying = null
        onClearClip()
    }

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = mediaBusy, onRefresh = onRefresh)
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    nowPlaying?.let { NowPlayingCard(it) { stopPlayback() } }
                    error?.let { MediaBanner(it) }

                    when {
                        status == null -> MediaBanner("Reading microphone… (needs a connected, logged-in device)")
                        // The mic has NO detection layer: PDM over I2S is unprobeable, so the
                        // device can only report what was compiled in. This is therefore a
                        // statement about the build, never about whether hardware is present.
                        status.noSourceCompiledIn ->
                            MediaBanner("No microphone in this build — the firmware was built without onboard mic support, and no G2 glasses are connected.")
                        else -> {
                            if (!status.connected) {
                                MediaBanner("Microphone is stopped. Start it below — it doesn't auto-start unless enabled in device setup.")
                            }
                            StatusCard(status, level, onToggleMic, onStartRec, onStopRec)
                            if (status.enabled) {
                                TuningPanel(controls, controlsLoading, onLoadControls, onSetControl)
                            }
                        }
                    }

                    RecordingsSection(recordings, mediaBusy, onPlay, onDelete)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(name: String, onStop: () -> Unit) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.accent, CardShape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶ $name", color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("■ stop", color = hw.accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { onStop() })
    }
}

@Composable
private fun StatusCard(status: MicStatus, level: Int, onToggle: (Boolean) -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Microphone", color = hw.onGradient, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    MediaChip("${status.sampleRate} Hz", false)
                    MediaChip("${status.bitDepth}-bit", false)
                    MediaChip(if (status.channels == 1) "mono" else "${status.channels}ch", false)
                    if (status.recording) MediaChip("● rec", true)
                }
            }
            Switch(
                checked = status.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = hw.onGradient, checkedTrackColor = hw.accent, uncheckedThumbColor = hw.muted, uncheckedTrackColor = hw.cardBg),
            )
        }
        if (status.enabled) {
            VuMeter(if (status.enabled) level else 0)
            if (status.recording) {
                SecondaryButton(onClick = onStop, text = "■ Stop recording")
            } else {
                PrimaryButton(onClick = onStart, text = "● Record")
            }
        } else {
            MediaNote("Turn the mic on to meter levels and record.")
        }
    }
}

@Composable
private fun VuMeter(level: Int) {
    val hw = LocalHwColors.current
    val pct = level.coerceIn(0, 100)
    val fill = when {
        pct >= 90 -> hw.danger
        pct >= 70 -> Color(0xFFE0A800) // amber
        else -> hw.success
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Level", color = hw.muted, style = MaterialTheme.typography.labelMedium)
            Text("$pct%", color = hw.onGradient, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(hw.cardBorder)) {
            Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(fill))
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
            Text("Capture format", color = hw.muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "▴" else "▾", color = hw.muted)
        }
        if (expanded) {
            when {
                loading && controls == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = hw.onGradient, strokeWidth = 2.dp)
                    Text("Loading…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                }
                controls == null -> MediaNote("No reply — needs an admin login.")
                controls.entries.isEmpty() -> MediaNote("No adjustable settings.")
                else -> controls.entries.forEach { ce -> ControlEntryRow(ce) { token -> onSet(ce.key, token) } }
            }
        }
    }
}

@Composable
private fun RecordingsSection(recordings: List<RecordingItem>?, busy: Boolean, onPlay: (RecordingItem) -> Unit, onDelete: (RecordingItem) -> Unit) {
    val hw = LocalHwColors.current
    MediaSectionLabel("Recordings" + (recordings?.let { " (${it.size})" } ?: ""))
    when {
        recordings == null -> MediaNote("Loading recordings…")
        recordings.isEmpty() -> MediaNote("No recordings yet.")
        else -> recordings.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(CardShape).background(hw.cardBg).border(1.dp, hw.cardBorder, CardShape)
                    .clickable(enabled = !busy) { onPlay(item) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.filename, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.size / 1024} KB", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("▶ play", color = hw.accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Text("🗑", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onDelete(item) }.padding(start = 4.dp))
            }
        }
    }
}
