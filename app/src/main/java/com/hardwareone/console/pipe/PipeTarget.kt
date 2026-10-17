package com.hardwareone.console.pipe

/**
 * A relay destination in OPEN form: address type, raw host bytes and port. The allowlist compares
 * these byte for byte, so the phone only ever connects to exactly what was approved at enrollment.
 * [toString] never reveals the destination; use [display] deliberately (UI only, never logs).
 */
class PipeTarget private constructor(val type: Int, private val bytes: ByteArray, val port: Int) {
    val host: ByteArray get() = bytes.copyOf()

    /** What DNS/socket code needs: the A-label name, or the IP literal. */
    fun hostString(): String = when (type) {
        DNS -> String(bytes, Charsets.US_ASCII)
        IPV6 -> java.net.Inet6Address.getByAddress(null, bytes, -1).hostAddress ?: ""
        else -> java.net.InetAddress.getByAddress(bytes).hostAddress ?: ""
    }

    /** `host:port` for the settings screen (IPv6 bracketed). Private: keep it out of logs. */
    fun display(): String = if (type == IPV6) "[${hostString()}]:$port" else "${hostString()}:$port"

    override fun equals(other: Any?): Boolean =
        other is PipeTarget && type == other.type && port == other.port && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (type * 31 + port) * 31 + bytes.contentHashCode()

    override fun toString(): String = "PipeTarget(type=$type)"

    /** Why an enrollment URL was refused (shown to the user; contains no input text). */
    class Invalid(val reason: String) : Exception(reason)

