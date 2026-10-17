package com.hardwareone.console.pipe

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The stage-4 contract of [PipeEngine] (DECISIONS.md R3, R5–R10, R13, R14/A-5, FW-3, FW-4, F5),
 * one rule per test, on its real threads. The peer is [FakeP4], transcribed from the firmware's
 * `BLE_PhoneSecureChannel.h` and `BLE_PhonePipeCore.h`; every phone value is opened by it, so a
 * frame that passes here would also pass the P4. The link is [FakeLink], which records each
 * submission and decides its outcome and completion. Expected values are the firmware fixture,
 * PipeTestKit's independent encoders, or written out in full.
 *
 * Timing tests use real time with polled waits. The handshake-expiry tests take about 4.5 s each
 * and the completion-deadline tests about 2–3 s; everything else is sub-second. A CPU suspend is
 * modelled by moving the engine clock alone ([SecureRig.suspendFor]), and a test that must fire a
 * teardown timer at a chosen moment holds it in a [HeldScheduler].
 */
class PipeEngineSecureTest {
    private val rigs = CopyOnWriteArrayList<SecureRig>()

    @After
    fun closeRigs() {
        rigs.forEach { it.close() }
    }

    private fun rig(
        mtu: Int = 517,
        p4Psk: ByteArray = PipeFixture.psk,
        random: (ByteArray) -> Unit = java.security.SecureRandom()::nextBytes,
        phoneKey: ByteArray? = null,
        p4Key: ByteArray? = null,
        p4Nonce: ByteArray? = null,
        p4InnerNonce: ByteArray = P4_NONCE,
        impostor: Boolean = false,
    ) = SecureRig(
        mtu = mtu, p4Psk = p4Psk, random = random, phoneKey = phoneKey, p4Key = p4Key, p4Nonce = p4Nonce,
        p4InnerNonce = p4InnerNonce, p4Impostor = impostor,
    ).also { rigs += it }

    /**
     * Waits for the session's PipeEnd, lets the teardown finish, and checks it is [expected] in
     * full, reported exactly once, with the engine already idle and [disconnects] disconnect()
     * calls made before it (none after).
     */
    private fun assertEndedOnce(r: SecureRig, expected: PipeEnd, disconnects: Int = 1, c: SecureRig.Conn = r.conn): EndRecord {
        val end = r.awaitEnd(c, timeoutMs = 7_000)
        Thread.sleep(150)
        r.sync(c)
        assertEquals(expected, end.end)
        assertEquals("PipeEnd once per session", 1, r.endsOf(c).size)
        assertFalse("the engine is idle when PipeEnd arrives", end.activeAtEnd)
        assertEquals("disconnects before PipeEnd", disconnects, end.disconnectsAtEnd)
        assertEquals("disconnects in total", disconnects, c.link.disconnects.size)
        return end
    }

    /** The next submission on the current link gets [outcome] / [completion]; returns its index. */
    private fun nextSubmission(r: SecureRig, outcome: FakeLink.Outcome = FakeLink.Outcome.OK, completion: Int? = 0): Int {
        val next = r.link.sent.size
        r.link.outcome = { if (it.index == next) outcome else FakeLink.Outcome.OK }
        r.link.completion = { if (it.index == next) completion else 0 }
        return next
    }

    private fun ms(fromNanos: Long, toNanos: Long): Double = (toNanos - fromNanos) / 1_000_000.0

    // ---- Golden transcript and the first submissions ---------------------------------------------

    /**
     * The fixture's MTU-247 session end to end through the engine: with the fixture phone key,
     * phone nonce and inner nonce 0x1112131415161718 on the phone, and the fixture device key and
     * nonce on the P4, HELLO, CONFIRM and the phone's first DATA (HELLO_ACK at counter 1) are the
     * firmware's bytes, and the P4's own values are the fixture's too.
     */
    @Test
    fun theGoldenHandshakeAndFirstDataMatchTheFirmwareFixtureAtMtu247() {
        val random = FakeRandom(PipeFixture.bytes("phone_nonce_hex"), hex("1112131415161718"))
        val r = rig(
            mtu = PipeFixture.long("att_mtu").toInt(),
            random = random,
            phoneKey = PipeFixture.bytes("phone_private_key_hex"),
            p4Key = PipeFixture.bytes("device_private_key_hex"),
            p4Nonce = PipeFixture.bytes("device_nonce_hex"),
            p4InnerNonce = hex("0102030405060708"),
        )
        assertEquals(247, r.mtu)
        r.autoHandshake = false
        assertTrue(r.begin())
        r.subscribe()

        Poll.until("HELLO") { r.link.sent.size == 1 }
        assertEquals(PipeFixture.string("hello_hex"), toHex(r.link.sent[0].value))
        r.sync()
        val helloAck = r.conn.replies.single()
        assertEquals(PipeFixture.string("hello_ack_hex"), toHex(helloAck))
        r.write(helloAck)

        Poll.until("CONFIRM") { r.link.sent.size == 2 }
        assertEquals(PipeFixture.string("confirm_hex"), toHex(r.link.sent[1].value))
        r.sync()
        val confirmAck = r.conn.replies[1]
        assertEquals(PipeFixture.string("confirm_ack_hex"), toHex(confirmAck))
        r.write(confirmAck)
        r.awaitStage("secure")

        // The P4's first DATA: counter 1, inner HELLO seq 0 offering P = 247 − 3 − 25 = 219.
        val innerHello = r.p4.inner.frames(Op.HELLO, 0, helloBody(219, hex("0102030405060708"))).single()
        assertEquals(PipeFixture.string("p4_to_phone_plaintext_hello_hex"), toHex(innerHello))
        val p4Data = r.p4.seal(innerHello)
        assertEquals(PipeFixture.string("p4_to_phone_data_hex"), toHex(p4Data))
        r.write(p4Data)

        Poll.until("first phone DATA") { r.link.sent.size == 3 }
        assertEquals(PipeFixture.string("phone_to_p4_data_hex"), toHex(r.link.sent[2].value))
        r.awaitStage("ready")
        val ack = r.sync().single()
        assertEquals(PipeFixture.string("phone_to_p4_plaintext_hello_ack_hex"), toHex(ack.bytes))
        assertEquals(PipeFixture.long("data_counter_each_direction"), ack.counter)
        assertEquals("an injected key leaves the handshake nonce and the inner nonce to draw", 2, random.calls)
        assertEquals(3, r.link.sent.size)
    }

    /** R3: the CCCD answer comes first, so the engine notifies nothing before subscribed(); then HELLO. */
    @Test
    fun nothingIsNotifiedBeforeSubscribedAndHelloIsTheFirstSubmission() {
        val key = bytes(32, 0x42)
        val nonce = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
        val r = rig(random = FakeRandom(nonce), phoneKey = key)
        r.autoHandshake = false
        assertTrue(r.begin())
        r.engine.subscribed(r.token + 1) // another session's CCCD answer: ignored
        Thread.sleep(250)
        assertTrue("nothing before the CCCD response: ${r.link.sent}", r.link.sent.isEmpty())
        assertEquals("handshake", r.engine.currentStats.stage)
        assertTrue(r.engine.active)

        r.subscribe()
        Poll.until("HELLO") { r.link.sent.isNotEmpty() }
        Thread.sleep(150)
        val hello = r.link.sent.single()
        assertEquals(toHex(byteArrayOf(0x01) + P4Crypto.publicKey(key) + nonce), toHex(hello.value))
        assertEquals(PipeSubmit.OK, hello.result)
    }

