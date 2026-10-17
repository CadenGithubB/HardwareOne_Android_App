package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeSession.Rx
import com.hardwareone.console.pipe.PipeSession.StreamState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Stream lifecycle and flow control of the phone's half of pipe v1, driven the way the P4 drives
 * it. Expected values come from the firmware core (`BLE_PhonePipeCore.h`, the P4 side), the
 * handoff's v1 protocol snapshot and the agreed stage-4 decisions (DECISIONS.md: Q12 stream IDs,
 * A-1 connect bound, R8 sizing), not from [PipeSession]. Every frame the phone emits is checked
 * for version, reserved byte, length field, sequence and frame size as it is drained.
 *
 * Sessions are sized by their inner frame limit (`maxFrame`, P at stage 4). Tests that only need
 * some frame size keep the one they were written for (512, or 61 for small DATA bodies); the
 * stage-4 sizing test runs at P = 36/216/219/487.
 *
 * Destinations are synthetic only: 192.0.2.0/24, 2001:db8::/32 and names under `.example`.
 */
class PipeSessionStreamTest {

    // ---- OPEN admission ----------------------------------------------------------------------

    @Test
    fun openPassesTheDecodedTargetToAdmitBeforeCreatingTheStream() {
        for ((type, host, port) in listOf(Triple(TYPE_V4, V4, 443), Triple(TYPE_V6, V6, 8443), Triple(TYPE_DNS, DNS_HOST, 8443))) {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1, type, host, port))
            assertEquals(1, h.admitted.size)
            val target = h.admitted[0]
            assertEquals(type, target.type)
            assertEquals(port, target.port)
            assertArrayEquals(host, target.host)
            assertEquals("admit ran before the stream existed", listOf(0), h.liveAtAdmit)
            assertEquals(StreamState.CONNECTING, h.stream(1).state)
            assertEquals(target, h.stream(1).target)
            assertEquals("nothing is sent until the single connect succeeds", emptyList<String>(), h.texts())
        }
    }

    /** Handoff: deny an unapproved target before DNS/TCP; "OPEN_ERR also retires its ID immediately". */
    @Test
    fun deniedOpenAnswersOpenErrWithTheAdmitCodeAndRetiresTheId() {
        for ((denial, code) in listOf(
            PipeError.TARGET_DENIED to E_TARGET,
            PipeError.VPN_UNAVAILABLE to E_VPN,
            PipeError.NOT_AUTHORIZED to E_UNAUTHORIZED,
        )) {
            val h = Device()
            h.hello()
            h.verdict = { denial }
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertEquals(1, h.admitted.size)
            assertEquals(listOf("OPEN_ERR#1=$code"), h.texts())
            assertNull("the stream never reaches CONNECTING", h.session.find(1))
            assertEquals(0, h.session.liveStreams)
            assertFalse("a late connect cannot revive a refused OPEN", h.session.connected(1, T0 + 2))
            assertEquals(emptyList<String>(), h.texts())
            h.assertRetired(1, T0 + 3)
            assertNull(h.session.error)
        }
    }

    /** Handoff: two live streams; firmware never opens a third, so the phone refuses it as BUSY. */
    @Test
    fun thirdOpenWhileTwoStreamsAreLiveIsBusyWithoutAdmission() {
        val h = Device()
        h.hello()
        assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
        assertEquals(Rx.ACCEPTED, h.open(2, T0 + 2))
        // Both CONNECTING.
        assertEquals(Rx.ACCEPTED, h.open(3, T0 + 3))
        assertEquals("admit is not consulted for a refused third stream", 2, h.admitted.size)
        assertEquals(listOf("OPEN_ERR#3=$E_BUSY"), h.texts())
        assertNull(h.session.find(3))
        assertFalse(h.session.connected(3, T0 + 3))
        assertEquals(StreamState.CONNECTING, h.stream(1).state)
        assertEquals(StreamState.CONNECTING, h.stream(2).state)

        // Both OPEN.
        assertTrue(h.session.connected(1, T0 + 4))
        assertTrue(h.session.connected(2, T0 + 4))
        assertEquals(listOf("OPEN_OK#1=1024", "OPEN_OK#2=1024"), h.texts())
        assertEquals(Rx.ACCEPTED, h.open(4, T0 + 5))
        assertEquals(2, h.admitted.size)
        assertEquals(listOf("OPEN_ERR#4=$E_BUSY"), h.texts())
        assertEquals(2, h.session.liveStreams)

        // One ends; the next, higher ID is admitted normally.
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 6))
        assertEquals(listOf("CLOSE_ACK#1"), h.texts())
        assertEquals(Rx.ACCEPTED, h.open(5, T0 + 7))
        assertEquals(3, h.admitted.size)
        assertEquals(StreamState.CONNECTING, h.stream(5).state)
        assertEquals(2, h.session.liveStreams)

        // A BUSY-refused ID is retired like any other.
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 3, u16(1), T0 + 8))
        assertEquals(listOf("CLOSE_ACK#3"), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /**
     * [PipeSession.opens] counts the OPENs the device completed in this session, admitted or
     * refused (OPEN_ERR from admit, BUSY), once per message however it was fragmented. It is the
     * engine's evidence that the P4 progressed past READY ("Ready (phone side)" until then, FW-3).
     */
    @Test
    fun opensCountsEveryCompletedOpenWhetherAdmittedOrRefused() {
        val h = Device(maxFrame = 36) // 24 body bytes per fragment
        assertEquals("a new session", 0, h.session.opens)
        h.hello()
        assertEquals("HELLO is not an OPEN", 0, h.session.opens)

        // A 56-byte DNS OPEN arrives as 24 + 24 + 8 and counts once, at its LAST fragment.
        val name = "a".repeat(40) + ".example"
        val body = openBody(TYPE_DNS, name.toByteArray(Charsets.US_ASCII), 8443)
        assertEquals(56, body.size)
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, FIRST, body, 0, 24), T0 + 1))
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, 0, body, 24, 24), T0 + 1))
        assertEquals("fragments are not OPENs yet", 0, h.session.opens)
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, LAST, body, 48, 8), T0 + 1))
        assertEquals("admitted", 1, h.session.opens)
        assertEquals(StreamState.CONNECTING, h.stream(1).state)

        h.verdict = { PipeError.TARGET_DENIED }
        assertEquals(Rx.ACCEPTED, h.open(2, T0 + 2))
        assertEquals(listOf("OPEN_ERR#2=$E_TARGET"), h.texts())
        assertEquals("refused by admit", 2, h.session.opens)

        h.verdict = { null }
        assertEquals(Rx.ACCEPTED, h.open(3, T0 + 3))
        assertEquals(Rx.ACCEPTED, h.open(4, T0 + 4))
        assertEquals(listOf("OPEN_ERR#4=$E_BUSY"), h.texts())
        assertEquals("BUSY counts as well", 4, h.session.opens)

        // Other traffic, retired-ID traffic and stream ends leave it alone.
        assertTrue(h.session.connected(1, T0 + 5))
        assertEquals(listOf("OPEN_OK#1=1024"), h.texts())
        h.pump(1, pattern(30, 1), T0 + 6)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 3, u16(1), T0 + 7))
        assertEquals(listOf("CLOSE_ACK#3"), h.texts())
        h.assertRetired(2, T0 + 8)
        assertEquals(Rx.ACCEPTED, h.control(OP_PING, 0, u32(7), T0 + 9))
        assertEquals(listOf("PONG#0=7"), h.texts())
        assertEquals(4, h.session.opens)
        assertEquals(listOf(TYPE_DNS, TYPE_V4, TYPE_V4), h.admitted.map { it.type })

        // A session failure ends the streams but keeps the record of progress.
        assertFalse(h.session.fail(PipeError.CANCELLED))
        assertEquals(4, h.session.opens)
    }

    // ---- Stream ID rules ---------------------------------------------------------------------

    /** Handoff: "Duplicate/nonmonotonic OPEN ... is a session error. IDs are never recycled." */
    @Test
    fun openReusingOrGoingBelowTheHighestIdFailsTheSession() {
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertEquals(Rx.REJECTED, h.open(1, T0 + 2))
            h.assertFailed(E_PROTOCOL, "duplicate live ID")
            assertEquals(1, h.admitted.size)
        }
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(2, T0 + 1))
            assertEquals(Rx.REJECTED, h.open(1, T0 + 2))
            h.assertFailed(E_PROTOCOL, "decreasing ID")
        }
        run {
            val h = Device()
            h.hello()
            h.verdict = { PipeError.TARGET_DENIED }
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertEquals(listOf("OPEN_ERR#1=$E_TARGET"), h.texts())
            h.verdict = { null }
            assertEquals(Rx.REJECTED, h.open(1, T0 + 2))
            h.assertFailed(E_PROTOCOL, "ID retired by OPEN_ERR")
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(1, T0 + 1)
            assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 2))
            assertEquals(listOf("CLOSE_ACK#1"), h.texts())
            assertEquals(Rx.REJECTED, h.open(1, T0 + 3))
            h.assertFailed(E_PROTOCOL, "ID retired by CLOSE")
        }
    }

    /** Handoff: "an unknown future ID ... is a session error." */
    @Test
    fun framesForAnIdNeverOpenedFailTheSession() {
        val cases: List<Pair<String, (Device) -> Rx>> = listOf(
            "DATA" to { d: Device -> d.data(2, byteArrayOf(1), T0 + 5) },
            "CREDIT" to { d: Device -> d.control(OP_CREDIT, 2, u32(1024), T0 + 5) },
            "FIN" to { d: Device -> d.control(OP_FIN, 2, u32(0), T0 + 5) },
            "CLOSE" to { d: Device -> d.control(OP_CLOSE, 2, u16(1), T0 + 5) },
            "CLOSE_ACK" to { d: Device -> d.control(OP_CLOSE_ACK, 2, ByteArray(0), T0 + 5) },
            "RESET" to { d: Device -> d.control(OP_RESET, 2, u16(E_CANCELLED), T0 + 5) },
        )
        for ((name, send) in cases) {
            val h = Device()
            h.hello()
            h.openStream(1, T0 + 1)
            assertEquals(name, Rx.REJECTED, send(h))
            h.assertFailed(E_PROTOCOL, name)
        }
        // Nothing opened at all yet.
        val h = Device()
        h.hello()
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(1), T0 + 1))
        h.assertFailed(E_PROTOCOL, "DATA before any OPEN")
    }

    /** Handoff: "For IDs already retired, discard late DATA/CREDIT/FIN/ACK/CLOSE/RESET without reviving a stream; ACK a late CLOSE." */
    @Test
    fun lateFramesForRetiredIdsAreDiscardedWithoutRevivingTheStream() {
        val h = Device()
        h.hello()
        h.openStream(1, T0 + 1)
        h.openStream(2, T0 + 2)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 3))
        assertEquals(listOf("CLOSE_ACK#1"), h.texts())
        h.assertRetired(1, T0 + 4)
        assertEquals(1, h.session.liveStreams)

        assertEquals(Rx.ACCEPTED, h.control(OP_RESET, 2, u16(E_CANCELLED), T0 + 5))
        assertEquals("RESET needs no reply", emptyList<String>(), h.texts())
        h.assertRetired(2, T0 + 6)
        assertEquals(0, h.session.liveStreams)

        val s3 = h.openStream(3, T0 + 7)
        h.assertRetired(1, T0 + 8)
        h.assertRetired(2, T0 + 9)
        assertEquals(1, h.session.liveStreams)
        assertEquals(0L, s3.received)
        assertEquals(1024L, s3.peerLimit)
        assertFalse(s3.peerFin)
    }

    /**
     * Q12: OPEN IDs are nonzero and strictly increasing, with gaps allowed; the first ID may be any
     * nonzero value (firmware-002 §7: the P4 allocates contiguously, but that is not the contract).
     * ID 0 is stream 0's: an OPEN there fails (PipeSessionWireTest.streamMessagesOnStreamZeroAreRejected).
     */
    @Test
    fun openIdsMayStartAboveOneAndSkipValues() {
        val h = Device()
        h.hello()
        val s5 = h.openStream(5, T0 + 1)
        val s9 = h.openStream(9, T0 + 2)
        assertEquals(5, s5.id)
        assertEquals(9, s9.id)
        assertEquals(2, h.session.liveStreams)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 5, u16(1), T0 + 3))
        assertEquals(listOf("CLOSE_ACK#5"), h.texts())
        val s200 = h.openStream(200, T0 + 4)
        assertEquals(200, s200.id)
        h.pump(200, pattern(40, 1), T0 + 5)
        assertEquals(40L, s200.received)
        assertEquals(3, h.admitted.size)
        assertEquals(listOf(9, 200), h.session.streams.filter { it.live }.map { it.id }.sorted())
        assertEquals(PipeSession.State.READY, h.session.state)
        assertNull(h.session.error)
    }

    /**
     * Q12: "skipped IDs retired". Every never-opened ID below the high-water mark behaves exactly
     * like a closed stream: late DATA/CREDIT/FIN/CLOSE_ACK/RESET are discarded, CLOSE gets
     * CLOSE_ACK, and the live streams are untouched.
     */
    @Test
    fun skippedIdsBelowTheHighWaterMarkAreRetired() {
        val h = Device()
        h.hello()
        val s3 = h.openStream(3, T0 + 1)
        val s6 = h.openStream(6, T0 + 2)
        for (skipped in listOf(1, 2, 4, 5)) h.assertRetired(skipped, T0 + 3)
        assertEquals(2, h.session.liveStreams)
        assertEquals(StreamState.OPEN, s3.state)
        assertEquals(StreamState.OPEN, s6.state)
        assertEquals(0L, s3.received)
        assertEquals(1024L, s6.peerLimit)
        h.pump(3, pattern(10, 2), T0 + 4)
        h.pump(6, pattern(10, 3), T0 + 4)
        assertEquals(10L, s3.received)
        assertEquals(10L, s6.received)
    }

    /**
     * Q12: "a later OPEN for one is nonmonotonic and fails". After OPENs 2 and 5, an OPEN for a
     * skipped ID (1, 3, 4), a live lower ID (2) or the high-water mark itself (5) fails the session
     * before admit is consulted. A refused OPEN raises the high-water mark too.
     */
    @Test
    fun openForASkippedOrLowerIdFailsTheSession() {
        for (late in listOf(1, 3, 4, 2, 5)) {
            val h = Device()
            h.hello()
            h.openStream(2, T0 + 1)
            assertEquals(Rx.ACCEPTED, h.open(5, T0 + 2))
            assertEquals(Rx.REJECTED, h.open(late, T0 + 3))
            h.assertFailed(E_PROTOCOL, "OPEN $late after 2 and 5")
            assertEquals("OPEN $late: admit not consulted", 2, h.admitted.size)
        }
        for (verdict in listOf(PipeError.TARGET_DENIED, PipeError.VPN_UNAVAILABLE)) {
            val h = Device()
            h.hello()
            h.verdict = { verdict }
            assertEquals(Rx.ACCEPTED, h.open(7, T0 + 1))
            assertEquals(listOf("OPEN_ERR#7=${verdict.code}"), h.texts())
            h.verdict = { null }
            h.assertRetired(4, T0 + 2)
            assertEquals(Rx.REJECTED, h.open(6, T0 + 3))
            h.assertFailed(E_PROTOCOL, "OPEN 6 after refused 7")
        }
    }

    /**
     * Q12: "A non-OPEN frame for an ID above the high-water mark is a session error", also when the
     * IDs below it were reached with gaps.
     */
    @Test
    fun nonOpenFrameAboveTheHighWaterMarkFailsTheSession() {
        val cases: List<Pair<String, (Device) -> Rx>> = listOf(
            "DATA" to { d: Device -> d.data(8, byteArrayOf(1), T0 + 5) },
            "CREDIT" to { d: Device -> d.control(OP_CREDIT, 8, u32(1024), T0 + 5) },
            "FIN" to { d: Device -> d.control(OP_FIN, 8, u32(0), T0 + 5) },
            "CLOSE" to { d: Device -> d.control(OP_CLOSE, 8, u16(1), T0 + 5) },
            "CLOSE_ACK" to { d: Device -> d.control(OP_CLOSE_ACK, 8, ByteArray(0), T0 + 5) },
            "RESET" to { d: Device -> d.control(OP_RESET, 8, u16(E_CANCELLED), T0 + 5) },
        )
        for ((name, send) in cases) {
            val h = Device()
            h.hello()
            h.openStream(3, T0 + 1)
            h.openStream(7, T0 + 2)
            h.assertRetired(5, T0 + 3)
            assertEquals(name, Rx.REJECTED, send(h))
            h.assertFailed(E_PROTOCOL, "$name for 8 above high-water 7")
        }
    }

    /**
     * Q12: "Only inner sequences are contiguous", and fw-001 Q12: the inner sequence is enforced
     * even for discarded traffic. Late frames for retired or skipped IDs each consume one sequence
     * number, and a gap or a repeat among them still fails the session.
     */
    @Test
    fun discardedFramesStillFollowTheContiguousInnerSequence() {
        run {
            val h = Device()
            h.hello()
            h.openStream(3, T0 + 1)
            val before = h.session.rxSequence
            assertEquals(h.devSeq, before)
            h.assertRetired(1, T0 + 2) // DATA, CREDIT, FIN, CLOSE_ACK, RESET, CLOSE: one frame each
            assertEquals(before + 6, h.session.rxSequence)
            assertEquals(h.devSeq, h.session.rxSequence)
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(3, T0 + 1)
            h.devSeq++ // skip one number
            assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(1), T0 + 2))
            h.assertFailed(E_PROTOCOL, "gap before a discarded DATA")
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(3, T0 + 1)
            assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 2, u32(8192), T0 + 2))
            h.devSeq-- // repeat the discarded CREDIT's number
            assertEquals(Rx.REJECTED, h.control(OP_CLOSE, 2, u16(1), T0 + 3))
            h.assertFailed(E_PROTOCOL, "repeat after a discarded CREDIT")
            assertEquals("no CLOSE_ACK for the refused frame", emptyList<String>(), h.texts())
        }
    }

    // ---- CONNECTING --------------------------------------------------------------------------

    /**
     * Firmware: an OPEN_OK or OPEN_ERR for an ID the P4 has ended is a session failure (CORE:254-255),
     * so after CLOSE_ACK the phone must never answer the OPEN, whatever its connect does later.
     */
    @Test
    fun deviceCloseWhileConnectingIsAckedAndALateConnectSendsNothing() {
        val h = Device()
        h.hello()
        assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 2))
        assertEquals(listOf("CLOSE_ACK#1"), h.texts())
        assertNull(h.session.find(1))
        assertEquals(0, h.session.liveStreams)
        assertFalse("the late socket must be closed, not announced", h.session.connected(1, T0 + 3))
        h.session.connectFailed(1, PipeError.CONNECT_FAILED)
        assertTrue(h.session.tick(T0 + 1 + 3_000))
        assertEquals("no OPEN_OK or OPEN_ERR after CLOSE_ACK", emptyList<String>(), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    @Test
    fun deviceResetWhileConnectingEndsTheStreamSilently() {
        val h = Device()
        h.hello()
        assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
        assertEquals(Rx.ACCEPTED, h.control(OP_RESET, 1, u16(E_CANCELLED), T0 + 2))
        assertEquals(emptyList<String>(), h.texts())
        assertNull(h.session.find(1))
        assertFalse(h.session.connected(1, T0 + 3))
        assertTrue(h.session.tick(T0 + 1 + 3_000))
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /** Handoff: "Before OPEN_OK, only OPEN_OK/OPEN_ERR and cancellation CLOSE/RESET are legal on that stream." */
    @Test
    fun dataCreditFinOrCloseAckBeforeOpenOkFailTheSession() {
        val cases: List<Pair<String, (Device) -> Rx>> = listOf(
            "DATA" to { d: Device -> d.data(1, byteArrayOf(1), T0 + 2) },
            "CREDIT" to { d: Device -> d.control(OP_CREDIT, 1, u32(1024), T0 + 2) },
            "FIN" to { d: Device -> d.control(OP_FIN, 1, u32(0), T0 + 2) },
            "CLOSE_ACK" to { d: Device -> d.control(OP_CLOSE_ACK, 1, ByteArray(0), T0 + 2) },
        )
        for ((name, send) in cases) {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertEquals(name, Rx.REJECTED, send(h))
            h.assertFailed(E_PROTOCOL, name)
        }
    }

    /**
     * Handoff: "Android attempts TCP connect once"; A-1: DNS + connect ≤ 3 s from the reassembled
     * OPEN, with Android's target 2.5 s (CONNECT_DEADLINE_MS). tick() enforces it: still CONNECTING
     * at 2 499 ms, OPEN_ERR(8) at 2 500 ms, and a later connect result announces nothing.
     */
    @Test
    fun connectStillPendingAfterTwoAndAHalfSecondsIsAnsweredWithConnectTimeout() {
        assertEquals(2_500L, PipeWire.CONNECT_DEADLINE_MS)
        val h = Device()
        h.hello()
        val openedAt = T0 + 10
        assertEquals(Rx.ACCEPTED, h.open(1, openedAt))
        assertTrue(h.session.tick(openedAt + 2_499))
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(StreamState.CONNECTING, h.stream(1).state)
        assertTrue(h.session.tick(openedAt + 2_500))
        assertEquals(listOf("OPEN_ERR#1=$E_CONNECT_TIMEOUT"), h.texts())
        assertNull(h.session.find(1))
        assertFalse(h.session.connected(1, openedAt + 2_501))
        assertEquals(emptyList<String>(), h.texts())
        // P4's own OPEN cap may already have sent CLOSE: it must still be acknowledged.
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), openedAt + 2_502))
        assertEquals(listOf("CLOSE_ACK#1"), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /**
     * The connect clock starts when the OPEN is reassembled (its LAST fragment), not at its FIRST,
     * and runs per stream; a connect that completes before its deadline is announced normally.
     */
    @Test
    fun connectDeadlineCountsFromTheLastOpenFragmentForEachStream() {
        val h = Device(maxFrame = 36) // a 56-byte DNS OPEN is three fragments
        h.hello()
        val body = openBody(TYPE_DNS, ("a".repeat(40) + ".example").toByteArray(Charsets.US_ASCII), 8443)
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, FIRST, body, 0, 24), T0))
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, 0, body, 24, 24), T0 + 500))
        assertEquals(Rx.ACCEPTED, h.push(h.frame(OP_OPEN, 1, LAST, body, 48, 8), T0 + 1_000))
        assertEquals(Rx.ACCEPTED, h.open(2, T0 + 1_500))
        assertTrue(h.session.tick(T0 + 3_499))
        assertEquals("2.5 s from T0 is not 2.5 s from the LAST fragment", emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 3_500))
        assertEquals(listOf("OPEN_ERR#1=$E_CONNECT_TIMEOUT"), h.texts())
        assertEquals(StreamState.CONNECTING, h.stream(2).state)
        assertTrue(h.session.connected(2, T0 + 3_999))
        assertEquals(listOf("OPEN_OK#2=1024"), h.texts())
        assertTrue(h.session.tick(T0 + 4_000))
        assertTrue(h.session.tick(T0 + 9_000))
        assertEquals(StreamState.OPEN, h.stream(2).state)
        assertEquals(listOf(OP_PING), h.drain().map { it.op })
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    // ---- OPEN_OK and the device's DATA allowance ---------------------------------------------

    /** Golden bytes: report-spec section 6A, "OPEN_OK id1, 1024 | ph→P4 seq1". */
    @Test
    fun connectedQueuesOpenOkWithTheInitialLimit() {
        val h = Device()
        h.hello()
        assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
        assertEquals(0L, h.stream(1).receiveLimit)
        assertTrue(h.session.connected(1, T0 + 2))
        val frame = h.step()!!
        assertEquals("01110100010000000400030000040000", hex(frame))
        assertEquals(1024L, h.stream(1).receiveLimit)
        assertEquals(1024L, h.stream(1).peerLimit)
        h.session.connected(1, T0 + 3)
        assertEquals("one OPEN_OK per OPEN", emptyList<String>(), h.texts())
    }

    /**
     * The device has no allowance until OPEN_OK reaches it, so DATA while OPEN_OK is merely queued
     * (or handed to the stack but not accepted) is a violation. Which code it carries is not
     * fixed by the sources: firmware reports DATA on a not-yet-open stream as Protocol (CORE:200).
     */
    @Test
    fun deviceDataIsRefusedUntilOpenOkHasBeenSubmitted() {
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertTrue(h.session.connected(1, T0 + 2))
            assertNotEquals(Rx.ACCEPTED, h.data(1, byteArrayOf(1), T0 + 3))
            h.assertFailedWithAnyOf(setOf(E_FLOW, E_PROTOCOL), "OPEN_OK queued")
        }
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertTrue(h.session.connected(1, T0 + 2))
            assertTrue(h.session.peek(ByteArray(VALUE_MAX)) > 0)
            assertNotEquals(Rx.ACCEPTED, h.data(1, byteArrayOf(1), T0 + 3))
            h.assertFailedWithAnyOf(setOf(E_FLOW, E_PROTOCOL), "OPEN_OK peeked, not accepted")
        }
    }

    /** Golden probe (report-spec 6D): "DATA of exactly 1024 bytes" accepted, "the 1025th byte of DATA (Flow)". */
    @Test
    fun deviceDataUpToTheOpenOkLimitIsAcceptedAndOneByteMoreIsAFlowViolation() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        assertEquals(1024L, s.receiveLimit)
        val bytes = pattern(1024, 7)
        h.pump(1, bytes, T0 + 1)
        assertEquals(1024L, s.received)
        assertEquals(1024, s.downBuffered)
        val copy = ByteArray(4096)
        assertEquals(1024, h.session.downPeek(1, copy))
        assertArrayEquals(bytes, copy.copyOf(1024))
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(0), T0 + 2))
        h.assertFailed(E_FLOW)
    }

    @Test
    fun receiveLimitGrowsOnlyWhenTheCreditIsSubmitted() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        h.pump(1, pattern(1024, 1), T0 + 1)
        h.session.downConsumed(1, 600)
        assertTrue(h.session.tick(T0 + 2))
        assertEquals("queued, not yet granted", 1024L, s.receiveLimit)
        assertTrue(h.session.peek(ByteArray(VALUE_MAX)) > 0)
        assertEquals("handed to the stack, not yet accepted", 1024L, s.receiveLimit)
        assertEquals(listOf("CREDIT#1=${600 + 4096}"), h.texts())
        assertEquals(4696L, s.receiveLimit)
        h.pump(1, pattern(4696 - 1024, 2), T0 + 3)
        assertEquals(4096, s.downBuffered)
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(0), T0 + 4))
        h.assertFailed(E_FLOW)
    }

    /** Handoff: "Receipt beyond the granted limit is a session protocol error." A queued grant is not a grant. */
    @Test
    fun dataBeyondTheSubmittedLimitFailsWhileTheCreditIsStillQueued() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.pump(1, pattern(1024, 1), T0 + 1)
        h.session.downConsumed(1, 1024)
        assertTrue(h.session.tick(T0 + 2))
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(0), T0 + 3))
        h.assertFailed(E_FLOW)
    }

    // ---- Credit from the device --------------------------------------------------------------

    /** Golden probe (report-spec 6D): "CREDIT 1023 (Flow)" against an initial limit of 1024. */
    @Test
    fun decreasingDeviceCreditIsAFlowViolation() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(Rx.REJECTED, h.control(OP_CREDIT, 1, u32(1023), T0 + 1))
        h.assertFailed(E_FLOW)
    }

    @Test
    fun equalDeviceCreditChangesNothing() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(1024), T0 + 1))
        assertEquals(1024L, s.peerLimit)
        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(2048), T0 + 2))
        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(2048), T0 + 3))
        assertEquals(2048L, s.peerLimit)
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /** Handoff: "A sender rejects credit that decreases or exceeds `sent + 4096`." CORE:273 accepts exactly sent+4096. */
    @Test
    fun deviceCreditAboveSentPlusWindowIsAFlowViolationButExactlyThatIsAccepted() {
        run {
            val h = Device()
            h.hello()
            val s = h.openStream(1, T0)
            assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(4096), T0 + 1))
            assertEquals(4096L, s.peerLimit)
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(1, T0)
            assertEquals(Rx.REJECTED, h.control(OP_CREDIT, 1, u32(4097), T0 + 1))
            h.assertFailed(E_FLOW, "4097 at sent=0")
        }
        run {
            val h = Device()
            h.hello()
            val s = h.openStream(1, T0)
            assertEquals(100, h.session.tcpRead(1, pattern(100, 3), 0, 100))
            assertEquals(listOf("DATA#1[100]"), h.texts())
            assertEquals(100L, s.sent)
            assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(4196), T0 + 1))
            assertEquals(4196L, s.peerLimit)
            assertEquals(Rx.REJECTED, h.control(OP_CREDIT, 1, u32(4197), T0 + 2))
            h.assertFailed(E_FLOW, "4197 at sent=100")
        }
    }

    // ---- Phone → device DATA -----------------------------------------------------------------

    /** Handoff: "Sender must satisfy `sent + body_length <= peer_limit`"; "stop BLE DATA submissions when peer credit is exhausted". */
    @Test
    fun noDataIsSentBeyondThePeerLimitUntilTheDeviceGrantsMore() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        val payload = pattern(2000, 5)
        assertEquals(2000, h.session.tcpRead(1, payload, 0, payload.size))
        val first = h.drain()
        assertTrue(first.all { it.op == OP_DATA && it.id == 1 })
        assertEquals(1024, first.sumOf { it.body.size })
        assertEquals(1024L, s.sent)
        assertEquals(976, s.upBuffered)
        assertEquals("zero credit: nothing to send", emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 50))
        assertEquals(emptyList<String>(), h.texts())

        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(2024), T0 + 60))
        val second = h.drain()
        assertTrue(second.all { it.op == OP_DATA && it.id == 1 })
        assertEquals(976, second.sumOf { it.body.size })
        assertEquals(2000L, s.sent)
        assertArrayEquals(payload, dataBytes(h.history, 1))
    }

    /** Handoff: "Round-robin DATA between credit-eligible streams; control messages precede the next DATA frame." */
    @Test
    fun dataAlternatesBetweenStreamsAndControlPrecedesTheNextDataFrame() {
        val h = Device(maxFrame = 61) // 49-byte DATA bodies
        h.hello()
        h.openStream(1, T0)
        h.openStream(2, T0)
        val a = pattern(300, 1)
        val b = pattern(300, 2)
        assertEquals(300, h.session.tcpRead(1, a, 0, 300))
        assertEquals(300, h.session.tcpRead(2, b, 0, 300))
        val frames = h.drain()
        assertTrue(frames.all { it.op == OP_DATA })
        assertEquals(14, frames.size)
        for (i in 1 until frames.size) assertNotEquals("frame $i alternates streams", frames[i - 1].id, frames[i].id)

        val a2 = pattern(200, 3)
        val b2 = pattern(200, 4)
        assertEquals(200, h.session.tcpRead(1, a2, 0, 200))
        assertEquals(200, h.session.tcpRead(2, b2, 0, 200))
        val data = h.step()!!
        assertEquals(OP_DATA, data[1].toInt() and 0xff)
        assertEquals(Rx.ACCEPTED, h.control(OP_PING, 0, u32(0x0badf00dL), T0 + 1))
        val next = h.step()!!
        assertEquals("PONG goes ahead of the buffered DATA", OP_PONG, next[1].toInt() and 0xff)
        assertEquals(0x0badf00dL, le32(next, HDR))
        val rest = h.drain()
        assertTrue(rest.all { it.op == OP_DATA })
        assertArrayEquals(a + a2, dataBytes(h.history, 1))
        assertArrayEquals(b + b2, dataBytes(h.history, 2))
    }

    @Test
    fun aStreamWithoutCreditDoesNotStallTheOther() {
        val h = Device()
        h.hello()
        val s1 = h.openStream(1, T0)
        val s2 = h.openStream(2, T0)
        assertEquals(2000, h.session.tcpRead(1, pattern(2000, 1), 0, 2000))
        assertEquals(300, h.session.tcpRead(2, pattern(300, 2), 0, 300))
        val frames = h.drain()
        assertEquals(1024, frames.filter { it.id == 1 }.sumOf { it.body.size })
        assertEquals(300, frames.filter { it.id == 2 }.sumOf { it.body.size })
        assertEquals(976, s1.upBuffered)
        assertEquals(0, s2.upBuffered)

        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 2, u32(2048), T0 + 1))
        assertEquals("credits are per stream", 1024L, s1.peerLimit)
        assertEquals(2048L, s2.peerLimit)
        assertEquals(emptyList<String>(), h.texts())

        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(2048), T0 + 2))
        val more = h.drain()
        assertTrue(more.all { it.op == OP_DATA && it.id == 1 })
        assertEquals(976, more.sumOf { it.body.size })
    }

    /**
     * Handoff: "DATA always uses flags 0 and a nonempty body of at most `value-12` bytes"; at stage 4
     * the value is P = min(ATT_MTU - 3, 512) - 25 (R8), so the largest body is P - 12: 24 at MTU 64,
     * 204 at the G2-mode MTU 244, 207 at 247 and 475 at 517.
     */
    @Test
    fun dataBodiesFitTheFrameLimitAndFollowOpenOkAtEveryStage4Mtu() {
        for ((mtu, maxBody) in listOf(64 to 24, 244 to 204, 247 to 207, 517 to 475)) {
            assertEquals("MTU $mtu", plimit(mtu), PipeWire.frameLimit(mtu))
            val h = Device(plimit(mtu))
            h.hello()
            assertEquals("MTU $mtu", plimit(mtu), h.session.value)
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertTrue(h.session.connected(1, T0 + 2))
            val payload = pattern(1024, mtu)
            val early = h.session.tcpRead(1, payload, 0, payload.size)
            val first = h.drain()
            assertEquals("MTU $mtu: OPEN_OK first", "OPEN_OK#1=1024", first[0].text)
            assertTrue("MTU $mtu: nothing but DATA after it", first.drop(1).all { it.op == OP_DATA })
            if (early < payload.size) {
                assertEquals(payload.size - early, h.session.tcpRead(1, payload, early, payload.size - early))
                h.drain()
            }
            val data = h.history.filter { it.op == OP_DATA }
            assertEquals("MTU $mtu: largest DATA body", maxBody, data.maxOf { it.body.size })
            assertArrayEquals(payload, dataBytes(h.history, 1))
            for (f in h.frames) assertTrue("MTU $mtu: frame of ${f.size} bytes", f.size <= maxBody + HDR)
        }
    }

    // ---- Credit from the phone ---------------------------------------------------------------

    /** Handoff: "Coalesce credit updates after 512 bytes consumed or 100 ms"; grants keep `limit - consumed <= 4096`. */
    @Test
    fun consumingHalfAKilobyteQueuesACreditOnTheNextTick() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        h.pump(1, pattern(1024, 3), T0 + 1)
        h.session.downConsumed(1, 511)
        assertTrue(h.session.tick(T0 + 2))
        assertEquals("511 bytes within 100 ms are not enough", emptyList<String>(), h.texts())
        h.session.downConsumed(1, 1)
        assertTrue(h.session.tick(T0 + 3))
        assertEquals(listOf("CREDIT#1=${512 + 4096}"), h.texts())
        assertEquals(512L, s.consumed)
        assertEquals(4608L, s.receiveLimit)
    }

    @Test
    fun smallConsumptionIsCreditedOneHundredMillisecondsAfterTheLastGrant() {
        val h = Device()
        h.hello()
        h.openStream(1, T0) // OPEN_OK is the first grant
        h.pump(1, pattern(100, 1), T0 + 10)
        h.session.downConsumed(1, 100)
        assertTrue(h.session.tick(T0 + 10))
        assertTrue(h.session.tick(T0 + 99))
        assertEquals(emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 100))
        assertEquals(listOf("CREDIT#1=${100 + 4096}"), h.texts())

        h.pump(1, pattern(50, 2), T0 + 150)
        h.session.downConsumed(1, 50)
        assertTrue(h.session.tick(T0 + 150))
        assertTrue(h.session.tick(T0 + 199))
        assertEquals(emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 200))
        assertEquals(listOf("CREDIT#1=${150 + 4096}"), h.texts())
    }

    /** Handoff: CREDIT is "a monotonically nondecreasing cumulative absolute limit" with `limit - consumed <= 4096`. */
    @Test
    fun phoneCreditGrantsAreNondecreasingAndNeverMoreThanAWindowAhead() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        var now = T0
        var last = 1024L
        var grants = 0
        for (i in 0 until 40) {
            now += 37
            val room = (s.receiveLimit - s.received).toInt()
            val n = minOf(room, 300 + (i * 53) % 400)
            if (n > 0) h.pump(1, pattern(n, i), now)
            assertTrue(s.downBuffered <= 4096)
            h.session.downConsumed(1, minOf(s.downBuffered, 100 + (i * 97) % 600))
            assertTrue(h.session.tick(now))
            for (m in h.drain()) {
                assertEquals(OP_CREDIT, m.op)
                val limit = le32(m.body, 0)
                assertTrue("grant $limit after $last", limit >= last)
                assertTrue("grant $limit runs ahead of consumed ${s.consumed}", limit - s.consumed <= 4096)
                last = limit
                grants++
            }
            assertEquals(last, s.receiveLimit)
        }
        assertTrue(grants > 5)
    }

    /** Handoff: "Coalesce pending CREDIT." Two unsent grants for one stream leave one frame with the newer limit. */
    @Test
    fun unsentCreditsForAStreamCoalesceIntoOne() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        h.pump(1, pattern(1024, 0), T0 + 1)
        h.session.downConsumed(1, 512)
        assertTrue(h.session.tick(T0 + 2))
        h.session.downConsumed(1, 512)
        assertTrue(h.session.tick(T0 + 3))
        assertEquals(listOf("CREDIT#1=${1024 + 4096}"), h.texts())
        assertEquals(5120L, s.receiveLimit)
    }

    // ---- FIN ---------------------------------------------------------------------------------

    /** Handoff: "P4 FIN becomes TCP `shutdownOutput` after queued TCP writes complete. Reverse traffic remains allowed." */
    @Test
    fun deviceFinWithTheReceivedCountShutsDownOnlyAfterTheDownBufferDrains() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        h.pump(1, pattern(300, 1), T0 + 1)
        assertEquals(Rx.ACCEPTED, h.control(OP_FIN, 1, u32(300), T0 + 2))
        assertTrue(s.peerFin)
        assertFalse(h.session.shutdownDue(1))
        h.session.downConsumed(1, 200)
        assertFalse(h.session.shutdownDue(1))
        h.session.downConsumed(1, 100)
        assertTrue(h.session.shutdownDue(1))
        h.session.outputShutdown(1)
        assertFalse("shutdown happens once", h.session.shutdownDue(1))

        assertEquals(64, h.session.tcpRead(1, pattern(64, 2), 0, 64))
        assertEquals(listOf("DATA#1[64]"), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    @Test
    fun deviceFinWithNothingSentIsDueAtOnce() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(Rx.ACCEPTED, h.control(OP_FIN, 1, u32(0), T0 + 1))
        assertTrue(h.session.shutdownDue(1))
    }

    /** Golden probe (report-spec 6D): "FIN mismatch" is rejected. */
    @Test
    fun deviceFinWithAWrongCountFails() {
        for (count in listOf(299L, 301L, 0L)) {
            val h = Device()
            h.hello()
            h.openStream(1, T0)
            h.pump(1, pattern(300, 1), T0 + 1)
            assertEquals(Rx.REJECTED, h.control(OP_FIN, 1, u32(count), T0 + 2))
            h.assertFailed(E_PROTOCOL, "FIN $count after 300")
        }
    }

    /** Golden probe (report-spec 6D): "duplicate FIN" is rejected. */
    @Test
    fun duplicateDeviceFinFails() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.pump(1, pattern(10, 1), T0 + 1)
        assertEquals(Rx.ACCEPTED, h.control(OP_FIN, 1, u32(10), T0 + 2))
        assertEquals(Rx.REJECTED, h.control(OP_FIN, 1, u32(10), T0 + 3))
        h.assertFailed(E_PROTOCOL)
    }

    /** Handoff: "No DATA after FIN in that direction." */
    @Test
    fun deviceDataAfterItsFinFails() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.pump(1, pattern(10, 1), T0 + 1)
        assertEquals(Rx.ACCEPTED, h.control(OP_FIN, 1, u32(10), T0 + 2))
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(1), T0 + 3))
        h.assertFailed(E_PROTOCOL)
    }

    /**
     * Handoff: TCP EOF becomes FIN "with the sent count"; "Send FIN only after all DATA has been
     * submitted ... No DATA may follow." Credit stalls must delay the FIN, never reorder it.
     */
    @Test
    fun phoneFinFollowsTheLastDataAndCarriesTheTotalSent() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        val payload = pattern(2000, 9)
        assertEquals(2000, h.session.tcpRead(1, payload, 0, payload.size))
        h.session.tcpEof(1)
        assertTrue(h.session.tick(T0 + 1))
        val first = h.drain()
        assertTrue(first.all { it.op == OP_DATA })
        assertEquals(1024, first.sumOf { it.body.size })
        assertTrue(h.session.tick(T0 + 2))
        assertEquals("no FIN while read bytes are unsent", emptyList<String>(), h.texts())

        assertEquals(Rx.ACCEPTED, h.control(OP_CREDIT, 1, u32(2048), T0 + 3))
        assertTrue(h.session.tick(T0 + 3))
        val second = h.drain()
        assertTrue(second.all { it.op == OP_DATA })
        assertEquals(976, second.sumOf { it.body.size })
        assertTrue(h.session.tick(T0 + 4))
        assertEquals(listOf("FIN#1=2000"), h.texts())
        assertEquals(2000L, s.sent)

        assertTrue(h.session.tick(T0 + 5))
        assertTrue(h.session.tick(T0 + 300))
        h.session.tcpRead(1, payload, 0, 10)
        assertTrue(h.session.tick(T0 + 301))
        assertEquals("one FIN, and nothing after it", emptyList<String>(), h.texts())
        val wire = h.history.filter { it.id == 1 && it.op != OP_OPEN_OK }.map { it.op }
        assertEquals(OP_FIN, wire.last())
        assertEquals(1, wire.count { it == OP_FIN })
        assertArrayEquals(payload, dataBytes(h.history, 1))

        // Reverse traffic is still allowed after the phone's FIN.
        h.pump(1, pattern(100, 4), T0 + 302)
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    @Test
    fun phoneFinForAnEmptyResponseCarriesZero() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.session.tcpEof(1)
        assertTrue(h.session.tick(T0 + 1))
        assertEquals(listOf("FIN#1=0"), h.texts())
    }

    // ---- CLOSE / CLOSE_ACK -------------------------------------------------------------------

    /** Handoff: "CLOSE aborts any remaining traffic ...; the peer discards remaining buffers and sends CLOSE_ACK." */
    @Test
    fun deviceCloseOnAnOpenStreamAcksAndDiscardsItsBuffers() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(500, h.session.tcpRead(1, pattern(500, 1), 0, 500))
        h.pump(1, pattern(300, 2), T0 + 1)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 2))
        assertEquals("no DATA from the discarded buffer", listOf("CLOSE_ACK#1"), h.texts())
        assertNull(h.session.find(1))
        assertEquals(0, h.session.liveStreams)
        assertEquals(0, h.session.upSpace(1))
        assertEquals(0, h.session.downPeek(1, ByteArray(4096)))
        assertFalse(h.session.shutdownDue(1))
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /**
     * Firmware: an OPEN_OK, CREDIT or FIN for an ID the P4 has ended is either fatal (OPEN_OK,
     * CORE:255) or meaningless, so the device's CLOSE/RESET must drop them while unsent. The other
     * stream's queued control frames are untouched.
     */
    @Test
    fun deviceCloseOrResetPurgesUnsentOpenOkCreditAndFinForThatStreamOnly() {
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertTrue(h.session.connected(1, T0 + 2))
            assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 3))
            assertEquals("OPEN_OK purged", listOf("CLOSE_ACK#1"), h.texts())
        }
        run {
            val h = Device()
            h.hello()
            assertEquals(Rx.ACCEPTED, h.open(1, T0 + 1))
            assertTrue(h.session.connected(1, T0 + 2))
            assertEquals(Rx.ACCEPTED, h.control(OP_RESET, 1, u16(E_CANCELLED), T0 + 3))
            assertEquals("OPEN_OK purged by RESET", emptyList<String>(), h.texts())
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(1, T0)
            h.openStream(2, T0)
            h.pump(1, pattern(600, 1), T0 + 1)
            h.pump(2, pattern(600, 2), T0 + 1)
            h.session.downConsumed(1, 600)
            h.session.downConsumed(2, 600)
            assertTrue(h.session.tick(T0 + 2))
            assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 3))
            assertEquals("CREDIT#1 purged", listOf("CLOSE_ACK#1", "CREDIT#2=4696"), h.texts().sorted())
        }
        run {
            val h = Device()
            h.hello()
            h.openStream(1, T0)
            h.openStream(2, T0)
            h.session.tcpEof(1)
            h.session.tcpEof(2)
            assertTrue(h.session.tick(T0 + 1))
            assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 2))
            assertEquals("FIN#1 purged", listOf("CLOSE_ACK#1", "FIN#2=0"), h.texts().sorted())
        }
    }

    /** Firmware sends reason 1 for every finished request (report-firmware F/H3); reason > 1 is invalid (CORE:250). */
    @Test
    fun closeReasonsZeroAndOneAreNormalButTwoFailsTheSession() {
        for (reason in 0..1) {
            val h = Device()
            h.hello()
            h.openStream(1, T0)
            assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(reason), T0 + 1))
            assertEquals(listOf("CLOSE_ACK#1"), h.texts())
            assertEquals(PipeSession.State.READY, h.session.state)
            assertNull("reason $reason is not an error", h.session.error)
        }
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(Rx.REJECTED, h.control(OP_CLOSE, 1, u16(2), T0 + 1))
        h.assertFailed(E_PROTOCOL)
    }

    /** Handoff: "CLOSE_ACK is legal only in closing state"; the phone never sends CLOSE, so it is never closing. */
    @Test
    fun closeAckOnAnOpenStreamFailsTheSession() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(Rx.REJECTED, h.control(OP_CLOSE_ACK, 1, ByteArray(0), T0 + 1))
        h.assertFailed(E_PROTOCOL)
    }

    /** Handoff: "Simultaneous CLOSE is allowed; each sends ACK ... ACK a late CLOSE." */
    @Test
    fun crossingAndLateClosesOnRetiredIdsAreAcked() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.openStream(2, T0)
        h.session.resetStream(1, PipeError.TCP_IO)
        assertEquals("CLOSE crossing the phone's RESET", Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 1))
        assertEquals(listOf("CLOSE_ACK#1", "RESET#1=$E_TCP"), h.texts().sorted())

        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 2, u16(1), T0 + 2))
        assertEquals(listOf("CLOSE_ACK#2"), h.texts())
        assertEquals("a repeated CLOSE is acknowledged again", Rx.ACCEPTED, h.control(OP_CLOSE, 2, u16(1), T0 + 3))
        assertEquals(listOf("CLOSE_ACK#2"), h.texts())
        assertEquals(0, h.session.liveStreams)
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    /** Handoff: "Cancelling one stream must not abort the other." */
    @Test
    fun cancellingOneStreamLeavesTheOtherStreamsBuffersAndCountersIntact() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        val s2 = h.openStream(2, T0)
        val down2 = pattern(200, 1)
        val up2 = pattern(300, 2)
        h.pump(1, pattern(100, 3), T0 + 1)
        h.pump(2, down2, T0 + 1)
        assertEquals(300, h.session.tcpRead(1, pattern(300, 4), 0, 300))
        assertEquals(300, h.session.tcpRead(2, up2, 0, 300))

        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 2))
        assertEquals(200L, s2.received)
        assertEquals(200, s2.downBuffered)
        assertEquals(300, s2.upBuffered)
        assertEquals(0L, s2.sent)
        assertEquals(1024L, s2.peerLimit)
        assertEquals(1024L, s2.receiveLimit)
        val copy = ByteArray(4096)
        assertEquals(200, h.session.downPeek(2, copy))
        assertArrayEquals(down2, copy.copyOf(200))

        assertEquals(listOf("CLOSE_ACK#1", "DATA#2[300]"), h.texts())
        assertArrayEquals(up2, dataBytes(h.history, 2))
        assertTrue(dataBytes(h.history, 1).isEmpty())
        h.pump(2, pattern(100, 5), T0 + 3)
        assertEquals(300L, s2.received)
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    // ---- Local stream failure ----------------------------------------------------------------

    /** Handoff: "OPEN failures use OPEN_ERR; established stream failures use RESET." "TCP error → send RESET(11)". */
    @Test
    fun resetStreamOnAnOpenStreamSendsResetWithTheCode() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        val s2 = h.openStream(2, T0)
        assertEquals(300, h.session.tcpRead(1, pattern(300, 1), 0, 300))
        assertEquals(100, h.session.tcpRead(2, pattern(100, 2), 0, 100))
        h.session.resetStream(1, PipeError.TCP_IO)
        val out = h.drain()
        assertEquals(listOf("RESET#1=$E_TCP", "DATA#2[100]"), out.map { it.text })
        val reset = h.frames[h.frames.size - 2]
        assertEquals("RESET frame", "0125010003000000020003000b00", hex(reset))
        assertNull(h.session.find(1))
        assertEquals(StreamState.OPEN, s2.state)
        assertEquals(100L, s2.sent)
        h.assertRetired(1, T0 + 1)
        h.pump(2, pattern(10, 3), T0 + 2)
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    @Test
    fun resetStreamWhileConnectingSendsOpenErrInstead() {
        val h = Device()
        h.hello()
        val s2 = h.openStream(2, T0)
        assertEquals(Rx.ACCEPTED, h.open(3, T0 + 1))
        h.session.resetStream(3, PipeError.VPN_UNAVAILABLE)
        assertEquals(listOf("OPEN_ERR#3=$E_VPN"), h.texts())
        assertFalse(h.session.connected(3, T0 + 2))
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(StreamState.OPEN, s2.state)
        assertEquals(1, h.session.liveStreams)
    }

    // ---- Liveness ----------------------------------------------------------------------------

    /** Handoff: "While streams exist, send PING after 5 s without inbound frames ... Only one ping per endpoint may be outstanding." */
    @Test
    fun idleLiveStreamGetsExactlyOnePingAfterFiveSeconds() {
        val h = Device()
        h.hello()
        h.openStream(1, T0) // last inbound frame at T0
        assertTrue(h.session.tick(T0 + 4_999))
        assertEquals(emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 5_000))
        val ping = h.drain()
        assertEquals(1, ping.size)
        assertEquals(OP_PING, ping[0].op)
        assertEquals(0, ping[0].id)
        assertEquals(4, ping[0].body.size)
        assertTrue(h.session.tick(T0 + 7_000))
        assertEquals("one outstanding PING", emptyList<String>(), h.texts())

        assertEquals(Rx.ACCEPTED, h.control(OP_PONG, 0, ping[0].body, T0 + 7_000))
        assertTrue(h.session.tick(T0 + 11_999))
        assertEquals(emptyList<String>(), h.texts())
        assertTrue(h.session.tick(T0 + 12_000))
        assertEquals(listOf(OP_PING), h.drain().map { it.op })
    }

    /** Handoff: "require PONG within 5 s"; failure is DEADLINE. */
    @Test
    fun missingPongFailsTheSessionWithDeadline() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertTrue(h.session.tick(T0 + 5_000))
        assertEquals(listOf(OP_PING), h.drain().map { it.op })
        assertTrue(h.session.tick(T0 + 9_999))
        assertEquals(PipeSession.State.READY, h.session.state)
        assertFalse(h.session.tick(T0 + 10_000))
        h.assertFailed(E_DEADLINE)
    }

    /** Handoff: "No idle application keepalive with zero streams." */
    @Test
    fun noPingIsSentWithoutLiveStreams() {
        val h = Device()
        h.hello()
        assertTrue(h.session.tick(T0 + 5_000))
        assertTrue(h.session.tick(T0 + 60_000))
        assertEquals(emptyList<String>(), h.texts())

        h.openStream(1, T0 + 60_001)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 60_002))
        assertEquals(listOf("CLOSE_ACK#1"), h.texts())
        assertTrue(h.session.tick(T0 + 70_000))
        assertTrue(h.session.tick(T0 + 120_000))
        assertEquals(emptyList<String>(), h.texts())
        assertEquals(PipeSession.State.READY, h.session.state)
    }

    // ---- Bounded queues and buffers ----------------------------------------------------------

    /** Handoff: "Reserve eight control-message descriptors ... On control queue overflow fail the session; do not silently drop." */
    @Test
    fun controlQueueOverflowFailsTheSessionWithNoResources() {
        run {
            val h = Device()
            h.hello()
            for (cookie in 1L..8L) assertEquals(Rx.ACCEPTED, h.control(OP_PING, 0, u32(cookie), T0 + cookie))
            assertEquals("all eight queued replies are delivered", (1..8).map { "PONG#0=$it" }, h.texts())
        }
        val h = Device()
        h.hello()
        for (cookie in 1L..8L) assertEquals(Rx.ACCEPTED, h.control(OP_PING, 0, u32(cookie), T0 + cookie))
        assertNotEquals(Rx.ACCEPTED, h.control(OP_PING, 0, u32(9), T0 + 9))
        h.assertFailed(E_RESOURCES)
    }

    /** Handoff: "Limit every socket read to available bounded space"; 4 KiB per stream per direction. */
    @Test
    fun upstreamBufferNeverHoldsMoreThanTheWindow() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        assertEquals(4096, h.session.upSpace(1))
        val big = pattern(5000, 4)
        val took = h.session.tcpRead(1, big, 0, big.size)
        assertTrue("took $took", took <= 4096)
        assertEquals(took, s.upBuffered)
        val fill = 4096 - s.upBuffered
        assertEquals(fill, h.session.tcpRead(1, big, 0, fill))
        assertEquals(4096, s.upBuffered)
        assertEquals(0, h.session.upSpace(1))
        assertEquals(0, h.session.tcpRead(1, big, 0, 1))
        assertEquals(4096, s.upBuffered)
        assertEquals(1024, h.drain().sumOf { it.body.size })
        assertEquals("sending frees exactly what was sent", 1024, h.session.upSpace(1))
    }

    @Test
    fun downstreamBufferNeverExceedsTheWindow() {
        run {
            // A reader that never drains: no storage is freed, so no credit and no growth.
            val h = Device()
            h.hello()
            val s = h.openStream(1, T0)
            h.pump(1, pattern(1024, 1), T0 + 1)
            assertTrue(h.session.tick(T0 + 500))
            assertEquals("no credit without consumption", emptyList<String>(), h.texts())
            assertEquals(1024L, s.receiveLimit)
            assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(1), T0 + 501))
            h.assertFailed(E_FLOW)
        }
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        h.pump(1, pattern(1024, 1), T0 + 1)
        h.session.downConsumed(1, 1024)
        assertTrue(h.session.tick(T0 + 2))
        assertEquals(listOf("CREDIT#1=5120"), h.texts())
        h.pump(1, pattern(4096, 2), T0 + 3)
        assertEquals(4096, s.downBuffered)
        assertEquals(Rx.REJECTED, h.data(1, byteArrayOf(1), T0 + 4))
        h.assertFailed(E_FLOW)
    }

    /** Handoff: "zero-credit cancellation"; "Control is not credit-controlled ... do not ... deadlock waiting for DATA credit." */
    @Test
    fun zeroCreditStreamStillGetsAnImmediateCloseAck() {
        val h = Device()
        h.hello()
        val s = h.openStream(1, T0)
        assertEquals(2000, h.session.tcpRead(1, pattern(2000, 1), 0, 2000))
        assertEquals(1024, h.drain().sumOf { it.body.size })
        assertEquals(s.peerLimit, s.sent)
        assertEquals(976, s.upBuffered)
        assertEquals(Rx.ACCEPTED, h.control(OP_CLOSE, 1, u16(1), T0 + 1))
        val ack = h.step()!!
        assertEquals(OP_CLOSE_ACK, ack[1].toInt() and 0xff)
        assertEquals(1, le16(ack, 2))
        assertEquals(emptyList<String>(), h.texts())
        assertNull(h.session.find(1))
    }

    // ---- Session failure ---------------------------------------------------------------------

    /** Handoff: a session failure "fails all streams and disconnects (best-effort stream-0 RESET)". */
    @Test
    fun failEndsEveryStreamAndStopsTransmission() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        assertEquals(Rx.ACCEPTED, h.open(2, T0 + 1))
        assertEquals(300, h.session.tcpRead(1, pattern(300, 1), 0, 300))
        h.pump(1, pattern(200, 2), T0 + 2)
        assertTrue(h.session.connected(2, T0 + 3))

        assertFalse(h.session.fail(PipeError.DEADLINE))
        h.assertFailed(E_DEADLINE)
        assertTrue(h.session.streams.none { it.live })
        assertFalse(h.session.connected(2, T0 + 4))
        assertEquals(0, h.session.upSpace(1))
        assertEquals(0, h.session.downPeek(1, ByteArray(4096)))
        assertFalse(h.session.tick(T0 + 5))
        assertNotEquals(Rx.ACCEPTED, h.data(1, byteArrayOf(1), T0 + 6))
        assertEquals(0, h.session.peek(ByteArray(VALUE_MAX)))

        val out = ByteArray(VALUE_MAX)
        val n = h.session.failureFrame(out)
        assertEquals("0125" + "0000" + hex(u32(h.phoneSeq)) + "0200" + "03" + "00" + "0d00", hex(out.copyOf(n)))
    }

    @Test
    fun aViolationOnOneStreamEndsBothStreams() {
        val h = Device()
        h.hello()
        h.openStream(1, T0)
        h.openStream(2, T0)
        assertEquals(100, h.session.tcpRead(2, pattern(100, 1), 0, 100))
        assertEquals(Rx.REJECTED, h.control(OP_CLOSE_ACK, 1, ByteArray(0), T0 + 1))
        h.assertFailed(E_PROTOCOL)
        assertNull(h.session.find(2))
        assertEquals(0, h.session.upSpace(2))
        assertFalse(h.session.connected(2, T0 + 2))
    }

    // ---- P4-side harness ---------------------------------------------------------------------

    /** One reassembled phone → device message. */
    class Msg(val op: Int, val id: Int, val body: ByteArray) {
        val text: String
            get() = when {
                op == OP_DATA -> "DATA#$id[${body.size}]"
                body.isEmpty() -> "${opName(op)}#$id"
                body.size == 2 -> "${opName(op)}#$id=${le16(body, 0)}"
                body.size == 4 -> "${opName(op)}#$id=${le32(body, 0)}"
                else -> "${opName(op)}#$id(${body.size} bytes)"
            }

        override fun toString(): String = text
    }

    /**
     * Plays the P4: frames device → phone messages with its own sequence counter and the greedy
     * fragmenter the firmware uses (CORE:149-171), and drains the phone's TX exactly like the
     * engine (peek, then accept), validating every frame and reassembling control messages.
     */
    class Device(val maxFrame: Int = 512, val offer: Int = maxFrame, val t0: Long = T0) {
        val admitted = mutableListOf<PipeTarget>()
        /** [PipeSession.liveStreams] at the moment each admit ran. */
        val liveAtAdmit = mutableListOf<Int>()
        var verdict: (PipeTarget) -> PipeError? = { null }
        private var noise = 0
        /** Built at [t0], as the engine builds it when CONFIRM_ACK verifies. */
        val session = PipeSession(maxFrame, t0, this::onAdmit, this::fillRandom)

        /** Device → phone framing limit: the P4's own P (its offer) until HELLO_ACK, then the chosen value. */
        var devValue = offer
        /** Largest inner frame the phone may emit: its maxFrame, then the chosen value. */
        var phoneCap = maxFrame
        var devSeq = 0L
        var phoneSeq = 0L
        /** Every raw frame the phone submitted, in order. */
        val frames = mutableListOf<ByteArray>()
        /** Every complete message the phone submitted, in order. */
        val history = mutableListOf<Msg>()
        private var asmOp = -1
        private var asmId = 0
        private val asm = ByteArrayOutputStream()

        private fun onAdmit(target: PipeTarget): PipeError? {
            admitted += target
            liveAtAdmit += session.liveStreams
            return verdict(target)
        }

        private fun fillRandom(b: ByteArray) {
            for (i in b.indices) b[i] = (1 + noise++ % 255).toByte()
        }

        fun frame(op: Int, id: Int, flags: Int, body: ByteArray, off: Int = 0, n: Int = body.size): ByteArray {
            val f = ByteArray(HDR + n)
            f[0] = 1
            f[1] = op.toByte()
            put16(f, 2, id)
            put32(f, 4, devSeq)
            put16(f, 8, n)
            f[10] = flags.toByte()
            f[11] = 0
            System.arraycopy(body, off, f, HDR, n)
            return f
        }

        fun push(f: ByteArray, now: Long): Rx {
            val r = session.receive(f, f.size, now)
            devSeq++
            return r
        }

        /** A control message, fragmented FIRST … LAST at the device's framing limit. */
        fun control(op: Int, id: Int, body: ByteArray, now: Long): Rx {
            if (body.isEmpty()) return push(frame(op, id, FIRST or LAST, body), now)
            val room = devValue - HDR
            var off = 0
            var r: Rx
            do {
                val n = minOf(room, body.size - off)
                val flags = (if (off == 0) FIRST else 0) or (if (off + n == body.size) LAST else 0)
                r = push(frame(op, id, flags, body, off, n), now)
                off += n
                if (off < body.size) assertEquals("control fragment ending at $off", Rx.ACCEPTED, r)
            } while (off < body.size)
            return r
        }

        /** One DATA frame. */
        fun data(id: Int, body: ByteArray, now: Long): Rx {
            assertTrue(body.size in 1..devValue - HDR)
            return push(frame(OP_DATA, id, 0, body), now)
        }

        /** Sends [bytes] as full-size DATA frames, each of which must be accepted. */
        fun pump(id: Int, bytes: ByteArray, now: Long) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(devValue - HDR, bytes.size - off)
                assertEquals("device DATA within its allowance", Rx.ACCEPTED, data(id, bytes.copyOfRange(off, off + n), now))
                off += n
            }
        }

        fun hello(now: Long = t0) {
            val chosen = minOf(maxFrame, offer)
            assertEquals(Rx.ACCEPTED, control(OP_HELLO, 0, helloBody(offer), now))
            phoneCap = chosen
            val acks = drain()
            assertEquals(1, acks.size)
            val ack = acks[0]
            assertEquals(OP_HELLO_ACK, ack.op)
            assertEquals(0, ack.id)
            assertEquals(20, ack.body.size)
            assertArrayEquals(NONCE, ack.body.copyOfRange(0, 8))
            assertTrue(ack.body.copyOfRange(8, 16).any { it != 0.toByte() })
            assertEquals(2, ack.body[16].toInt())
            assertEquals(0, ack.body[17].toInt())
            assertEquals(chosen, le16(ack.body, 18))
            devValue = chosen
            assertEquals(PipeSession.State.READY, session.state)
        }

        fun open(id: Int, now: Long, type: Int = TYPE_V4, host: ByteArray = V4, port: Int = 443, limit: Long = 1024): Rx =
            control(OP_OPEN, id, openBody(type, host, port, limit), now)

        /** OPEN, the single connect succeeding, and OPEN_OK submitted: DATA may flow both ways. */
        fun openStream(id: Int, now: Long): PipeSession.Stream {
            assertEquals(Rx.ACCEPTED, open(id, now))
            assertTrue(session.connected(id, now))
            assertEquals(listOf("OPEN_OK#$id=1024"), texts())
            return stream(id)
        }

        fun stream(id: Int): PipeSession.Stream = session.find(id) ?: throw AssertionError("stream $id is not live")

        /** Submits the next TX frame (peek, then accept) and returns it; null when idle. */
        fun step(): ByteArray? = stepMsg()?.first

        private fun stepMsg(): Pair<ByteArray, Msg?>? {
            val buf = ByteArray(VALUE_MAX)
            val size = session.peek(buf)
            if (size == 0) return null
            val f = buf.copyOf(size)
            assertTrue("frame of $size bytes within value $phoneCap", size in HDR..phoneCap)
            assertEquals("version", 1, f[0].toInt())
            assertEquals("reserved", 0, f[11].toInt())
            assertEquals("length field", size - HDR, le16(f, 8))
            assertEquals("phone TX sequence", phoneSeq, le32(f, 4))
            val again = ByteArray(VALUE_MAX)
            assertEquals("peek repeats the pending frame", size, session.peek(again))
            assertArrayEquals(f, again.copyOf(size))
            assertTrue(session.accept())
            phoneSeq++
            frames += f
            val op = f[1].toInt() and 0xff
            val id = le16(f, 2)
            val flags = f[10].toInt() and 0xff
            val body = f.copyOfRange(HDR, size)
            if (op == OP_DATA) {
                assertEquals("DATA flags", 0, flags)
                assertTrue("DATA body is never empty", body.isNotEmpty())
                assertTrue("DATA on a nonzero stream", id != 0)
                assertEquals("DATA never splits a control message", -1, asmOp)
                val m = Msg(op, id, body)
                history += m
                return f to m
            }
            assertEquals("control flags", 0, flags and (FIRST or LAST).inv())
            if (flags and FIRST != 0) {
                assertEquals("FIRST while a control message is half-sent", -1, asmOp)
                asmOp = op
                asmId = id
                asm.reset()
            } else {
                assertEquals("continuation opcode", asmOp, op)
                assertEquals("continuation stream", asmId, id)
            }
            asm.write(body, 0, body.size)
            if (flags and LAST == 0) return f to null
            asmOp = -1
            val m = Msg(op, id, asm.toByteArray())
            history += m
            return f to m
        }

        /** Submits everything the phone has to send and returns the complete messages, in order. */
        fun drain(): List<Msg> {
            val out = mutableListOf<Msg>()
            var count = 0
            while (true) {
                val (_, msg) = stepMsg() ?: break
                if (msg != null) out += msg
                assertTrue("TX must go idle", ++count <= 10_000)
            }
            assertEquals("no control message is left half-sent", -1, asmOp)
            return out
        }

        fun texts(): List<String> = drain().map { it.text }

        /**
         * Late DATA/CREDIT/FIN/CLOSE_ACK/RESET for a retired [id] are accepted and dropped without
         * reviving it or touching other streams; a late CLOSE is answered with CLOSE_ACK.
         */
        fun assertRetired(id: Int, now: Long) {
            val live = session.liveStreams
            assertEquals(Rx.ACCEPTED, data(id, byteArrayOf(1), now))
            assertEquals(Rx.ACCEPTED, control(OP_CREDIT, id, u32(8192), now))
            assertEquals(Rx.ACCEPTED, control(OP_FIN, id, u32(0), now))
            assertEquals(Rx.ACCEPTED, control(OP_CLOSE_ACK, id, ByteArray(0), now))
            assertEquals(Rx.ACCEPTED, control(OP_RESET, id, u16(E_TCP), now))
            assertNull(session.find(id))
            assertEquals(live, session.liveStreams)
            assertEquals("late frames for retired #$id are discarded silently", emptyList<String>(),
                drain().filter { it.id == id }.map { it.text })
            assertEquals(Rx.ACCEPTED, control(OP_CLOSE, id, u16(1), now))
            assertEquals(listOf("CLOSE_ACK#$id"), drain().filter { it.id == id }.map { it.text })
            assertNull(session.find(id))
            assertEquals(live, session.liveStreams)
            assertEquals(PipeSession.State.READY, session.state)
        }

        fun assertFailed(code: Int, what: String = "") = assertFailedWithAnyOf(setOf(code), what)

        fun assertFailedWithAnyOf(codes: Set<Int>, what: String = "") {
            assertEquals("$what: session state", PipeSession.State.FAILED, session.state)
            val code = session.error?.code
            assertTrue("$what: error code $code not in $codes", code != null && code in codes)
            assertEquals("$what: every stream ended", 0, session.liveStreams)
            assertEquals("$what: nothing more is submitted", 0, session.peek(ByteArray(VALUE_MAX)))
        }
    }

    companion object {
        const val T0 = 1_000_000L
        const val HDR = 12
        const val VALUE_MAX = 512
        const val FIRST = 0x01
        const val LAST = 0x02

        // Opcodes: handoff "Session and stream messages"; CORE:15-17.
        const val OP_HELLO = 0x01
        const val OP_HELLO_ACK = 0x02
        const val OP_OPEN = 0x10
        const val OP_OPEN_OK = 0x11
        const val OP_OPEN_ERR = 0x12
        const val OP_DATA = 0x20
        const val OP_CREDIT = 0x21
        const val OP_FIN = 0x22
        const val OP_CLOSE = 0x23
        const val OP_CLOSE_ACK = 0x24
        const val OP_RESET = 0x25
        const val OP_PING = 0x30
        const val OP_PONG = 0x31

        // Errors: handoff "Errors are fixed numeric values"; CORE:18-20.
        const val E_PROTOCOL = 1
        const val E_UNAUTHORIZED = 3
        const val E_TARGET = 4
        const val E_VPN = 5
        const val E_CONNECT_TIMEOUT = 8
        const val E_BUSY = 9
        const val E_RESOURCES = 10
        const val E_TCP = 11
        const val E_FLOW = 12
        const val E_DEADLINE = 13
        const val E_CANCELLED = 14

        const val TYPE_V4 = 1
        const val TYPE_V6 = 2
        const val TYPE_DNS = 3

        /** HELLO nonce u64 0x0102030405060708, as in report-spec section 6A. */
        val NONCE = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)
        /** 192.0.2.1 (RFC 5737). */
        val V4 = byteArrayOf(192.toByte(), 0, 2, 1)
        /** 2001:db8::1 (RFC 3849). */
        val V6 = ByteArray(16).also {
            it[0] = 0x20.toByte()
            it[1] = 0x01.toByte()
            it[2] = 0x0d.toByte()
            it[3] = 0xb8.toByte()
            it[15] = 0x01.toByte()
        }
        val DNS_HOST = "relay.example".toByteArray(Charsets.US_ASCII)

        private val OP_NAMES = mapOf(
            OP_HELLO to "HELLO", OP_HELLO_ACK to "HELLO_ACK", OP_OPEN to "OPEN", OP_OPEN_OK to "OPEN_OK",
            OP_OPEN_ERR to "OPEN_ERR", OP_DATA to "DATA", OP_CREDIT to "CREDIT", OP_FIN to "FIN",
            OP_CLOSE to "CLOSE", OP_CLOSE_ACK to "CLOSE_ACK", OP_RESET to "RESET", OP_PING to "PING", OP_PONG to "PONG",
        )

        fun opName(op: Int): String = OP_NAMES[op] ?: "OP_%02x".format(op)

        /**
         * Stage-4 `P = min(ATT_MTU - 3, 512) - 25`, 0 below the required ATT MTU 64 (DECISIONS R8):
         * the largest inner frame one secure DATA value carries.
         */
        fun plimit(mtu: Int): Int = if (mtu < 64) 0 else minOf(mtu - 3, 512) - 25

        fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

        fun le32(b: ByteArray, o: Int): Long = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)

        fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v ushr 8).toByte()
        }

        fun put32(b: ByteArray, o: Int, v: Long) {
            put16(b, o, (v and 0xffff).toInt())
            put16(b, o + 2, ((v ushr 16) and 0xffff).toInt())
        }

        fun u16(v: Int): ByteArray = ByteArray(2).also { put16(it, 0, v) }

        fun u32(v: Long): ByteArray = ByteArray(4).also { put32(it, 0, v) }

        /** `nonce:u64, max_streams:u8=2, reserved:u8=0, max_value:u16`. */
        fun helloBody(maxValue: Int): ByteArray = NONCE + byteArrayOf(2, 0) + u16(maxValue)

        /** `receive_limit:u32, port:u16, address_type:u8, host_length:u8, host[host_length]`. */
        fun openBody(type: Int, host: ByteArray, port: Int, limit: Long = 1024): ByteArray {
            val b = ByteArray(8 + host.size)
            put32(b, 0, limit)
            put16(b, 4, port)
            b[6] = type.toByte()
            b[7] = host.size.toByte()
            System.arraycopy(host, 0, b, 8, host.size)
            return b
        }

        fun pattern(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 7 + seed * 31) and 0xff).toByte() }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** The DATA bodies the phone sent on stream [id], concatenated in order. */
        fun dataBytes(msgs: List<Msg>, id: Int): ByteArray {
            val out = ByteArrayOutputStream()
            for (m in msgs) if (m.op == OP_DATA && m.id == id) out.write(m.body, 0, m.body.size)
            return out.toByteArray()
        }
    }
}
