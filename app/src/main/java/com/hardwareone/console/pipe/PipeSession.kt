package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeWire.CONTROL_CAP
import com.hardwareone.console.pipe.PipeWire.CONTROL_QUEUE
import com.hardwareone.console.pipe.PipeWire.FIRST
import com.hardwareone.console.pipe.PipeWire.HEADER
import com.hardwareone.console.pipe.PipeWire.LAST
import com.hardwareone.console.pipe.PipeWire.MAX_STREAMS
import com.hardwareone.console.pipe.PipeWire.MIN_VALUE
import com.hardwareone.console.pipe.PipeWire.U32_MAX
import com.hardwareone.console.pipe.PipeWire.VALUE_CAP
import com.hardwareone.console.pipe.PipeWire.VERSION
import com.hardwareone.console.pipe.PipeWire.WINDOW
import com.hardwareone.console.pipe.PipeWire.get16
import com.hardwareone.console.pipe.PipeWire.get32
import com.hardwareone.console.pipe.PipeWire.put16
import com.hardwareone.console.pipe.PipeWire.put32

/**
 * The phone's half of pipe v1: a pure, bounded protocol and stream-state owner for ONE
 * authenticated session. No Bluetooth, crypto, sockets, threads or clocks: the engine decrypts
 * each secure DATA value into one inner frame, calls every method under a single lock and passes
 * `now` in milliseconds. The session is created when CONFIRM_ACK verifies, so its HELLO deadline
 * starts there, and a fresh connection gets a fresh instance: nothing (sequence numbers, stream
 * IDs, buffers) survives a reconnect.
 *
 * Storage is fixed at construction: two stream slots with one 4 KiB ring per direction, eight
 * control-message descriptors and one 272-byte reassembly buffer. Peer-supplied lengths never
 * size an allocation.
 *
 * The phone never initiates CLOSE: its own stream failures are RESET (terminal, no ACK) and TCP
 * EOF becomes FIN. It never opens streams, retransmits, or revives a retired stream ID.
 */
