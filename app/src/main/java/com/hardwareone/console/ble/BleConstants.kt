package com.hardwareone.console.ble

import java.util.UUID

/**
 * The HardwareOne BLE contract, verified against the ESP32-S3 firmware.
 *
 * The device advertises [COMMAND_SERVICE]; we scan/match by that UUID rather than by
 * the (user-configurable) advertised name.
 */
object BleConstants {

    // --- Command service: the only service this v1 app uses ---
    val COMMAND_SERVICE: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")

    /** WRITE / WRITE_NO_RESPONSE — send a CLI command line as UTF-8 text. */
    val REQUEST_CHAR: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcde01")

    /** NOTIFY (has CCCD) — command output arrives here as plain-text notifications. */
    val RESPONSE_CHAR: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcde02")

    /** READ — JSON {"state":...,"uptime":...,"rx":...,"tx":...}. */
    val STATUS_CHAR: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcde03")

    // --- Standard Device Information service (0x180A) — optional, read after connect ---
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_CHAR: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val MODEL_CHAR: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_CHAR: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration Descriptor (standard 0x2902). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU we request. Firmware offers 517; Android typically grants 247–517. */
    const val TARGET_MTU = 517

    /** Firmware caps a command line at ~512 bytes. */
    const val MAX_COMMAND_BYTES = 512
}
