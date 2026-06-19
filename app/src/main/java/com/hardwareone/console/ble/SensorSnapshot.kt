package com.hardwareone.console.ble

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed `sensors json` reply (firmware Sensors contract). The per-sensor `data` is the
 * sensor's *native* shape — we don't type each of the ~15 schemas; instead [parse] flattens
 * any `data` object into a render-ready [ReadingNode] tree (labels humanized, a few well-known
 * units added). New sensors therefore render with zero app changes.
 *
 * Envelope: `{ "v":1, "seq":1234, "sensors":[ {id,name,kind,enabled,connected,data?}, ... ] }`.
 */
data class SensorSnapshot(
    val version: Int,
    val seq: Long,
    val sensors: List<SensorEntry>,
) {
    companion object {
        fun parse(json: String): SensorSnapshot? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val arr = o.optJSONArray("sensors") ?: return null
            val list = buildList {
                for (i in 0 until arr.length()) {
                    (arr.optJSONObject(i))?.let { add(parseEntry(it)) }
                }
            }
            return SensorSnapshot(o.optInt("schema", o.optInt("v", 1)), o.optLong("seq", 0), list)
        }

        private fun parseEntry(s: JSONObject): SensorEntry {
            val kind = when (s.optString("kind").lowercase()) {
                "scalar" -> SensorEntry.Kind.SCALAR
                "vector" -> SensorEntry.Kind.VECTOR
                "stream" -> SensorEntry.Kind.STREAM
                else -> SensorEntry.Kind.UNKNOWN
            }
            val data = s.optJSONObject("data")
            val id = s.optString("id")
            // Top-level primitive data values, for two-way-binding live action controls
            // (e.g. FM data.frequency / data.volume / data.muted).
            val numbers = HashMap<String, Double>()
            val flags = HashMap<String, Boolean>()
            if (data != null) {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (val v = data.opt(k)) {
                        is Boolean -> flags[k] = v
                        is Int -> numbers[k] = v.toDouble()
                        is Long -> numbers[k] = v.toDouble()
                        is Double -> numbers[k] = v
                    }
                }
            }
            return SensorEntry(
                id = id,
                name = s.optString("name").ifEmpty { id },
                kind = kind,
                enabled = s.optBoolean("enabled"),
                connected = s.optBoolean("connected"),
                hasData = data != null,
                dataValid = data?.optBoolean("valid", true) ?: true,
                readings = data?.let { buildReadings(it) } ?: emptyList(),
                numbers = numbers,
                flags = flags,
            )
        }

        // Plumbing/meta keys we never want to show as a reading row (state lives at the entry
        // level; timestamps/seqs are noise).
        private val HIDDEN_KEYS = setOf(
            "valid", "enabled", "connected", "ts", "timestamp", "seq", "agems",
            "init", "initialized",
        )

        private fun buildReadings(obj: JSONObject): List<ReadingNode> {
            val out = ArrayList<ReadingNode>()
            val keys = obj.keys() // Android JSONObject preserves insertion order
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.lowercase() in HIDDEN_KEYS) continue
                when (val v = obj.opt(key)) {
                    is JSONObject -> {
                        val children = buildReadings(v)
                        if (children.isNotEmpty()) {
                            out.add(ReadingNode.Group(humanize(key) + groupUnit(key), children))
                        }
                    }
                    is JSONArray -> {
                        val children = buildArray(v)
                        if (children.isNotEmpty()) out.add(ReadingNode.Group(humanize(key), children))
                    }
                    else -> out.add(leaf(key, v))
                }
            }
            return out
        }

        private fun buildArray(arr: JSONArray): List<ReadingNode> {
            val out = ArrayList<ReadingNode>()
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is JSONObject -> out.add(ReadingNode.Group("#$i", buildReadings(v)))
                    else -> out.add(ReadingNode.Leaf("#$i", formatValue(v)))
                }
            }
            return out
        }

        private fun leaf(key: String, v: Any?): ReadingNode.Leaf {
            // Strip unit suffixes baked into the key (distance_mm → "Distance" + " mm").
            val (labelKey, unit) = when {
                key.endsWith("_mm") -> key.removeSuffix("_mm") to " mm"
                key.endsWith("_cm") -> key.removeSuffix("_cm") to " cm"
                else -> key to leafUnit(key)
            }
            return ReadingNode.Leaf(humanize(labelKey), formatValue(v) + unit)
        }

        private fun leafUnit(key: String): String = when (key.lowercase()) {
            "ambient", "temp", "ambienttemp", "objecttemp", "compobjecttemp" -> " °C"
            "frequency" -> " MHz"
            "alt" -> " m"
            "lat", "lon", "latitude", "longitude" -> "°"
            else -> ""
        }

        private fun groupUnit(key: String): String = when (key.lowercase()) {
            "accel" -> " (m/s²)"
            "gyro" -> " (°/s)"
            "ori" -> " (°)"
            else -> ""
        }

        private fun formatValue(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "—"
            is Boolean -> if (v) "yes" else "no"
            is Int, is Long -> v.toString()
            is Double -> trimDouble(v)
            is String -> v.ifEmpty { "—" }
            else -> v.toString()
        }

        private fun trimDouble(d: Double): String =
            "%.6f".format(d).trimEnd('0').trimEnd('.')

        private fun humanize(key: String): String =
            key.replace("_", " ")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(" ")
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        /**
         * Parse a per-sensor `<x>read json` reply. Unlike [parse], the reply is the bare *data*
         * object (the same shape that appears in a sensor's `data` field), not wrapped in an entry.
         * Returns null if the reply isn't JSON — e.g. a feature-compiled-out "Unknown command" — so
         * the caller can skip it. Check [ReadData.valid] (false = stale/unavailable).
         */
        fun parseReadData(json: String): ReadData? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val numbers = HashMap<String, Double>()
            val flags = HashMap<String, Boolean>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = o.opt(k)) {
                    is Boolean -> flags[k] = v
                    is Int -> numbers[k] = v.toDouble()
                    is Long -> numbers[k] = v.toDouble()
                    is Double -> numbers[k] = v
                }
            }
            return ReadData(o.optBoolean("valid", true), buildReadings(o), numbers, flags)
        }
    }
}

/** Parsed `<x>read json` data for one sensor (see [SensorSnapshot.parseReadData]). */
data class ReadData(
    val valid: Boolean,
    val readings: List<ReadingNode>,
    val numbers: Map<String, Double>,
    val flags: Map<String, Boolean>,
)

data class SensorEntry(
    val id: String,
    val name: String,
    val kind: Kind,
    val enabled: Boolean,
    val connected: Boolean,
    /** A `data` object was present (readings may still be empty). */
    val hasData: Boolean,
    /** `data.valid` (true when absent) — false means the reading is stale/unavailable. */
    val dataValid: Boolean,
    val readings: List<ReadingNode>,
    /** Top-level numeric `data` fields (for binding live controls, e.g. FM frequency/volume). */
    val numbers: Map<String, Double>,
    /** Top-level boolean `data` fields (e.g. FM `muted`). */
    val flags: Map<String, Boolean>,
) {
    enum class Kind { SCALAR, VECTOR, STREAM, UNKNOWN }
}

/** A node in a sensor's flattened reading tree: a labeled value, or a named group of nodes. */
sealed interface ReadingNode {
    data class Leaf(val label: String, val value: String) : ReadingNode
    data class Group(val label: String, val children: List<ReadingNode>) : ReadingNode
}
