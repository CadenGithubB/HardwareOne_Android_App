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

    /** Running the app-layer secure-channel handshake (X25519/ChaCha20-Poly1305). */
    data class Securing(val deviceName: String) : ConnectionState

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

/** Live details of the connected device, for the "Device" menu. Null when disconnected. */
data class DeviceInfo(
    val name: String,
    val address: String,
    val mtu: Int = 0,
    val secure: Boolean = false,
    val firmware: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
)

/** A line destined for the on-screen console, tagged for colouring. */
data class BleMessage(val text: String, val kind: Kind) {
    enum class Kind { INCOMING, INFO, ERROR }
}

/**
 * The assembled reply to a captured command (one diverted off the console), keyed by [tag].
 * [text] is the raw reply body; [timedOut] is true when nothing came back in time.
 */
data class Capture(val tag: String, val text: String, val timedOut: Boolean)
