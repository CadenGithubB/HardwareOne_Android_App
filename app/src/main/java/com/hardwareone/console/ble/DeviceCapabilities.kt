package com.hardwareone.console.ble

import org.json.JSONObject

/** One entry from the device's feature registry (`features json`). */
data class DeviceFeature(
    /** Stable id — `i2c`, `camera`, `espnow`, … The contract; names are display text. */
    val id: String,
    val name: String,
    val category: String,
    val heapKB: Int,
    /**
     * Built into this firmware binary. FALSE IS PERMANENT until the device is reflashed —
     * the commands for this feature are not registered at all, so asking for them yields
     * `Unknown command`.
     */
    val compiled: Boolean,
    /** Running right now. Unlike [compiled] this is a runtime state the user can change. */
    val enabled: Boolean,
    /** The device permits `features <id> on|off` at runtime. */
    val toggleable: Boolean,
)

/**
 * What this particular device can do — the app's half of the firmware's modular build.
 *
 * HardwareOne is configured per board at compile time (`I2C_FEATURE_LEVEL`, `ENABLE_*`), so two
 * devices running "the same" firmware genuinely differ in which commands exist. The device
 * publishes that through `features json`, whose handler says as much: *"Lets the app gate UI on
 * what the device actually has."* Model the union, render the intersection.
 *
 * **Gate on [DeviceFeature.compiled], not [DeviceFeature.enabled].** Not-compiled means the
 * feature cannot exist without a reflash, so hiding it is correct. Not-enabled is just switched
 * off — the user may well be coming to the app to switch it on, so it must stay reachable.
 *
 * **Absence means "assume yes".** [isCompiled] returns true for an id this device never mentioned,
 * so a firmware older than a given feature — or the window before login, when the map is null —
 * degrades to the previous always-try behaviour rather than hiding the whole app. Only an explicit
 * `compiled:false` hides anything. The reactive unknown-command backstop in the ViewModel covers
 * whatever the registry doesn't list (`batterystatus` has no entry, for instance).
 */
class DeviceCapabilities(private val byId: Map<String, DeviceFeature>) {

    val features: List<DeviceFeature> get() = byId.values.sortedBy { it.id }

    fun feature(id: String): DeviceFeature? = byId[id]

    /** True unless the device explicitly said this feature is absent from its build. */
    fun isCompiled(id: String): Boolean = byId[id]?.compiled ?: true

    /** True when the feature is compiled AND currently running. */
    fun isEnabled(id: String): Boolean = byId[id]?.let { it.compiled && it.enabled } ?: true

    companion object {
        /** Ids the Sensors page depends on — the I²C layer that owns `sensors` and `devices`. */
        const val FEATURE_I2C = "i2c"

        fun parse(json: String): DeviceCapabilities? {
            val o = try { JSONObject(json) } catch (_: Exception) { return null }
            val arr = o.optJSONArray("features") ?: return null // shape guard — not a features reply
            val map = LinkedHashMap<String, DeviceFeature>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val id = f.optString("id").trim()
                if (id.isEmpty()) continue
                map[id] = DeviceFeature(
                    id = id,
                    name = f.optString("name").ifBlank { id },
                    category = f.optString("category"),
                    heapKB = f.optInt("heapKB"),
                    // Default TRUE on a missing key, for the same reason absence means "assume
                    // yes" — never hide a feature because a field was omitted.
                    compiled = f.optBoolean("compiled", true),
                    enabled = f.optBoolean("enabled", true),
                    toggleable = f.optBoolean("toggleable", false),
                )
            }
            return if (map.isEmpty()) null else DeviceCapabilities(map)
        }
    }
}
