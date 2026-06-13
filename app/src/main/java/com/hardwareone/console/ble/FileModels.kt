package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Models for the BLE file browser (firmware `files`/`fileread`/`filewrite`/… commands).
 * All commands require an established Secure Channel and an admin login; replies are a single
 * JSON object (reassembled by the capture layer), except the create/rename/delete/mkdir verbs
 * which reply with plain text.
 */

/** Per-entry / per-directory permission bits (what the logged-in user may do). */
object FilePerm {
    const val READ = 0x01
    const val WRITE = 0x02
    const val DELETE = 0x04
    const val RENAME = 0x08
    const val CREATE = 0x10
    const val IMPORT = 0x20
}

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val sizeLabel: String,
    val count: Int?,
    val perms: Int,
) {
    fun can(bit: Int) = (perms and bit) != 0
}

/** `files json [path]` → directory listing. */
data class FileListing(
    val success: Boolean,
    val dirPerms: Int,
    val entries: List<FileEntry>,
    val error: String?,
) {
    fun canDir(bit: Int) = (dirPerms and bit) != 0

    companion object {
        fun parse(json: String): FileListing? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("success") && !o.has("files")) return null
            if (!o.optBoolean("success", false)) {
                return FileListing(false, 0, emptyList(), o.optString("error").ifEmpty { "error" })
            }
            val arr = o.optJSONArray("files")
            val entries = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    add(
                        FileEntry(
                            name = e.optString("name"),
                            isDir = e.optString("type") == "folder",
                            sizeLabel = e.optString("size"),
                            count = if (e.has("count")) e.optInt("count") else null,
                            perms = e.optInt("perms"),
                        ),
                    )
                }
            }
            return FileListing(true, o.optInt("dirPerms"), entries, null)
        }
    }
}

/** `files stats json [path]` → storage usage for the path's tier. */
data class FileStats(val total: Long, val used: Long, val free: Long, val usagePercent: Int) {
    companion object {
        fun parse(json: String): FileStats? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.optBoolean("success", false)) return null
            return FileStats(
                total = o.optLong("total"),
                used = o.optLong("used"),
                free = o.optLong("free"),
                usagePercent = o.optInt("usagePercent"),
            )
        }
    }
}

/** `fileread <path> <offset> <len>` → one bounded window of a file. */
data class FileReadChunk(
    val success: Boolean,
    val size: Long,
    val offset: Long,
    val len: Int,
    val eof: Boolean,
    val enc: String, // "utf8" | "b64"
    val data: String,
    val error: String?,
) {
    companion object {
        fun parse(json: String): FileReadChunk? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("success")) return null
            if (!o.optBoolean("success", false)) {
                return FileReadChunk(false, 0, 0, 0, true, "utf8", "", o.optString("error").ifEmpty { "error" })
            }
            return FileReadChunk(
                success = true,
                size = o.optLong("size"),
                offset = o.optLong("offset"),
                len = o.optInt("len"),
                eof = o.optBoolean("eof", false),
                enc = o.optString("enc", "utf8"),
                data = o.optString("data"),
                error = null,
            )
        }
    }
}

/** A fully-read file held for viewing (assembled from `fileread` windows). */
data class FileViewerState(
    val path: String,
    val text: String,
    val binary: Boolean,
    val size: Long,
)

/** Live progress of a file upload/download, plus a terminal error/done state. */
data class FileTransfer(
    val name: String,
    val done: Long,
    val total: Long,
    val finished: Boolean = false,
    val error: String? = null,
    val upload: Boolean = true,
)

/** `filewrite <path> <offset> <b64chunk> [final]` → device's file size after the write. */
data class FileWriteResult(val success: Boolean, val size: Long, val final: Boolean, val error: String?) {
    companion object {
        fun parse(json: String): FileWriteResult? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("success")) return null
            val ok = o.optBoolean("success", false)
            return FileWriteResult(
                success = ok,
                size = o.optLong("size"),
                final = o.optBoolean("final", false),
                error = if (ok) null else o.optString("error").ifEmpty { "error" },
            )
        }
    }
}
