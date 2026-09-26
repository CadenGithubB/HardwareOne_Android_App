package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Camera + microphone models for the media pages. Both devices are stream sensors whose live
 * pixels/audio only exist on the firmware's HTTP interface (never over the CLI/BLE channel), so
 * the app drives *capture-to-file* + tuning + a saved-file browser instead of a live view. Saved
 * JPEG/WAV files are pulled with the same windowed binary `fileread` the Files/Maps pages use.
 */

/** `cameraread` → camera status (always JSON). */
data class CameraStatus(
    /** Camera compiled in for this board — a build fact, false only on a camera-less build. */
    val supported: Boolean,
    /**
     * The SCCB probe has seen a sensor chip at least once since boot. Sticky: it survives the
     * camera being stopped, which is what lets "absent" be told apart from "never started".
     */
    val detected: Boolean,
    /** Driver started. */
    val enabled: Boolean,
    /** Driver initialised. RUNTIME state, not presence — see [detected]. */
    val connected: Boolean,
    val streaming: Boolean,
    /** Chip the probe identified (OV2640/OV3660/OV5640), or a placeholder before first start. */
    val model: String,
    val width: Int,
    val height: Int,
    val psram: Boolean,
) {
    val resolution: String get() = if (width > 0 && height > 0) "${width}×${height}" else "—"

    companion object {
        fun parse(json: String): CameraStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("enabled") && !o.has("connected") && !o.has("supported")) return null
            return CameraStatus(
                // Default true on both: firmware predating these keys still has a camera, and an
                // app that declared "absent" over a missing field would repeat the exact bug
                // these fields were added to fix.
                supported = o.optBoolean("supported", true),
                detected = o.optBoolean("detected", true),
                enabled = o.optBoolean("enabled"),
                connected = o.optBoolean("connected"),
                streaming = o.optBoolean("streaming"),
                model = o.optString("model").ifBlank { "camera" },
                width = o.optInt("width"),
                height = o.optInt("height"),
                psram = o.optBoolean("psram"),
            )
        }
    }
}

/** `micread json` → microphone status. */
data class MicStatus(
    /** Driver started (`gMicRunning`). */
    val enabled: Boolean,
    /**
     * Driver initialised (`micConnected`). This is RUNTIME state, not hardware presence — it only
     * goes true once the mic has been started, so it must never be read as "is there a mic".
     * Use [hardwarePresent] for that.
     */
    val connected: Boolean,
    val recording: Boolean,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val level: Int,
    /**
     * NOT a hardware probe. PDM over I2S has no ID register, so the device reports a compile-time
     * constant here — true iff `ENABLE_MICROPHONE_SENSOR` (HAL_Audio.cpp). False means "this build
     * has no onboard mic", never "the mic is missing".
     */
    val pdmCompiledIn: Boolean,
    /** G2 glasses mic reachable — a BLE link check on the glasses, not local hardware. */
    val g2LinkUp: Boolean,
) {
    /**
     * True when this build has no mic source at all. The mic has NO detection layer — the honest
     * model is build flag plus runtime state — so this is the only absence claim the app can
     * make, and it is a statement about the firmware, not the hardware.
     */
    val noSourceCompiledIn: Boolean get() = !pdmCompiledIn && !g2LinkUp && !connected

    companion object {
        fun parse(json: String): MicStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("enabled") && !o.has("sampleRate")) return null
            return MicStatus(
                enabled = o.optBoolean("enabled"),
                connected = o.optBoolean("connected"),
                recording = o.optBoolean("recording"),
                sampleRate = o.optInt("sampleRate"),
                bitDepth = o.optInt("bitDepth"),
                channels = o.optInt("channels"),
                level = o.optInt("level"),
                // Absent on firmware that predates these keys — assume present rather than
                // declaring "no microphone" on the strength of a missing field.
                pdmCompiledIn = o.optBoolean("pdmAvailable", true),
                g2LinkUp = o.optBoolean("g2Available", false),
            )
        }

        /** `miclevel json` → `{schema,enabled,level}` (0–100), or null if unparseable. */
        fun parseLevel(json: String): Int? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("level")) return null
            return o.optInt("level").coerceIn(0, 100)
        }
    }
}

/** One saved photo. [path] is the full device path used to `fileread`/`imagedelete`. */
data class ImageItem(
    val filename: String,
    val size: Long,
    val location: String, // "LittleFS" | "SD"
    val path: String,
)

/** Parsed `images json [sd]` → the storage location + its file list (filenames only). */
data class ImageListing(
    val location: String,
    val available: Boolean,
    val images: List<Pair<String, Long>>, // filename → size
) {
    companion object {
        fun parse(json: String): ImageListing? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("location")) return null
            val arr = o.optJSONArray("images")
            val list = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val name = e.optString("filename")
                    if (name.isNotEmpty()) add(name to e.optLong("size"))
                }
            }
            return ImageListing(o.optString("location"), o.optBoolean("available", true), list)
        }
    }
}

/** One saved recording. The device may hold it on SD or LittleFS; [candidates] are the paths to try. */
data class RecordingItem(
    val filename: String,
    val size: Long,
) {
    /** Recordings live in "/sd/recordings" or "/recordings"; try SD first, then internal. */
    val candidates: List<String> get() = listOf("/sd/recordings/$filename", "/recordings/$filename")

    companion object {
        fun parse(json: String): List<RecordingItem>? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val arr = o.optJSONArray("recordings") ?: return null
            return buildList {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val name = e.optString("filename")
                    if (name.isNotEmpty()) add(RecordingItem(name, e.optLong("size")))
                }
            }
        }
    }
}

/** A fully-pulled media blob handed to the UI (image to decode, or audio to play). */
data class MediaBlob(val name: String, val path: String, val bytes: ByteArray)
