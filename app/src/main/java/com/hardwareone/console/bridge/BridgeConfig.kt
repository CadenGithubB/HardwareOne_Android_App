package com.hardwareone.console.bridge

/**
 * Bounds for the Home AI bridge. They mirror the home relay's own limits (relay.py) so the phone
 * never admits a request the relay would refuse, and they cap what a misbehaving peer on either
 * side can make the phone buffer or wait for. Tests shrink the timeouts; production uses defaults.
 */
data class BridgeLimits(
    /** Request/response head (status line + headers) cap, both directions. */
    val maxHeaderBytes: Int = 8192,
    val maxHeaderLines: Int = 32,
    /** Device → relay body caps, per route (same as relay.py MAX_BODY / MAX_TERMINAL). */
    val maxChatBodyBytes: Int = 14336,
    val maxTerminalBodyBytes: Int = 4096,
    /** Relay → device body caps: a streamed chat reply, a Terminal JSON reply, an error body. */
    val maxReplyBytes: Long = 1024L * 1024L,
    val maxTerminalReplyBytes: Int = 4096,
    val maxErrorReplyBytes: Int = 16384,
    /** Accepted-but-unfinished device connections; extra ones are closed without a reply. */
    val maxConcurrentConnections: Int = 4,
    /** Device side: TLS handshake, then head, then body must each arrive within these windows. */
    val deviceHandshakeTimeoutMs: Int = 5_000,
    val deviceHeaderTimeoutMs: Int = 5_000,
    val deviceBodyTimeoutMs: Int = 5_000,
    /** Relay side. The firmware gives up waiting for headers after 60 s and treats a 60 s silent
     *  stream as stalled; the bridge waits slightly longer so it never fails first. */
    val upstreamConnectTimeoutMs: Int = 10_000,
    val upstreamHandshakeTimeoutMs: Int = 10_000,
    val upstreamHeaderTimeoutMs: Int = 65_000,
    /**
     * How long the relay may stay silent after its headers before the bridge gives the turn up.
     * relay.py itself stops at 300 s. The device's own 60 s stall timer is kept alive by heartbeats.
     */
    val upstreamIdleTimeoutMs: Int = 300_000,
    /**
     * While the relay is silent, the bridge writes one SSE comment line to the device per interval.
     * The firmware resets its stall timer on any byte and ignores comment lines, so a slow first
     * token no longer ends the turn at 60 s. Must be shorter than [upstreamIdleTimeoutMs] to apply.
     */
    val upstreamHeartbeatIntervalMs: Int = 20_000,
    val probeTimeoutMs: Int = 10_000,
    /** Whole-request deadlines (relay.py TOTAL_TIMEOUT / IO_TIMEOUT). */
    val chatTotalTimeoutMs: Long = 300_000,
    val terminalTotalTimeoutMs: Long = 30_000,
)

/** The two operations the bridge forwards. Anything else is answered locally with 404. */
enum class BridgeRoute(val path: String, val accept: String) {
    CHAT("/v1/chat/completions", "text/event-stream"),
    TERMINAL("/v1/terminal", "application/json");

    companion object {
        fun forPath(path: String): BridgeRoute? = entries.firstOrNull { it.path == path }
    }
}

/**
 * User configuration. The relay host/port are the only destination the bridge will ever connect
 * to; nothing in a device request can change them. [relayCaPem] is the home relay's public CA.
 */
data class BridgeConfig(
    val relayHost: String,
    val relayPort: Int = DEFAULT_PORT,
    val relayCaPem: String,
    val listenPort: Int = DEFAULT_PORT,
    /** Optional fixed hotspot address to bind; null = detect the tethering interface. */
    val listenAddressOverride: String? = null,
) {
    /** Human-readable problems, empty when the configuration is usable. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (!HOST.matches(relayHost)) out += "Relay host must be an IPv4 address or hostname (letters, digits, dots, hyphens)."
        if (relayPort !in 1..65535) out += "Relay port must be between 1 and 65535."
        if (listenPort !in 1..65535) out += "Listen port must be between 1 and 65535."
        if (listenAddressOverride != null && !IPV4.matches(listenAddressOverride)) out += "Listen address must be an IPv4 address."
        val certs = runCatching { Pem.decodeCertificates(relayCaPem) }.getOrNull()
        if (certs.isNullOrEmpty()) out += "Import the home relay's CA certificate (ca-cert.pem) first."
        return out
    }

    companion object {
        const val DEFAULT_PORT = 18790
        private val HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?")
        val IPV4 = Regex("(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}")
    }
}
