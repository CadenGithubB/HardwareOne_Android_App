package com.hardwareone.console.pipe

import java.util.UUID

/**
 * HardwareOne BLE relay pipe, protocol v1: fixed wire values. They mirror the firmware's
 * `BLE_PhonePipeCore.h` (namespace hw1pipe) and the stage-4 contract byte for byte; change them
 * only together with the firmware. Inner-frame integers are little-endian; the HW1-PIPE-v1 secure
 * channel that carries every inner frame ([PipeSecureChannel]) uses big-endian counters.
 */
object PipeWire {
    val SERVICE_UUID: UUID = UUID.fromString("b7e10000-6f7d-4b2e-a8c4-3d9f5021c6a0")
    /** Device → phone. Write without response (write command) only. */
    val RX_UUID: UUID = UUID.fromString("b7e10001-6f7d-4b2e-a8c4-3d9f5021c6a0")
    /** Phone → device. Notify only. */
    val TX_UUID: UUID = UUID.fromString("b7e10002-6f7d-4b2e-a8c4-3d9f5021c6a0")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val VERSION = 1
    const val HEADER = 12
    /** Largest ATT value either side uses, even at ATT MTU 517. */
    const val VALUE_CAP = 512
    /** Smallest inner max_value either side may offer or choose. */
    const val MIN_VALUE = 20
    /** Stage 4 refuses a subscription below this negotiated ATT MTU (V = 61, P = 36). */
    const val MIN_MTU = 64
    /** Secure DATA framing around each inner frame: type, counter(8), tag(16). */
    const val SECURE_OVERHEAD = 25
    /** One control-message reassembly buffer per direction. */
    const val CONTROL_CAP = 272
    /** Per-stream, per-direction byte buffer, and the most credit may run ahead of consumption. */
    const val WINDOW = 4096
    const val MAX_STREAMS = 2
    const val CONTROL_QUEUE = 8
    const val FIRST = 0x01
    const val LAST = 0x02
    /**
     * Receive limit the phone grants in OPEN_OK: the design's initial window, as in the firmware's
     * goldens. Every later CREDIT grants consumed + 4096 (Q11).
     */
    const val INITIAL_LIMIT = 1024L
    const val U32_MAX = 0xFFFF_FFFFL

    const val HELLO_DEADLINE_MS = 3_000L
    const val REASSEMBLY_DEADLINE_MS = 2_000L
    /**
     * One TCP connect attempt per OPEN, DNS included, from the reassembled OPEN to its answer. The
     * agreed ceiling is 3 s (A-1); 2.5 s leaves the P4's OPEN response allowance some margin.
     */
    const val CONNECT_DEADLINE_MS = 2_500L
    const val PING_IDLE_MS = 5_000L
    const val PONG_DEADLINE_MS = 5_000L
    const val CREDIT_COALESCE_BYTES = 512L
    const val CREDIT_COALESCE_MS = 100L

    /** `V = min(ATT_MTU - 3, 512)`; 0 when the MTU is below the ATT minimum. */
    fun valueLimit(mtu: Int): Int = if (mtu < 23) 0 else minOf(mtu - 3, VALUE_CAP)

    /** `P = V - 25`, the largest inner frame one secure DATA value carries; 0 below [MIN_MTU]. */
    fun frameLimit(mtu: Int): Int = if (mtu < MIN_MTU) 0 else valueLimit(mtu) - SECURE_OVERHEAD

    fun get16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    fun get32(b: ByteArray, o: Int): Long = get16(b, o).toLong() or (get16(b, o + 2).toLong() shl 16)
    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
    }
    fun put32(b: ByteArray, o: Int, v: Long) {
        put16(b, o, (v and 0xffff).toInt())
        put16(b, o + 2, ((v ushr 16) and 0xffff).toInt())
    }
}

enum class PipeOp(val code: Int) {
    HELLO(0x01), HELLO_ACK(0x02),
    OPEN(0x10), OPEN_OK(0x11), OPEN_ERR(0x12),
    DATA(0x20), CREDIT(0x21), FIN(0x22), CLOSE(0x23), CLOSE_ACK(0x24), RESET(0x25),
    PING(0x30), PONG(0x31);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): PipeOp? = byCode[code]
    }
}

/** Fixed numeric errors. Never accompanied by OS/network text on the wire or in logs. */
enum class PipeError(val code: Int) {
    BAD_PROTOCOL(1), UNSUPPORTED_VERSION(2), NOT_AUTHORIZED(3), TARGET_DENIED(4), VPN_UNAVAILABLE(5),
    DNS_FAILED(6), CONNECT_FAILED(7), CONNECT_TIMEOUT(8), BUSY(9), NO_RESOURCES(10), TCP_IO(11),
    FLOW_VIOLATION(12), DEADLINE(13), CANCELLED(14), DISPLAY_PRIORITY(15);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): PipeError? = byCode[code]
    }
}

/** Fixed-capacity byte ring. Never grows; callers bound every push by [space]. */
class ByteRing(val capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var head = 0
    var size = 0
        private set
    val space: Int get() = capacity - size

    fun clear() {
        head = 0
        size = 0
        bytes.fill(0)
    }

    fun push(src: ByteArray, off: Int, n: Int): Int {
        val count = minOf(n, space)
        for (i in 0 until count) bytes[(head + size + i) % capacity] = src[off + i]
        size += count
        return count
    }

    fun peek(dst: ByteArray, off: Int, n: Int): Int {
        val count = minOf(n, size)
        for (i in 0 until count) dst[off + i] = bytes[(head + i) % capacity]
        return count
    }

    fun drop(n: Int) {
        require(n in 0..size)
        head = (head + n) % capacity
        size -= n
    }
}
