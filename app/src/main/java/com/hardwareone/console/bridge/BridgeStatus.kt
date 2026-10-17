package com.hardwareone.console.bridge

/** Why the bridge could not do what was asked. Each maps to one actionable message in the UI. */
enum class FailureKind {
    /** No tethering interface with an IPv4 address was found. */
    NO_HOTSPOT,
    /** The listener could not bind (port busy, address vanished). */
    LISTENER_FAILED,
    /** No VPN network is visible to this app. */
    NO_VPN,
    /** The VPN is up but does not route the relay address. */
    ROUTE_MISSING,
    /** TCP to the relay failed (refused, unreachable, timed out) before anything was sent. */
    RELAY_UNREACHABLE,
    /** The relay presented a certificate the configured CA does not vouch for. */
    RELAY_TLS_UNTRUSTED,
    /** The relay's certificate is trusted but names a different host. */
    RELAY_TLS_HOSTNAME,
    /** The relay answered 401/403: the device token it was given is wrong. */
    RELAY_REJECTED_AUTH,
    /** The relay answered another error status, or with a shape the bridge cannot forward. */
    RELAY_ERROR,
    /** The request was sent (or partly sent) and the outcome is unknown. Never retried. */
    OUTCOME_UNKNOWN,
    /** The device's request was refused locally (bad path, size, headers…). */
    DEVICE_REQUEST_INVALID,
    /** A device connected but the TLS handshake failed (does not trust the bridge CA, clock…). */
    DEVICE_TLS_FAILED,
    /** The device went away before the reply finished. */
    CANCELLED_BY_DEVICE,
    /** The bridge's own deadline closed the request. Outcome unknown. */
    TIMED_OUT,
    /** Another request on the same route is still in flight. */
    BUSY,
}

sealed class ListenerState {
    data object Stopped : ListenerState()
    data class Waiting(val detail: String) : ListenerState()
    data class Listening(val address: String, val port: Int, val interfaceName: String?) : ListenerState() {
        val deviceUrl: String get() = "https://$address:$port/v1"
    }
    data class Failed(val detail: String) : ListenerState()
}

enum class RelayReachability { UNKNOWN, NO_VPN, ROUTE_MISSING, UNREACHABLE, TLS_UNTRUSTED, TLS_HOSTNAME, REACHABLE, AUTH_REJECTED, ERROR }

data class RelayState(
    val reachability: RelayReachability = RelayReachability.UNKNOWN,
    val detail: String? = null,
    val checkedAtMs: Long? = null,
) {
    companion object {
        fun fromFailure(kind: FailureKind, detail: String, now: Long): RelayState = when (kind) {
            FailureKind.NO_VPN -> RelayState(RelayReachability.NO_VPN, detail, now)
            FailureKind.ROUTE_MISSING -> RelayState(RelayReachability.ROUTE_MISSING, detail, now)
            FailureKind.RELAY_UNREACHABLE -> RelayState(RelayReachability.UNREACHABLE, detail, now)
            FailureKind.RELAY_TLS_UNTRUSTED -> RelayState(RelayReachability.TLS_UNTRUSTED, detail, now)
            FailureKind.RELAY_TLS_HOSTNAME -> RelayState(RelayReachability.TLS_HOSTNAME, detail, now)
            FailureKind.RELAY_REJECTED_AUTH -> RelayState(RelayReachability.AUTH_REJECTED, detail, now)
            else -> RelayState(RelayReachability.ERROR, detail, now)
        }
    }
}

enum class RequestOutcome {
    /** The relay's reply (success or its own error status) was delivered in full. */
    COMPLETED,
    /** Refused on the phone before contacting the relay. */
    REJECTED_LOCALLY,
    /** Could not reach/trust the relay; nothing was sent, so nothing ran. */
    FAILED_BEFORE_SEND,
    /** Sent but not answered, or the stream broke: the agent may have acted. */
    OUTCOME_UNKNOWN,
    /** The device dropped the connection mid-reply; the agent may still be acting. */
    CANCELLED_BY_DEVICE,
}

data class RequestRecord(
    val route: BridgeRoute?,
    val deviceAddress: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val outcome: RequestOutcome,
    val failure: FailureKind? = null,
    /** The HTTP status the device was given (ours or the relay's). */
    val httpStatus: Int? = null,
    /** Non-secret description. Never contains a token or a conversation body. */
    val detail: String? = null,
    val bytesForwarded: Long = 0,
)

/** Receives state from the relay core. Callbacks run on bridge threads; keep them quick. */
interface BridgeObserver {
    fun onListener(state: ListenerState) {}
    fun onRelay(state: RelayState) {}
    fun onRequest(record: RequestRecord) {}
    /** A device TCP connection was accepted / finished (for wake locks and activity display). */
    fun onConnectionOpened(deviceAddress: String) {}
    fun onConnectionClosed(deviceAddress: String) {}
}