    // ---- Pacing: one outstanding, 30 ms apart ---------------------------------------------------

    /**
     * Q10 / R10: with fast completions the engine submits at most one value per 30 ms (1 ms is
     * the engine clock's granularity), never while one is inside notify or awaiting its completion.
     */
    @Test
    fun submissionsAreAtLeast30MsApartWithOneOutstanding() {
        assertEquals(30L, PipeEngine.NOTIFY_INTERVAL_MS)
        val r = rig()
        r.ready()
        r.openStream(1, 443, FakeSocket().apply { feed(pattern(1024, 1)) })
        r.openStream(2, 8443, FakeSocket().apply { feed(pattern(1024, 2)) })
        r.data(1, pattern(500, 3))
        r.data(2, pattern(500, 4))
        r.send(Op.PING, 0, le32(42))
        Poll.until("all traffic out") { r.dataBytes(1) == 1024 && r.dataBytes(2) == 1024 && r.frames(0, Op.PONG).isNotEmpty() }
        r.awaitQuiet()

        val sent = r.link.sent.toList()
        assertTrue("enough submissions to judge pacing: ${sent.size}", sent.size >= 12)
        for ((a, b) in sent.zipWithNext()) {
            assertTrue("#${b.index} only ${ms(a.atNanos, b.atNanos)} ms after #${a.index}", ms(a.atNanos, b.atNanos) >= PipeEngine.NOTIFY_INTERVAL_MS - 1.0)
            assertTrue("#${b.index} before #${a.index}'s completion", b.atNanos > a.completedNanos)
            assertTrue("engine clock gap #${a.index}→#${b.index}", b.atMs - a.atMs >= PipeEngine.NOTIFY_INTERVAL_MS - 1)
        }
        assertEquals(0, r.link.overlaps.get())
        r.assertWellFormed(487)
    }

    /** A completion slower than the 30 ms gap holds the next submission until it arrives. */
    @Test
    fun aSlowCompletionHoldsTheNextSubmissionUntilItArrives() {
        val r = rig()
        r.ready(link = FakeLink(r.clock).apply { completionDelayMs = 80 })
        r.openStream(1, 443, FakeSocket().apply { feed(pattern(1500, 6)) })
        r.send(Op.PING, 0, le32(7))
        Poll.until("traffic out") { r.dataBytes(1) == 1024 && r.frames(0, Op.PONG).isNotEmpty() }
        r.awaitQuiet()
        val sent = r.link.sent.toList()
        assertTrue(sent.size >= 6)
        for ((a, b) in sent.zipWithNext()) {
            assertTrue("#${b.index} before #${a.index}'s completion", b.atNanos > a.completedNanos)
            assertTrue("#${b.index} ${ms(a.atNanos, b.atNanos)} ms after #${a.index}", ms(a.atNanos, b.atNanos) >= 79.0)
        }
        assertEquals(0, r.link.overlaps.get())
    }

    // ---- Completion status ----------------------------------------------------------------------

    /** R10: 0 and 0x8f (GATT_CONNECTION_CONGESTED, enqueued) both mean queued locally. */
    @Test
    fun completionStatusesZeroAnd8fBothContinueTheSession() {
        val r = rig()
        r.ready(link = FakeLink(r.clock).apply { completion = { if (it.index % 2 == 0) 0x8f else 0 } })
        val socket = r.openStream(1, 443)
        r.data(1, byteArrayOf(1, 2, 3))
        Poll.until("bytes to TCP") { socket.writtenBytes.size == 3 }
        socket.feed(byteArrayOf(4, 5, 6))
        r.await("DATA") { it.op == Op.DATA && it.id == 1 }
        r.send(Op.PING, 0, le32(1))
        r.await("PONG") { it.op == Op.PONG }
        r.awaitQuiet()
        assertTrue(r.link.sent.count { it.status == 0x8f } >= 3)
        assertTrue(r.link.sent.count { it.status == 0 } >= 3)
        assertTrue(r.ends.isEmpty())
        assertEquals("ready", r.engine.currentStats.stage)
        assertTrue(r.link.disconnects.isEmpty())
        assertArrayEquals(byteArrayOf(4, 5, 6), r.dataOf(1))
    }

