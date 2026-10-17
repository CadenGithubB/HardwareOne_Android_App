package com.hardwareone.console.pipe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream

/*
 * Shared helpers for the pipe v1 JVM tests (owned by PipeSessionWireTest; other test files use
 * them read-only). Every encoder here follows the protocol sources, NOT PipeSession's own encoder,
 * so comparing a session's output with these helpers is an independent check:
 *
 *  - BLE_PhonePipeCore.h (firmware P4 core) and its harness Peer, phone_pipe_harness.cpp:7-19
 *  - the v1 protocol snapshot in the Android handoff
 *
 * Stage 4 carries each inner frame as one HW1-PIPE-v1 secure DATA value, so a session is sized by
 * P = V − 25 (the largest inner frame), not by the ATT value V; [specFrame] is that rule.
 *
 * API in brief:
 *  - [PipeRig]: one PipeSession (sized by `maxFrame`) plus its [Device] peer; `hello()` and
 *    `open(id)` reach READY / an open stream through the public API and record every target the
 *    session asked `admit` about.
 *  - [specValue] / [specFrame]: V and P for an ATT MTU, written from the contract, not PipeWire.
 *  - [vector] / [vectorInt]: one value of the firmware's `phone_secure_channel_v1_vectors.json` fixture.
 *  - [Device]: frames device (P4) → phone messages like the firmware, with its own sequence.
 *  - [drain] / [assertDrains]: peek + accept everything the phone has ready, decoded as [WireFrame].
 *  - [messagesOf]: joins phone frames into [WireMessage]s, checking FIRST/LAST structure.
 *  - [rawFrame] / [Device.frame]: one frame with explicit header fields, for malformed input.
 *  - [hex] / [toHex], [le16] / [le32], [u16] / [u32]: byte helpers (all wire integers are LE).
 */

/** Protocol opcodes, from the v1 message table (handoff "Session and stream messages"). */
object Op {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val OPEN = 0x10
    const val OPEN_OK = 0x11
    const val OPEN_ERR = 0x12
    const val DATA = 0x20
    const val CREDIT = 0x21
    const val FIN = 0x22
    const val CLOSE = 0x23
    const val CLOSE_ACK = 0x24
    const val RESET = 0x25
    const val PING = 0x30
    const val PONG = 0x31
}

/** Header flag bits: FIRST=0x01, LAST=0x02; a one-frame control message carries both. */
const val F_FIRST = 0x01
const val F_LAST = 0x02
const val F_BOTH = 0x03

/** OPEN address_type values: 1 IPv4 (4 bytes), 2 IPv6 (16 bytes), 3 ASCII DNS name (1–253). */
const val ADDR_IPV4 = 1
const val ADDR_IPV6 = 2
const val ADDR_DNS = 3

/** P4 HELLO nonce of the report fixtures (§6 A/B): u64 0x0102030405060708, little-endian. */
val P4_NONCE: ByteArray get() = hex("0807060504030201")

/** Phone nonce the fixtures expect in HELLO_ACK: u64 0x1112131415161718, little-endian. */
val PHONE_NONCE: ByteArray get() = hex("1817161514131211")

/** RFC 5737 documentation address 192.0.2.1, the default synthetic relay (firmware HX `opened()`). */
val TEST_IPV4: ByteArray get() = hex("c0000201")

/** `V = min(ATT_MTU - 3, 512)`, or 0 below ATT MTU 23 (firmware `valueLimit`). */
fun specValue(mtu: Int): Int = if (mtu < 23) 0 else minOf(mtu - 3, 512)

/**
 * Stage-4 `P = V - 25`: the largest inner frame one secure DATA value (type 1 + counter 8 + tag 16
 * around the frame) carries, or 0 below the required ATT MTU 64 (DECISIONS R8; firmware
 * `Session::start(mtu, nonce, now, outerOverhead)`). 64/244/247/517 give 36/216/219/487.
 */
fun specFrame(mtu: Int): Int = if (mtu < 64) 0 else specValue(mtu) - 25

