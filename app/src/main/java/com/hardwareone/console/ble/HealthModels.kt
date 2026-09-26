package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * `healthstatus json` — the R1 ring's live telemetry plus the logging/history state around it.
 *
 * The vitals here are only meaningful while the ring is actually connected to the device, which
 * it cannot be while the phone holds the BLE link. The rest — is logging on, how often it samples,
 * when history last synced — stays true offline and is what the Health page's header reports.
 */
data class HealthStatus(
    val ringConnected: Boolean,
    val setupState: String,
    val hr: Int?,
    val hrv: Int?,
    val spo2: Int?,
    /** Tenths of a degree Celsius, as the device reports it. */
    val tempTenths: Int?,
    val battery: Int?,
    /** Raw wear code: 0 unknown, 1 not worn, 2 worn. */
    val wear: Int?,
    val loggingEnabled: Boolean,
    val loggingActive: Boolean,
    val pollIntervalSec: Int,
    val r1Selected: Boolean,
    val historyState: String,
    val historyStoreAvailable: Boolean,
) {
    val tempCelsius: Double? get() = tempTenths?.let { it / 10.0 }

    companion object {
        fun parse(json: String): HealthStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            // Shape guard — this reply is a flat object, so key presence is the only signal that
            // it is ours and not some other command's JSON that cross-claimed the capture.
            if (!o.has("ringConnected") && !o.has("healthLoggingEnabled")) return null
            fun valued(validKey: String, key: String): Int? =
                if (o.optBoolean(validKey, false)) o.optInt(key) else null
            return HealthStatus(
                ringConnected = o.optBoolean("ringConnected", false),
                setupState = o.optString("setupState", "unknown"),
                hr = valued("hrValid", "hr"),
                hrv = valued("hrvValid", "hrv"),
                spo2 = valued("spo2Valid", "spo2"),
                tempTenths = valued("tempValid", "tempTenths"),
                battery = valued("batteryValid", "battery"),
                wear = valued("wearValid", "wear"),
                loggingEnabled = o.optBoolean("healthLoggingEnabled", false),
                loggingActive = o.optBoolean("healthLoggingActive", false),
                pollIntervalSec = o.optInt("healthLoggingPollIntervalSec", 0),
                r1Selected = o.optBoolean("healthLoggingR1Selected", false),
                historyState = o.optString("historyState", "idle"),
                historyStoreAvailable = o.optBoolean("historyStoreAvailable", false),
            )
        }
    }
}

/** One logged row: whatever r1 columns were present, at the row's timestamp. */
data class HealthSample(
    /** Raw first-column timestamp text, as written by the device. */
    val time: String,
    val connected: Boolean,
    val hr: Int?,
    val hrv: Int?,
    val spo2: Int?,
    val tempC: Double?,
    val battery: Int?,
    val wear: Int?,
)

/**
 * The device's sensor log (`/captures/sensors/health.csv` by default), parsed into ring samples.
 *
 * **Columns are resolved from the header, never assumed.** The log's shape depends on the device's
 * sensor-log mask, so `r1_hr` sits at a different index depending on whether thermal/ToF/GPS are
 * also being logged — and `r1_temp` sits *between* spo2 and battery rather than at the end. Index
 * assumptions would silently mislabel data rather than fail.
 *
 * Rows without any r1 reading are dropped: with several sensors in the mask, most rows carry no
 * ring data at all and would otherwise render as gaps.
 */
data class HealthLog(val samples: List<HealthSample>) {

    companion object {
        private const val COL_TIME_FALLBACK = 0

        /**
         * @param text CSV text. May begin mid-row when read from a file tail, in which case
         *   [headerLine] carries the real header read separately from offset 0.
         */
        fun parse(text: String, headerLine: String? = null): HealthLog {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (lines.isEmpty() && headerLine == null) return HealthLog(emptyList())

            val header = (headerLine ?: lines.firstOrNull())?.takeIf { it.contains("r1_") }
                ?: return HealthLog(emptyList()) // no ring columns in this log at all
            val cols = header.split(',').map { it.trim().lowercase() }
            fun idx(name: String) = cols.indexOf(name).takeIf { it >= 0 }

            val iTime = idx("time") ?: idx("timestamp") ?: COL_TIME_FALLBACK
            val iConn = idx("r1_connected")
            val iHr = idx("r1_hr")
            val iHrv = idx("r1_hrv")
            val iSpo2 = idx("r1_spo2")
            val iTemp = idx("r1_temp")
            val iBat = idx("r1_battery")
            val iWear = idx("r1_wear")

            // When the header came from a separate read, every line here is data. Otherwise the
            // first line IS the header and must not be parsed as a row.
            val rows = if (headerLine != null) lines else lines.drop(1)

            val out = ArrayList<HealthSample>(rows.size)
            for (row in rows) {
                val f = row.split(',')
                // A row must be as wide as the header or it isn't a whole record: a tail read
                // starts mid-row and a log still being written can end mid-row. Checking only
                // that the hr index exists is NOT enough — a truncated row still has a field at
                // that position, it's just a different column's value, so the reading would be
                // silently mislabelled rather than skipped.
                if (f.size < cols.size) continue
                fun str(i: Int?): String? = i?.let { f.getOrNull(it)?.trim() }?.takeIf {
                    it.isNotEmpty() && it != "-" && !it.equals("nan", ignoreCase = true)
                }
                fun int(i: Int?): Int? = str(i)?.toIntOrNull()
                val hr = int(iHr)
                val hrv = int(iHrv)
                val spo2 = int(iSpo2)
                val temp = str(iTemp)?.toDoubleOrNull()
                val bat = int(iBat)
                if (hr == null && hrv == null && spo2 == null && temp == null && bat == null) continue
                out += HealthSample(
                    time = f.getOrNull(iTime)?.trim().orEmpty(),
                    connected = str(iConn).let { it == "1" || it.equals("true", ignoreCase = true) },
                    hr = hr, hrv = hrv, spo2 = spo2, tempC = temp, battery = bat, wear = int(iWear),
                )
            }
            return HealthLog(out)
        }
    }
}
