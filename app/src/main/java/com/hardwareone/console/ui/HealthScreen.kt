package com.hardwareone.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.HealthLog
import com.hardwareone.console.ble.HealthSample
import com.hardwareone.console.ble.HealthStatus
import com.hardwareone.console.ui.theme.LocalHwColors

/**
 * R1 ring health.
 *
 * The ring shares the device's BLE radio with this app, so it is normally NOT connected while
 * you're looking at this — live vitals will read stale or empty, and that is expected rather than
 * a fault. What this page is really for is the data the device already recorded to its sensor log
 * while the ring was on.
 *
 * The ring's richer stored history — hourly aggregates, activity buckets, sleep — is not shown
 * because the firmware has no way to read those files back yet (the store is write-only; even the
 * device's own web UI only reports sync status for them).
 */
@Composable
fun HealthScreen(
    status: HealthStatus?,
    log: HealthLog?,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onToggleLogging: (Boolean) -> Unit,
    nav: HeaderNav,
) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = busy, onRefresh = onRefresh)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RingStateCard(status, onToggleLogging)
                    error?.let {
                        SectionCard("Recorded data") {
                            Text(it, color = hw.danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    log?.samples?.takeIf { it.isNotEmpty() }?.let { RecordedCards(it) }
                    if (log == null && error == null && !busy) {
                        SectionCard("Recorded data") {
                            Text(
                                "Tap refresh to pull the ring readings the device has logged.",
                                color = hw.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun RingStateCard(status: HealthStatus?, onToggleLogging: (Boolean) -> Unit) {
    val hw = LocalHwColors.current
    SectionCard("Ring") {
        if (status == null) {
            Text("No status yet.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        StatRow("Connected", if (status.ringConnected) "yes" else "no — expected while the phone holds the link")
        StatRow("Setup", status.setupState)
        StatRow("History sync", status.historyState)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Health logging", color = hw.onGradient, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(if (status.loggingActive) "recording" else "idle")
                        if (status.pollIntervalSec > 0) append(" · every ${status.pollIntervalSec}s")
                        if (!status.r1Selected) append(" · 'r1' NOT in the log selection")
                    },
                    color = if (status.r1Selected) hw.muted else hw.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(checked = status.loggingEnabled, onCheckedChange = onToggleLogging)
        }
    }
}

@Composable
private fun RecordedCards(samples: List<HealthSample>) {
    val hw = LocalHwColors.current
    MetricCard("Heart rate", "bpm", samples.mapNotNull { it.hr?.toFloat() }, hw.danger)
    MetricCard("HRV", "ms", samples.mapNotNull { it.hrv?.toFloat() }, hw.link)
    MetricCard("SpO₂", "%", samples.mapNotNull { it.spo2?.toFloat() }, hw.success)
    MetricCard("Skin temperature", "°C", samples.mapNotNull { it.tempC?.toFloat() }, hw.accent)
    MetricCard("Ring battery", "%", samples.mapNotNull { it.battery?.toFloat() }, hw.success)

    SectionCard("Log") {
        StatRow("Samples with ring data", samples.size.toString())
        samples.lastOrNull()?.time?.takeIf { it.isNotEmpty() }?.let { StatRow("Most recent", it) }
        val worn = samples.count { it.wear == 2 }
        StatRow("Worn", "$worn of ${samples.size} samples")
    }
}

/** One metric: latest/min/max plus its trace. Absent metrics are omitted, not shown empty. */
@Composable
private fun MetricCard(title: String, unit: String, values: List<Float>, color: Color) {
    if (values.isEmpty()) return
    val hw = LocalHwColors.current
    val latest = values.last()
    SectionCard("$title · ${fmt(latest)} $unit") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("min ${fmt(values.min())}", color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("max ${fmt(values.max())}", color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("avg ${fmt(values.average().toFloat())}", color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("n=${values.size}", color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.size(6.dp))
        Sparkline(values, color)
    }
}

/** Trace of [values] scaled to its own min/max. Flat series draw a centred line, not a divide-by-zero. */
@Composable
private fun Sparkline(values: List<Float>, color: Color) {
    val hw = LocalHwColors.current
    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp).background(hw.terminalBg)) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0001f }
        val stepX = size.width / (values.size - 1).toFloat()
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = if (span == null) size.height / 2f else size.height * (1f - (v - lo) / span)
            // Inset by the stroke so peaks and troughs aren't clipped at the edges.
            val yy = y.coerceIn(1.5f, size.height - 1.5f)
            if (i == 0) path.moveTo(0f, yy) else path.lineTo(i * stepX, yy)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val hw = LocalHwColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = hw.onGradient,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)