class PipeSession(
    /** Largest inner frame the secure channel carries, P = V - 25; HELLO_ACK may choose less. */
    maxFrame: Int,
    now: Long,
    /** Decides an OPEN before any DNS/TCP (allowlist first, then VPN). null = go ahead and connect. */
    private val admit: (PipeTarget) -> PipeError?,
    /** Fills the array from a cryptographic RNG (phone nonce, PING cookies). */
    private val random: (ByteArray) -> Unit,
    /** Per-stream outcomes for diagnostics (F3): what ended, how, and when its OPEN arrived. */
    private val outcome: (Outcome, PipeError?, Long) -> Unit = { _, _, _ -> },
    /** Every stream leaving the session, for the qualification log (row 10). */
    private val streamEnd: (StreamEnd) -> Unit = {},
) {
    /** A stream outcome: the phone's OPEN_ERR or RESET, or the device's RESET or CLOSE. */
    enum class Outcome { OPEN_ERR, LOCAL_RESET, PEER_RESET, PEER_CLOSE }

    /**
     * A stream that is gone. [how] is `close:<reason>`, `reset:<code>` (either side; [byPeer]),
     * `open_err:<code>` or `session_end`. Byte counts are DATA body bytes; no target or content.
     */
    class StreamEnd(
        val id: Int,
        val openedAt: Long,
        val okSubmitted: Boolean,
        val how: String,
        val byPeer: Boolean,
        val bytesToDevice: Long,
        val bytesFromDevice: Long,
    )

    enum class State { AWAIT_HELLO, READY, FAILED }
    enum class StreamState { FREE, CONNECTING, OPEN }
    enum class Rx { ACCEPTED, BAD_LENGTH, REJECTED }

    class Stream internal constructor() {
        var id = 0
            internal set
        var state = StreamState.FREE
            internal set
        var target: PipeTarget? = null
            internal set
        /** TCP → device bytes waiting to be sent as DATA. */
        internal val up = ByteRing(WINDOW)
        /** Device → TCP bytes waiting for the socket writer. */
        internal val down = ByteRing(WINDOW)
        /** DATA body bytes submitted to the device / its cumulative allowance for them. */
        var sent = 0L
            internal set
        var peerLimit = 0L
            internal set
        /** DATA body bytes received from the device / accepted by TCP writes / granted to it. */
        var received = 0L
            internal set
        var consumed = 0L
            internal set
        var receiveLimit = 0L
            internal set
        internal var queuedLimit = 0L
        internal var creditedConsumed = 0L
        internal var creditAt = 0L
        internal var openedAt = 0L
        /** OPEN_OK has been submitted; until then the device may only CLOSE or RESET. */
        internal var okSubmitted = false
        var peerFin = false
            internal set
        var tcpEof = false
            internal set
        internal var finQueued = false
        internal var outputShut = false
        val live: Boolean get() = state != StreamState.FREE
        val upBuffered: Int get() = up.size
        val downBuffered: Int get() = down.size

        internal fun reset() {
            id = 0; state = StreamState.FREE; target = null
            up.clear(); down.clear()
            sent = 0; peerLimit = 0; received = 0; consumed = 0; receiveLimit = 0
            queuedLimit = 0; creditedConsumed = 0; creditAt = 0; openedAt = 0
            okSubmitted = false; peerFin = false; tcpEof = false; finQueued = false; outputShut = false
        }
    }

    private class Message {
        var op = PipeOp.RESET
        var id = 0
        var len = 0
        var offset = 0
        val body = ByteArray(CONTROL_CAP)
    }

    var state = State.AWAIT_HELLO
        private set
    var error: PipeError? = null
        private set
    /** True when the device ended the session with RESET on stream 0 (nothing to send back). */
    var failedByPeer = false
        private set
    /** Current max inner frame: the local limit until HELLO_ACK, then the negotiated one. */
    var value: Int = maxFrame
        private set
    /** HELLO_ACK has been accepted by the stack (the phone's local readiness, nothing more). */
    var helloAckSent = false
        private set
    /** OPENs the device sent in this session, admitted or refused (evidence of its progress). */
    var opens = 0
        private set
    /** The device's pipe HELLO nonce, as on the wire: the session's `corr` in both sides' logs. */
    var deviceNonce: ByteArray? = null
        private set
    var txSequence = 0L
        private set
    var rxSequence = 0L
        private set
    val streams = arrayOf(Stream(), Stream())

    private var highestId = 0
    private val startedAt = now
    private var lastRx = now
    private var pingPending = false
    private var pingAt = 0L
    private var pingCookie = 0L

    private val messages = Array(CONTROL_QUEUE) { Message() }
    private var front = 0
    private var count = 0
    private var round = 0

    private val packet = ByteArray(VALUE_CAP)
    private var packetSize = 0
    private var pending = false
    private var pendingControl = false

    private val assembly = ByteArray(CONTROL_CAP)
    private var assembling = false
    private var assemblyOp = PipeOp.RESET
    private var assemblyId = 0
    private var assemblySize = 0
    private var assemblyAt = 0L

    init {
        if (value < MIN_VALUE || value > VALUE_CAP) fail(PipeError.BAD_PROTOCOL)
    }

    val liveStreams: Int get() = streams.count { it.live }
    val queuedControl: Int get() = count

    fun find(id: Int): Stream? = if (id == 0) null else streams.firstOrNull { it.id == id && it.live }

    // ---- Device → phone -------------------------------------------------------------------

    /**
     * Validates and takes one RX value. ACCEPTED means the frame is copied into owned state and
     * the write may be acknowledged. Anything else has already failed the whole session.
     */
    fun receive(frame: ByteArray, size: Int, now: Long): Rx {
        if (state == State.FAILED) return Rx.REJECTED
        if (size < HEADER || size > frame.size || size > value || get16(frame, 8) != size - HEADER) {
            fail(PipeError.BAD_PROTOCOL)
            return Rx.BAD_LENGTH
        }
        if (frame[0].toInt() != VERSION) return reject(PipeError.UNSUPPORTED_VERSION)
        if (frame[11].toInt() != 0 || get32(frame, 4) != rxSequence || rxSequence == U32_MAX) return reject(PipeError.BAD_PROTOCOL)
        rxSequence++
        lastRx = now
        val op = PipeOp.of(frame[1].toInt() and 0xff) ?: return reject(PipeError.BAD_PROTOCOL)
        val id = get16(frame, 2)
        val n = size - HEADER
        val flags = frame[10].toInt() and 0xff

        if (op == PipeOp.DATA) {
            if (flags != 0 || assembling || n == 0 || id == 0 || state != State.READY) return reject(PipeError.BAD_PROTOCOL)
            val s = find(id) ?: return if (retired(id)) Rx.ACCEPTED else reject(PipeError.BAD_PROTOCOL)
            if (s.state != StreamState.OPEN || !s.okSubmitted || s.peerFin) return reject(PipeError.BAD_PROTOCOL)
            if (s.received + n > s.receiveLimit || n > s.down.space) return reject(PipeError.FLOW_VIOLATION)
            s.down.push(frame, HEADER, n)
            s.received += n
            return Rx.ACCEPTED
        }

        // Control: FIRST [continuation…] LAST, contiguous, one buffer, same opcode and stream.
        if (flags and (FIRST or LAST).inv() != 0) return reject(PipeError.BAD_PROTOCOL)
        if (flags == 0 && !assembling) return reject(PipeError.BAD_PROTOCOL)
        if (n == 0 && flags != (FIRST or LAST)) return reject(PipeError.BAD_PROTOCOL)
        if (flags and FIRST != 0) {
            if (assembling) return reject(PipeError.BAD_PROTOCOL)
            assembling = true; assemblyOp = op; assemblyId = id; assemblySize = 0; assemblyAt = now
        }
        // An orphan LAST (or continuation) never joins a finished message's leftovers.
        if (!assembling || op != assemblyOp || id != assemblyId || n > CONTROL_CAP - assemblySize) return reject(PipeError.BAD_PROTOCOL)
        System.arraycopy(frame, HEADER, assembly, assemblySize, n)
        assemblySize += n
        if (flags and LAST == 0) return Rx.ACCEPTED
        assembling = false
        return if (control(op, id, assembly, assemblySize, now)) Rx.ACCEPTED else Rx.REJECTED
    }

    private fun control(op: PipeOp, id: Int, b: ByteArray, n: Int, now: Long): Boolean {
        if (id == 0) {
            when {
                op == PipeOp.RESET && n == 2 && PipeError.of(get16(b, 0)) != null -> {
                    failedByPeer = true
                    return fail(PipeError.of(get16(b, 0))!!)
                }
                op == PipeOp.HELLO && state == State.AWAIT_HELLO -> return hello(b, n)
                op == PipeOp.PING && n == 4 && state == State.READY -> return queue(PipeOp.PONG, 0, b, 4)
                op == PipeOp.PONG && n == 4 && state == State.READY && pingPending && get32(b, 0) == pingCookie -> {
                    pingPending = false
                    return true
                }
            }
            return fail(PipeError.BAD_PROTOCOL)
        }
        if (state != State.READY) return fail(PipeError.BAD_PROTOCOL)
        val valid = when (op) {
            PipeOp.OPEN -> n >= 8 && n == 8 + (b[7].toInt() and 0xff)
            PipeOp.CREDIT, PipeOp.FIN -> n == 4
            PipeOp.CLOSE -> n == 2 && get16(b, 0) <= 1
            PipeOp.CLOSE_ACK -> n == 0
            PipeOp.RESET -> n == 2 && PipeError.of(get16(b, 0)) != null
            else -> false
        }
        if (!valid) return fail(PipeError.BAD_PROTOCOL)
        if (op == PipeOp.OPEN) return open(id, b, n, now)

        val s = find(id)
        if (s == null) {
            // IDs are never reused: a retired stream's late frames are discarded (a late CLOSE is
            // still acknowledged); a frame for an ID never opened is a session error.
            if (id > highestId) return fail(PipeError.BAD_PROTOCOL)
            return if (op == PipeOp.CLOSE) queue(PipeOp.CLOSE_ACK, id, b, 0) else true
        }
        when (op) {
            PipeOp.CLOSE -> {
                outcome(Outcome.PEER_CLOSE, null, s.openedAt)
                record(s, "close:${get16(b, 0)}", byPeer = true)
                end(s)
                return queue(PipeOp.CLOSE_ACK, id, b, 0)
            }
            PipeOp.RESET -> {
                outcome(Outcome.PEER_RESET, PipeError.of(get16(b, 0)), s.openedAt)
                record(s, "reset:${get16(b, 0)}", byPeer = true)
                end(s)
                return true
            }
            else -> {}
        }
        if (s.state != StreamState.OPEN || !s.okSubmitted) return fail(PipeError.BAD_PROTOCOL)
        when (op) {
            PipeOp.CREDIT -> {
                val limit = get32(b, 0)
                if (limit < s.peerLimit || limit > s.sent + WINDOW) return fail(PipeError.FLOW_VIOLATION)
                s.peerLimit = limit
                return true
            }
            PipeOp.FIN -> {
                if (s.peerFin || get32(b, 0) != s.received) return fail(PipeError.BAD_PROTOCOL)
                s.peerFin = true
                return true
            }
            else -> return fail(PipeError.BAD_PROTOCOL) // CLOSE_ACK: the phone never sends CLOSE
        }
    }

    private fun hello(b: ByteArray, n: Int): Boolean {
        // v1 offers exactly two streams each way; the firmware also requires the ACK to say 2.
        if (n != 12 || !nonzero(b, 0, 8) || (b[8].toInt() and 0xff) != MAX_STREAMS || b[9].toInt() != 0) return fail(PipeError.BAD_PROTOCOL)
        val offer = get16(b, 10)
        if (offer < MIN_VALUE || offer > VALUE_CAP) return fail(PipeError.BAD_PROTOCOL)
        deviceNonce = b.copyOfRange(0, 8)
        val chosen = minOf(value, offer)
        if (chosen < MIN_VALUE) return fail(PipeError.BAD_PROTOCOL)
        val ack = ByteArray(20)
        System.arraycopy(b, 0, ack, 0, 8)
        val nonce = ByteArray(8)
        repeat(8) {
            if (!nonzero(nonce, 0, 8)) random(nonce)
        }
        if (!nonzero(nonce, 0, 8)) return fail(PipeError.NO_RESOURCES)
        System.arraycopy(nonce, 0, ack, 8, 8)
        ack[16] = MAX_STREAMS.toByte()
        put16(ack, 18, chosen)
        // HELLO_ACK and everything after it is framed at the chosen limit.
        value = chosen
        state = State.READY
        return queue(PipeOp.HELLO_ACK, 0, ack, ack.size)
    }

    private fun open(id: Int, b: ByteArray, n: Int, now: Long): Boolean {
        // IDs only increase; gaps are allowed and a skipped ID is retired (Q12).
        if (id <= highestId) return fail(PipeError.BAD_PROTOCOL) // duplicate or non-monotonic
        opens++
        val limit = get32(b, 0)
        val port = get16(b, 4)
        val type = b[6].toInt() and 0xff
        val length = b[7].toInt() and 0xff
        if (limit < 1 || limit > WINDOW || port == 0) return fail(PipeError.BAD_PROTOCOL)
        val target = PipeTarget.fromWire(type, b, 8, length, port) ?: return fail(PipeError.BAD_PROTOCOL)
        highestId = id
        val slot = streams.firstOrNull { !it.live } ?: run {
            streamEnd(StreamEnd(id, now, false, "open_err:${PipeError.BUSY.code}", false, 0, 0))
            return openError(id, PipeError.BUSY, now)
        }
        admit(target)?.let {
            streamEnd(StreamEnd(id, now, false, "open_err:${it.code}", false, 0, 0))
            return openError(id, it, now)
        }
        slot.reset()
        slot.id = id
        slot.state = StreamState.CONNECTING
        slot.target = target
        slot.peerLimit = limit
        slot.openedAt = now
        return true
    }

    private fun openError(id: Int, error: PipeError, openedAt: Long): Boolean {
        outcome(Outcome.OPEN_ERR, error, openedAt)
        val b = ByteArray(2)
        put16(b, 0, error.code)
        return queue(PipeOp.OPEN_ERR, id, b, 2)
    }

    // ---- Engine → session: TCP side --------------------------------------------------------

    /** The stream's single TCP connect succeeded. false = the stream is gone: close the socket, send nothing. */
    fun connected(id: Int, now: Long): Boolean {
        val s = find(id) ?: return false
        if (state != State.READY || s.state != StreamState.CONNECTING) return false
        val b = ByteArray(4)
        put32(b, 0, PipeWire.INITIAL_LIMIT)
        if (!queue(PipeOp.OPEN_OK, id, b, 4)) return false
        s.state = StreamState.OPEN
        s.queuedLimit = PipeWire.INITIAL_LIMIT
        s.creditAt = now
        return true
    }

    /** The connect attempt failed (or was refused late); retires the ID with OPEN_ERR. */
    fun connectFailed(id: Int, error: PipeError) {
        val s = find(id) ?: return
        if (state != State.READY || s.state != StreamState.CONNECTING) return
        val openedAt = s.openedAt
        record(s, "open_err:${error.code}", byPeer = false)
        s.reset()
        openError(id, error, openedAt)
    }

    /**
     * Local stream failure (TCP error, VPN loss, destination change). Until its OPEN_OK has started
     * going out the device has seen no OPEN_OK, so it gets OPEN_ERR (purging the OPEN_OK); after that,
     * RESET. Nothing not yet started for the stream (OPEN_OK, CREDIT, FIN) goes out first.
     */
    fun resetStream(id: Int, error: PipeError) {
        val s = find(id) ?: return
        if (state != State.READY) return
        if (s.state == StreamState.CONNECTING) return connectFailed(id, error)
        val okStarted = s.okSubmitted || controlStarted(PipeOp.OPEN_OK, id)
        val openedAt = s.openedAt
        record(s, if (okStarted) "reset:${error.code}" else "open_err:${error.code}", byPeer = false)
        end(s)
        if (!okStarted) {
            openError(id, error, openedAt)
            return
        }
        outcome(Outcome.LOCAL_RESET, error, openedAt)
        val b = ByteArray(2)
        put16(b, 0, error.code)
        queue(PipeOp.RESET, id, b, 2)
    }

    /** Free space for TCP reads on this stream; reads are never larger. */
    fun upSpace(id: Int): Int {
        val s = find(id) ?: return 0
        return if (state == State.READY && s.state == StreamState.OPEN && !s.tcpEof) s.up.space else 0
    }

    fun tcpRead(id: Int, src: ByteArray, off: Int, n: Int): Int {
        if (upSpace(id) < n) return 0
        return find(id)!!.up.push(src, off, n)
    }

    fun tcpEof(id: Int) {
        val s = find(id) ?: return
        if (s.state == StreamState.OPEN) s.tcpEof = true
    }

    /** Copies bytes for the socket writer without consuming them. */
    fun downPeek(id: Int, dst: ByteArray): Int {
        val s = find(id) ?: return 0
        if (state != State.READY || s.state != StreamState.OPEN) return 0
        return s.down.peek(dst, 0, dst.size)
    }

    /** A TCP write accepted [n] bytes: they are consumed (not proof of relay receipt). */
    fun downConsumed(id: Int, n: Int) {
        val s = find(id) ?: return
        if (state != State.READY || s.state != StreamState.OPEN || n > s.down.size) return
        s.down.drop(n)
        s.consumed += n
    }

    /** The device's FIN has drained to the socket: time for `shutdownOutput()`. */
    fun shutdownDue(id: Int): Boolean {
        val s = find(id) ?: return false
        return state == State.READY && s.state == StreamState.OPEN && s.peerFin && s.down.size == 0 && !s.outputShut
    }

    fun outputShutdown(id: Int) {
        find(id)?.outputShut = true
    }

    // ---- Timers ----------------------------------------------------------------------------

    /** Deadlines, coalesced CREDIT, FIN after drain, and PING. false = the session failed. */
    fun tick(now: Long): Boolean {
        if (state == State.FAILED) return false
        if (state == State.AWAIT_HELLO && now - startedAt >= PipeWire.HELLO_DEADLINE_MS) return fail(PipeError.DEADLINE)
        if (assembling && now - assemblyAt >= PipeWire.REASSEMBLY_DEADLINE_MS) return fail(PipeError.DEADLINE)
        if (state != State.READY) return true
        var live = false
        for (s in streams) {
            if (!s.live) continue
            live = true
            if (s.state == StreamState.CONNECTING) {
                if (now - s.openedAt >= PipeWire.CONNECT_DEADLINE_MS) connectFailed(s.id, PipeError.CONNECT_TIMEOUT)
                continue
            }
            if (!s.okSubmitted) continue
            // FIN only once every byte read from TCP has been submitted as DATA.
            if (s.tcpEof && !s.finQueued && s.up.size == 0) {
                val b = ByteArray(4)
                put32(b, 0, s.sent)
                if (!queue(PipeOp.FIN, s.id, b, 4)) return false
                s.finQueued = true
            }
            if (s.peerFin) continue
            if (s.consumed > U32_MAX - WINDOW) return fail(PipeError.FLOW_VIOLATION)
            val progressed = s.consumed - s.creditedConsumed
            if (progressed > 0 && (progressed >= PipeWire.CREDIT_COALESCE_BYTES || now - s.creditAt >= PipeWire.CREDIT_COALESCE_MS)) {
                val limit = s.consumed + WINDOW
                if (limit > s.queuedLimit) {
                    val b = ByteArray(4)
                    put32(b, 0, limit)
                    if (!queue(PipeOp.CREDIT, s.id, b, 4)) return false
                    s.queuedLimit = limit
                }
                s.creditedConsumed = s.consumed
                s.creditAt = now
            }
        }
        if (pingPending && now - pingAt >= PipeWire.PONG_DEADLINE_MS) return fail(PipeError.DEADLINE)
        if (live && !pingPending && now - lastRx >= PipeWire.PING_IDLE_MS) {
            val b = ByteArray(4)
            random(b)
            pingCookie = get32(b, 0)
            pingAt = now
            pingPending = true
            return queue(PipeOp.PING, 0, b, 4)
        }
        return true
    }

    // ---- Phone → device: one submission at a time ------------------------------------------

    /**
     * Prepares the next TX value (control first, then round-robin DATA within credit) and returns
     * its size, 0 when idle. The same frame is returned until [accept]: the engine submits it and
     * calls [accept] only when the stack definitely took it; any other outcome fails the session.
     * The sequence number is consumed at [accept], so nothing prepared-but-unsent leaves a gap.
     */
    fun peek(out: ByteArray): Int {
        if (state != State.READY) return 0
        if (pending) {
            System.arraycopy(packet, 0, out, 0, packetSize)
            return packetSize
        }
        if (txSequence >= U32_MAX) {
            fail(PipeError.BAD_PROTOCOL)
            return 0
        }
        val room = value - HEADER
        var n: Int
        val op: PipeOp
        val id: Int
        var flags = 0
        if (count > 0) {
            val m = messages[front]
            op = m.op; id = m.id
            n = minOf(m.len - m.offset, room)
            flags = (if (m.offset == 0) FIRST else 0) or (if (m.offset + n == m.len) LAST else 0)
            System.arraycopy(m.body, m.offset, packet, HEADER, n)
            pendingControl = true
        } else {
            var chosen: Stream? = null
            for (i in 0 until MAX_STREAMS) {
                val c = streams[(round + i) % MAX_STREAMS]
                if (c.state == StreamState.OPEN && c.okSubmitted && c.up.size > 0 && c.peerLimit > c.sent) {
                    chosen = c
                    round = (round + i + 1) % MAX_STREAMS
                    break
                }
            }
            val s = chosen ?: return 0
            n = minOf(room.toLong(), s.peerLimit - s.sent).toInt()
            n = s.up.peek(packet, HEADER, n)
            op = PipeOp.DATA; id = s.id
            pendingControl = false
        }
        packet[0] = VERSION.toByte()
        packet[1] = op.code.toByte()
        put16(packet, 2, id)
        put32(packet, 4, txSequence)
        put16(packet, 8, n)
        packet[10] = flags.toByte()
        packet[11] = 0
        packetSize = n + HEADER
        pending = true
        System.arraycopy(packet, 0, out, 0, packetSize)
        return packetSize
    }

    /** The stack accepted the frame from [peek]: consume its sequence and bytes. */
    fun accept(): Boolean {
        if (!pending) return false
        val n = packetSize - HEADER
        if (pendingControl) {
            val m = messages[front]
            m.offset += n
            if (m.offset == m.len) {
                if (m.op == PipeOp.HELLO_ACK) helloAckSent = true
                val s = find(m.id)
                if (s != null && s.state == StreamState.OPEN) {
                    if (m.op == PipeOp.OPEN_OK) {
                        s.receiveLimit = get32(m.body, 0)
                        s.okSubmitted = true
                    } else if (m.op == PipeOp.CREDIT) {
                        s.receiveLimit = get32(m.body, 0)
                    }
                }
                front = (front + 1) % CONTROL_QUEUE
                count--
            }
        } else {
            val s = find(get16(packet, 2))
            // A stream reset while its DATA was being submitted keeps nothing to drop.
            if (s != null && s.state == StreamState.OPEN) {
                if (s.up.size < n) return fail(PipeError.BAD_PROTOCOL)
                s.up.drop(n)
                s.sent += n
            }
        }
        txSequence++
        pending = false
        return true
    }

    /** Best-effort stream-0 RESET for a failed session; 0 when there is nothing safe to send. */
    fun failureFrame(out: ByteArray): Int {
        val why = error ?: return 0
        if (state != State.FAILED || failedByPeer || txSequence >= U32_MAX) return 0
        out[0] = VERSION.toByte()
        out[1] = PipeOp.RESET.code.toByte()
        put16(out, 2, 0)
        put32(out, 4, txSequence)
        put16(out, 8, 2)
        out[10] = (FIRST or LAST).toByte()
        out[11] = 0
        put16(out, HEADER, why.code)
        return HEADER + 2
    }

    /** Ends everything: every stream, queue and reassembly. Idempotent; the first reason wins. */
    fun fail(why: PipeError): Boolean {
        if (state != State.FAILED) {
            state = State.FAILED
            error = why
        }
        for (s in streams) {
            if (s.live) record(s, "session_end", byPeer = failedByPeer)
            s.reset()
        }
        count = 0
        pending = false
        assembling = false
        return false
    }

    private fun reject(why: PipeError): Rx {
        fail(why)
        return Rx.REJECTED
    }

    private fun retired(id: Int): Boolean = id in 1..highestId && find(id) == null

    private fun record(s: Stream, how: String, byPeer: Boolean) =
        streamEnd(StreamEnd(s.id, s.openedAt, s.okSubmitted, how, byPeer, s.sent, s.received))

    /** A control message of [op] for [id] has started going out (partly submitted or pending). */
    private fun controlStarted(op: PipeOp, id: Int): Boolean {
        for (i in 0 until count) {
            val m = messages[(front + i) % CONTROL_QUEUE]
            if (m.op == op && m.id == id && (m.offset > 0 || (i == 0 && pending && pendingControl))) return true
        }
        return false
    }

    /**
     * [s] ends (the device's CLOSE/RESET, or a local reset): drop it and every not-yet-started
     * message for it, so no OPEN_OK, CREDIT or FIN follows. A message already being submitted stays.
     */
    private fun end(s: Stream) {
        val id = s.id
        s.reset()
        var kept = 0
        for (i in 0 until count) {
            val m = messages[(front + i) % CONTROL_QUEUE]
            val started = m.offset > 0 || (i == 0 && pending && pendingControl)
            if (m.id == id && !started) continue
            if (kept != i) {
                val dst = messages[(front + kept) % CONTROL_QUEUE]
                dst.op = m.op; dst.id = m.id; dst.len = m.len; dst.offset = m.offset
                System.arraycopy(m.body, 0, dst.body, 0, m.len)
            }
            kept++
        }
        count = kept
    }

    private fun queue(op: PipeOp, id: Int, b: ByteArray, n: Int): Boolean {
        if (n > CONTROL_CAP) return fail(PipeError.BAD_PROTOCOL)
        if (op == PipeOp.CREDIT) {
            // Coalesce: an unstarted CREDIT for the same stream just takes the newer limit.
            for (i in 0 until count) {
                val m = messages[(front + i) % CONTROL_QUEUE]
                if (m.op == op && m.id == id && m.offset == 0 && !(i == 0 && pending && pendingControl)) {
                    System.arraycopy(b, 0, m.body, 0, n)
                    return true
                }
            }
        }
        if (count == CONTROL_QUEUE) return fail(PipeError.NO_RESOURCES)
        val m = messages[(front + count) % CONTROL_QUEUE]
        m.op = op; m.id = id; m.len = n; m.offset = 0
        if (n > 0) System.arraycopy(b, 0, m.body, 0, n)
        count++
        return true
    }

    private fun nonzero(b: ByteArray, off: Int, n: Int): Boolean {
        for (i in off until off + n) if (b[i].toInt() != 0) return true
        return false
    }
}
