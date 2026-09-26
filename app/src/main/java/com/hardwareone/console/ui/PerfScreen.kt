package com.hardwareone.console.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.FsUsage
import com.hardwareone.console.ble.MemReport
import com.hardwareone.console.ble.PerfTop
import com.hardwareone.console.ble.TaskStats
import com.hardwareone.console.ui.theme.LocalHwColors

/** The three sub-views, mirroring the OLED Perf mode's CPU/Stack pages + a memory profiler. */
enum class PerfPage(val label: String) { CPU("CPU"), STACK("Stack"), MEMORY("Memory") }

private val Amber = Color(0xFFE0A800) // mid-band warning (matches BatteryGlyph)

/**
 * Live performance + memory profiler — the Android counterpart of the device's OLED "Perf" mode.
 * Three pages: CPU (loop health + per-task CPU% bars from `perftop json`), Stack (per-task
 * min-free from `taskstats json`), Memory (DRAM/PSRAM/flash gauges from `memreport`/`fsusage`).
 * Stateless: the active [page] and polling are driven by the caller (MainActivity), so only the
 * visible page's command is polled — the CPU page's `perftop` samples ~0.75 s on-device.
 */
@Composable
fun PerfScreen(
    perfTop: PerfTop?,
    taskStats: TaskStats?,
    taskStatsError: String?,
    memReport: MemReport?,
    fsUsage: FsUsage?,
    page: PerfPage,
    onSelectPage: (PerfPage) -> Unit,
    onRefresh: () -> Unit,
    nav: HeaderNav,
) {
    val hw = LocalHwColors.current
    val loading = when (page) {
        PerfPage.CPU -> perfTop == null
        // A failed fetch is not still loading — without this the header spinner runs forever.
        PerfPage.STACK -> taskStats == null && taskStatsError == null
        PerfPage.MEMORY -> memReport == null && fsUsage == null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(hw.gradient)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, busy = loading, onRefresh = onRefresh)
                Spacer(Modifier.height(8.dp))
                PerfTabs(page, onSelectPage)
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (page) {
                            PerfPage.CPU -> CpuPage(perfTop)
                            PerfPage.STACK -> StackPage(taskStats, taskStatsError)
                            PerfPage.MEMORY -> MemoryPage(memReport, fsUsage)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PerfTabs(page: PerfPage, onSelect: (PerfPage) -> Unit) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PerfPage.values().forEach { p ->
            val selected = p == page
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) hw.onGradient else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = p.label,
                    color = if (selected) Color.Black else hw.onGradient,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// CPU page — loop health strip + per-task CPU% bars (mirrors the OLED Perf CPU page)
// ---------------------------------------------------------------------------------------------
@Composable
private fun CpuPage(perf: PerfTop?) {
    val hw = LocalHwColors.current
    if (perf == null) { PerfLoading("Sampling CPU…"); return }

    SectionCard("Main loop") {
        val l = perf.loop
        if (!l.valid) {
            InfoRow("Loop", "warming up…")
        } else {
            InfoRow("Rate", "${l.lapsPerSec} laps/s")
            InfoRow("Period", "${l.avgMs} ms avg · ${l.maxMs} ms max")
            InfoRow("Stalls (5s)", l.stalls5s.toString())
            InfoRow("Stalls (total)", l.totalStalls.toString())
        }
    }

    val idle = perf.tasks.filter { it.idle }.sortedBy { it.name }
    val busy = perf.tasks.filter { !it.idle }.sortedByDescending { it.cpuPct }

    if (idle.isNotEmpty()) {
        // Per-core IDLE% = free headroom; green fill reads "lots spare".
        SectionCard("Core headroom (idle)") {
            idle.forEach { t -> CpuRow(t.name, t.cpuPct, idle = true) }
        }
    }

    SectionCard("Tasks by CPU") {
        when {
            !perf.cpuValid -> Text("Measuring…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
            busy.isEmpty() -> Text(
                text = "All non-idle tasks <1% this interval.",
                color = hw.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> busy.forEach { t -> CpuRow(t.name, t.cpuPct, idle = false) }
        }
    }
}

@Composable
private fun CpuRow(name: String, pct: Int, idle: Boolean) {
    val hw = LocalHwColors.current
    val color = when {
        idle -> hw.success            // idle = headroom; green = plenty spare
        pct >= 70 -> hw.danger
        pct >= 40 -> Amber
        else -> hw.accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = name,
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "$pct%",
                color = hw.muted,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        MeterBar(fraction = pct / 100f, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Stack page — per-task min-free, riskiest first (mirrors the OLED Perf Stack page)
// ---------------------------------------------------------------------------------------------
/**
 * Bytes of headroom below which a task is treated as fully "at risk" — the reference the
 * fallback bar fills against. A task with this much free reads as empty; 0 free reads as full.
 */
private const val STACK_DANGER_FLOOR = 2048f

@Composable
private fun StackPage(stats: TaskStats?, error: String?) {
    val hw = LocalHwColors.current
    if (stats == null) {
        // Say what went wrong instead of spinning. The likeliest cause is a reply too big for the
        // device's command buffer, which is silent on the wire — see onTaskStatsCapture.
        if (error != null) {
            SectionCard("Stack") {
                Text(error, color = hw.danger, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Tap refresh to try again.",
                    color = hw.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            PerfLoading("Reading task stacks…")
        }
        return
    }
    // Lowest headroom first = closest to overflowing, whichever figure we can show.
    val tasks = stats.tasks.sortedBy { it.stackFree }
    // The device reports peak consumption only if it also sends each stack's total size; ESP-IDF's
    // task API exposes only the high-water mark, so without that field "used" is unknowable and we
    // fall back to headroom. See TaskInfo.
    val showUsed = tasks.any { it.stackUsed != null }

    val title = if (showUsed) "Stack used · ${stats.total} tasks" else "Stack headroom · ${stats.total} tasks"
    SectionCard(title) {
        Text(
            text = if (showUsed) {
                "Peak bytes each task has consumed, of its total stack. Fuller bar = less room left."
            } else {
                "Lowest free bytes each task has ever had. Fuller bar = closer to overflow."
            },
            color = hw.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (tasks.isEmpty()) {
            Text("No task data.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
        } else tasks.forEach { t ->
            val risky = t.stackFree < 512
            val warn = t.stackFree < 1024
            val color = when { risky -> hw.danger; warn -> Amber; else -> hw.success }
            val used = t.stackUsed
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (risky) "! ${t.name}" else t.name,
                        color = if (risky) hw.danger else hw.onGradient,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        // Spell out which direction the number runs; a bare byte count next to a
                        // bar was the ambiguity here.
                        text = if (used != null) "$used / ${t.stackSize} B" else "${t.stackFree} B free",
                        color = hw.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                // Both bars fill as the situation worsens, matching the CPU page's usage bars. The
                // old bar did the opposite — longest for the roomiest task — so a healthy task drew
                // the biggest bar on a page you read to find trouble.
                MeterBar(
                    fraction = if (used != null && t.stackSize > 0) {
                        used.toFloat() / t.stackSize
                    } else {
                        1f - (t.stackFree.toFloat() / STACK_DANGER_FLOOR).coerceIn(0f, 1f)
                    },
                    color = color,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Memory page — DRAM / PSRAM / flash gauges (the memreport + fsusage profiler)
// ---------------------------------------------------------------------------------------------
@Composable
private fun MemoryPage(mem: MemReport?, fs: FsUsage?) {
    val hw = LocalHwColors.current
    if (mem == null && fs == null) { PerfLoading("Reading memory…"); return }

    mem?.let { m ->
        SectionCard("Internal RAM (DRAM)") {
            GaugeRow("Used", m.dramUsed, m.dramTotal)
            InfoRow("Free", bytesLabel(m.dramFree))
            InfoRow("Min ever free", bytesLabel(m.dramMinFree))
            InfoRow("Largest block", bytesLabel(m.internalLargest))
        }
        if (m.psramAvailable) {
            SectionCard("PSRAM") {
                GaugeRow("Used", m.psramUsed, m.psramTotal)
                InfoRow("Free", bytesLabel(m.psramFree))
            }
        }
    }
    fs?.let { f ->
        SectionCard("Filesystem (flash)") {
            if (!f.ready) {
                InfoRow("Status", "not ready")
            } else {
                GaugeRow("Used", f.usedBytes, f.totalBytes)
                InfoRow("Free", bytesLabel(f.freeBytes))
            }
        }
    }
}

/** A labelled used/total capacity gauge (fills amber ≥75%, red ≥90%). */
@Composable
private fun GaugeRow(label: String, used: Long, total: Long) {
    val hw = LocalHwColors.current
    val frac = if (total > 0) used.toFloat() / total else 0f
    val color = when { frac >= 0.9f -> hw.danger; frac >= 0.75f -> Amber; else -> hw.success }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${bytesLabel(used)} / ${bytesLabel(total)} (${(frac * 100).toInt()}%)",
                color = hw.onGradient,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        MeterBar(fraction = frac, color = color, height = 10.dp)
    }
}

// ---------------------------------------------------------------------------------------------
// Shared atoms
// ---------------------------------------------------------------------------------------------

/** A horizontal fill bar over a track — generalised from [BatteryGlyph]. */
@Composable
private fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val hw = LocalHwColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2f))
            .background(hw.cardBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height / 2f))
                .background(color),
        )
    }
}

@Composable
private fun PerfLoading(message: String) {
    val hw = LocalHwColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = hw.onGradient, strokeWidth = 3.dp)
        Text(message, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun bytesLabel(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024))
}