    /** R10: any other completion status ends the session as TRANSPORT, with nothing more notified (no RESET). */
    @Test
    fun anyOtherCompletionStatusEndsTheSessionWithoutAnotherNotification() {
        for (status in intArrayOf(0x01, 0x81, 0x85, 0x101)) {
            val r = rig()
            r.ready()
            val socket = r.openStream(1, 443)
            r.awaitQuiet()
            val pong = nextSubmission(r, completion = status)
            r.send(Op.PING, 0, le32(9))
            assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true))
            assertEquals("status $status: the PONG and nothing after it", pong + 1, r.link.sent.size)
            assertEquals(Op.PONG, r.sync().last().op)
            assertTrue(socket.closed)
        }
    }

    // ---- Submission results ---------------------------------------------------------------------

    private fun failingPong(outcome: FakeLink.Outcome, expectUnsettled: Boolean) {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.awaitQuiet()
        val pong = nextSubmission(r, outcome)
        r.send(Op.PING, 0, le32(9))
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, unsettled = expectUnsettled, authenticated = true, ready = true))
        assertEquals("never resent, no RESET", pong + 1, r.link.sent.size)
        assertTrue(r.link.sent[pong].completedNanos == 0L)
        assertTrue(socket.closed)
        assertEquals(1, r.connector.calls.size)
    }

    /** A definite pre-native refusal is settled: TRANSPORT, unsettled false, no RESET. */
    @Test
    fun aRefusedSubmissionEndsTransportSettled() = failingPong(FakeLink.Outcome.REFUSED, expectUnsettled = false)

    /** ERROR_UNKNOWN / a dead binder may still submit later: TRANSPORT, unsettled true. */
    @Test
    fun anUncertainSubmissionEndsTransportUnsettled() = failingPong(FakeLink.Outcome.UNCERTAIN, expectUnsettled = true)

    /** A notify that throws is uncertain too. */
    @Test
    fun aThrowingSubmissionCountsAsUncertain() = failingPong(FakeLink.Outcome.THROW, expectUnsettled = true)

    // ---- The 2 s completion deadline ------------------------------------------------------------

    /**
     * R10 / android-003 §1.6: the 2 s runs from just before the call. A call still blocked then
     * ends the session (DEADLINE, unsettled) and the link is disconnected from the watchdog while
     * the call is still blocked; its late OK return changes nothing.
     */
    @Test
    fun aNotifyBlockedPastTwoSecondsEndsTheSessionAndDisconnectsWhileStillBlocked() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.awaitQuiet()
        val index = nextSubmission(r, FakeLink.Outcome.block(3_000))
        r.send(Op.PING, 0, le32(9))
        Poll.until("PONG submission") { r.link.sent.size > index }
        val pong = r.link.sent[index]

        Poll.until("disconnect", 4_000) { r.link.disconnects.isNotEmpty() }
        val at = r.link.disconnects.first()
        assertFalse("disconnected while notify is still blocked", pong.returned)
        assertTrue("disconnect ${ms(pong.atNanos, at)} ms after the call started", ms(pong.atNanos, at) in 1_900.0..2_600.0)
        val end = r.awaitEnd()
        assertFalse("PipeEnd while notify is still blocked", pong.returned)
        assertEquals(PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = true, authenticated = true, ready = true), end.end)
        assertTrue(socket.closed)

        Poll.until("the late return", 2_000) { pong.returned }
        assertEquals(PipeSubmit.OK, pong.result)
        Thread.sleep(200)
        assertEquals("nothing after the late OK", index + 1, r.link.sent.size)
        assertEquals(1, r.link.disconnects.size)
        assertEquals(1, r.endsOf().size)
        assertFalse(r.engine.active)
        assertEquals("idle", r.engine.currentStats.stage)
    }

    /** An OK whose onNotificationSent never comes also ends at 2 s; a completion after that is stale. */
    @Test
    fun anOkWithNoCompletionEndsAtTheTwoSecondDeadline() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, completion = null)
        r.send(Op.PING, 0, le32(9))
        Poll.until("PONG submission") { r.link.sent.size > index }
        val pong = r.link.sent[index]
        Poll.until("disconnect", 4_000) { r.link.disconnects.isNotEmpty() }
        assertTrue("deadline ${ms(pong.atNanos, r.link.disconnects.first())} ms", ms(pong.atNanos, r.link.disconnects.first()) in 1_900.0..2_600.0)
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = true, authenticated = true, ready = true))
        assertEquals(index + 1, r.link.sent.size)

        val stale = r.engine.currentStats.staleCallbacks
        r.link.complete(pong, 0)
        Poll.until("late completion counted stale") { r.engine.currentStats.staleCallbacks == stale + 1 }
        assertEquals(1, r.endsOf().size)
        assertFalse(r.engine.active)
    }

    /*
     * The two checks below do not depend on the watchdog. In the app its timer runs on uptime,
     * which stops in a CPU suspend, while the engine clock (elapsedRealtime) does not: after a
     * suspend these checks are the real guard. [SecureRig.suspendFor] models one, moving the engine
     * clock while the watchdog's real-time timer stays put, so each end below comes long before
     * the watchdog's own 2 s could.
     */

    /**
     * android-003 §1.6: the 2 s is checked again when the call returns. The PONG's notify spans a
     * 2.1 s suspend and returns OK: that return is terminal at once, DEADLINE and unsettled (it
     * came back past the deadline, whatever its completion does). The completion that follows
     * settles nothing, and the watchdog's 2 s, when it passes, finds nothing to do.
     */
    @Test
    fun anOkReturnedPastTheDeadlineOnTheEngineClockIsTerminalWithoutTheWatchdog() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val stale = r.engine.currentStats.staleCallbacks
        val index = nextSubmission(r)
        r.link.duringNotify = { if (it.index == index) r.suspendFor(PipeEngine.NOTIFY_COMPLETION_MS + 100) }
        r.send(Op.PING, 0, le32(9))
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = true, authenticated = true, ready = true))
        val pong = r.link.sent[index]
        assertEquals(PipeSubmit.OK, pong.result)
        assertTrue("ended ${ms(pong.atNanos, end.atNanos)} ms after the call in real time", ms(pong.atNanos, end.atNanos) < 1_000.0)
        assertEquals("nothing after the PONG", index + 1, r.link.sent.size)
        Poll.until("its completion counted stale") { r.engine.currentStats.staleCallbacks == stale + 1 }

        Thread.sleep(maxOf(0L, 2_300 - ms(pong.atNanos, System.nanoTime()).toLong()))
        assertEquals("the watchdog's 2 s changes nothing", 1, r.link.disconnects.size)
        assertEquals(1, r.endsOf().size)
        assertFalse(r.engine.active)
    }

    /**
     * R10: the completion's own arrival time is checked as well. The PONG's notify returns OK at
     * once and a 2.1 s suspend passes before its onNotificationSent arrives: that completion is
     * terminal at once, DEADLINE and settled (it did arrive), and nothing more is submitted.
     */
    @Test
    fun aCompletionArrivingPastTheDeadlineOnTheEngineClockIsTerminalWithoutTheWatchdog() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, completion = null)
        r.send(Op.PING, 0, le32(9))
        Poll.until("PONG returned") { r.link.sent.size > index && r.link.sent[index].returned }
        val pong = r.link.sent[index]
        r.suspendFor(PipeEngine.NOTIFY_COMPLETION_MS + 100)
        r.link.complete(pong, 0)
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = false, authenticated = true, ready = true))
        assertTrue("ended ${ms(pong.atNanos, end.atNanos)} ms after the call in real time", ms(pong.atNanos, end.atNanos) < 1_000.0)
        assertEquals("nothing after the PONG", index + 1, r.link.sent.size)

        Thread.sleep(maxOf(0L, 2_300 - ms(pong.atNanos, System.nanoTime()).toLong()))
        assertEquals("the watchdog's 2 s changes nothing", 1, r.link.disconnects.size)
        assertEquals(1, r.endsOf().size)
        assertFalse(r.engine.active)
    }

    /**
     * The watchdog leaves a submission alone whose completion arrived in time but still waits
     * behind a busy engine thread. The PONG's completion arrives 1.5 s after the call while the
     * engine thread is held (by a task on its own executor) past the 2 s mark; when the thread is
     * free the queued completion settles the PONG and the session carries on.
     */
    @Test
    fun aCompletionQueuedInTimeBehindABusyEngineThreadIsNotADeadline() {
        val engineThread = Executors.newSingleThreadScheduledExecutor { t -> Thread(t, "pipe-engine").apply { isDaemon = true } }
        val r = SecureRig(executor = engineThread).also { rigs += it }
        r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, completion = null)
        r.send(Op.PING, 0, le32(9))
        Poll.until("PONG returned") { r.link.sent.size > index && r.link.sent[index].returned }
        val pong = r.link.sent[index]
        val busy = CountDownLatch(1)
        val free = CountDownLatch(1)
        engineThread.execute {
            busy.countDown()
            free.await(10, TimeUnit.SECONDS)
        }
        try {
            assertTrue("engine thread held", busy.await(2, TimeUnit.SECONDS))
            Thread.sleep(maxOf(0L, 1_500 - ms(pong.atNanos, System.nanoTime()).toLong()))
            r.link.complete(pong, 0)
            Thread.sleep(maxOf(0L, 2_400 - ms(pong.atNanos, System.nanoTime()).toLong()))
            assertTrue("no deadline end: ${r.ends}", r.ends.isEmpty())
            assertTrue("no disconnect", r.link.disconnects.isEmpty())
            assertTrue(r.engine.active)
        } finally {
            free.countDown()
        }
        r.send(Op.PING, 0, le32(10))
        pongFor(r, 10)
        r.awaitQuiet()
        assertTrue(r.ends.isEmpty())
        assertTrue(r.link.disconnects.isEmpty())
        assertEquals("ready", r.engine.currentStats.stage)
        assertEquals(0, r.engine.currentStats.staleCallbacks)
        r.assertWellFormed(487)
    }

    // ---- Stale callbacks ------------------------------------------------------------------------

    /**
     * FW-5 / android-003 §1: a completion (or write, or link loss) tagged with the previous
     * session's token is dropped and counted; it does not settle the new session's HELLO, which
     * stays pending until its own completion.
     */
    @Test
    fun aCompletionWithAnOldTokenIsDroppedAndDoesNotCompleteTheNewSession() {
        val r = rig()
        val a = r.ready()
        r.awaitQuiet()
        r.engine.linkLost(a.token)
        assertEndedOnce(r, PipeEnd(a.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true), disconnects = 0, c = a)

        val manual = FakeLink(r.clock).apply { completion = { null } }
        assertTrue(r.begin(link = manual))
        val b = r.conn
        assertNotEquals(a.token, b.token)
        r.subscribe()
        Poll.until("HELLO on the new session") { manual.sent.size == 1 }
        r.sync() // the P4 answers HELLO_ACK at once; CONFIRM must still wait for HELLO's completion

        val stale = r.engine.currentStats.staleCallbacks
        manual.complete(manual.sent[0], 0, forToken = a.token)
        r.engine.onWrite(a.token, a.p4.inner.frames(Op.PING, 0, le32(1)).single())
        r.engine.linkLost(a.token)
        Poll.until("three stale callbacks") { r.engine.currentStats.staleCallbacks == stale + 3 }
        Thread.sleep(150)
        assertEquals("CONFIRM waits for the new session's own completion", 1, manual.sent.size)
        assertTrue(r.engine.active)
        assertTrue(r.endsOf(b).isEmpty())

        manual.completion = { 0 }
        manual.complete(manual.sent[0], 0)
        Poll.until("CONFIRM") { manual.sent.size == 2 }
        r.awaitStage("secure")
        r.innerHello()
        r.awaitStage("ready")
        assertTrue(r.endsOf(b).isEmpty())
        assertEquals(stale + 3, r.engine.currentStats.staleCallbacks)
    }

    // ---- AUTH ends ------------------------------------------------------------------------------

    /** A peer without our PSK that answers CONFIRM anyway: its CONFIRM_ACK fails the tag (AUTH), nothing more is sent. */
    @Test
    fun anImpostorConfirmAckUnderAnotherPskEndsAuth() {
        val r = rig(p4Psk = ByteArray(32).also { it[0] = 2 }, impostor = true)
        assertTrue(r.begin())
        r.subscribe()
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = false, ready = false))
        assertEquals("HELLO and CONFIRM only", 2, r.link.sent.size)
        assertEquals(2, r.conn.replies.size)
        assertEquals(PipeEndKind.AUTH, r.engine.currentStats.lastEnd)
    }

    /** The firmware with a different PSK fails CONFIRM and answers REJECT 05 02: AUTH, NOT_AUTHORIZED. */
    @Test
    fun theFirmwaresRejectAfterAMismatchedPskEndsAuth() {
        val r = rig(p4Psk = ByteArray(32).also { it[0] = 2 })
        assertTrue(r.begin())
        r.subscribe()
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.NOT_AUTHORIZED, unsettled = false, authenticated = false, ready = false))
        assertEquals(listOf(1), r.conn.p4Errors.toList())
        assertEquals("HELLO and CONFIRM only", 2, r.link.sent.size)
    }

    /** One bad P4 → phone DATA after READY; [setup] runs first and must leave the link quiet. */
    private class BadData(val name: String, val setup: (SecureRig) -> Unit = {}, val value: (SecureRig) -> ByteArray)

    private fun ping(r: SecureRig, cookie: Long) = r.p4.inner.frames(Op.PING, 0, le32(cookie)).single()

    private fun pongFor(r: SecureRig, cookie: Long) = r.await("PONG $cookie") { it.op == Op.PONG && u32(it.body, 0) == cookie }

    /**
     * R6 / R7 / FW-4: a tampered tag, ciphertext or counter, a replay, a lower counter and counter
     * 0 each end the session as AUTH, silently (no RESET), after it reached READY.
     */
    @Test
    fun tamperedReplayedAndNonIncreasingDataEndAuthWithoutAReset() {
        var replay = ByteArray(0)
        val cases = listOf(
            BadData("tag flip", value = { r -> r.p4.seal(ping(r, 1)).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } }),
            BadData("ciphertext flip", value = { r -> r.p4.seal(ping(r, 1)).also { it[9] = (it[9].toInt() xor 0x80).toByte() } }),
            BadData("counter bumped", value = { r -> r.p4.seal(ping(r, 1)).also { it[8] = (it[8].toInt() + 1).toByte() } }),
            BadData("replay", setup = { r ->
                replay = r.p4.seal(ping(r, 5))
                r.write(replay)
                pongFor(r, 5)
            }, value = { replay }),
            BadData("lower counter", setup = { r ->
                r.p4.txCounter = 9
                r.sendFrame(ping(r, 6))
                pongFor(r, 6)
            }, value = { r ->
                r.p4.txCounter = 8
                r.p4.seal(ping(r, 7))
            }),
            BadData("counter zero", value = { r ->
                r.p4.txCounter = 0
                r.p4.seal(ping(r, 8))
            }),
        )
        for (case in cases) {
            val r = rig()
            r.ready()
            case.setup(r)
            r.awaitQuiet()
            val mark = r.link.sent.size
            r.write(case.value(r))
            assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true))
            assertEquals("${case.name}: nothing after the failure", mark, r.link.sent.size)
        }
    }

    /** R7: no plaintext after the handshake, in either direction: a plain inner frame, REJECT or HELLO_ACK is AUTH. */
    @Test
    fun plaintextAfterEstablishmentEndsAuth() {
        val cases = listOf<Pair<String, (SecureRig) -> ByteArray>>(
            "plain inner PING" to { r -> ping(r, 1) },
            "REJECT 05 02" to { _ -> hex("0502") },
            "a second HELLO_ACK" to { r -> r.conn.replies[0] },
            "CONFIRM_ACK again" to { r -> r.conn.replies[1] },
        )
        for ((name, value) in cases) {
            val r = rig()
            r.ready()
            r.awaitQuiet()
            val mark = r.link.sent.size
            r.write(value(r))
            assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true))
            assertEquals("$name: nothing after the failure", mark, r.link.sent.size)
        }
    }

    /** R5 / A-5: the peer owes HELLO_ACK and stays silent until 4.5 s after the CCCD answer: AUTH (charged). */
    @Test
    fun peerSilenceUntilTheHandshakeExpiryEndsAuth() {
        val r = rig()
        r.autoHandshake = false
        assertTrue(r.begin())
        r.subscribe()
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.DEADLINE, unsettled = false, authenticated = false, ready = false))
        val after = ms(r.conn.subscribedAtNanos, end.atNanos)
        assertTrue("expired $after ms after the CCCD answer", after >= PipeEngine.HANDSHAKE_MS - 10 && after < PipeEngine.HANDSHAKE_MS + 450)
        assertEquals("only HELLO", 1, r.link.sent.size)
    }

    /**
     * A-5: the expiry while our own CONFIRM still awaits its completion is a local stall, not the
     * peer's fault: TRANSPORT, and the CONFIRM is left unsettled.
     */
    @Test
    fun aHandshakeExpiryWhileOurSubmissionIsPendingIsTransport() {
        val r = rig()
        r.autoHandshake = false
        val link = FakeLink(r.clock).apply { completion = { if (it.index == 1) null else 0 } }
        assertTrue(r.begin(link = link))
        r.subscribe()
        Poll.until("HELLO completed") { link.sent.size == 1 && link.sent[0].completedNanos != 0L }
        // The P4 answers late, so CONFIRM's own 2 s deadline is still running at the 4.5 s expiry.
        Thread.sleep(maxOf(0L, 2_800 - ms(r.conn.subscribedAtNanos, System.nanoTime()).toLong()))
        r.sync()
        r.write(r.conn.replies.single())
        Poll.until("CONFIRM") { link.sent.size == 2 }
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = true, authenticated = false, ready = false))
        val after = ms(r.conn.subscribedAtNanos, end.atNanos)
        assertTrue("ended $after ms after the CCCD answer", after >= PipeEngine.HANDSHAKE_MS - 10 && after < PipeEngine.HANDSHAKE_MS + 700)
        assertEquals(2, link.sent.size)
    }

    /** A-5: a CCCD answer slower than 1 s is a known local stall, so even peer silence is TRANSPORT. */
    @Test
    fun aSlowCccdAnswerMakesTheHandshakeExpiryTransport() {
        val r = rig()
        r.autoHandshake = false
        assertTrue(r.begin())
        r.subscribe(responseMs = PipeEngine.STALL_MS + 500)
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = false, authenticated = false, ready = false))
        assertEquals(1, r.link.sent.size)
    }

    /**
     * A-5: a handshake submission of ours whose completion took more than 1 s is a known local
     * stall too. HELLO's onNotificationSent arrives 1.2 s after the call (inside its 2 s), the P4
     * then owes HELLO_ACK and stays silent until the 4.5 s expiry: TRANSPORT, never charged. The
     * same silence after a prompt completion is AUTH ([peerSilenceUntilTheHandshakeExpiryEndsAuth]).
     */
    @Test
    fun aSlowHelloCompletionMakesTheHandshakeExpiryTransport() {
        val r = rig()
        r.autoHandshake = false
        val link = FakeLink(r.clock).apply { completion = { if (it.index == 0) null else 0 } }
        assertTrue(r.begin(link = link))
        r.subscribe()
        Poll.until("HELLO returned") { link.sent.size == 1 && link.sent[0].returned }
        val hello = link.sent[0]
        Thread.sleep(maxOf(0L, PipeEngine.STALL_MS + 200 - ms(hello.atNanos, System.nanoTime()).toLong()))
        link.complete(hello, 0)
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = false, authenticated = false, ready = false))
        assertTrue("completed ${ms(hello.atNanos, hello.completedNanos)} ms after the call", ms(hello.atNanos, hello.completedNanos) in 1_150.0..1_900.0)
        val after = ms(r.conn.subscribedAtNanos, end.atNanos)
        assertTrue("expired $after ms after the CCCD answer", after >= PipeEngine.HANDSHAKE_MS - 10 && after < PipeEngine.HANDSHAKE_MS + 450)
        assertEquals("only HELLO", 1, link.sent.size)
    }

    // ---- TRANSPORT ends -------------------------------------------------------------------------

    /** Link loss: TRANSPORT, every socket closed before PipeEnd, nothing notified and no disconnect() on a gone link. */
    @Test
    fun linkLossEndsTransportWithoutAFrameOrADisconnectCall() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.awaitQuiet()
        val mark = r.link.sent.size
        var closedAtEnd = false
        r.onEndHook = { closedAtEnd = socket.closed }
        r.engine.linkLost(r.token)
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true), disconnects = 0)
        assertTrue("sockets close before PipeEnd", closedAtEnd)
        assertEquals(mark, r.link.sent.size)
    }

    /** end(): TRANSPORT, silent (no RESET even though READY), one disconnect; another token's end is ignored. */
    @Test
    fun endEndsTransportSilentlyAndDisconnects() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.awaitQuiet()
        val mark = r.link.sent.size
        r.engine.end(r.token + 7)
        Thread.sleep(100)
        assertTrue(r.engine.active)
        assertTrue(r.ends.isEmpty())
        r.engine.end(r.token)
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true))
        assertEquals("no RESET after end()", mark, r.link.sent.size)
        assertTrue(socket.closed)
    }

    /**
     * Callbacks only queue events. More than 64 queued while the engine thread is blocked inside
     * notify is a local stall: TRANSPORT (NO_RESOURCES), unsettled because that call has not
     * returned, and no RESET. Exactly 64 still fit.
     */
    @Test
    fun eventQueueOverflowWhileTheEngineIsBlockedEndsTransport() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, FakeLink.Outcome.block(800))
        r.send(Op.PING, 0, le32(3))
        Poll.until("engine inside notify") { r.link.sent.size > index }
        val pong = r.link.sent[index]
        repeat(PipeEngine.MAX_EVENTS) { r.write(byteArrayOf(0x10)) }
        Thread.sleep(50)
        assertTrue("64 queued events fit", r.ends.isEmpty())
        assertTrue(r.engine.active)
        assertTrue(r.link.disconnects.isEmpty())

        r.write(byteArrayOf(0x10))
        val end = assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES, unsettled = true, authenticated = true, ready = true))
        assertTrue("ended while the engine thread was still blocked", end.atNanos < pong.returnedNanos || !pong.returned)
        Poll.until("notify returns") { pong.returned }
        Thread.sleep(100)
        assertEquals("no RESET", index + 1, r.link.sent.size)
        assertEquals(1, r.endsOf().size)
    }

    // ---- PROTOCOL and CANCELLED ends ------------------------------------------------------------

    /**
     * Q12 / R7: an authenticated inner violation (a skipped inner sequence number) ends PROTOCOL
     * with one best-effort stream-0 RESET(1), sealed under the session key at the next counter,
     * which the P4 opens; the disconnect follows it.
     */
    @Test
    fun anInnerProtocolViolationEndsProtocolWithAnEncryptedReset() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val mark = r.link.sent.size
        r.sendFrame(rawFrame(Op.PING, 0, r.p4.inner.seq + 1, F_BOTH, le32(7)))
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true))
        assertEquals(mark + 1, r.link.sent.size)
        val reset = r.sync().single { it.sent.index == mark }
        assertEquals(toHex(rawFrame(Op.RESET, 0, 1, F_BOTH, le16(1))), toHex(reset.bytes))
        assertEquals(2L, reset.counter)
        assertEquals(emptyList<Int>(), r.conn.p4Errors.toList())
        assertTrue("disconnect after the RESET", r.link.disconnects.single() > reset.sent.returnedNanos)
    }

    /** R7: with a notification still awaiting completion the encryption state is not certain: no RESET. */
    @Test
    fun anInnerViolationWithASubmissionOutstandingSendsNoReset() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, completion = null)
        r.send(Op.PING, 0, le32(4))
        Poll.until("PONG submitted") { r.link.sent.size > index && r.link.sent[index].returned }
        r.sendFrame(rawFrame(Op.PING, 0, r.p4.inner.seq + 3, F_BOTH, le32(7)))
        Poll.until("disconnect") { r.link.disconnects.isNotEmpty() }
        r.link.complete(r.link.sent[index], 0)
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true))
        assertEquals("the PONG and no RESET", index + 1, r.link.sent.size)
    }

    /** A P4 stream-0 RESET ends the session PROTOCOL with the P4's code and nothing back. */
    @Test
    fun aPeerSessionResetEndsProtocolWithoutAReply() {
        val r = rig()
        r.ready()
        r.awaitQuiet()
        val mark = r.link.sent.size
        r.send(Op.RESET, 0, le16(PipeError.DISPLAY_PRIORITY.code))
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.DISPLAY_PRIORITY, unsettled = false, authenticated = true, ready = true))
        assertEquals(mark, r.link.sent.size)
    }

    /** FW-3: an authority change once READY sends one encrypted stream-0 RESET(14), then disconnects. */
    @Test
    fun cancelOnceReadySendsAnEncryptedReset14() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.awaitQuiet()
        val mark = r.link.sent.size
        r.engine.cancel()
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true))
        assertEquals(mark + 1, r.link.sent.size)
        val reset = r.sync().single { it.sent.index == mark }
        assertEquals(toHex(rawFrame(Op.RESET, 0, 2, F_BOTH, le16(14))), toHex(reset.bytes))
        assertEquals(3L, reset.counter)
        assertTrue(socket.closed)
        assertTrue(r.link.disconnects.single() > reset.sent.returnedNanos)
    }

    /**
     * R7: the teardown never waits on the RESET's binder call. With the RESET blocked for 3 s the
     * link is disconnected RESET_GRACE_MS after the call started and PipeEnd (unsettled) follows
     * while the call is still blocked. No session is admitted while the engine thread is still inside that call
     * (begin() is false, the server answers 0x11); once it has returned the next one is, and the
     * late return touches neither it nor the old link again: its HELLO goes out and it reaches READY.
     */
    @Test
    fun aBlockedResetHoldsNeitherTheTeardownNorTheNextSession() {
        val r = rig()
        val old = r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, FakeLink.Outcome.block(3_000))
        val cancelledAt = System.nanoTime()
        r.engine.cancel()
        Poll.until("RESET submitted") { old.link.sent.size > index }
        val reset = old.link.sent[index]
        Poll.until("disconnect", 3_000) { old.link.disconnects.isNotEmpty() }
        assertFalse("disconnected while the RESET call is still blocked", reset.returned)
        assertTrue("disconnect ${ms(cancelledAt, old.link.disconnects.first())} ms after cancel()", ms(cancelledAt, old.link.disconnects.first()) in 150.0..1_000.0)
        val end = r.awaitEnd(old)
        assertFalse("PipeEnd while the RESET call is still blocked", reset.returned)
        assertEquals(PipeEnd(old.token, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = true, authenticated = true, ready = true), end.end)

        val refused = FakeLink(r.clock)
        assertFalse("no session while the old RESET is still inside notify", r.begin(link = refused))
        assertFalse("still inside notify when refused", reset.returned)
        assertFalse(r.engine.active)
        Poll.until("the old RESET returns", 3_500) { reset.returned }
        val fresh = FakeLink(r.clock)
        beginOnceFree(r, fresh)
        r.subscribe()
        r.awaitStage("secure")
        r.innerHello()
        r.awaitStage("ready")
        Thread.sleep(250)
        assertTrue(fresh.disconnects.isEmpty())
        assertEquals(1, old.link.disconnects.size)
        assertTrue(r.endsOf().isEmpty())
        assertEquals(index + 1, old.link.sent.size)
        assertTrue(refused.sent.isEmpty())
    }

    /** Before CONFIRM_ACK there is no key to seal a RESET with: cancel() sends nothing more. */
    @Test
    fun cancelDuringTheHandshakeSendsNothing() {
        val r = rig()
        r.autoHandshake = false
        assertTrue(r.begin())
        r.subscribe()
        Poll.until("HELLO completed") { r.link.sent.size == 1 && r.link.sent[0].completedNanos != 0L }
        r.engine.cancel()
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = false, authenticated = false, ready = false))
        assertEquals(1, r.link.sent.size)
    }

    // ---- One engine thread, sessions one after another -------------------------------------------

    /**
     * begin() until it admits: the engine thread leaves notify just after the fake call has
     * returned, so the first CCCD after that return may still be refused. Returns the session.
     */
    private fun beginOnceFree(r: SecureRig, link: FakeLink = FakeLink(r.clock)): SecureRig.Conn {
        Poll.until("admitted once the engine thread is free", 1_000) { r.begin(link = link) }
        return r.conn
    }

    /**
     * Every session's handshake expiry, events and submissions run on the one engine thread. While
     * it is still inside an earlier session's notify (a PONG blocked for 3 s, whose session the
     * watchdog has already ended and finished), begin() is false and the server answers 0x11: a
     * session admitted then would have neither its 4.5 s expiry nor its link loss processed until
     * that call returned. Once it has returned, the next CCCD is admitted and that session runs
     * normally to READY.
     */
    @Test
    fun noSessionIsAdmittedWhileTheEngineThreadIsStillInsideAnEarlierNotify() {
        val r = rig()
        val a = r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, FakeLink.Outcome.block(3_000))
        r.send(Op.PING, 0, le32(9))
        Poll.until("PONG submission") { a.link.sent.size > index }
        val pong = a.link.sent[index]
        val endA = r.awaitEnd(a)
        assertEquals(PipeEnd(a.token, PipeEndKind.TRANSPORT, PipeError.DEADLINE, unsettled = true, authenticated = true, ready = true), endA.end)
        assertFalse(r.engine.active)
        assertEquals("idle", r.engine.currentStats.stage)

        val refused = FakeLink(r.clock)
        assertFalse("no session while the engine thread is still inside A's notify", r.begin(link = refused))
        assertFalse("still inside notify when refused", pong.returned)
        assertFalse(r.engine.active)
        assertEquals("idle", r.engine.currentStats.stage)

        Poll.until("A's late return", 2_000) { pong.returned }
        val b = beginOnceFree(r)
        r.subscribe()
        r.awaitStage("secure")
        r.innerHello()
        r.awaitStage("ready")
        r.awaitQuiet()
        assertTrue(r.endsOf(b).isEmpty())
        assertTrue(b.link.disconnects.isEmpty())
        assertEquals("A: its PONG and nothing after it", index + 1, a.link.sent.size)
        assertEquals(1, a.link.disconnects.size)
        assertTrue(refused.sent.isEmpty())
        assertTrue(refused.disconnects.isEmpty())
        assertEquals(1, r.endsOf(a).size)
        r.assertWellFormed(487, b)
    }

    /**
     * FW-5: a teardown's 300 ms settle wait belongs to its own session. Session A ends with its
     * PONG awaiting completion (A's settle timer is armed), the completion arrives and A ends
     * settled. Session B then ends with its HELLO awaiting completion. A's timer firing now leaves
     * B alone; B's own completion settles it (no instance retirement), and B's own timer, firing
     * after that, finds nothing to do. Both timers are held by the test ([HeldScheduler]) and fired
     * on the watchdog thread at chosen moments.
     */
    @Test
    fun anEarlierSessionsSettleTimerDoesNotForceTheNextSessionsTeardown() {
        val watchdog = HeldScheduler().apply { hold = { it == PipeEngine.SETTLE_MS } }
        val r = SecureRig(watchdog = watchdog).also { rigs += it }
        val a = r.ready()
        r.awaitQuiet()
        val index = nextSubmission(r, completion = null)
        r.send(Op.PING, 0, le32(1))
        Poll.until("A's PONG returned") { a.link.sent.size > index && a.link.sent[index].returned }
        r.engine.end(a.token)
        Poll.until("A's settle timer armed") { watchdog.held.size == 1 }
        a.link.complete(a.link.sent[index], 0)
        assertEndedOnce(r, PipeEnd(a.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true), c = a)

        val link = FakeLink(r.clock).apply { completion = { null } }
        assertTrue(r.begin(link = link))
        val b = r.conn
        r.subscribe()
        Poll.until("B's HELLO returned") { link.sent.size == 1 && link.sent[0].returned }
        r.engine.end(b.token)
        Poll.until("B's settle timer armed") { watchdog.held.size == 2 }
        assertEquals(1, link.disconnects.size)

        watchdog.fire(watchdog.held[0])
        Thread.sleep(100)
        assertTrue("A's timer finished B: ${r.endsOf(b).map { it.end }}", r.endsOf(b).isEmpty())
        assertTrue(r.engine.active)
        assertEquals("ending", r.engine.currentStats.stage)

        link.complete(link.sent[0], 0)
        assertEndedOnce(r, PipeEnd(b.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = false, ready = false), c = b)
        watchdog.fire(watchdog.held[1])
        Thread.sleep(50)
        assertEquals(1, r.endsOf(b).size)
        assertEquals(1, r.endsOf(a).size)
        assertFalse(r.engine.active)
        assertEquals("idle", r.engine.currentStats.stage)
        assertEquals(PipeEngine.SETTLE_MS, watchdog.held[1].delayMs)
    }

    // ---- Counters and sequences -----------------------------------------------------------------

    /** R6 / FW-4: P4 outer counters may skip, including across 2^63, up to 2^64 − 2; the inner sequence stays contiguous. */
    @Test
    fun outerCounterGapsFromTheP4AreAccepted() {
        val r = rig()
        r.ready()
        for ((i, counter) in listOf(7L, 1_000L, Long.MAX_VALUE, Long.MIN_VALUE, -2L).withIndex()) {
            r.p4.txCounter = counter
            r.send(Op.PING, 0, le32(100L + i))
            pongFor(r, 100L + i)
        }
        r.awaitQuiet()
        assertTrue(r.ends.isEmpty())
        assertEquals("ready", r.engine.currentStats.stage)
        assertTrue(r.link.disconnects.isEmpty())
        assertEquals("HELLO_ACK, CONFIRM_ACK, the inner HELLO and five PINGs", 8L, r.engine.currentStats.framesIn)
        r.assertWellFormed(487)
    }

    // ---- MTU ------------------------------------------------------------------------------------

    /**
     * R8 at the smallest admitted MTU 64: V = 61, P = 36, DATA bodies ≤ 24. Every value fits V,
     * every inner frame fits P, a 25-byte OPEN arrives in two fragments, and DATA is split at 24.
     */
    @Test
    fun atMtu64EveryValueAndInnerFrameFitsTheSmallestLimits() {
        val r = rig(mtu = 64)
        r.ready()
        assertEquals(36, r.p4.inner.value)
        val ack = r.sync().single()
        val phoneNonce = ack.body.copyOfRange(8, 16)
        assertTrue(phoneNonce.any { it != 0.toByte() })
        assertEquals(toHex(rawFrame(Op.HELLO_ACK, 0, 0, F_BOTH, P4_NONCE + phoneNonce + hex("0200 2400"))), toHex(ack.bytes))

        // A 25-byte OPEN body (relay.example.com) needs two fragments of at most 24 bytes.
        val name = ascii("relay.example.com")
        val socket = FakeSocket()
        r.connector.plan(443, ConnectPlan(socket))
        val fragments = r.p4.inner.frames(Op.OPEN, 1, openBody(ADDR_DNS, name, 443))
        assertEquals(listOf(F_FIRST to 24, F_LAST to 1), fragments.map { (it[10].toInt() and 0xff) to it.size - 12 })
        fragments.forEach { r.sendFrame(it) }
        r.await("OPEN_OK") { it.op == Op.OPEN_OK && it.id == 1 }
        assertArrayEquals(name, r.connector.calls.single().target.host)

        val down = pattern(100, 3)
        r.data(1, down)
        Poll.until("100 bytes to TCP") { socket.writtenBytes.size == 100 }
        assertArrayEquals(down, socket.writtenBytes)
        val up = pattern(300, 4)
        socket.feed(up)
        Poll.until("300 bytes as DATA") { r.dataBytes(1) == 300 }
        r.send(Op.PING, 0, le32(5))
        r.await("PONG") { it.op == Op.PONG }
        r.awaitQuiet()

        assertTrue(r.link.sent.all { it.value.size <= 61 })
        val frames = r.sync()
        assertTrue(frames.all { it.frame.size <= 36 })
        val data = frames.filter { it.op == Op.DATA }
        assertTrue(data.all { it.length in 1..24 })
        assertEquals(24, data.maxOf { it.length })
        assertArrayEquals(up, r.dataOf(1))
        assertEquals(64, r.engine.currentStats.mtu)
        assertEquals(36, r.engine.currentStats.frame)
        r.assertWellFormed(36)
    }

    /** R8: below MTU 64 begin() refuses (the GATT server answers 0x0d) and no session exists. */
    @Test
    fun anMtuBelow64MakesBeginFalse() {
        val r = rig()
        for (mtu in intArrayOf(0, 23, 63)) {
            assertFalse("MTU $mtu", r.begin(mtu))
            assertFalse(r.engine.active)
        }
        Thread.sleep(100)
        assertTrue(r.conns.all { it.link.sent.isEmpty() })
        assertTrue(r.ends.isEmpty())
        assertTrue(r.stages.isEmpty())
        r.ready(mtu = 64)
        assertEquals("ready", r.engine.currentStats.stage)
    }

    // ---- Stages and end flags -------------------------------------------------------------------

    /**
     * FW-3: stages go handshake → secure (CONFIRM_ACK verified) → ready (only once HELLO_ACK's
     * completion arrived, not when it was submitted) → ending → idle, with PipeEnd after ending.
     */
    @Test
    fun stagesGoHandshakeSecureReadyEndingIdle() {
        val r = rig()
        val link = FakeLink(r.clock).apply { completion = { if (it.index == 2) null else 0 } }
        assertTrue(r.begin(link = link))
        r.subscribe()
        r.awaitStage("secure")
        r.innerHello()
        Poll.until("HELLO_ACK submitted") { link.sent.size == 3 && link.sent[2].returned }
        Thread.sleep(150)
        assertEquals("secure", r.engine.currentStats.stage)
        assertEquals(listOf("handshake", "secure"), r.stages.map { it.second })

        link.complete(link.sent[2], 0)
        r.awaitStage("ready")
        r.awaitQuiet()
        r.engine.end(r.token)
        r.awaitEnd()
        Poll.until("idle reported") { r.stages.size == 5 }
        Thread.sleep(100)
        assertEquals(listOf("handshake", "secure", "ready", "ending", "idle"), r.stages.map { it.second })
        assertTrue("session stages carry its token", r.stages.dropLast(1).all { it.first == r.token })
        assertEquals(
            listOf("stage:handshake", "stage:secure", "stage:ready", "stage:ending", "end:TRANSPORT", "stage:idle"),
            r.log.toList(),
        )
    }

    /** CONFIRM_ACK verified but the inner HELLO is bad: PROTOCOL, authenticated, not ready, and no RESET before HELLO_ACK. */
    @Test
    fun aBadInnerHelloEndsAuthenticatedButNotReady() {
        val r = rig()
        assertTrue(r.begin())
        r.subscribe()
        r.awaitStage("secure")
        r.awaitQuiet()
        r.send(Op.HELLO, 0, helloBody(r.p4.inner.value, maxStreams = 3))
        assertEndedOnce(r, PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = false))
        assertEquals(2, r.link.sent.size)
    }

    // ---- Target and VPN changes -----------------------------------------------------------------

    /**
     * FW-3 / android-003 §2: a target-only change ends live streams (RESET 4 when open, OPEN_ERR 4
     * while connecting) and keeps the channel. Queued old DATA is never sent, a later OPEN with a
     * higher ID to the new target works and gets only its own socket's bytes, the old target is
     * refused, and the connect result from before the change is dropped and its socket closed.
     */
    @Test
    fun retargetEndsLiveStreamsWithCode4AndKeepsTheChannel() {
        val r = rig()
        val newHost = hex("c6336410") // 198.51.100.16 (RFC 5737)
        r.gate.allow = { it.type == ADDR_IPV4 && it.host.contentEquals(TEST_IPV4) && it.port == 443 }
        r.ready()
        val s1 = r.openStream(1, 443, FakeSocket().apply { feed(pattern(3000, 1)) })
        Poll.until("credit used") { r.dataBytes(1) == 1024 }
        val hold = CountDownLatch(1)
        val late = r.connector.plan(443, ConnectPlan(hold = hold))
        r.open(2, 443)
        Poll.until("connect 2 started") { r.connector.calls.size == 2 }

        // The allowlist swap happens inside the engine's single retarget step (android-003 §2).
        r.engine.retarget { r.gate.allow = { it.type == ADDR_IPV4 && it.host.contentEquals(newHost) && it.port == 8443 } }
        val reset = r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }
        assertArrayEquals(le16(4), reset.body)
        val err = r.await("OPEN_ERR 2") { it.op == Op.OPEN_ERR && it.id == 2 }
        assertArrayEquals(le16(4), err.body)
        Poll.until("socket 1 closed") { s1.closed }

        val s3 = r.openStream(3, 8443, host = newHost)
        hold.countDown()
        Poll.until("the pre-retarget socket is closed") { late.returned && late.socket.closed }
        r.data(3, byteArrayOf(7, 8, 9))
        Poll.until("stream 3 to TCP") { s3.writtenBytes.size == 3 }
        s3.feed(byteArrayOf(5, 5))
        r.await("stream 3 DATA") { it.op == Op.DATA && it.id == 3 }
        r.open(4, 443)
        r.await("OPEN_ERR 4") { it.op == Op.OPEN_ERR && it.id == 4 }
        r.send(Op.PING, 0, le32(77))
        pongFor(r, 77)
        r.awaitQuiet()

        val frames = r.sync()
        assertTrue("no OPEN_OK for the stale connect", frames.none { it.id == 2 && it.op == Op.OPEN_OK })
        assertTrue("no old DATA after the RESET", frames.none { it.id == 1 && it.op == Op.DATA && it.sent.index > reset.sent.index })
        assertArrayEquals(byteArrayOf(5, 5), r.dataOf(3))
        assertEquals("OPEN 4 to the old target never connected", 3, r.connector.calls.size)
        assertEquals(mapOf(PipeError.TARGET_DENIED to 2), r.engine.currentStats.refused)
        assertEquals(mapOf(PipeError.TARGET_DENIED to 1), r.engine.currentStats.resets)
        assertTrue(r.ends.isEmpty())
        assertTrue(r.link.disconnects.isEmpty())
        assertEquals("ready", r.engine.currentStats.stage)
        assertFalse(s3.closed)
        r.assertWellFormed(487)
    }

    /**
     * F5 / FW-3: VPN loss ends streams with code 5 (RESET when open, OPEN_ERR while connecting),
     * the session stays READY, a new OPEN gets OPEN_ERR 5 before any connect while the gate says
     * no VPN, and works again once it is back.
     */
    @Test
    fun vpnLossEndsStreamsWithCode5AndKeepsTheSessionReady() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        val hold = CountDownLatch(1)
        val late = r.connector.plan(8443, ConnectPlan(hold = hold))
        r.open(2, 8443)
        Poll.until("connect 2 started") { r.connector.callsTo(8443) == 1 }

        r.gate.vpn = false
        r.engine.vpnLost()
        assertArrayEquals(le16(5), r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }.body)
        assertArrayEquals(le16(5), r.await("OPEN_ERR 2") { it.op == Op.OPEN_ERR && it.id == 2 }.body)
        Poll.until("socket 1 closed") { s1.closed }
        hold.countDown()
        Poll.until("late socket closed") { late.returned && late.socket.closed }

        r.send(Op.PING, 0, le32(0xdeadbeefL))
        pongFor(r, 0xdeadbeefL)
        r.open(3, 9443)
        assertArrayEquals(le16(5), r.await("OPEN_ERR 3") { it.op == Op.OPEN_ERR && it.id == 3 }.body)
        assertEquals(0, r.connector.callsTo(9443))
        assertEquals("ready", r.engine.currentStats.stage)

        r.gate.vpn = true
        r.openStream(4, 9444)
        r.awaitQuiet()
        assertTrue(r.sync().none { it.id == 2 && it.op == Op.OPEN_OK })
        assertEquals(mapOf(PipeError.VPN_UNAVAILABLE to 2), r.engine.currentStats.refused)
        assertEquals(mapOf(PipeError.VPN_UNAVAILABLE to 1), r.engine.currentStats.resets)
        assertTrue(r.ends.isEmpty())
        assertTrue(r.link.disconnects.isEmpty())
    }

    // ---- Completion ordering --------------------------------------------------------------------

    /**
     * onNotificationSent is a oneway callback and may overtake notify's return. A completion that
     * arrives inside the call still settles that submission: nothing is stale and traffic flows.
     */
    @Test
    fun aCompletionArrivingBeforeNotifyReturnsSettlesThatSubmission() {
        val r = rig()
        r.ready(link = FakeLink(r.clock).apply { completeInline = true })
        val socket = r.openStream(1, 443)
        r.data(1, pattern(700, 2))
        Poll.until("bytes to TCP") { socket.writtenBytes.size == 700 }
        socket.feed(pattern(900, 3))
        Poll.until("DATA") { r.dataBytes(1) == 900 }
        r.awaitQuiet()
        assertEquals(0, r.engine.currentStats.staleCallbacks)
        assertTrue(r.ends.isEmpty())
        assertEquals(0, r.link.overlaps.get())
        r.assertWellFormed(487)
    }

    private fun pattern(n: Int, salt: Int): ByteArray = ByteArray(n) { ((it * 31 + salt) % 251).toByte() }
}
