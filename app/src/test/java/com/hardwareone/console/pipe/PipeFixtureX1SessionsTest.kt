package com.hardwareone.console.pipe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Firmware fixture X1, `phone_pipe_v1_sessions.json`, copied verbatim from hardwareone-idf
 * `tools/ble_secure/fixtures/` (SHA-256 checked below). It holds four complete HW1-PIPE-v1 sessions
 * at ATT MTU 64, 244, 247 and 517, written by the P4's production adapter (`BLE_PhoneGatt.cpp`,
 * `BLE_PhoneSecureChannel.h`, `BLE_PhonePipeCore.h`) against a scripted phone: CCCD, secure
 * handshake, pipe HELLO, stream 1 (IPv4, 1100 B up, 600 B down, PING both ways, FIN, CLOSE 0) and
 * stream 2 (DNS, 40 B up, 30 B down, CLOSE 1).
 *
 * The P4 → phone values are replayed into Android's own [PipeSecureChannel] and [PipeSession],
 * wired by [Phone] the way [PipeEngine] wires them (see there). Android's entropy is
 * the fixture's: the phone private key and secure nonce, then the pipe HELLO_ACK nonce, then (only
 * if Android decides to PING) the fixture phone's PING cookie, all through [FakeRandom]. The PSK is
 * derived from the fixture's passphrase by [PipeSecret]. What the phone's *application* does (the
 * TCP connect completing, the 600 and 30 bytes the relay sends, its EOF) is TCP input; what
 * Android sends, and when, is decided by Android's own code.
 *
 * The fixture's `script` (five lines) says what that application does. [Script] parses it: every
 * line must match its expected wording exactly, its numbers must be the ones the fixture's frames
 * carry ([x1FramesFollowTheScript]), and the TCP input each replay applies (inferred from the phone
 * frames' opcodes by [applyApplication]) must equal the script's ([Script.application]).
 *
 * Two replays per session:
 *
 *  - **Aligned** ([replayAligned]): every phone → P4 value must equal the fixture's `value_hex`
 *    byte for byte, in order (outer counter, inner sequence, opcode, stream, flags and body
 *    included), interleaved with the P4 frames as in the fixture, and every P4 frame must be
 *    accepted and decrypt to its `inner_hex`. The replay is lock-step: when the fixture's next frame
 *    is the phone's, the phone clock runs until Android sends its own next value, and the P4 frames
 *    after it keep their fixture spacing from that moment (the P4 reacts to what it receives). Three
 *    places in the fixture rest on timing that Android's policy does not reproduce. The replay keeps
 *    the P4 frames and their order unchanged and adjusts only the environment, so that Android's
 *    own rules produce the same bytes; each adjustment is recorded and asserted:
 *     1. *Relay reads* follow the fixture phone's CREDITs: the relay socket accepts
 *        `limit − 4096` bytes (all 1024 B of stream 1's first window) when that CREDIT is due, and
 *        nothing else. Android credits any consumed progress after 512 B or 100 ms
 *        ([PipeWire.CREDIT_COALESCE_BYTES] / [PipeWire.CREDIT_COALESCE_MS]); the fixture phone
 *        credits once, after the whole first window, and never for the last 76 B of stream 1 or
 *        the 40 B of stream 2.
 *     2. *The phone's PING*: Android PINGs only after 5 s of RX idle with a live stream
 *        (android-002 Q11; the script's "five idle seconds"); the fixture phone PINGs 60 ms after
 *        the P4's CREDIT. The replay lets Android's clock run until its own idle rule fires: a
 *        literal 4,958 ms later at MTU 64/244/247 and 4,988 ms at MTU 517 (Android's first 50 ms
 *        tick 5,018 / 5,048 ms after that CREDIT, less the fixture's 60 ms).
 *     3. *The P4's PING*: the fixture puts 5,060 ms between the P4's PONG and its own idle PING.
 *        Android's 50 ms ticker would PING first (5,000 ms idle), so the replay delivers the P4's
 *        PING 4,999 ms after its PONG: 61 ms early, at every MTU.
 *    Apart from those, each wait is one engine tick (CREDIT and FIN are timer decisions) or one
 *    30 ms notification gap. Every wait and the 61 ms cut are asserted as literal values per MTU,
 *    and the replay takes its 5 s idle from the script, not from Android's constant
 *    ([PipeWire.PING_IDLE_MS] is asserted equal to it in [x1FramesFollowTheScript]), so a change to
 *    Android's PING rule fails the aligned replays instead of moving the adjustments with it.
 *
 *    *Not covered by the aligned replays: control-first TX ordering.* Android sends a queued
 *    control message before any DATA (android-003 §4). In X1's aligned timing no submission ever
 *    has a control message and sendable DATA ready together ([Phone.choices] is asserted empty), so
 *    the byte-for-byte comparison would not notice a DATA-first order; X1's frame order cannot
 *    produce that situation without moving the application's TCP input against the script. The
 *    rule is checked where it does arise: in the at-fixture-clock replays at MTU 64 and 244, where
 *    the 30 ms notification gap leaves a coalesced CREDIT and the next DATA frame of the relay's
 *    600 B ready at the same submission and Android sends the CREDIT (each such submission
 *    asserted, with its time and opcode), and in
 *    `PipeSessionStreamTest.dataAlternatesBetweenStreamsAndControlPrecedesTheNextDataFrame`.
 *  - **At the fixture's own clock** ([replayAtFixtureClock]): no adjustments, the relay reads every
 *    byte as it arrives. This records what Android really does with the P4's timing: it credits
 *    while the first window is still arriving, never sends the 60 ms PING, and therefore refuses
 *    the P4's PONG (an unsolicited PONG is a protocol error) and ends the session.
 *
 * The MTU-247 session's handshake and first DATA values must also equal the older golden transcript
 * `phone_secure_channel_v1_vectors.json`. Targets are the fixture's documentation-only values.
 */
class PipeFixtureX1SessionsTest {

    // ---- The fixture ------------------------------------------------------------------------

    private class Fx(o: JSONObject) {
        val i = o.getInt("i")
        val t = o.getLong("t_ms")
        val fromP4 = o.getString("dir") == "p4_to_phone"
        val att: String = o.getString("att")
        val kind: String = o.getString("kind")
        val value: ByteArray = hex(o.getString("value_hex"))
        val counter: Long? = if (o.has("counter")) o.getLong("counter") else null
        val op: String? = o.optString("op").ifEmpty { null }
        val stream: Int? = if (o.has("stream")) o.getInt("stream") else null
        val seq: Long? = if (o.has("seq")) o.getLong("seq") else null
        val flags: Int? = if (o.has("flags")) o.getInt("flags") else null
        val body: ByteArray? = if (o.has("body_hex")) hex(o.getString("body_hex")) else null
        val inner: ByteArray? = if (o.has("inner_hex")) hex(o.getString("inner_hex")) else null
        override fun toString(): String = "frame $i ($kind${op?.let { " $it" } ?: ""}${stream?.let { " s$it" } ?: ""} @${t}ms)"
    }

    private class FxSession(o: JSONObject) {
        val mtu = o.getInt("att_mtu")
        val valueLimit = o.getInt("value_limit")
        val innerCapacity = o.getInt("inner_capacity")
        val maxDataBody = o.getInt("max_data_body")
        val frames: List<Fx> = o.getJSONArray("frames").let { a -> List(a.length()) { Fx(a.getJSONObject(it)) } }
        val phoneFrames: List<Fx> get() = frames.filter { !it.fromP4 }
    }

    /**
     * X1's `script`. Each line must match its pattern in full (the wording is fixed, only the
     * numbers are captured), so a reworded or reordered script fails here rather than being
     * silently reinterpreted.
     */
    private class Script(a: JSONArray) {
        val lines: List<String> = List(a.length()) { a.getString(it) }

        private fun numbers(k: Int, pattern: String): List<Long> =
            Regex(pattern).matchEntire(lines[k])?.groupValues?.drop(1)?.map { it.toLong() }
                ?: throw AssertionError("script line ${k + 1} does not read '$pattern': '${lines[k]}'")

        private val l1 = numbers(0, """secure HELLO, HELLO_ACK, CONFIRM, CONFIRM_ACK; pipe HELLO \(counter (\d+), seq (\d+)\) and HELLO_ACK""")
        private val l2 = numbers(1, """stream 1: OPEN IPv4, OPEN_OK (\d+); P4 uploads (\d+) bytes, stops at (\d+); phone CREDIT (\d+)\+(\d+)""")
        private val l3 = numbers(2, """phone sends (\d+) bytes, P4 reads them and grants CREDIT (\d+); phone PING, P4 PONG""")
        private val l4 = numbers(3, """five idle seconds with a live stream: P4 PING, phone PONG; phone FIN (\d+); P4 CLOSE (\d+), phone CLOSE_ACK""")
        private val l5 = numbers(4, """stream 2: OPEN DNS \(two fragments at MTU (\d+)\), OPEN_OK (\d+), (\d+) bytes up, (\d+) down, P4 CLOSE (\d+), CLOSE_ACK""")

        /** Line 1: the P4's pipe HELLO is outer counter 1, inner sequence 0. */
        val helloCounter = l1[0]
        val helloSeq = l1[1]
        /** Line 2: stream 1's OPEN_OK limit, the P4's upload, where it stops, and the phone's CREDIT as consumed + window. */
        val openOk1 = l2[0]
        val upload1 = l2[1]
        val stopsAt = l2[2]
        val creditConsumed = l2[3]
        val creditWindow = l2[4]
        /** Line 3: the bytes the phone's application sends on stream 1, and the P4's CREDIT for them. */
        val phoneSends = l3[0]
        val p4Credit = l3[1]
        /** Line 4: "five idle seconds" before the P4's PING; the phone's FIN; the P4's CLOSE reason. */
        val idleMs = 5_000L
        val fin = l4[0]
        val close1 = l4[1]
        /** Line 5: the MTU at which stream 2's OPEN is "two fragments"; its OPEN_OK limit, bytes each way, CLOSE reason. */
        val fragmentMtu = l5[0].toInt()
        val openOk2 = l5[1]
        val p4Sends2 = l5[2]
        val phoneSends2 = l5[3]
        val close2 = l5[4]

        /**
         * What the phone's application does, in order, as the replays record it ([Replay.app]):
         * stream 1's TCP connect completes (OPEN_OK); the relay consumes the [creditConsumed] bytes
         * the phone's CREDIT is computed from; the relay sends [phoneSends] bytes; it EOFs (FIN);
         * stream 2's connect completes; the relay sends [phoneSends2] bytes.
         */
        fun application(): List<String> = listOf(
            "connect 1", "relay 1 $creditConsumed", "tcp 1 $phoneSends", "eof 1", "connect 2", "tcp 2 $phoneSends2",
        )
    }

    private companion object {
        const val RESOURCE = "/pipe/phone_pipe_v1_sessions.json"
        /** firmware-003 §3 / firmware-004 §5 (`19ce9539…58c9`), unchanged across rounds 4 and 5. */
        const val SHA256 = "19ce9539fdd676eefa3ad624625a6772a399c6e3662efe7c7bdf8585bfc758c9"
        /** Phone clock at the CCCD write: any elapsedRealtime-like value (0 means "not yet" to the engine). */
        const val BASE = 1_000_000L
        /** How long a lock-step wait may run looking for Android's next value: more than the 5 s idle PING plus a tick and a gap. */
        const val MAX_WAIT_MS = 6_000L

        val raw: ByteArray by lazy {
            PipeFixtureX1SessionsTest::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
                ?: throw AssertionError("missing test resource $RESOURCE")
        }
        val json: JSONObject by lazy { JSONObject(String(raw, Charsets.UTF_8)) }
        val sessions: List<FxSession> by lazy {
            json.getJSONArray("sessions").let { a -> List(a.length()) { FxSession(a.getJSONObject(it)) } }
        }
        val script: Script by lazy { Script(json.getJSONArray("script")) }
        val psk: ByteArray by lazy {
            val ok = PipeSecret.normalize(json.getString("passphrase_utf8")) as PipeSecret.Result.Ok
            PipeSecret.derivePsk(ok.bytes)
        }

        fun fx(name: String): ByteArray = hex(json.getString(name))
        fun session(mtu: Int): FxSession = sessions.single { it.mtu == mtu }
    }

    // ---- Android's phone side, as PipeEngine assembles it ----------------------------------------

    /**
     * Android's phone side for one admitted session, built from the production classes the way
     * [PipeEngine] wires them, without threads or Bluetooth, on a virtual elapsedRealtime clock
     * (the engine's diagnostics hooks are left out; they send nothing):
     *
     *  - `begin` + `subscribed`: a fresh [PipeSecureChannel] whose HELLO is the first value; the
     *    engine ticker runs every [PipeEngine.TICK_MS] from `begin` (`scheduleWithFixedDelay`).
     *  - RX (`rxLocked`): the channel answers HELLO_ACK with CONFIRM; CONFIRM_ACK creates the
     *    [PipeSession] at P = V − 25 with the same RNG; each DATA plaintext goes to
     *    `PipeSession.receive` at the current time. Any failure ends the session ([ended]).
     *  - Timers (`tick`): the 4.5 s handshake cap, then `PipeSession.tick`.
     *  - Submission (`pump`): after every event, one value at a time, the next no sooner than
     *    [PipeEngine.NOTIFY_INTERVAL_MS] after the previous; inner frames ≤ P, values ≤ V, sealed by
     *    the channel and `accept`ed when submitted. Completions arrive at once with status 0.
     *  - Admission (`admit`): the allowlist holds the fixture's two targets; the VPN is up.
     *  - TCP side (`StreamIo`): [connect] is `connected()`, [tcpRead]/[tcpEof] are the reader
     *    thread, [relayReads] is the writer thread (`downPeek` + `downConsumed`).
     */
    private class Phone(val mtu: Int, psk: ByteArray, privateKey: ByteArray, val random: FakeRandom, start: Long, private val allow: List<PipeTarget>) {
        class Sent(val at: Long, val value: ByteArray, val inner: ByteArray?)

        val valueLimit = PipeWire.valueLimit(mtu)
        val frameLimit = PipeWire.frameLimit(mtu)
        val channel = PipeSecureChannel(psk, random, privateKey)
        var session: PipeSession? = null
            private set
        var now = start
            private set
        val sent = ArrayList<Sent>()
        val admitted = ArrayList<PipeTarget>()
        val outcomes = ArrayList<String>()
        val relay = HashMap<Int, ByteArrayOutputStream>()
        var ended: String? = null
            private set
        /**
         * Every submission at which a control message was queued *and* some stream had DATA it could
         * send (open, its OPEN_OK submitted, TCP bytes buffered, device credit left), as (ms after
         * the CCCD write, the opcode Android chose). Where this list is non-empty Android's
         * control-first rule decided the order.
         */
        val choices = ArrayList<Pair<Long, String>>()

        private val origin = start
        private val okSent = HashSet<Int>()
        private var nextTick = start + PipeEngine.TICK_MS
        private var handshakeTx: ByteArray? = null
        private var subscribedAt = 0L
        private var lastSubmitAt = Long.MIN_VALUE / 2
        private var pumpAt: Long? = null
        private val frame = ByteArray(PipeWire.VALUE_CAP)

        /** The CCCD enable was answered: HELLO goes out at once. */
        fun subscribe() {
            handshakeTx = channel.hello()
            subscribedAt = now
            changed()
        }

        /** One RX write command. Returns the decrypted inner frame and the session's verdict (null in the handshake). */
        fun write(value: ByteArray): Pair<ByteArray?, PipeSession.Rx?> {
            check(ended == null) { "write after the session ended: $ended" }
            var plain: ByteArray? = null
            var verdict: PipeSession.Rx? = null
            when (val r = channel.receive(value)) {
                is PipeSecureChannel.Rx.Reply ->
                    if (session == null && handshakeTx == null) handshakeTx = r.value else end("unexpected handshake reply")
                PipeSecureChannel.Rx.Established ->
                    if (session == null) {
                        session = PipeSession(
                            maxFrame = frameLimit,
                            now = now,
                            admit = { t -> admitted += t; if (t in allow) null else PipeError.TARGET_DENIED },
                            random = random,
                            outcome = { what, error, _ -> outcomes += "$what${error?.let { ":$it" } ?: ""}" },
                        )
                    } else end("second CONFIRM_ACK")
                is PipeSecureChannel.Rx.Data -> {
                    val s = session
                    if (s == null) end("DATA before CONFIRM_ACK") else {
                        plain = r.plaintext.copyOf()
                        verdict = s.receive(r.plaintext, r.plaintext.size, now)
                        if (s.state == PipeSession.State.FAILED) end("session failed: ${s.error}")
                    }
                }
                is PipeSecureChannel.Rx.Rejected -> end("REJECT ${r.reason}")
                PipeSecureChannel.Rx.Invalid -> end("channel refused the value")
            }
            changed()
            return plain to verdict
        }

        fun connect(id: Int) {
            assertTrue("connected($id) refused", session!!.connected(id, now))
            changed()
        }

        fun tcpRead(id: Int, bytes: ByteArray) {
            assertEquals("TCP read on stream $id", bytes.size, session!!.tcpRead(id, bytes, 0, bytes.size))
            changed()
        }

        fun tcpEof(id: Int) {
            session!!.tcpEof(id)
            changed()
        }

        /** The relay socket accepts [n] bytes of stream [id] (all that is buffered when null). */
        fun relayReads(id: Int, n: Int? = null) {
            val s = session!!
            val count = n ?: (s.find(id)?.downBuffered ?: 0)
            if (count == 0) return
            val buf = ByteArray(count)
            assertEquals("bytes buffered for the relay on stream $id", count, s.downPeek(id, buf))
            s.downConsumed(id, count)
            relay.getOrPut(id) { ByteArrayOutputStream() }.write(buf)
            changed()
        }

        /** Runs the engine's ticker and pumps up to [t], in time order. */
        fun advanceTo(t: Long) {
            require(t >= now) { "the phone clock never goes back ($now -> $t)" }
            while (true) {
                val p = pumpAt
                val next = if (p != null && p <= nextTick) p else nextTick
                if (next > t) break
                now = next
                if (p != null && next == p) pump() else tick()
            }
            now = t
        }

        /** Lets the phone clock run until Android submits one more value; returns how long that took, or null. */
        fun runUntilSent(maxMs: Long): Long? {
            val from = now
            val count = sent.size
            while (sent.size == count && ended == null) {
                val p = pumpAt
                val next = if (p != null && p <= nextTick) p else nextTick
                if (next - from > maxMs) return null
                now = next
                if (p != null && next == p) pump() else tick()
            }
            return if (sent.size > count) sent[count].at - from else null
        }

        private fun changed() {
            if (pumpAt == null) pumpAt = now
            while (pumpAt.let { it != null && it <= now }) pump()
        }

        private fun tick() {
            nextTick += PipeEngine.TICK_MS
            if (ended != null) return
            val s = session
            if (s == null) {
                if (subscribedAt > 0 && now - subscribedAt >= PipeEngine.HANDSHAKE_MS) end("handshake expired")
                return
            }
            s.tick(now)
            if (s.state == PipeSession.State.FAILED) {
                end("session failed: ${s.error}")
                return
            }
            if (pumpAt == null) pumpAt = now
        }

        private fun pump() {
            pumpAt = null
            if (ended != null) return
            val gapEnd = lastSubmitAt + PipeEngine.NOTIFY_INTERVAL_MS
            if (now < gapEnd) {
                pumpAt = gapEnd
                return
            }
            var inner: ByteArray? = null
            val s = session
            val value = if (s == null) {
                handshakeTx?.also { handshakeTx = null } ?: return
            } else {
                val dataReady = s.streams.any {
                    it.state == PipeSession.StreamState.OPEN && it.id in okSent && it.upBuffered > 0 && it.peerLimit > it.sent
                }
                val contended = s.queuedControl > 0 && dataReady
                val n = s.peek(frame)
                if (n == 0) return
                if (n > frameLimit) return end("inner frame of $n B over P = $frameLimit")
                inner = frame.copyOf(n)
                val op = PipeOp.of(inner[1].toInt() and 0xff)
                if (contended) choices += (now - origin) to (op?.name ?: "op ${inner[1]}")
                if (op == PipeOp.OPEN_OK) okSent += u16(inner, 2)
                channel.seal(inner) ?: return end("TX counter exhausted")
            }
            if (value.size > valueLimit) return end("value of ${value.size} B over V = $valueLimit")
            s?.accept()
            sent += Sent(now, value, inner)
            lastSubmitAt = now
            pumpAt = now + PipeEngine.NOTIFY_INTERVAL_MS
        }

        private fun end(why: String) {
            if (ended == null) ended = why
            pumpAt = null
        }
    }

    // ---- Replays ----------------------------------------------------------------------------

    private class Replay(val fx: FxSession, val phone: Phone) {
        /** Every way Android's output differed from the fixture (empty = byte for byte). */
        val mismatches = ArrayList<String>()
        /** Lock-step waits for Android's own timers, by fixture frame: (index, ms). */
        val waits = ArrayList<Pair<Int, Long>>()
        /** P4 frames delivered earlier than the fixture's spacing (adjustment 3): (index, ms). */
        val capped = ArrayList<Pair<Int, Long>>()
        /** Relay reads that followed a fixture CREDIT (adjustment 1): (index, bytes). */
        val reads = ArrayList<Pair<Int, Int>>()
        /** The application input applied, in order, in [Script.application]'s notation. */
        val app = ArrayList<String>()
        /** P4 frame index → Android's verdict, for every P4 DATA value delivered. */
        val verdicts = LinkedHashMap<Int, PipeSession.Rx?>()
        /** The first P4 frame Android refused (the replay stops there), or null. */
        var refused: Fx? = null
    }

    private fun targets(): List<PipeTarget> {
        val t = json.getJSONObject("targets")
        val s1 = t.getJSONObject("stream_1")
        val s2 = t.getJSONObject("stream_2")
        val ipv4 = java.net.InetAddress.getByName(s1.getString("ipv4")).address
        val dns = ascii(s2.getString("dns"))
        return listOf(
            PipeTarget.fromWire(s1.getInt("address_type"), ipv4, 0, ipv4.size, s1.getInt("port"))!!,
            PipeTarget.fromWire(s2.getInt("address_type"), dns, 0, dns.size, s2.getInt("port"))!!,
        )
    }

    private fun newPhone(fx: FxSession, start: Long): Phone {
        val cookie = fx.phoneFrames.firstOrNull { it.op == "PING" }?.body ?: hex("00000000")
        val random = FakeRandom(fx("phone_nonce_hex"), hex(json.getJSONObject("pipe_nonces").getString("phone_hex")), cookie)
        return Phone(fx.mtu, psk, fx("phone_private_key_hex"), random, start, targets())
    }

    /**
     * The TCP input a fixture phone frame implies, applied when the replay reaches it and recorded
     * in [Replay.app] (checked against [Script.application]).
     */
    private fun applyApplication(r: Replay, f: Fx, creditReads: Boolean, consumed: MutableMap<Int, Long>) {
        val p = r.phone
        val id = f.stream ?: return
        when (f.op) {
            "OPEN_OK" -> {
                p.connect(id)
                r.app += "connect $id"
            }
            "DATA" -> {
                // The relay's bytes for a run of phone DATA frames arrive in one TCP read at the run's start.
                val frames = r.fx.frames
                val at = frames.indexOf(f)
                if (at > 0 && !frames[at - 1].fromP4 && frames[at - 1].op == "DATA" && frames[at - 1].stream == id) return
                val run = ByteArrayOutputStream()
                var k = at
                while (k < frames.size && !frames[k].fromP4 && frames[k].op == "DATA" && frames[k].stream == id) run.write(frames[k++].body!!)
                p.tcpRead(id, run.toByteArray())
                r.app += "tcp $id ${run.size()}"
            }
            "FIN" -> {
                p.tcpEof(id)
                r.app += "eof $id"
            }
            // The fixture phone's CREDIT is consumed + window (script line 2): the relay consumed limit − window.
            "CREDIT" -> if (creditReads) {
                val target = u32(f.body!!, 0) - script.creditWindow
                val n = (target - (consumed[id] ?: 0L)).toInt()
                if (n > 0) {
                    p.relayReads(id, n)
                    consumed[id] = target
                    r.reads += f.i to n
                    r.app += "relay $id $n"
                }
            }
        }
    }

    /** Compares one phone value with its fixture frame: the whole value, then each field for the report. */
    private fun compare(r: Replay, f: Fx, got: Phone.Sent?) {
        if (got == null) {
            r.mismatches += "MTU ${r.fx.mtu} $f: Android sent nothing (expected ${describe(f.value, f.inner)})"
            return
        }
        if (got.value.contentEquals(f.value)) {
            // Equal values imply equal fields; check the decoded ones anyway so a fixture typo cannot hide.
            if (f.counter != null) {
                assertEquals("$f outer counter", f.counter, counterOf(got.value))
                assertArrayEquals("$f inner frame", f.inner, got.inner)
                assertEquals("$f inner seq", f.seq, u32(got.inner!!, 4))
                assertEquals("$f opcode", f.op, PipeOp.of(got.inner[1].toInt() and 0xff)?.name)
                assertEquals("$f stream", f.stream, u16(got.inner, 2))
                assertEquals("$f flags", f.flags, got.inner[10].toInt() and 0xff)
                assertArrayEquals("$f body", f.body, got.inner.copyOfRange(12, got.inner.size))
            }
            return
        }
        r.mismatches += "MTU ${r.fx.mtu} $f: expected ${describe(f.value, f.inner)}, Android sent ${describe(got.value, got.inner)}"
    }

    /**
     * The aligned replay (class KDoc): lock-step with the fixture, the three documented environment
     * adjustments, everything compared byte for byte.
     */
    private fun replayAligned(fx: FxSession): Replay {
        var offset = BASE
        val phone = newPhone(fx, fx.frames[0].t + offset)
        val r = Replay(fx, phone)
        val consumed = HashMap<Int, Long>()
        var cursor = 0
        var lastP4: Fx? = null
        var lastRxAt = 0L
        var pingSinceLastP4 = false
        for (f in fx.frames) {
            if (f.fromP4) {
                if (f.kind == "cccd_enable") {
                    assertEquals("CCCD value", "0100", toHex(f.value))
                    phone.subscribe()
                    continue
                }
                // Adjustment 3: never the script's 5 s of RX idle with a live stream unless the fixture
                // phone PINGs in it. The 5 s is the script's, not Android's constant (class KDoc).
                val live = (phone.session?.liveStreams ?: 0) > 0
                if (lastP4 != null && !pingSinceLastP4 && live && f.t + offset - lastRxAt >= script.idleMs) {
                    val cut = f.t + offset - lastRxAt - (script.idleMs - 1)
                    offset -= cut
                    r.capped += f.i to cut
                }
                phone.advanceTo(f.t + offset)
                if (phone.sent.size > cursor) {
                    phone.sent.drop(cursor).forEach { r.mismatches += "MTU ${fx.mtu}: before $f Android also sent ${describe(it.value, it.inner)}" }
                    cursor = phone.sent.size
                }
                val (plain, verdict) = phone.write(f.value)
                if (f.inner != null) {
                    r.verdicts[f.i] = verdict
                    assertArrayEquals("$f decrypts to the fixture's inner frame", f.inner, plain)
                }
                if (phone.ended != null || (f.inner != null && verdict != PipeSession.Rx.ACCEPTED)) {
                    r.refused = f
                    r.mismatches += "MTU ${fx.mtu} $f: Android refused it ($verdict, ${phone.ended})"
                    return r
                }
                lastP4 = f
                lastRxAt = phone.now
                pingSinceLastP4 = false
            } else {
                if (f.op == "PING") pingSinceLastP4 = true
                phone.advanceTo(f.t + offset)
                applyApplication(r, f, creditReads = true, consumed = consumed)
                if (phone.sent.size == cursor) {
                    val waited = phone.runUntilSent(MAX_WAIT_MS)
                    if (waited != null) {
                        offset += waited
                        r.waits += f.i to waited
                    }
                }
                compare(r, f, phone.sent.getOrNull(cursor))
                if (cursor < phone.sent.size) cursor++
            }
        }
        // After the last frame: nothing else is due (both streams retired) and the session stays up
        // for twice the script's idle period.
        phone.advanceTo(phone.now + 2 * script.idleMs)
        phone.sent.drop(cursor).forEach { r.mismatches += "MTU ${fx.mtu}: after the last frame Android also sent ${describe(it.value, it.inner)}" }
        return r
    }

    /**
     * The P4 frames at their own fixture times (phone clock = P4 clock + [BASE]), no adjustments:
     * the relay reads each byte as it arrives, and the application events happen at the fixture
     * phone's times. Stops at the first P4 frame Android refuses.
     */
    private fun replayAtFixtureClock(fx: FxSession): Replay {
        val phone = newPhone(fx, fx.frames[0].t + BASE)
        val r = Replay(fx, phone)
        for (f in fx.frames) {
            phone.advanceTo(f.t + BASE)
            if (!f.fromP4) {
                applyApplication(r, f, creditReads = false, consumed = HashMap())
                continue
            }
            if (f.kind == "cccd_enable") {
                phone.subscribe()
                continue
            }
            val (plain, verdict) = phone.write(f.value)
            if (f.inner != null) {
                r.verdicts[f.i] = verdict
                assertArrayEquals("$f decrypts to the fixture's inner frame", f.inner, plain)
            }
            if (phone.ended != null || (f.inner != null && verdict != PipeSession.Rx.ACCEPTED)) {
                r.refused = f
                return r
            }
            if (f.op == "DATA") phone.relayReads(f.stream!!)
        }
        return r
    }

    // ---- Shared assertions --------------------------------------------------------------------

    /**
     * The aligned replay of [mtu] in full: every phone value byte for byte, every P4 frame
     * accepted, the session's end state, the script's application input, and exactly the three
     * documented adjustments. [waits] is every lock-step wait, (fixture frame, ms), as a literal:
     * the CREDIT and FIN each wait for Android's next 50 ms tick, each DATA frame after the first
     * of a run waits out the 30 ms notification gap (the fixture phone sends them 1 ms apart), and
     * the phone PING waits for Android's 5 s idle rule (adjustment 2).
     */
    private fun assertAligned(mtu: Int, waits: List<Pair<Int, Long>>): Replay {
        val fx = session(mtu)
        val r = replayAligned(fx)
        val p = r.phone
        assertEquals("MTU $mtu: Android's values differ from X1:\n" + r.mismatches.joinToString("\n"), emptyList<String>(), r.mismatches)
        assertEquals("every phone frame was compared", fx.phoneFrames.size, p.sent.size)
        assertEquals("phone values, in order", fx.phoneFrames.map { toHex(it.value) }, p.sent.map { toHex(it.value) })
        assertEquals("every P4 DATA value accepted", fx.frames.filter { it.fromP4 && it.inner != null }.associate { it.i to PipeSession.Rx.ACCEPTED }, r.verdicts)

        // Interleaving is part of the comparison: at each P4 frame Android had sent exactly the
        // phone values the fixture puts before it (anything extra is a mismatch above).

        // End state: READY, both streams retired by the P4, nothing failed, nothing left to send.
        val s = p.session!!
        assertNull(p.ended)
        assertEquals(PipeSession.State.READY, s.state)
        assertEquals(PipeSecureChannel.State.ESTABLISHED, p.channel.state)
        assertEquals(0, s.liveStreams)
        assertEquals(fx.frames.count { it.fromP4 && it.inner != null }.toLong(), s.rxSequence)
        assertEquals(fx.phoneFrames.count { it.inner != null }.toLong(), s.txSequence)
        assertEquals(targets(), p.admitted)
        assertEquals(listOf("PEER_CLOSE", "PEER_CLOSE"), p.outcomes)
        assertEquals("RNG draws: secure nonce, HELLO_ACK nonce, PING cookie", 3, p.random.calls)
        // The application input is the script's, in order.
        assertEquals("application input (script)", script.application(), r.app)
        // Adjustment 1: the relay read exactly the first window of stream 1, once.
        val credit = fx.phoneFrames.single { it.op == "CREDIT" }
        assertEquals(listOf(credit.i to 1024), r.reads)
        assertEquals(1024, p.relay.getValue(1).size())
        assertEquals(setOf(1), p.relay.keys)
        // The relay's bytes are the P4's first 1024 B of stream 1, in order.
        val upload = ByteArrayOutputStream().also { o -> fx.frames.filter { it.fromP4 && it.op == "DATA" && it.stream == 1 }.forEach { o.write(it.body!!) } }.toByteArray()
        assertEquals(1100, upload.size)
        assertArrayEquals(upload.copyOf(1024), p.relay.getValue(1).toByteArray())
        // Adjustment 2 and every other wait, as literals. The one long wait is the phone PING,
        // whose preceding P4 frame is the CREDIT 1624 (60 ms before it in the fixture).
        assertEquals("lock-step waits (fixture frame, ms)", waits, r.waits)
        val ping = fx.phoneFrames.single { it.op == "PING" }
        assertEquals(listOf(ping.i), r.waits.filter { it.second > 1_000 }.map { it.first })
        assertEquals(listOf("CREDIT", 60L), fx.frames[ping.i - 1].let { listOf(it.op, ping.t - it.t) })
        // Adjustment 3: the P4's PING, and only it, was delivered 61 ms early: 4,999 ms after its
        // PONG instead of the fixture's 5,060.
        val p4Ping = fx.frames.single { it.fromP4 && it.op == "PING" }
        assertEquals(listOf("PONG", 5_060L), fx.frames[p4Ping.i - 1].let { listOf(it.op, p4Ping.t - it.t) })
        assertEquals("P4 frames delivered early (fixture frame, ms)", listOf(p4Ping.i to 61L), r.capped)
        // Control-first ordering is never decisive here (class KDoc): nothing to choose between.
        assertEquals("submissions with control and DATA both ready", emptyList<Pair<Long, String>>(), p.choices)
        return r
    }

    // ---- Tests ------------------------------------------------------------------------------

    /**
     * The resource is the firmware file byte for byte, its sizing fields are the stage-4 rule
     * (V = min(MTU − 3, 512), P = V − 25, DATA body ≤ P − 12), its constants are the golden
     * transcript's, Android derives the same PSK from its passphrase, and the MTU-247 session opens
     * with exactly the golden's handshake and first DATA values in both directions.
     */
    @Test
    fun x1IsTheFirmwareFileAndExtendsTheGoldenTranscript() {
        assertEquals(SHA256, toHex(MessageDigest.getInstance("SHA-256").digest(raw)))
        assertEquals(listOf(64, 244, 247, 517), sessions.map { it.mtu })
        for (s in sessions) {
            assertEquals("V at ${s.mtu}", PipeWire.valueLimit(s.mtu), s.valueLimit)
            assertEquals("P at ${s.mtu}", PipeWire.frameLimit(s.mtu), s.innerCapacity)
            assertEquals("DATA body at ${s.mtu}", PipeWire.frameLimit(s.mtu) - PipeWire.HEADER, s.maxDataBody)
            assertEquals("frame indexes at ${s.mtu}", s.frames.indices.toList(), s.frames.map { it.i })
            // ATT roles: the P4 writes the CCCD once, then only write commands; the phone only notifies.
            assertEquals(listOf("write_request") + List(s.frames.count { it.fromP4 } - 1) { "write_cmd" }, s.frames.filter { it.fromP4 }.map { it.att })
            assertTrue(s.phoneFrames.all { it.att == "notify" })
            // Each DATA frame's decoded fields are its own value's: outer counter, then the inner header.
            for (f in s.frames.filter { it.inner != null }) {
                val inner = f.inner!!
                assertEquals("$f counter", f.counter, counterOf(f.value))
                assertEquals("$f size", inner.size + PipeWire.SECURE_OVERHEAD, f.value.size)
                assertEquals("$f header", listOf(1, f.op, f.stream, f.seq, f.body!!.size, f.flags, 0),
                    listOf(inner[0].toInt(), PipeOp.of(inner[1].toInt() and 0xff)?.name, u16(inner, 2), u32(inner, 4), u16(inner, 8), inner[10].toInt() and 0xff, inner[11].toInt()))
                assertArrayEquals("$f body", f.body, inner.copyOfRange(PipeWire.HEADER, inner.size))
            }
        }
        assertEquals(toHex(PipeFixture.psk), toHex(psk))
        for (name in listOf(
            "psk_hex", "phone_private_key_hex", "phone_public_key_hex", "device_private_key_hex", "device_public_key_hex",
            "phone_nonce_hex", "device_nonce_hex", "shared_secret_hex", "phone_to_p4_key_hex", "p4_to_phone_key_hex",
        )) assertEquals(name, PipeFixture.string(name), json.getString(name))
        assertEquals(PipeFixture.string("passphrase_utf8"), json.getString("passphrase_utf8"))
        assertEquals(PipeFixture.string("label_ascii"), json.getString("label_ascii"))
        assertEquals(PipeFixture.long("pbkdf2_iterations"), json.getLong("pbkdf2_iterations"))

        val f = session(247).frames
        assertEquals(247, vectorInt("att_mtu"))
        assertEquals(vector("hello_hex").toList(), f[1].value.toList())
        assertEquals(vector("hello_ack_hex").toList(), f[2].value.toList())
        assertEquals(vector("confirm_hex").toList(), f[3].value.toList())
        assertEquals(vector("confirm_ack_hex").toList(), f[4].value.toList())
        assertEquals(vector("p4_to_phone_data_hex").toList(), f[5].value.toList())
        assertEquals(vector("p4_to_phone_plaintext_hello_hex").toList(), f[5].inner!!.toList())
        assertEquals(vector("phone_to_p4_data_hex").toList(), f[6].value.toList())
        assertEquals(vector("phone_to_p4_plaintext_hello_ack_hex").toList(), f[6].inner!!.toList())
        assertEquals(vectorInt("data_counter_each_direction").toLong(), f[5].counter)
        assertEquals(vectorInt("data_counter_each_direction").toLong(), f[6].counter)
    }

    /**
     * X1's `script` is what its frames do, at every MTU, and Android's PING rule is its "five idle
     * seconds":
     *  - every line reads as expected ([Script]) and its numbers are these literals;
     *  - per session, the frames' order, with each run of same-direction, same-opcode, same-stream
     *    frames collapsed to one entry, is the script's order (line by line below);
     *  - the frames carry the script's numbers: the P4 HELLO's counter and sequence, both OPEN_OK
     *    limits, the 1100 B upload stopping at 1024 B until the phone's CREDIT 1024 + 4096, the
     *    600 B back and the P4's CREDIT 1624, the FIN 600, CLOSE reasons 0 and 1, stream 2's OPEN in
     *    two fragments only at MTU 64, and 40 B / 30 B on stream 2; the P4's PING comes at least
     *    five seconds after the P4 frame before it, with stream 1 still open;
     *  - [PipeWire.PING_IDLE_MS] is those five seconds (android-002 Q11), so the replays' 5 s and
     *    Android's agree.
     */
    @Test
    fun x1FramesFollowTheScript() {
        val sc = script
        assertEquals(5, sc.lines.size)
        assertEquals(
            listOf(1L, 0L, 1024L, 1100L, 1024L, 1024L, 4096L, 600L, 1624L, 5_000L, 600L, 0L, 64L, 1024L, 40L, 30L, 1L),
            listOf(sc.helloCounter, sc.helloSeq, sc.openOk1, sc.upload1, sc.stopsAt, sc.creditConsumed, sc.creditWindow,
                sc.phoneSends, sc.p4Credit, sc.idleMs, sc.fin, sc.close1, sc.fragmentMtu.toLong(), sc.openOk2, sc.p4Sends2, sc.phoneSends2, sc.close2),
        )
        assertEquals("Android's PING idle rule is the script's five idle seconds", sc.idleMs, PipeWire.PING_IDLE_MS)
        assertEquals(listOf("connect 1", "relay 1 1024", "tcp 1 600", "eof 1", "connect 2", "tcp 2 30"), sc.application())

        val order = listOf(
            // Line 1: secure handshake; pipe HELLO and HELLO_ACK.
            "p4 cccd_enable", "phone secure_hello", "p4 secure_hello_ack", "phone secure_confirm", "p4 secure_confirm_ack",
            "p4 HELLO 0", "phone HELLO_ACK 0",
            // Line 2: OPEN, OPEN_OK, the upload up to 1024 B, the phone's CREDIT, the rest of the upload.
            "p4 OPEN 1", "phone OPEN_OK 1", "p4 DATA 1", "phone CREDIT 1", "p4 DATA 1",
            // Line 3: 600 B back, the P4's CREDIT, phone PING, P4 PONG.
            "phone DATA 1", "p4 CREDIT 1", "phone PING 0", "p4 PONG 0",
            // Line 4: idle, P4 PING, phone PONG, FIN, CLOSE 0, CLOSE_ACK.
            "p4 PING 0", "phone PONG 0", "phone FIN 1", "p4 CLOSE 1", "phone CLOSE_ACK 1",
            // Line 5: stream 2.
            "p4 OPEN 2", "phone OPEN_OK 2", "p4 DATA 2", "phone DATA 2", "p4 CLOSE 2", "phone CLOSE_ACK 2",
        )
        for (s in sessions) {
            val m = "MTU ${s.mtu}"
            val names = s.frames.map { "${if (it.fromP4) "p4" else "phone"} ${it.op?.let { op -> "$op ${it.stream}" } ?: it.kind}" }
            assertEquals("$m frame order (runs collapsed)", order, names.filterIndexed { k, n -> k == 0 || names[k - 1] != n })

            fun p4(op: String, id: Int) = s.frames.filter { it.fromP4 && it.op == op && it.stream == id }
            fun phone(op: String, id: Int) = s.phoneFrames.filter { it.op == op && it.stream == id }
            fun total(frames: List<Fx>) = frames.sumOf { it.body!!.size }.toLong()
            val hello = p4("HELLO", 0).single()
            assertEquals("$m P4 HELLO counter, seq", listOf(sc.helloCounter, sc.helloSeq), listOf(hello.counter, hello.seq))
            assertEquals("$m OPEN_OK limits", listOf(sc.openOk1, sc.openOk2), listOf(1, 2).map { u32(phone("OPEN_OK", it).single().body!!, 0) })
            val credit = phone("CREDIT", 1).single()
            assertEquals("$m P4 upload, and the part before the phone's CREDIT", listOf(sc.upload1, sc.stopsAt),
                listOf(total(p4("DATA", 1)), total(p4("DATA", 1).filter { it.i < credit.i })))
            assertEquals("$m phone CREDIT", sc.creditConsumed + sc.creditWindow, u32(credit.body!!, 0))
            assertEquals("$m the CREDIT is for what the P4 stopped at", sc.stopsAt, sc.creditConsumed)
            assertEquals("$m phone bytes on stream 1, P4 CREDIT, FIN", listOf(sc.phoneSends, sc.p4Credit, sc.fin),
                listOf(total(phone("DATA", 1)), u32(p4("CREDIT", 1).single().body!!, 0), u32(phone("FIN", 1).single().body!!, 0)))
            assertEquals("$m CLOSE reasons", listOf(sc.close1, sc.close2), listOf(1, 2).map { u16(p4("CLOSE", it).single().body!!, 0).toLong() })
            assertEquals("$m stream 2 OPEN fragments", if (s.mtu == sc.fragmentMtu) 2 else 1, p4("OPEN", 2).size)
            assertEquals("$m stream 2 bytes each way", listOf(sc.p4Sends2, sc.phoneSends2), listOf(total(p4("DATA", 2)), total(phone("DATA", 2))))
            val p4Ping = p4("PING", 0).single()
            assertTrue("$m P4 PING after five idle seconds", p4Ping.t - s.frames[p4Ping.i - 1].t >= sc.idleMs)
            assertTrue("$m stream 1 still open at the P4 PING", p4("CLOSE", 1).single().i > p4Ping.i)
        }
    }

    /**
     * MTU 64 (V 61, P 36, DATA body 24): the smallest link. HELLO_ACK fits one frame, the P4's
     * 1100 B arrive as 46 DATA frames, Android's 600 B leave as 25, stream 2's DNS OPEN arrives in
     * two fragments and its 30 B leave as 24 + 6. Aligned replay: every phone value byte for byte.
     * Waits: CREDIT (frame 52) 15 ms; the 24 DATA frames after the first of the 600 B (58–81) and
     * the 6 B second fragment on stream 2 (96) 29 ms each; PING (83) 4,958 ms; FIN (87) 9 ms.
     */
    @Test
    fun mtu64SessionIsByteForByte() {
        assertAligned(64, listOf(52 to 15L) + (58..81).map { it to 29L } + listOf(83 to 4_958L, 87 to 9L, 96 to 29L))
    }

    /**
     * MTU 244 (V 241, P 216, body 204), the P4's G2-mode request: aligned replay, byte for byte.
     * Waits: CREDIT 25 ms, two DATA gaps of 29 ms, PING 4,958 ms, FIN 9 ms.
     */
    @Test
    fun mtu244SessionIsByteForByte() {
        assertAligned(244, listOf(15 to 25L, 18 to 29L, 19 to 29L, 21 to 4_958L, 25 to 9L))
    }

    /**
     * MTU 247 (V 244, P 219, body 207): aligned replay, byte for byte, and Android's handshake and
     * first DATA values are also the older golden transcript's (`phone_secure_channel_v1_vectors.json`).
     * Waits: CREDIT 5 ms, two DATA gaps of 29 ms, PING 4,958 ms, FIN 9 ms.
     */
    @Test
    fun mtu247SessionIsByteForByteAndStartsWithTheGolden() {
        val sent = assertAligned(247, listOf(14 to 5L, 17 to 29L, 18 to 29L, 20 to 4_958L, 24 to 9L)).phone.sent
        assertEquals(vector("hello_hex").toList(), sent[0].value.toList())
        assertEquals(vector("confirm_hex").toList(), sent[1].value.toList())
        assertEquals(vector("phone_to_p4_data_hex").toList(), sent[2].value.toList())
        assertEquals(vector("phone_to_p4_plaintext_hello_ack_hex").toList(), sent[2].inner!!.toList())
    }

    /**
     * MTU 517 (V 512, P 487, body 475), the P4's standalone request: aligned replay, byte for byte.
     * Waits: CREDIT 15 ms, one DATA gap of 29 ms, PING 4,988 ms (Android's tick phase lands 30 ms
     * later here than at the other MTUs), FIN 9 ms.
     */
    @Test
    fun mtu517SessionIsByteForByte() {
        assertAligned(517, listOf(12 to 15L, 15 to 29L, 17 to 4_988L, 21 to 9L))
    }

    /**
     * What Android does with the P4's own timing ([replayAtFixtureClock]: no adjustments, ticks
     * every 50 ms from the CCCD write, each TCP connect completing at the fixture phone's OPEN_OK
     * time, the relay reading every DATA byte as it arrives), written out in full for one session:
     *
     *  - HELLO, CONFIRM, HELLO_ACK and OPEN_OK are the fixture's values byte for byte.
     *  - Android's next value carries the same outer counter and inner sequence as the fixture's
     *    CREDIT, but Android sent it while the P4's first 1024 B were still arriving, with a lower
     *    limit (512 B / 100 ms coalescing), and it keeps crediting as bytes are consumed:
     *    [credits] is every CREDIT Android sent before the PONG, as (ms after the CCCD write,
     *    limit = consumed + 4096). The fixture phone sends one, 5120.
     *  - [choices] is every submission at which a control message and sendable DATA were ready
     *    together, as (ms after the CCCD write, what Android sent): Android's control-first rule
     *    decided each of them, the one place in X1 where it does (class KDoc).
     *  - Android never sends the fixture phone's PING (it PINGs only after 5 s of RX idle), so the
     *    P4's PONG is unsolicited: Android refuses that frame and the session fails with
     *    BAD_PROTOCOL. Every P4 frame before it is accepted and decrypts to its `inner_hex`.
     *  - The application input up to then is the script's stream-1 connect and its 600 B (no EOF
     *    yet), and the relay, reading on arrival here, has read all 1100 B of the P4's upload.
     */
    private fun assertAtFixtureClock(mtu: Int, credits: List<Pair<Long, Long>>, choices: List<Pair<Long, String>>) {
        val fx = session(mtu)
        val r = replayAtFixtureClock(fx)
        val p = r.phone
        assertEquals("application input (script, up to the refusal)", listOf("connect 1", "tcp 1 ${script.phoneSends}"), r.app)
        assertEquals("relay bytes on stream 1", mapOf(1 to script.upload1.toInt()), p.relay.mapValues { it.value.size() })
        assertEquals("submissions with control and DATA both ready (ms, op sent)", choices, p.choices)
        val pong = fx.frames.first { it.fromP4 && it.op == "PONG" }
        assertEquals("the first refused P4 frame", pong.i, r.refused?.i)
        assertEquals(PipeSession.Rx.REJECTED, r.verdicts[pong.i])
        assertEquals("session failed: BAD_PROTOCOL", p.ended)
        assertEquals("every earlier P4 DATA value accepted",
            fx.frames.filter { it.fromP4 && it.inner != null && it.i < pong.i }.map { it.i },
            r.verdicts.filterValues { it == PipeSession.Rx.ACCEPTED }.keys.toList())

        val expected = fx.phoneFrames
        val k = expected.indexOfFirst { it.op == "CREDIT" }
        assertEquals("HELLO, CONFIRM, HELLO_ACK, OPEN_OK", listOf("secure_hello", "secure_confirm", "HELLO_ACK", "OPEN_OK"), expected.take(k).map { it.op ?: it.kind })
        assertEquals(expected.take(k).map { toHex(it.value) }, p.sent.take(k).map { toHex(it.value) })

        // The divergence: same counter, sequence, opcode and stream as the fixture's CREDIT, lower limit, earlier.
        val fixtureCredit = expected[k]
        val first = p.sent[k]
        assertEquals("the fixture phone's single CREDIT", listOf(Op.CREDIT, 1, 5120L), listOf(fixtureCredit.inner!![1].toInt(), fixtureCredit.stream, u32(fixtureCredit.body!!, 0)))
        assertEquals("Android's value in its place: counter, sequence, opcode, stream",
            listOf(fixtureCredit.counter, fixtureCredit.seq, Op.CREDIT.toLong(), 1L),
            listOf(counterOf(first.value), u32(first.inner!!, 4), first.inner[1].toLong(), u16(first.inner, 2).toLong()))
        val lastWindowData = fx.frames.last { it.fromP4 && it.op == "DATA" && it.stream == 1 && it.i < fixtureCredit.i }
        assertTrue("Android's first CREDIT (${first.at - BASE} ms) precedes the window's last DATA (${lastWindowData.t} ms)", first.at - BASE < lastWindowData.t)
        val sentCredits = p.sent.drop(k).filter { it.inner!![1].toInt() == Op.CREDIT }.map { (it.at - BASE) to u32(it.inner!!, 12) }
        assertEquals("Android's CREDITs (ms, limit)", credits, sentCredits)
        assertTrue("no PING", p.sent.none { it.inner != null && it.inner[1].toInt() == Op.PING })
        assertEquals("RNG draws: secure nonce and HELLO_ACK nonce only", 2, p.random.calls)
    }

    /**
     * MTU 64 at the fixture's clock: a CREDIT every 100 ms of the 1.3 s upload, 15 in all. The last
     * one (5196, queued at the 1,850 ms tick) and the second of the 600 B's 25 DATA frames are both
     * ready when the 30 ms gap after the first DATA frame ends at 1,866 ms: Android sends the CREDIT.
     */
    @Test
    fun mtu64AtTheFixtureClockCreditsEarlyAndRefusesTheUnsolicitedPong() = assertAtFixtureClock(
        64,
        listOf(
            450L to 4168L, 550L to 4264L, 650L to 4336L, 750L to 4408L, 850L to 4504L, 950L to 4576L, 1050L to 4648L,
            1150L to 4744L, 1250L to 4816L, 1350L to 4888L, 1450L to 4984L, 1550L to 5056L, 1650L to 5120L, 1750L to 5192L,
            1866L to 5196L,
        ),
        listOf(1866L to "CREDIT"),
    )

    /**
     * MTU 244 at the fixture's clock: 612 B consumed at the 450 ms tick, then 1024 and 1100. The
     * CREDIT 5196 (queued at the 650 ms tick) and the 600 B's second DATA frame are both ready when
     * the gap after the first DATA frame ends at 666 ms: Android sends the CREDIT.
     */
    @Test
    fun mtu244AtTheFixtureClockCreditsEarlyAndRefusesTheUnsolicitedPong() =
        assertAtFixtureClock(244, listOf(450L to 4708L, 550L to 5120L, 666L to 5196L), listOf(666L to "CREDIT"))

    /** MTU 247 at the fixture's clock: 621 B consumed at the 450 ms tick, then all 1100, before the 600 B arrive. */
    @Test
    fun mtu247AtTheFixtureClockCreditsEarlyAndRefusesTheUnsolicitedPong() =
        assertAtFixtureClock(247, listOf(450L to 4717L, 550L to 5196L), emptyList())

    /** MTU 517 at the fixture's clock: 950 B consumed at the 400 ms tick, then all 1100, before the 600 B arrive. */
    @Test
    fun mtu517AtTheFixtureClockCreditsEarlyAndRefusesTheUnsolicitedPong() =
        assertAtFixtureClock(517, listOf(400L to 5046L, 500L to 5196L), emptyList())

    // ---- Formatting ---------------------------------------------------------------------------

    private fun counterOf(value: ByteArray): Long {
        var c = 0L
        for (k in 1..8) c = (c shl 8) or (value[k].toLong() and 0xff)
        return c
    }

    private fun describe(value: ByteArray, inner: ByteArray?): String {
        if (inner == null || value.isEmpty() || value[0].toInt() != 0x10) return "${value.size} B ${toHex(value).take(24)}…"
        val op = PipeOp.of(inner[1].toInt() and 0xff)?.name ?: "op 0x%02x".format(inner[1].toInt() and 0xff)
        val body = inner.copyOfRange(12, inner.size)
        val shown = if (body.size <= 8) toHex(body) else "${body.size} B"
        return "$op s${u16(inner, 2)} ctr ${counterOf(value)} seq ${u32(inner, 4)} flags ${inner[10]} body $shown"
    }
}
