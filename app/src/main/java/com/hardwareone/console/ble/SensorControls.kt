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
            val options = parseOptions(e.optString("options", ""))
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

        /**
         * Parse the device's `options` hint into selectable choices.
         *
         * The device's format is `value|label` pairs — `"0|Preserve,1|Off,2|On"` — where the
         * VALUE is what a `set` command takes and the label is display text only. Splitting on
         * `,` alone (as this used to) produced entries like `0|Preserve` that were both shown
         * raw and sent back verbatim, so every one of the ~41 settings using this format had a
         * dropdown that displayed nonsense and set nothing.
         *
         * Each comma-separated token splits on its FIRST `|`, or failing that its FIRST `:` —
         * decided per token, not per string, so a mixed list still parses. The colon form is
         * legacy but very much alive: the whole camera module still uses it (`cameraFramesize`,
         * `cameraWBMode`, `cameraSpecialEffect`, `cameraStorageLocation`, `g2StreamToneMap`), so
         * dropping it would break those dropdowns. Taking the FIRST separator is what lets a label
         * like `Mode: fast` survive intact. A token with no separator is its own value and label.
         */
        private fun parseOptions(raw: String): List<ControlOption> {
            if (raw.isBlank()) return emptyList()
            // "bitmask:1|Thermal,2|ToF" is a checkbox grid over an int mask, NOT a select. Its
            // value is the mask, so offering it as a dropdown would set a single bit and wipe the
            // rest. Fall through to the numeric renderer instead.
            if (raw.trimStart().startsWith("bitmask:", ignoreCase = true)) return emptyList()
            return raw.split(',').mapNotNull { part ->
                val item = part.trim()
                if (item.isEmpty()) return@mapNotNull null
                val at = item.indexOf('|').takeIf { it >= 0 } ?: item.indexOf(':')
                if (at <= 0) {
                    ControlOption(item, item)
                } else {
                    val value = item.substring(0, at).trim()
                    val label = item.substring(at + 1).trim()
                    ControlOption(value, label.ifEmpty { value })
                }
            }
        }

        private fun displayOf(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "—"
            is Boolean -> if (v) "on" else "off"
            is Double -> "%.6f".format(v).trimEnd('0').trimEnd('.')
            else -> v.toString()
        }
    }
}

/**
 * One choice in a select-type control. [value] is what the device's set command expects; [label]
 * is display text only. They differ for every `value|label` option the firmware publishes.
 */
data class ControlOption(val value: String, val label: String)

data class ControlEntry(
    val key: String,
    val label: String,
    val type: Type,
    val min: Double?,
    val max: Double?,
    /** Enum choices from the device's `options` hint; empty when this isn't a select. */
    val options: List<ControlOption>,
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
