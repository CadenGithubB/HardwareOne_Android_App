package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * One entry from the firmware's `devices json` reply:
 * `{"v":1,"count":3,"devices":[{"name":"DS3231","addr":104,"bus":0}, ...]}`.
 *
 * `addr` is a **decimal** I²C address (e.g. 104 == 0x68). This is the same device list the
 * web dashboard shows — the firmware builds both from one shared builder.
 */
data class I2cDevice(
    val name: String,
    val addr: Int,
    val bus: Int,
) {
    /** Conventional 7-bit hex form, e.g. "0x68". */
    val addrHex: String get() = "0x%02X".format(addr)

    companion object {
        /** Parse a `devices json` reply. Returns null on malformed JSON; empty list if none. */
        fun parseList(json: String): List<I2cDevice>? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val arr = o.optJSONArray("devices") ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    add(
                        I2cDevice(
                            name = d.optString("name").ifEmpty { "device" },
                            addr = d.optInt("addr"),
                            bus = d.optInt("bus"),
                        ),
                    )
                }
            }
        }
    }
}
