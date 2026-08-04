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
    val enabled: Boolean,
    val connected: Boolean,
    val streaming: Boolean,
    val model: String,
    val width: Int,
    val height: Int,
    val psram: Boolean,
) {
    val resolution: String get() = if (width > 0 && height > 0) "${width}×${height}" else "—"

    companion object {
        fun parse(json: String): CameraStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            // A compiled-out camera replies "{}" — treat a payload with no camera keys as absent.
            if (!o.has("enabled") && !o.has("connected")) return null
            return CameraStatus(
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
    val enabled: Boolean,
    val connected: Boolean,
    val recording: Boolean,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val level: Int,
) {
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
