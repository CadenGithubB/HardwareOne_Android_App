package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed replies for the Phase-1 ESP-NOW `... json` commands (driven over BLE exactly like
 * `status json`). Each tolerates the `{"schema":1,"ok":false,"error":...}` envelope — absence
 * of `ok:false` means success — and exposes that reason as [error] so the screen can show it
 * (e.g. "ESP-NOW not initialized") instead of an empty card.
 */

/** The error reason if the reply is an error envelope, else null. */
private fun errorOf(o: JSONObject): String? =
    if (o.has("ok") && !o.optBoolean("ok", true)) o.optString("error").ifEmpty { "error" } else null

/** `espnowmode json` → enable flag + direct/mesh. */
data class EspNowMode(val enabled: Boolean, val mode: String, val error: String?) {
    companion object {
        fun parse(json: String): EspNowMode? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowMode(false, "", it) }
            return EspNowMode(o.optBoolean("enabled"), o.optString("mode"), null)
        }
    }
}

/** `espnowencstatus json` → encryption state (fingerprint/length only when encrypted). */
data class EspNowEnc(
    val running: Boolean,
    val encrypted: Boolean,
    val passphraseSet: Boolean,
    val keyFingerprint: String,
    val error: String?,
) {
    companion object {
        fun parse(json: String): EspNowEnc? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowEnc(false, false, false, "", it) }
            return EspNowEnc(
                running = o.optBoolean("running"),
                encrypted = o.optBoolean("encrypted"),
                passphraseSet = o.optBoolean("passphraseSet"),
                keyFingerprint = o.optString("keyFingerprint"),
                error = null,
            )
        }
    }
}

/** `espnowmeshrole json` → role + master/backup targets. */
data class EspNowMeshRole(
    val role: String,
    val masterMac: String,
    val backupEnabled: Boolean,
    val backupMac: String,
    val error: String?,
) {
    companion object {
        fun parse(json: String): EspNowMeshRole? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowMeshRole("", "", false, "", it) }
            return EspNowMeshRole(
                role = o.optString("role"),
                masterMac = o.optString("masterMac"),
                backupEnabled = o.optBoolean("backupEnabled"),
                backupMac = o.optString("backupMac"),
                error = null,
            )
        }
    }
}

/** `espnowdeviceinfo json` → this device's mesh identity/metadata (any string may be ""). */
data class EspNowDeviceInfo(
    val name: String,
    val friendlyName: String,
    val room: String,
    val zone: String,
    val tags: String,
    val stationary: Boolean,
    val meshRole: String,
    val mac: String,
    val error: String?,
) {
    companion object {
        fun parse(json: String): EspNowDeviceInfo? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowDeviceInfo("", "", "", "", "", false, "", "", it) }
            return EspNowDeviceInfo(
                name = o.optString("name"),
                friendlyName = o.optString("friendlyName"),
                room = o.optString("room"),
                zone = o.optString("zone"),
                tags = o.optString("tags"),
                stationary = o.optBoolean("stationary"),
                meshRole = o.optString("meshRole"),
                mac = o.optString("mac"),
                error = null,
            )
        }
    }
}

/** `espnowlist json` → paired devices (config view). */
data class EspNowPaired(val devices: List<Device>, val error: String?) {
    data class Device(val mac: String, val name: String, val encrypted: Boolean, val meshId: Int)

    companion object {
        fun parse(json: String): EspNowPaired? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowPaired(emptyList(), it) }
            val arr = o.optJSONArray("devices")
            val list = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    add(Device(d.optString("mac"), d.optString("name"), d.optBoolean("encrypted"), d.optInt("meshId")))
                }
            }
            return EspNowPaired(list, null)
        }
    }
}

/** `espnowmessages json [sinceSeq] [mac]` → buffered peer messages (and relayed results). */
data class EspNowMessages(val messages: List<Message>, val error: String?) {
    data class Message(val seq: Long, val mac: String, val name: String, val msg: String)

    companion object {
        fun parse(json: String): EspNowMessages? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowMessages(emptyList(), it) }
            val list = buildList {
                o.optJSONArray("messages")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        add(Message(m.optLong("seq"), m.optString("mac"), m.optString("name"), m.optString("msg")))
                    }
                }
            }
            return EspNowMessages(list, null)
        }
    }
}

/** One line in a peer's message feed (incoming from the device, or an optimistic local echo). */
data class EspNowChatLine(val from: String, val text: String, val outgoing: Boolean)

/** `espnowmeshstatus json` → live mesh peers + discovered (unpaired) devices. */
data class EspNowMeshStatus(
    val peers: List<Peer>,
    val unpaired: List<Unpaired>,
    val error: String?,
) {
    /** A known mesh peer's liveness. NOTE: peers carry no rssi (only [Unpaired] does). */
    data class Peer(
        val mac: String,
        val name: String,
        val alive: Boolean,
        val secondsSinceHeartbeat: Int,
        val heartbeatCount: Int,
    )

    /** A heard-but-unpaired device (has rssi). */
    data class Unpaired(
        val mac: String,
        val name: String,
        val rssi: Int,
        val secondsSinceLastSeen: Int,
    )

    companion object {
        fun parse(json: String): EspNowMeshStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            errorOf(o)?.let { return EspNowMeshStatus(emptyList(), emptyList(), it) }
            val peers = buildList {
                o.optJSONArray("peers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        add(
                            Peer(
                                mac = p.optString("mac"),
                                name = p.optString("name"),
                                alive = p.optBoolean("alive"),
                                secondsSinceHeartbeat = p.optInt("secondsSinceHeartbeat"),
                                heartbeatCount = p.optInt("heartbeatCount"),
                            ),
                        )
                    }
                }
            }
            val unpaired = buildList {
                o.optJSONArray("unpaired")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val u = arr.optJSONObject(i) ?: continue
                        add(
                            Unpaired(
                                mac = u.optString("mac"),
                                name = u.optString("name"),
                                rssi = u.optInt("rssi"),
                                secondsSinceLastSeen = u.optInt("secondsSinceLastSeen"),
                            ),
                        )
                    }
                }
            }
            return EspNowMeshStatus(peers, unpaired, null)
        }
    }
}
