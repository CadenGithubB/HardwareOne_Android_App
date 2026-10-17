package com.hardwareone.console.pipe

/**
 * The single global authentication cooldown (R14): 30 s on a suspend-inclusive clock such as elapsedRealtime.
 * The owner decides what is charged (once per failed attempt: invalid auth/crypto or a peer-caused
 * handshake expiry; never transport, local, resource, inner-protocol or cancellation failures) and
 * refuses every CCCD while [active]. Pure and unsynchronised: the owner calls it under its own lock
 * and passes `now` in milliseconds.
 */
class PipeCooldown(private val durationMs: Long = DEFAULT_MS) {
    init {
        require(durationMs > 0) { "durationMs must be positive" }
    }

    private var charged = false
    private var until = 0L

    /** Starts (or restarts) the window at [now]; a charge never shortens one already running. */
    fun charge(now: Long) {
        val end = now + durationMs
        until = if (charged) maxOf(until, end) else end
        charged = true
    }

    fun active(now: Long): Boolean = charged && now < until

    fun remainingMs(now: Long): Long = if (active(now)) until - now else 0L

    /** Ends any window (e.g. the user replaced the secret). */
    fun clear() {
        charged = false
        until = 0L
    }

    companion object {
        const val DEFAULT_MS = 30_000L
    }
}
