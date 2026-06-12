package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed `controls json <module>` reply (firmware sensor-controls contract) — a self-describing
 * per-sensor settings descriptor with **live current values baked in**, so each control binds to
 * its real position in one call. The app renders these generically; the firmware's SettingEntry
 * registry is the source of truth, so the panel never drifts.
 *
 * `controls json`         → `{ "v":1, "modules":[ "imu","tof", ... ] }` (discovery)
 * `controls json <module>`→ `{ "v":1, "module":"imu", "name":"...", "entries":[ ... ] }`
 *
 * A control is set with **`<key> <value>`** (command matching is case-insensitive), bool via
 * `<key> on|off`; validated against min/max by the firmware.
 */
data class ControlsModule(
    val module: String,
    val name: String,
    val entries: List<ControlEntry>,
) {
    companion object {
        /** Parse `controls json` (no arg) → the list of module names. */
        fun parseModuleList(json: String): List<String>? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val arr = o.optJSONArray("modules") ?: return null
            return buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                .filter { it.isNotEmpty() }
        }

        /** Parse `controls json <module>` → the module descriptor (null on error/garbage). */
        fun parse(json: String): ControlsModule? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (o.has("error")) return null
            val arr = o.optJSONArray("entries") ?: return null
            val entries = buildList {
                for (i in 0 until arr.length()) {
                    (arr.optJSONObject(i))?.let { add(parseEntry(it)) }
                }
            }
            return ControlsModule(o.optString("module"), o.optString("name"), entries)
        }

        private fun parseEntry(e: JSONObject): ControlEntry {
            val type = when (e.optString("type").lowercase()) {
                "int" -> ControlEntry.Type.INT
                "float" -> ControlEntry.Type.FLOAT
                "bool" -> ControlEntry.Type.BOOL
                "string" -> ControlEntry.Type.STRING
                else -> ControlEntry.Type.UNKNOWN
            }
            val optionsStr = e.optString("options", "")
            val options = if (optionsStr.isBlank()) emptyList()
            else optionsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val v = e.opt("value")
            return ControlEntry(
                key = e.optString("key"),
                label = e.optString("label").ifEmpty { e.optString("key") },
                type = type,
                min = if (e.has("min")) e.optDouble("min") else null,
                max = if (e.has("max")) e.optDouble("max") else null,
                options = options,
                numberValue = (v as? Number)?.toDouble(),
                boolValue = v as? Boolean,
                displayValue = displayOf(v),
                group = e.optString("group").ifEmpty { null },
                readOnly = e.optBoolean("readOnly", false),
            )
        }

        private fun displayOf(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "—"
            is Boolean -> if (v) "on" else "off"
            is Double -> "%.6f".format(v).trimEnd('0').trimEnd('.')
            else -> v.toString()
        }
    }
}

data class ControlEntry(
    val key: String,
    val label: String,
    val type: Type,
    val min: Double?,
    val max: Double?,
    /** Enum choices (from a comma-separated `options`); empty when not an enum. */
    val options: List<String>,
    /** Current value for int/float controls. */
    val numberValue: Double?,
    /** Current value for bool controls. */
    val boolValue: Boolean?,
    /** Pre-formatted current value for display (read-only rows, selects, text). */
    val displayValue: String,
    val group: String?,
    val readOnly: Boolean,
) {
    enum class Type { INT, FLOAT, BOOL, STRING, UNKNOWN }
}
