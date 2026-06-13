package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed `batterystatus json` reply (unified firmware schema, all interfaces):
 * `{"v":1,"present":true,"backend":"fuelgauge","voltage":3.97,"percentage":82,
 *   "status":"Discharging","charging":false,"usbPresent":true,"vbusSense":true,
 *   "lastReadMsAgo":1200,"ratePctPerHr":-4.2,"etaMinutes":1170}`
 *
 * [available] is the "compiled in / has battery hardware" gate the UI keys off — `present:false`
 * or `backend:"usb-only"` means no battery, so the card is hidden. `ratePctPerHr`/`etaMinutes`
 * are fuel-gauge-only; `percentage` is -1 when the backend can't report one.
 */
data class BatteryInfo(
    val present: Boolean,
    val backend: String,
    val voltage: Double,
    val percentage: Int,
    val status: String,
    val charging: Boolean,
    val usbPresent: Boolean,
    val ratePctPerHr: Double?,
    val etaMinutes: Int?,
) {
    val usbOnly: Boolean get() = backend.equals("usb-only", ignoreCase = true)
    val estimated: Boolean get() = backend.equals("adc", ignoreCase = true)

    /** Real battery hardware is present — render the card only when true. */
    val available: Boolean get() = present && !usbOnly

    companion object {
        fun parse(json: String): BatteryInfo? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            // Guard against routing the wrong reply here (must look like a battery doc).
            if (!o.has("present") && !o.has("voltage")) return null
            return BatteryInfo(
                present = o.optBoolean("present", false),
                backend = o.optString("backend"),
                voltage = o.optDouble("voltage", 0.0),
                percentage = o.optInt("percentage", -1),
                status = o.optString("status"),
                charging = o.optBoolean("charging", false),
                usbPresent = o.optBoolean("usbPresent", false),
                ratePctPerHr = if (o.has("ratePctPerHr")) o.optDouble("ratePctPerHr") else null,
                etaMinutes = if (o.has("etaMinutes")) o.optInt("etaMinutes") else null,
            )
        }
    }
}
