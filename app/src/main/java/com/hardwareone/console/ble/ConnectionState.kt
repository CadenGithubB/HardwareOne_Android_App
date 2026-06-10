package com.hardwareone.console.ble

/**
 * Phases of the firmware-mandated connect sequence:
 * connect → discover → MTU → enable notifications → ready.
 *
 * Authentication (login) is tracked separately ([BleManager.authenticated]) because the
 * link is "ready" for I/O before the app-layer login succeeds.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class DiscoveringServices(val deviceName: String) : ConnectionState
    data class NegotiatingMtu(val deviceName: String) : ConnectionState
    data class EnablingNotifications(val deviceName: String) : ConnectionState

    /** Link up, notifications enabled. Ready to send commands (log in first). */
    data class Ready(val deviceName: String, val mtu: Int) : ConnectionState

    /** Terminal failure for the current attempt; carries a human-readable reason. */
    data class Failed(val reason: String) : ConnectionState
}

/** A device surfaced by a scan, deduplicated by MAC address. */
data class DiscoveredDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

/** A line destined for the on-screen console, tagged for colouring. */
data class BleMessage(val text: String, val kind: Kind) {
    enum class Kind { INCOMING, INFO, ERROR }
}
