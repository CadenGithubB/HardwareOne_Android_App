package com.hardwareone.console.pipe

import org.json.JSONObject

/**
 * The pipe's persisted configuration besides the secret: the one approved relay destination.
 * Stage 4 keeps no P4 address and no enrollment marker (R12); the secret has its own store (R11).
 */
data class PipeConfig(val target: PipeTarget) {
    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("type", target.type)
        .put("host", target.host.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        .put("port", target.port)
        .toString()

    companion object {
        private const val VERSION = 2
        private const val LEGACY_VERSION = 1

        /** null on a malformed record, another version, or a target the OPEN validation refuses. */
        fun fromJson(text: String): PipeConfig? = decode(text, VERSION)

        /**
         * The earlier bonded-design record ({"v":1,"address":…,"type","host","port","at"}): keeps
         * only the target. The address and time are meaningless without the bond and are ignored.
         */
        fun fromLegacyEnrollment(text: String): PipeConfig? = decode(text, LEGACY_VERSION)

        private fun decode(text: String, version: Int): PipeConfig? = runCatching {
            val o = JSONObject(text)
            // Integers only: a string, fraction, boolean or out-of-range number is not coerced.
            if (o.opt("v") as? Int != version) return null
            val type = o.opt("type") as? Int ?: return null
            val port = o.opt("port") as? Int ?: return null
            val host = unhex(o.opt("host") as? String ?: return null) ?: return null
            // The stored target passes the same validation as an OPEN, so a corrupt record cannot approve.
            PipeTarget.fromWire(type, host, 0, host.size, port)?.let { PipeConfig(it) }
        }.getOrNull()

        /** Lowercase hex pairs, as [toJson] writes them; anything else is a malformed record. */
        private fun unhex(text: String): ByteArray? {
            if (text.length % 2 != 0) return null
            val out = ByteArray(text.length / 2)
            for (i in out.indices) {
                val hi = nibble(text[2 * i])
                val lo = nibble(text[2 * i + 1])
                if (hi < 0 || lo < 0) return null
                out[i] = (hi shl 4 or lo).toByte()
            }
            return out
        }

        private fun nibble(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }
    }
}
