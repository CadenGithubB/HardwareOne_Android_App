package com.hardwareone.console.bridge

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide view of the Home AI bridge and the handle the UI uses to control it. The
 * [HomeBridgeService] owns the running bridge and publishes here; screens only observe and send
 * start/stop/probe intents. Nothing in this state is secret.
 */
object HomeBridge {
    data class UiState(
        val running: Boolean = false,
        val listener: ListenerState = ListenerState.Stopped,
        val relay: RelayState = RelayState(),
        /** null = not tracking (bridge stopped). */
        val vpnAvailable: Boolean? = null,
        val vpnInterface: String? = null,
        val hotspotCandidates: List<String> = emptyList(),
        /** The hotspot's prefix (e.g. `10.42.0.0/24`) while a listener is up. */
        val hotspotSubnet: String? = null,
        /** Where the kernel sends replies to hotspot clients; null until checked. */
        val replyPath: ReplyPath? = null,
        val lastRequest: RequestRecord? = null,
        val completed: Int = 0,
        val failed: Int = 0,
        val activeConnections: Int = 0,
        val lastDeviceAddress: String? = null,
        val lastDeviceSeenMs: Long? = null,
        val probing: Boolean = false,
        /** Why the bridge is not running when the user expected it to be. */
        val error: String? = null,
    ) {
        val deviceUrl: String? get() = (listener as? ListenerState.Listening)?.deviceUrl
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    internal fun update(block: (UiState) -> UiState) = _state.update(block)

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, HomeBridgeService::class.java).setAction(HomeBridgeService.ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, HomeBridgeService::class.java).setAction(HomeBridgeService.ACTION_STOP))
    }

    fun probe(context: Context) {
        if (!state.value.running) return
        context.startService(Intent(context, HomeBridgeService::class.java).setAction(HomeBridgeService.ACTION_PROBE))
    }

    /** One line for the Settings card and the Devices page. */
    fun summary(s: UiState): String = when {
        !s.running -> s.error ?: "Off"
        s.listener is ListenerState.Listening -> "Listening on ${(s.listener as ListenerState.Listening).address}${replyWord(s.replyPath)} · relay ${relayWord(s.relay)}"
        s.listener is ListenerState.Waiting -> "Waiting for hotspot · relay ${relayWord(s.relay)}"
        s.listener is ListenerState.Failed -> "Listener failed"
        else -> "Starting…"
    }

    private fun replyWord(p: ReplyPath?): String = when (p) {
        is ReplyPath.Captured -> " · but the VPN captures hotspot replies (open the app)"
        is ReplyPath.Blocked -> " · but replies to hotspot clients are blocked (lockdown?)"
        else -> ""
    }

    fun relayWord(r: RelayState): String = when (r.reachability) {
        RelayReachability.UNKNOWN -> "unchecked"
        RelayReachability.NO_VPN -> "no VPN"
        RelayReachability.ROUTE_MISSING -> "not routed by VPN"
        RelayReachability.UNREACHABLE -> "unreachable"
        RelayReachability.TLS_UNTRUSTED -> "certificate untrusted"
        RelayReachability.TLS_HOSTNAME -> "certificate name mismatch"
        RelayReachability.REACHABLE -> "reachable"
        RelayReachability.AUTH_REJECTED -> "rejected device token"
        RelayReachability.ERROR -> "error"
    }
}
