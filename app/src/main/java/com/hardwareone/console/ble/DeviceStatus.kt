package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed form of the firmware's `status json` reply (compact CLI/BLE form, schema v1).
 *
 * Every nested object is nullable: the firmware only emits a `connectivity.*` sub-object
 * when that feature is compiled in, so absence means "not in this build" — render nothing,
 * don't show "off". See docs/DEVICE_STATUS.md for the byte-for-byte contract.
 */
data class DeviceStatus(
    val schemaVersion: Int,
    val firmware: String,
    val board: String,
    val resetReason: String,
    val resetReasonCode: Int,
    val crashCount: Int,
    val systemTime: String,
    val uptime: String,
    val net: Net?,
    val mem: Mem?,
    val storage: Storage?,
    val connectivity: Connectivity?,
    /** Set when the device returned a soft failure (e.g. {"error":"oom"}). */
    val error: String?,
) {
    data class Net(
        val ssid: String,
        val ip: String,
        val rssi: Int,
        val channel: Int,
        val mac: String,
    ) {
        val connected: Boolean get() = ip.isNotEmpty()
    }

    data class Mem(
        val heapFreeKb: Int,
        val heapTotalKb: Int,
        val psramTotalKb: Int,
        val psramFreeKb: Int,
    )

    data class Storage(
        val totalKb: Int,
        val usedKb: Int,
        val freeKb: Int,
        val sd: Sd?,
    ) {
        data class Sd(val totalMb: Int, val usedMb: Int, val freeMb: Int)
    }

    data class Connectivity(
        val espnow: EspNow?,
        val bond: Bond?,
        val mqtt: Mqtt?,
        val bluetooth: Bluetooth?,
        val webserver: WebServer?,
        val i2c: I2c?,
        val llm: Llm?,
    ) {
        data class EspNow(
            val enabled: Boolean,
            val running: Boolean,
            val mesh: Boolean,
            val deviceName: String,
            val encrypted: Boolean,
            val passphraseSet: Boolean,
        )

        /** Summary of the 1:1 ESP-NOW bond (full detail is in `bondstatus json`). */
        data class Bond(
            val enabled: Boolean,
            val role: String, // "master"/"worker"; firmware emits int today, normalized on parse
            val online: Boolean,
            val synced: Boolean,
            val peer: String,
        )

        data class Mqtt(val enabled: Boolean, val connected: Boolean, val host: String)

        data class Bluetooth(
            val running: Boolean,
            val state: String,
            val mode: String,
            val server: Boolean,
            val client: Boolean,
            val g2Connected: Boolean,
        )

        data class WebServer(
            val running: Boolean,
            val https: Boolean,
            val port: Int,
            val sessions: Int,
            val maxSessions: Int,
        )

        data class I2c(
            val compiled: Boolean,
            val enabled: Boolean,
            val devices: Int,
            val activeDevices: Int,
            val sdaPin: Int,
            val sclPin: Int,
        )

        data class Llm(
            val state: String,
            val model: String,
            val psramKb: Int,
            val tokPerSec: Double,
        )
    }

    companion object {
        /** Parse a `status json` reply. Returns null if it isn't valid JSON. */
        fun parse(json: String): DeviceStatus? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (o.has("error")) {
                return DeviceStatus(
                    // schema-shape version: firmware renamed "v" → "schema"; read either.
                    schemaVersion = o.optInt("schema", o.optInt("v", 0)),
                    firmware = "", board = "", resetReason = "", resetReasonCode = 0,
                    crashCount = 0, systemTime = "", uptime = "",
                    net = null, mem = null, storage = null, connectivity = null,
                    error = o.optString("error"),
                )
            }
            return DeviceStatus(
                schemaVersion = o.optInt("schema", o.optInt("v", 1)),
                firmware = o.optString("fw"),
                board = o.optString("board"),
                resetReason = o.optString("reset_reason"),
                resetReasonCode = o.optInt("reset_reason_code"),
                crashCount = o.optInt("crash_count"),
                systemTime = o.optString("system_time"),
                uptime = o.optString("uptime_hms"),
                net = o.optJSONObject("net")?.let {
                    Net(
                        ssid = it.optString("ssid"),
                        ip = it.optString("ip"),
                        rssi = it.optInt("rssi"),
                        channel = it.optInt("channel"),
                        mac = it.optString("mac"),
                    )
                },
                mem = o.optJSONObject("mem")?.let {
                    Mem(
                        heapFreeKb = it.optInt("heap_free_kb"),
                        heapTotalKb = it.optInt("heap_total_kb"),
                        psramTotalKb = it.optInt("psram_total_kb"),
                        psramFreeKb = it.optInt("psram_free_kb"),
                    )
                },
                storage = o.optJSONObject("storage")?.let { s ->
                    Storage(
                        totalKb = s.optInt("total_kb"),
                        usedKb = s.optInt("used_kb"),
                        freeKb = s.optInt("free_kb"),
                        sd = s.optJSONObject("sd")?.let {
                            Storage.Sd(
                                totalMb = it.optInt("total_mb"),
                                usedMb = it.optInt("used_mb"),
                                freeMb = it.optInt("free_mb"),
                            )
                        },
                    )
                },
                connectivity = o.optJSONObject("connectivity")?.let { c ->
                    Connectivity(
                        espnow = c.optJSONObject("espnow")?.let {
                            Connectivity.EspNow(
                                enabled = it.optBoolean("enabled"),
                                running = it.optBoolean("running"),
                                mesh = it.optBoolean("mesh"),
                                deviceName = it.optString("deviceName"),
                                encrypted = it.optBoolean("encrypted"),
                                passphraseSet = it.optBoolean("passphraseSet"),
                            )
                        },
                        bond = c.optJSONObject("bond")?.let {
                            // role is an int (0/1) in status json today; tolerate the planned
                            // switch to a "master"/"worker" string.
                            val r = it.optString("role")
                            Connectivity.Bond(
                                enabled = it.optBoolean("enabled"),
                                role = when (r) { "1" -> "master"; "0" -> "worker"; else -> r },
                                online = it.optBoolean("online"),
                                synced = it.optBoolean("synced"),
                                peer = it.optString("peer"),
                            )
                        },
                        mqtt = c.optJSONObject("mqtt")?.let {
                            Connectivity.Mqtt(
                                enabled = it.optBoolean("enabled"),
                                connected = it.optBoolean("connected"),
                                host = it.optString("host"),
                            )
                        },
                        bluetooth = c.optJSONObject("bluetooth")?.let {
                            Connectivity.Bluetooth(
                                running = it.optBoolean("running"),
                                state = it.optString("state"),
                                mode = it.optString("mode"),
                                server = it.optBoolean("server"),
                                client = it.optBoolean("client"),
                                g2Connected = it.optBoolean("g2Connected"),
                            )
                        },
                        webserver = c.optJSONObject("webserver")?.let {
                            Connectivity.WebServer(
                                running = it.optBoolean("running"),
                                https = it.optBoolean("https"),
                                port = it.optInt("port"),
                                sessions = it.optInt("sessions"),
                                maxSessions = it.optInt("maxSessions"),
                            )
                        },
                        i2c = c.optJSONObject("i2c")?.let {
                            Connectivity.I2c(
                                compiled = it.optBoolean("compiled"),
                                enabled = it.optBoolean("enabled"),
                                devices = it.optInt("devices"),
                                activeDevices = it.optInt("activeDevices"),
                                sdaPin = it.optInt("sdaPin"),
                                sclPin = it.optInt("sclPin"),
                            )
                        },
                        llm = c.optJSONObject("llm")?.let {
                            Connectivity.Llm(
                                state = it.optString("state"),
                                model = it.optString("model"),
                                psramKb = it.optInt("psramKB"),
                                tokPerSec = it.optDouble("tokPerSec", 0.0),
                            )
                        },
                    )
                },
                error = null,
            )
        }
    }
}