private val secureVectors: JSONObject by lazy {
    val stream = PipeSession::class.java.getResourceAsStream("/pipe/phone_secure_channel_v1_vectors.json")
        ?: throw AssertionError("missing test resource /pipe/phone_secure_channel_v1_vectors.json")
    stream.use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
}

/**
 * The bytes of one `*_hex` entry of the firmware fixture `phone_secure_channel_v1_vectors.json`
 * (copied verbatim from hardwareone-idf `tools/ble_secure/fixtures/`).
 */
fun vector(name: String): ByteArray = hex(secureVectors.getString(name))

/** One integer entry of the same fixture (`att_mtu`, `data_counter_each_direction`). */
fun vectorInt(name: String): Int = secureVectors.getInt(name)

/** Bytes from hex text. Spaces, `|` and `_` are ignored, so report fixtures paste verbatim. */
fun hex(text: String): ByteArray {
    val digits = text.filter { it != ' ' && it != '|' && it != '_' && it != '\n' }
    require(digits.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** Lowercase hex without separators. */
fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

/** [n] bytes, each [value]. */
fun bytes(n: Int, value: Int): ByteArray = ByteArray(n).also { it.fill(value.toByte()) }

fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())

fun le32(v: Long): ByteArray =
    byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

/** HELLO body: `nonce:u64, max_streams:u8, reserved:u8, max_value:u16`. */
fun helloBody(maxValue: Int, nonce: ByteArray = P4_NONCE, maxStreams: Int = 2, reserved: Int = 0): ByteArray =
    nonce + byteArrayOf(maxStreams.toByte(), reserved.toByte()) + le16(maxValue)

/** OPEN body: `receive_limit:u32, port:u16, address_type:u8, host_length:u8, host`. */
fun openBody(type: Int, host: ByteArray, port: Int, receiveLimit: Long = 1024, hostLength: Int = host.size): ByteArray =
    le32(receiveLimit) + le16(port) + byteArrayOf(type.toByte(), hostLength.toByte()) + host

/**
 * One frame with every header field explicit: `version, op, id:u16, seq:u32, length:u16, flags,
 * reserved | body`. [length] defaults to the body size and [version]/[reserved] to the valid values.
 */
fun rawFrame(
    op: Int,
    id: Int,
    seq: Long,
    flags: Int,
    body: ByteArray = ByteArray(0),
    version: Int = 1,
    reserved: Int = 0,
    length: Int = body.size,
): ByteArray {
    val f = ByteArray(12 + body.size)
    f[0] = version.toByte()
    f[1] = op.toByte()
    le16(id).copyInto(f, 2)
    le32(seq).copyInto(f, 4)
    le16(length).copyInto(f, 8)
    f[10] = flags.toByte()
    f[11] = reserved.toByte()
    body.copyInto(f, 12)
    return f
}

/**
 * Deterministic stand-in for the session's cryptographic RNG. Call k fills its array with
 * pattern k (the last pattern once they run out), repeated cyclically to the array's length.
 */
class FakeRandom(private vararg val patterns: ByteArray) : (ByteArray) -> Unit {
    var calls = 0
        private set

    override fun invoke(out: ByteArray) {
        val p = patterns[minOf(calls, patterns.size - 1)]
        calls++
        for (i in out.indices) out[i] = p[i % p.size]
    }
}

/** One phone → device (or device → phone) frame, decoded field by field. */
class WireFrame(val bytes: ByteArray) {
    val size: Int get() = bytes.size
    val version: Int get() = bytes[0].toInt() and 0xff
    val op: Int get() = bytes[1].toInt() and 0xff
    val id: Int get() = u16(bytes, 2)
    val seq: Long get() = u32(bytes, 4)
    val length: Int get() = u16(bytes, 8)
    val flags: Int get() = bytes[10].toInt() and 0xff
    val reserved: Int get() = bytes[11].toInt() and 0xff
    val body: ByteArray get() = bytes.copyOfRange(12, bytes.size)
    val hexString: String get() = toHex(bytes)
    override fun toString(): String = "WireFrame($hexString)"
}

/** A whole message: one DATA frame, or a control message's FIRST … LAST fragments joined. */
class WireMessage(val op: Int, val id: Int, val body: ByteArray) {
    override fun toString(): String = "WireMessage(op=0x%02x, id=%d, body=%s)".format(op, id, toHex(body))
}

/**
 * Joins frames into messages and fails the test unless the framing is legal: header version 1 and
 * reserved 0, DATA with flags 0 and a body, control fragments contiguous (FIRST, continuations,
 * LAST) with one opcode and stream ID, and no flag bits beyond FIRST/LAST.
 */
fun messagesOf(frames: List<WireFrame>): List<WireMessage> {
    val out = ArrayList<WireMessage>()
    var head: WireFrame? = null
    val joined = ByteArrayOutputStream()
    for (f in frames) {
        assertEquals("version: $f", 1, f.version)
        assertEquals("reserved: $f", 0, f.reserved)
        assertEquals("length field: $f", f.size - 12, f.length)
        if (f.op == Op.DATA) {
            assertTrue("DATA inside a control message: $f", head == null)
            assertEquals("DATA flags: $f", 0, f.flags)
            assertTrue("empty DATA: $f", f.length > 0)
            out += WireMessage(f.op, f.id, f.body)
            continue
        }
        assertEquals("flag bits: $f", 0, f.flags and F_BOTH.inv())
        if (f.flags and F_FIRST != 0) {
            assertTrue("FIRST while assembling: $f", head == null)
            head = f
            joined.reset()
        } else {
            val first = head ?: throw AssertionError("continuation without FIRST: $f")
            assertEquals("continuation opcode: $f", first.op, f.op)
            assertEquals("continuation stream: $f", first.id, f.id)
        }
        joined.write(f.body)
        if (f.flags and F_LAST != 0) {
            out += WireMessage(f.op, f.id, joined.toByteArray())
            head = null
        }
    }
    assertTrue("unfinished control message", head == null)
    return out
}

/** Hands one ATT value to the session, as the GATT server's RX write handler would. */
fun deliver(session: PipeSession, frame: ByteArray, now: Long = 1): PipeSession.Rx =
    session.receive(frame, frame.size, now)

/** [deliver] for a hex fixture. */
fun deliver(session: PipeSession, hexFrame: String, now: Long = 1): PipeSession.Rx = deliver(session, hex(hexFrame), now)

/**
 * Submits everything the phone has ready, as the engine does when the BLE stack takes every
 * notification: peek, then accept. Returns the frames in order; each must fit the session's
 * current value. Fails the test after [limit] frames (a runaway producer).
 */
fun drain(session: PipeSession, limit: Int = 2000): List<WireFrame> {
    val out = ArrayList<WireFrame>()
    val buf = ByteArray(512)
    while (true) {
        val n = session.peek(buf)
        if (n == 0) return out
        assertTrue("phone frame of $n bytes exceeds value ${session.value}", n <= session.value)
        out += WireFrame(buf.copyOf(n))
        assertTrue("accept() refused the frame peek() returned", session.accept())
        if (out.size > limit) fail("the phone produced more than $limit frames")
    }
}

/** Drains the phone and compares its frames with [expected] hex fixtures, in order. */
fun assertDrains(session: PipeSession, vararg expected: String) {
    assertEquals(expected.map { toHex(hex(it)) }, drain(session).map { it.hexString })
}

/**
 * The device (P4) end of the link. Encodes exactly like the firmware harness `Peer`
 * (phone_pipe_harness.cpp:7-19), the same greedy algorithm as the core's `Session::peek`:
 * fragments carry at most [value]−12 body bytes; DATA uses flags 0; a control message's first
 * fragment has FIRST and its final one LAST (a one-frame message, empty body included, is
 * FIRST|LAST); one direction-wide [seq] numbers every fragment from 0.
 *
 * [value] starts as the device's local limit (HELLO is framed at it) and must become the
 * HELLO_ACK's max_value afterwards ([PipeRig.hello] does that).
 */
class Device(var value: Int = 512) {
    /** Sequence number of the next frame this device produces. */
    var seq = 0L

    /** The fragments of one message; each takes the next sequence number. */
    fun frames(op: Int, id: Int, body: ByteArray = ByteArray(0)): List<ByteArray> {
        val room = value - 12
        require(room > 0) { "device value $value cannot carry a body" }
        val out = ArrayList<ByteArray>()
        var offset = 0
        do {
            val n = minOf(body.size - offset, room)
            val flags = if (op == Op.DATA) 0 else (if (offset == 0) F_FIRST else 0) or (if (offset + n == body.size) F_LAST else 0)
            out += rawFrame(op, id, seq++, flags, body.copyOfRange(offset, offset + n))
            offset += n
        } while (offset < body.size)
        return out
    }

    fun frames(op: PipeOp, id: Int, body: ByteArray = ByteArray(0)): List<ByteArray> = frames(op.code, id, body)

    /** One frame with explicit [flags] (no fragmentation) at the next sequence number. */
    fun frame(op: Int, id: Int, flags: Int, body: ByteArray = ByteArray(0)): ByteArray = rawFrame(op, id, seq++, flags, body)

    /** Delivers the message's fragments in order; stops at the first one not ACCEPTED and returns that. */
    fun send(session: PipeSession, op: Int, id: Int, body: ByteArray = ByteArray(0), now: Long = 1): PipeSession.Rx {
        var result = PipeSession.Rx.ACCEPTED
        for (f in frames(op, id, body)) {
            result = session.receive(f, f.size, now)
            if (result != PipeSession.Rx.ACCEPTED) break
        }
        return result
    }

    fun send(session: PipeSession, op: PipeOp, id: Int, body: ByteArray = ByteArray(0), now: Long = 1): PipeSession.Rx =
        send(session, op.code, id, body, now)
}

/**
 * One phone session under test with its device peer. The session is created at [start] (the
 * engine creates it when CONFIRM_ACK verifies) with local inner-frame limit [maxFrame], and the
 * device frames at that same limit until HELLO_ACK; [admit] decides OPENs (null = approve and
 * connect) and [admitted] records every target the session asked about, in order. [random] is the
 * injected RNG (phone nonce by default).
 *
 * [mtu] is kept for existing callers and only picks the default [maxFrame]: the pre-stage-4 frame
 * size `min(mtu - 3, 512)` ([specValue]), so fixtures recorded at an old MTU keep their framing.
 * Stage-4 sizing passes `maxFrame = specFrame(mtu)` (P) instead.
 */
class PipeRig(
    val mtu: Int = 517,
    start: Long = 0,
    private val admit: (PipeTarget) -> PipeError? = { null },
    val random: FakeRandom = FakeRandom(PHONE_NONCE),
    val maxFrame: Int = specValue(mtu),
) {
    val admitted = ArrayList<PipeTarget>()
    val session = PipeSession(maxFrame, start, { target -> admitted.add(target); admit(target) }, random)
    val device = Device(maxFrame)

    /**
     * Device HELLO offering [offer] (framed at the device's current value), then drains the
     * phone's reply, checks it is exactly one HELLO_ACK, and switches device framing to its
     * max_value. Returns the HELLO_ACK frames.
     */
    fun hello(offer: Int = device.value, now: Long = 1): List<WireFrame> {
        assertEquals("HELLO", PipeSession.Rx.ACCEPTED, device.send(session, Op.HELLO, 0, helloBody(offer), now))
        val frames = drain(session)
        val ack = messagesOf(frames).single()
        assertEquals("HELLO_ACK opcode", Op.HELLO_ACK, ack.op)
        assertEquals("HELLO_ACK length", 20, ack.body.size)
        device.value = u16(ack.body, 18)
        return frames
    }

    /**
     * Device OPEN of stream [id] (default 192.0.2.1:443, receive_limit 1024), the single TCP
     * connect succeeding, and everything the phone then has ready submitted (OPEN_OK first).
     */
    fun open(id: Int, body: ByteArray = openBody(ADDR_IPV4, TEST_IPV4, 443), now: Long = 1): List<WireFrame> {
        assertEquals("OPEN $id", PipeSession.Rx.ACCEPTED, device.send(session, Op.OPEN, id, body, now))
        assertTrue("connected($id) refused", session.connected(id, now))
        return drain(session)
    }
}
