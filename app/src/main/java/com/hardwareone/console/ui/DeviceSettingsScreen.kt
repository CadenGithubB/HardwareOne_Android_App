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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.ControlsModule
import com.hardwareone.console.ui.theme.LocalHwColors

private val CardShape = RoundedCornerShape(14.dp)

/** Display grouping for the device settings modules. */
private enum class ModuleGroup(val title: String) {
    CONNECTIVITY("Connectivity"),
    SYSTEM("System"),
    HARDWARE("Hardware"),
    APPS("Apps & features"),
    LOGGING("Logging"),
    OTHER("Other"),
}

private data class ModuleMeta(val id: String, val label: String, val group: ModuleGroup)

// Curated device (non-sensor) settings modules, in display order. `controls json` lists EVERY
// registered firmware settings module — sensors included — but sensor modules are edited on the
// Sensors page, so this screen shows the device/system ones. `id` is the firmware SettingsModule
// name (the `controls json <id>` argument). Any non-sensor module the firmware adds later that
// isn't listed here still appears, under the OTHER group.
private val DEVICE_MODULES = listOf(
    ModuleMeta("wifi", "Wi-Fi", ModuleGroup.CONNECTIVITY),
    ModuleMeta("http", "Web server", ModuleGroup.CONNECTIVITY),
    ModuleMeta("bluetooth", "Bluetooth", ModuleGroup.CONNECTIVITY),
    ModuleMeta("espnow", "ESP-NOW", ModuleGroup.CONNECTIVITY),
    ModuleMeta("mqtt", "MQTT", ModuleGroup.CONNECTIVITY),
    ModuleMeta("power", "Power", ModuleGroup.SYSTEM),
    ModuleMeta("notifications", "Notifications", ModuleGroup.SYSTEM),
    ModuleMeta("output", "Output routing", ModuleGroup.SYSTEM),
    ModuleMeta("cli", "CLI", ModuleGroup.SYSTEM),
    ModuleMeta("batteryLog", "Battery logging", ModuleGroup.SYSTEM),
    ModuleMeta("crash", "Crash handling", ModuleGroup.SYSTEM),
    ModuleMeta("debug", "Debug", ModuleGroup.SYSTEM),
    ModuleMeta("led", "LED / NeoPixel", ModuleGroup.HARDWARE),
    ModuleMeta("oled", "Display (OLED)", ModuleGroup.HARDWARE),
    ModuleMeta("i2c", "I²C bus", ModuleGroup.HARDWARE),
    ModuleMeta("maps", "Maps", ModuleGroup.APPS),
    ModuleMeta("llm", "LLM", ModuleGroup.APPS),
    ModuleMeta("automation", "Automations", ModuleGroup.APPS),
    ModuleMeta("edgeimpulse", "Edge Impulse", ModuleGroup.APPS),
    ModuleMeta("espsr", "Speech (ESP-SR)", ModuleGroup.APPS),
    ModuleMeta("sensorlog", "Sensor logging", ModuleGroup.LOGGING),
    ModuleMeta("systemlog", "System logging", ModuleGroup.LOGGING),
)

// Sensor modules the Sensors page already exposes — kept out of the OTHER bucket so this page
// stays device/system-focused and doesn't duplicate the Sensors page.
private val SENSOR_MODULES = setOf(
    "thermal", "tof", "imu", "input", "anoencoder", "apds", "gps",
    "fmradio", "rtc", "presence", "camera", "microphone",
)

/**
 * Device settings page: a generic editor over the firmware's `controls json` module registry.
 * Each card is one settings module; expanding it lazily fetches `controls json <module>` and renders
 * the entries with the same self-describing widgets the Sensors page uses (slider/toggle/select/
 * text), two-way-bound to the live values. Secrets (passwords, keys) are never emitted by the
 * firmware, so they can't be edited here — those stay device-side by design.
 */
@Composable
fun DeviceSettingsScreen(
    modules: Set<String>,
    controls: Map<String, ControlsModule>,
    controlsLoading: Set<String>,
    onLoadControls: (id: String) -> Unit,
    onSetControl: (moduleId: String, key: String, token: String) -> Unit,
    onRefresh: () -> Unit,
    nav: HeaderNav,
) {
    val hw = LocalHwColors.current

    // Case-insensitive presence test against the firmware's module list.
    fun present(id: String) = modules.any { it.equals(id, ignoreCase = true) }

    val shown = DEVICE_MODULES.filter { present(it.id) }
    val curatedIds = DEVICE_MODULES.map { it.id.lowercase() }.toSet()
    val other = modules
        .filter { it.lowercase() !in curatedIds && it.lowercase() !in SENSOR_MODULES }
        .sorted()
        .map { ModuleMeta(it, it, ModuleGroup.OTHER) }
    val byGroup = (shown + other).groupBy { it.group }

    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                AppHeader(nav, onRefresh = onRefresh)

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (modules.isEmpty()) {
                        Banner("Reading device settings… (needs a connected, logged-in device)")
                    } else {
                        Text(
                            "Changes apply on the device and are saved to its flash. Passwords and keys " +
                                "aren't shown here — set those on the device.",
                            color = hw.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ModuleGroup.values().forEach { group ->
                            val items = byGroup[group].orEmpty()
                            if (items.isNotEmpty()) {
                                Text(
                                    group.title,
                                    color = hw.muted,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                items.forEach { meta ->
                                    ModuleCard(
                                        meta = meta,
                                        controls = controls[meta.id],
                                        loading = meta.id in controlsLoading,
                                        onLoad = { onLoadControls(meta.id) },
                                        onSetControl = { key, token -> onSetControl(meta.id, key, token) },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    meta: ModuleMeta,
    controls: ControlsModule?,
    loading: Boolean,
    onLoad: () -> Unit,
    onSetControl: (key: String, token: String) -> Unit,
) {
    val hw = LocalHwColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .clickable {
                expanded = !expanded
                if (expanded && controls == null && !loading) onLoad()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // Prefer the firmware's own module description once loaded; fall back to our label.
                Text(
                    text = controls?.name?.takeIf { it.isNotBlank() } ?: meta.label,
                    color = hw.onGradient,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta.id,
                    color = hw.muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(if (expanded) "▴" else "▾", color = hw.muted)
        }

        if (expanded) {
            when {
                loading && controls == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = hw.onGradient, strokeWidth = 2.dp)
                    Text("Loading settings…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                }
                controls == null -> Note("No reply — needs an admin login on this device.")
                controls.entries.isEmpty() -> Note("No adjustable settings in this module.")
                else -> controls.entries.forEach { ce ->
                    ControlEntryRow(ce) { token -> onSetControl(ce.key, token) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, color = LocalHwColors.current.muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Banner(text: String) {
    val hw = LocalHwColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text, color = hw.muted, style = MaterialTheme.typography.bodyMedium)
    }
}