    companion object {
        const val IPV4 = 1
        const val IPV6 = 2
        const val DNS = 3

        /**
         * Validates an OPEN's address fields exactly as the firmware encodes them: IPv4 = 4
         * network-order bytes, IPv6 = 16, DNS = 1–253 bytes of lowercase letters, digits, '-' and
         * '.'. Returns null for anything else (the caller treats that as a protocol error).
         */
        fun fromWire(type: Int, b: ByteArray, off: Int, length: Int, port: Int): PipeTarget? {
            if (port !in 1..0xffff || off + length > b.size) return null
            val ok = when (type) {
                IPV4 -> length == 4
                IPV6 -> length == 16
                DNS -> length in 1..253 && (off until off + length).all { dnsByte(b[it]) }
                else -> false
            }
            return if (ok) PipeTarget(type, b.copyOfRange(off, off + length), port) else null
        }

        internal fun dnsByte(c: Byte): Boolean {
            val ch = c.toInt().toChar()
            return ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.'
        }

        /**
         * Derives the OPEN target from the device's configured base URL (`https://host[:port]/…/v1`)
         * exactly as the firmware does, accepting only forms whose bytes cannot differ between the
         * two parsers. The firmware is more lenient (shorthand/octal IPv4, IPv6 zones dropped,
         * trailing dots, empty labels); those forms are refused here rather than approved under a
         * different meaning, so the user must configure the device with the canonical form.
         *
         * Firmware reference: System_LLMHttpCore.h (URL checks), http_parser (authority),
         * BLE_PhonePipe.cpp phonePipeOpen (IPv4 → IPv6 → lowercased name).
         */
        fun parseUrl(url: String): PipeTarget {
            if (url.length > 384) throw Invalid("The URL is longer than the device accepts (384 characters).")
            if (!url.startsWith("https://")) throw Invalid("The URL must start with lowercase https:// (the pipe never carries plain HTTP).")
            if (url.any { it.code !in 0x21..0x7e }) throw Invalid("The URL may only contain printable ASCII without spaces (use the xn-- form of an international name).")
            val rest = url.substring(8)
            if (rest.any { it == '@' || it == '?' || it == '#' || it == '\\' }) throw Invalid("The URL must not contain user info, a query, a fragment or a backslash.")
            val slash = rest.indexOf('/')
            if (slash <= 0) throw Invalid("The URL needs a host followed by a path ending in /v1.")
            val path = rest.substring(slash)
            if (!(path.endsWith("/v1") || path.endsWith("/v1/"))) throw Invalid("The URL path must end with /v1 (the device's base URL).")
            if (path.contains("/../") || path.contains("/./")) throw Invalid("The URL path must not contain /../ or /./.")
            return parseAuthority(rest.substring(0, slash), defaultPort = 443)
        }

        /** `host`, `host:port`, `[ipv6]` or `[ipv6]:port`, canonical forms only. */
        fun parseAuthority(authority: String, defaultPort: Int = 443): PipeTarget {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) throw Invalid("An IPv6 address needs a closing ].")
                val host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                val portText = when {
                    after.isEmpty() -> null
                    after.startsWith(":") -> after.substring(1)
                    else -> throw Invalid("Unexpected text after the IPv6 address.")
                }
                if (host.contains('%')) throw Invalid("IPv6 zone IDs (%…) are not allowed: the device would silently drop the zone.")
                if (host.contains('.')) throw Invalid("Write the IPv6 address in hex groups only (no embedded dotted IPv4).")
                val bytes = parseIpv6(host) ?: throw Invalid("That is not a valid IPv6 address.")
                if (!usableIpv6(bytes)) throw Invalid("That IPv6 address cannot be a relay (unspecified, loopback, multicast or IPv4-mapped — use the IPv4 address).")
                return PipeTarget(IPV6, bytes, port(portText, defaultPort))
            }
            val colon = authority.indexOf(':')
            val host = if (colon >= 0) authority.substring(0, colon) else authority
            val portText = if (colon >= 0) authority.substring(colon + 1) else null
            if (host.isEmpty()) throw Invalid("The URL has no host.")
            val port = port(portText, defaultPort)
            if (looksNumeric(host)) {
                val bytes = parseCanonicalIpv4(host)
                    ?: throw Invalid("Write the IPv4 address as four plain decimal numbers (the device reads shorthand, octal and hex forms differently).")
                if (!usableIpv4(bytes)) throw Invalid("That IPv4 address cannot be a relay (unspecified, loopback, multicast or broadcast).")
                return PipeTarget(IPV4, bytes, port)
            }
            // The device lowercases A–Z only; any other byte outside [a-z0-9.-] could never match.
            // (Unicode-aware lowercase() would turn e.g. U+212A KELVIN SIGN into ASCII 'k'.)
            if (host.any { it.code !in 0x21..0x7e }) throw Invalid("The host must be plain ASCII (use the xn-- form of an international name).")
            val name = host.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
            if (!validDnsName(name)) throw Invalid("The host must be an ASCII DNS name: labels of 1–63 letters, digits or hyphens, no leading or trailing hyphen, no trailing dot, at most 253 characters, not ending in a numeric label.")
            return PipeTarget(DNS, name.toByteArray(Charsets.US_ASCII), port)
        }

        private fun port(text: String?, default: Int): Int {
            if (text == null) return default
            if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) throw Invalid("The port must be a number from 1 to 65535.")
            val p = text.toInt()
            if (p !in 1..0xffff) throw Invalid("The port must be a number from 1 to 65535.")
            return p
        }

        /**
         * True when the device's lwIP `inet_pton(AF_INET)` could read [host] as an IPv4 address:
         * every dot-separated part is a C integer literal (decimal, 0-octal or 0x-hex). Also true
         * for over-long numeric dotted strings, which no canonical form covers.
         */
        internal fun looksNumeric(host: String): Boolean {
            val parts = host.split('.')
            if (parts.last().let { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }) return true
            return parts.size <= 4 && parts.all { p ->
                p.isNotEmpty() && (p.all { it in '0'..'9' } ||
                    // lwIP accepts "0x" with no digits as 0, so "192.0.2.0x" is an IPv4 address there.
                    ((p.startsWith("0x") || p.startsWith("0X")) && p.substring(2).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }))
            }
        }

        /** Exactly four decimal octets 0–255, no leading zeros. */
        internal fun parseCanonicalIpv4(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun usableIpv4(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xff
            return first != 0 && first != 127 && first < 224
        }

        private fun usableIpv6(b: ByteArray): Boolean {
            if ((b[0].toInt() and 0xff) == 0xff) return false
            val zeroPrefix = (0 until 15).all { b[it].toInt() == 0 }
            if (zeroPrefix && (b[15].toInt() == 0 || b[15].toInt() == 1)) return false
            // ::ffff:0:0/96 — the device sends 16 bytes, Java would connect over IPv4: refuse.
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
            return !mapped
        }

        /** RFC 1123 host name in lowercase A-label form; the last label must not be all digits. */
        internal fun validDnsName(name: String): Boolean {
            if (name.isEmpty() || name.length > 253 || name.endsWith(".")) return false
            val labels = name.split('.')
            for (label in labels) {
                if (label.isEmpty() || label.length > 63) return false
                if (label.startsWith("-") || label.endsWith("-")) return false
                if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
            }
            return !labels.last().all { it in '0'..'9' }
        }

        /**
         * IPv6 hex groups with at most one `::` to 16 bytes. Deliberately not java.net.InetAddress,
         * which maps some IPv6 text to IPv4 while the device sends 16 bytes.
         */
        internal fun parseIpv6(text: String): ByteArray? {
            if (text.isEmpty() || text.length > 39) return null
            val double = text.indexOf("::")
            if (double >= 0 && text.indexOf("::", double + 1) >= 0) return null
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(':').map { g ->
                    if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
                    g.toInt(16)
                }
            }
            val words = if (double >= 0) {
                val head = groups(text.substring(0, double)) ?: return null
                val tail = groups(text.substring(double + 2)) ?: return null
                val missing = 8 - head.size - tail.size
                if (missing < 1) return null
                head + List(missing) { 0 } + tail
            } else {
                groups(text)?.takeIf { it.size == 8 } ?: return null
            }
            val out = ByteArray(16)
            for ((i, w) in words.withIndex()) {
                out[2 * i] = (w ushr 8).toByte()
                out[2 * i + 1] = w.toByte()
            }
            return out
        }
    }
}
