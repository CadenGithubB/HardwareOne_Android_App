package com.hardwareone.console.pipe

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [PipeEngine]'s inner protocol and TCP side through the stage-4 secure channel: every P4 message
 * is sealed by [FakeP4] and every phone value is opened by it ([SecureRig]), with a fake BLE link,
 * a fake connector and bounded in-memory sockets. Expected bytes and behaviour come from the P4's
 * own core (`BLE_PhonePipeCore.h`), the v1 protocol snapshot and the golden frames derived from
 * them, not from the implementation. These are the still-valid scenarios of the plaintext-era
 * suite (connect/read/write, credit, FIN, CLOSE, stream retirement, no replay, bounded connects);
 * ATT answers, MTU 23, plaintext RESET and 130 ms pacing are gone, and the stage-4 rules themselves
 * live in [PipeEngineSecureTest]. Destinations are documentation-only (RFC 5737, RFC 3849,
 * example.com). Waits are bounded polls; every engine is shut down after each test.
 */
class PipeEngineTest {
    private val rigs = CopyOnWriteArrayList<SecureRig>()

    @After
    fun closeRigs() {
        rigs.forEach { it.close() }
    }

    private fun rig(golden: Boolean = false): SecureRig =
        (if (golden) SecureRig(random = GOLDEN_RANDOM, phoneKey = PipeFixture.bytes("phone_private_key_hex")) else SecureRig())
            .also { rigs += it }

    /** A golden P4 frame given as hex; its sequence field must be the P4's next one. */
    private fun sendRaw(r: SecureRig, text: String) {
        val frame = hex(text)
        assertEquals("golden frame sequence", r.p4.inner.seq, u32(frame, 4))
        r.p4.inner.seq++
        r.sendFrame(frame)
    }

    /** At most one encrypted stream-0 RESET (a known code, or [code]) follows [mark]. */
    private fun assertAtMostOneSessionReset(r: SecureRig, mark: Int, code: Int? = null) {
        val after = r.sync().filter { it.sent.index >= mark }
        assertEquals("every value after the failure is sealed DATA", r.link.sent.size - mark, after.size)
        assertTrue("after the failure: $after", after.size <= 1)
        for (f in after) {
            assertEquals(Op.RESET, f.op)
            assertEquals(0, f.id)
            assertEquals(F_BOTH, f.flags)
            assertEquals(2, f.length)
            val c = u16(f.bytes, 12)
            if (code != null) assertEquals("RESET code", code, c) else assertTrue("RESET code $c", c in 1..15)
        }
    }

    // ---- Session start ------------------------------------------------------------------------

    /**
     * The P4's inner HELLO (nonce 0x0102030405060708, max_value 487 = P at MTU 517) gets exactly
     * the HELLO_ACK the firmware decoder accepts, once, as the first DATA after HELLO and CONFIRM.
     */
    @Test
    fun theInnerHelloGetsOneGoldenHelloAck() {
        val r = rig(golden = true)
        assertTrue(r.begin())
        r.subscribe()
        r.awaitStage("secure")
        Thread.sleep(100)
        assertEquals("only HELLO and CONFIRM before the inner HELLO", 2, r.link.sent.size)
        sendRaw(r, "01 01 0000 00000000 0c00 03 00 | 0807060504030201 02 00 e701")
        val ack = r.await("HELLO_ACK") { it.op == Op.HELLO_ACK }
        assertEquals(golden("01 02 0000 00000000 1400 03 00 | 0807060504030201 1817161514131211 02 00 e701"), toHex(ack.bytes))
        assertEquals(1L, ack.counter)
        r.awaitStage("ready")
        Thread.sleep(200)
        assertEquals("exactly one HELLO_ACK", 1, r.sync().size)
        assertEquals(3, r.link.sent.size)
    }

    /** Writes, completions and link losses for no session (or a finished one) are dropped and counted. */
    @Test
    fun callbacksForNoCurrentSessionAreDroppedAndCounted() {
        val r = rig()
        r.engine.onWrite(1, hex("10"))
        r.engine.onNotificationSent(1, 0)
        r.engine.linkLost(1)
        Poll.until("three stale callbacks") { r.engine.currentStats.staleCallbacks == 3 }

        r.ready()
        r.awaitQuiet()
        val before = r.engine.currentStats.staleCallbacks
        r.engine.end(r.token)
        r.awaitEnd()
        r.send(Op.PING, 0, le32(7))
        Poll.until("the late write counted") { r.engine.currentStats.staleCallbacks == before + 1 }
        Thread.sleep(100)
        assertEquals("a session that ended takes no more writes", 3, r.link.sent.size)
    }

    // ---- OPEN, admission and the single connect ----------------------------------------------

    /**
     * Golden session A replayed through the engine at MTU 517 (P = 487). The fixture's 1.2.3.4 is
     * the firmware harness's 192.0.2.1. OPEN_OK and OPEN_ERR(4) match byte for byte, OPEN_OK now
     * granting consumed + 4096 = 4096 (Q11; the plaintext-era fixture granted 1024); DATA,
     * PONG and both CLOSE_ACKs match apart from the sequence number (a CREDIT may interleave),
     * and the sequence stays contiguous.
     */
    @Test
    fun goldenSessionThroughTheEngine() {
        val r = rig(golden = true)
        r.gate.allow = { it.type == ADDR_IPV4 && it.host.contentEquals(TEST_IPV4) && it.port == 443 }
        assertTrue(r.begin())
        r.subscribe()
        r.awaitStage("secure")
        sendRaw(r, "01 01 0000 00000000 0c00 03 00 | 0807060504030201 02 00 e701")
        r.await("HELLO_ACK") { it.op == Op.HELLO_ACK }
        r.awaitStage("ready")

        val socket = FakeSocket()
        r.connector.plan(443, ConnectPlan(socket))
        sendRaw(r, "01 10 0100 01000000 0c00 03 00 | 00040000 bb01 01 04 c0000201")
        val ok = r.await("OPEN_OK") { it.op == Op.OPEN_OK }
        assertEquals(golden("01 11 0100 01000000 0400 03 00 | 00040000"), toHex(ok.bytes))
        val call = r.connector.calls.single()
        assertEquals(ADDR_IPV4, call.target.type)
        assertArrayEquals(TEST_IPV4, call.target.host)
        assertEquals(443, call.target.port)
        assertTrue("one attempt, capped at 2.5 s: ${call.timeoutMs}", call.timeoutMs in 1..PipeWire.CONNECT_DEADLINE_MS)

        // OPEN id 2 for relay.example.com:8443 is not the approved target: OPEN_ERR(4), no connect.
        sendRaw(r, "01 10 0200 02000000 1900 03 00 | 00040000 fb20 03 11 72656c61792e6578616d706c652e636f6d")
        val err = r.await("OPEN_ERR") { it.op == Op.OPEN_ERR }
        assertEquals(golden("01 12 0200 02000000 0200 03 00 | 0400"), toHex(err.bytes))
        val denied = r.gate.seen.last()
        assertEquals(ADDR_DNS, denied.type)
        assertArrayEquals(RELAY_NAME, denied.host)
        assertEquals(8443, denied.port)

        sendRaw(r, "01 20 0100 03000000 0300 00 00 | aabbcc")
        Poll.until("aabbcc to TCP") { socket.writtenBytes.size == 3 }
        assertArrayEquals(hex("aabbcc"), socket.writtenBytes)
        socket.feed(hex("aabbcc"))
        val data = r.await("phone DATA") { it.op == Op.DATA }
        assertEquals(golden("01 20 0100 SSSSSSSS 0300 00 00 | aabbcc", data.seq), toHex(data.bytes))

        sendRaw(r, "01 30 0000 04000000 0400 03 00 | efbeadde")
        val pong = r.await("PONG") { it.op == Op.PONG }
        assertEquals(golden("01 31 0000 SSSSSSSS 0400 03 00 | efbeadde", pong.seq), toHex(pong.bytes))

        // The P4 ends every request with CLOSE reason 1.
        sendRaw(r, "01 23 0100 05000000 0200 03 00 | 0100")
        val closeAck = r.await("CLOSE_ACK") { it.op == Op.CLOSE_ACK }
        assertEquals(golden("01 24 0100 SSSSSSSS 0000 03 00", closeAck.seq), toHex(closeAck.bytes))
        Poll.until("socket closed by CLOSE") { socket.closed }

        sendRaw(r, "01 23 0100 06000000 0200 03 00 | 0100") // a late CLOSE on a retired ID
        val lateAck = r.await("late CLOSE_ACK") { it.op == Op.CLOSE_ACK && it.sent.index > closeAck.sent.index }
        assertEquals(golden("01 24 0100 SSSSSSSS 0000 03 00", lateAck.seq), toHex(lateAck.bytes))
        sendRaw(r, "01 20 0200 07000000 0100 00 00 | 01") // late DATA on the refused, retired ID 2
        r.awaitQuiet(extraMs = 200)
        assertEquals(1, r.connector.calls.size)
        assertTrue(r.link.disconnects.isEmpty())
        assertTrue(r.ends.isEmpty())
        r.assertWellFormed(487)
    }

    @Test
    fun ipv6AndDnsTargetsReachTheGateAndConnectorByteForByte() {
        val r = rig()
        r.ready()
        r.open(1, 443, type = ADDR_IPV6, host = DOC_V6)
        r.await("OPEN_OK 1") { it.op == Op.OPEN_OK && it.id == 1 }
        r.open(2, 8443, type = ADDR_DNS, host = RELAY_NAME)
        r.await("OPEN_OK 2") { it.op == Op.OPEN_OK && it.id == 2 }

        val v6 = r.connector.calls.single { it.target.port == 443 }.target
        assertEquals(ADDR_IPV6, v6.type)
        assertArrayEquals(DOC_V6, v6.host)
        val dns = r.connector.calls.single { it.target.port == 8443 }.target
        assertEquals(ADDR_DNS, dns.type)
        assertArrayEquals(RELAY_NAME, dns.host)
        assertEquals("the gate judged exactly the OPEN targets", listOf(v6, dns), r.gate.seen.toList())
    }

    @Test
    fun unapprovedTargetIsRefusedBeforeAnyConnect() {
        val r = rig()
        r.ready()
        r.gate.allow = { false }
        r.open(1, 443)
        val err = r.await("OPEN_ERR") { it.op == Op.OPEN_ERR && it.id == 1 }
        assertArrayEquals(le16(4), err.body)
        assertEquals(F_BOTH, err.flags)
        r.settle(300)
        assertTrue("no DNS/TCP for a denied target", r.connector.calls.isEmpty())
        assertTrue(r.sync().none { it.op == Op.OPEN_OK })
    }

    @Test
    fun missingVpnIsRefusedBeforeAnyConnect() {
        val r = rig()
        r.ready()
        r.gate.vpn = false
        r.open(1, 443)
        val err = r.await("OPEN_ERR") { it.op == Op.OPEN_ERR && it.id == 1 }
        assertArrayEquals(le16(5), err.body)
        r.settle(300)
        assertTrue(r.connector.calls.isEmpty())

        // The refusal retired ID 1 only; with the VPN back the next OPEN connects, once.
        r.gate.vpn = true
        r.openStream(2, 8443)
        assertEquals(0, r.connector.callsTo(443))
        assertEquals(1, r.connector.callsTo(8443))
    }

    @Test
    fun connectFailuresAnswerOpenErrWithTheirCodeAndAreNeverRetried() {
        val r = rig()
        r.ready()
        val cases = listOf(PipeError.DNS_FAILED to 6, PipeError.CONNECT_FAILED to 7, PipeError.CONNECT_TIMEOUT to 8)
        for ((i, case) in cases.withIndex()) {
            val id = i + 1
            r.connector.plan(9000 + id, ConnectPlan(error = case.first))
            r.open(id, 9000 + id)
            val err = r.await("OPEN_ERR $id") { it.op == Op.OPEN_ERR && it.id == id }
            assertArrayEquals("OPEN_ERR code for ${case.first}", le16(case.second), err.body)
        }
        r.settle(400)
        for (id in 1..3) assertEquals("exactly one connect for OPEN $id", 1, r.connector.callsTo(9000 + id))
        assertTrue(r.sync().none { it.op == Op.OPEN_OK || it.op == Op.DATA || it.op == Op.RESET })
        assertTrue(r.link.disconnects.isEmpty())
        assertEquals(mapOf(PipeError.DNS_FAILED to 1, PipeError.CONNECT_FAILED to 1, PipeError.CONNECT_TIMEOUT to 1), r.engine.currentStats.refused)
    }

    /** The one connect may take a while: it must not hold the engine thread, the link or the other stream. */
    @Test
    fun slowConnectDoesNotBlockTheLinkOrTheOtherStream() {
        val r = rig()
        r.ready()
        val hold = CountDownLatch(1)
        val slow = r.connector.plan(443, ConnectPlan(hold = hold))
        r.open(1, 443)
        Poll.until("connect started") { r.connector.callsTo(443) == 1 }
        assertTrue("connect runs on its own thread", r.connector.calls.single().thread !== Thread.currentThread())

        r.send(Op.PING, 0, le32(77))
        r.await("PONG while a connect hangs") { it.op == Op.PONG }
        val fast = r.openStream(2, 8443)
        r.data(2, byteArrayOf(1, 2, 3))
        Poll.until("stream 2 bytes to TCP") { fast.writtenBytes.size == 3 }
        assertTrue(r.sync().none { it.id == 1 })

        hold.countDown()
        val ok = r.await("OPEN_OK 1") { it.op == Op.OPEN_OK && it.id == 1 }
        assertArrayEquals("OPEN_OK grants the initial window", le32(GRANT), ok.body)
        assertFalse(slow.socket.closed)
        assertEquals(1, r.connector.callsTo(443))
    }

    @Test
    fun thirdOpenWhileTwoStreamsAreLiveIsBusyWithoutAdmissionOrConnect() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        val s2 = r.openStream(2, 8443)
        val judged = r.gate.seen.size
        val vpnChecks = r.gate.vpnChecks.get()

        r.open(3, 9443)
        val err = r.await("OPEN_ERR 3") { it.op == Op.OPEN_ERR && it.id == 3 }
        assertArrayEquals(le16(9), err.body)
        r.settle(200)
        assertEquals(judged, r.gate.seen.size)
        assertEquals(vpnChecks, r.gate.vpnChecks.get())
        assertEquals(0, r.connector.callsTo(9443))

        r.data(1, byteArrayOf(1))
        r.data(2, byteArrayOf(2))
        Poll.until("both streams still deliver") { s1.writtenBytes.size == 1 && s2.writtenBytes.size == 1 }
    }

    /**
     * DNS cannot be interrupted, so connect threads are bounded: a stream the P4 closes while its
     * connect hangs still holds a thread. With four abandoned attempts the next OPEN gets
     * OPEN_ERR 10 without a connect; once they return (their sockets are closed) OPEN works again.
     */
    @Test
    fun connectAttemptsAreBoundedIncludingAbandonedOnes() {
        val r = rig()
        r.ready()
        val hold = CountDownLatch(1)
        val plans = (1..PipeEngine.MAX_CONNECTS).map { id ->
            r.connector.plan(9100 + id, ConnectPlan(hold = hold)).also {
                r.open(id, 9100 + id)
                Poll.until("connect $id started") { r.connector.callsTo(9100 + id) == 1 }
                r.send(Op.CLOSE, id, le16(1))
                r.await("CLOSE_ACK $id") { f -> f.op == Op.CLOSE_ACK && f.id == id }
            }
        }
        val next = PipeEngine.MAX_CONNECTS + 1
        r.open(next, 9200)
        val err = r.await("OPEN_ERR $next") { it.op == Op.OPEN_ERR && it.id == next }
        assertArrayEquals(le16(10), err.body)
        r.settle(150)
        assertEquals(0, r.connector.callsTo(9200))

        hold.countDown()
        Poll.until("abandoned sockets closed") { plans.all { it.returned && it.socket.closed } }
        val socket = r.openStream(next + 1, 9201)
        r.data(next + 1, byteArrayOf(4, 2))
        Poll.until("bytes to TCP") { socket.writtenBytes.size == 2 }
        assertTrue(r.sync().none { it.op == Op.OPEN_OK && it.id <= PipeEngine.MAX_CONNECTS })
        assertEquals(mapOf(PipeError.NO_RESOURCES to 1), r.engine.currentStats.refused)
    }

    // ---- Device → TCP and the phone's credit -------------------------------------------------

    @Test
    fun deviceDataReachesTheSocketIntactThroughPartialWritesAndCreditAdvances() {
        val r = rig()
        r.ready()
        val socket = FakeSocket().apply { writeChunk = 7 }
        r.openStream(1, 443, socket)
        val grant = grantOf(r, 1)
        val first = pattern(1024, 3)
        r.data(1, first)
        Poll.until("1024 bytes through 7-byte writes") { socket.writtenBytes.size >= 1024 }
        assertArrayEquals(first, socket.writtenBytes)
        assertTrue(socket.events.filter { it.startsWith("write:") }.all { it.removePrefix("write:").toInt() in 1..7 })

        val credit = r.await("CREDIT above OPEN_OK's grant") { it.op == Op.CREDIT && it.id == 1 && u32(it.body, 0) > grant }
        val limit = u32(credit.body, 0)
        assertTrue("credit $limit runs at most 4096 ahead of consumption", limit <= 1024 + 4096)
        val more = pattern(minOf(limit - 1024, 1500L).toInt(), 9)
        r.data(1, more)
        val total = first.size + more.size
        Poll.until("every byte written") { socket.writtenBytes.size >= total }
        Thread.sleep(150)
        assertArrayEquals("no byte lost or duplicated", first + more, socket.writtenBytes)

        Poll.until("credit for everything consumed") { r.credits(1).lastOrNull() == total + 4096L }
        val credits = r.credits(1)
        assertTrue("credits strictly increase: $credits", credits.zipWithNext().all { (a, b) -> b > a })
        assertTrue(credits.all { it > grant && it <= total + 4096L })
        r.awaitQuiet()
        r.assertWellFormed(487)
    }

    @Test
    fun slowSocketWriterWithholdsCreditUntilBytesReachTcp() {
        val r = rig()
        r.ready()
        val socket = FakeSocket().apply { holdWrites(true) }
        r.openStream(1, 443, socket)
        r.data(1, pattern(1024, 4))
        r.settle(400)
        assertTrue("no credit while nothing was consumed", r.credits(1).isEmpty())
        assertEquals(0, socket.writtenBytes.size)

        socket.holdWrites(false)
        Poll.until("bytes reach TCP") { socket.writtenBytes.size == 1024 }
        val credit = r.await("CREDIT once storage freed") { it.op == Op.CREDIT && it.id == 1 }
        assertTrue(u32(credit.body, 0) in (grantOf(r, 1) + 1)..(1024L + 4096L))
    }

    /** DATA beyond OPEN_OK's grant is an authenticated flow violation: PROTOCOL, one sealed RESET(12). */
    @Test
    fun dataBeyondTheGrantedLimitEndsTheSessionWithAnEncryptedFlowReset() {
        val r = rig()
        r.ready()
        val socket = FakeSocket().apply { holdWrites(true) }
        r.openStream(1, 443, socket)
        r.data(1, pattern(grantOf(r, 1).toInt(), 4))
        r.awaitQuiet(extraMs = 100)
        val mark = r.link.sent.size

        r.data(1, byteArrayOf(0x55))
        val end = r.awaitEnd()
        assertEquals(PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.FLOW_VIOLATION, unsettled = false, authenticated = true, ready = true), end.end)
        Poll.until("socket closed") { socket.closed }
        r.settle(150)
        assertAtMostOneSessionReset(r, mark, code = 12)
        assertEquals(mark + 1, r.link.sent.size)
        assertEquals(PipeError.FLOW_VIOLATION, r.engine.currentStats.lastError)
        assertEquals(1, r.link.disconnects.size)
    }

    // ---- TCP → device within the device's credit ---------------------------------------------

    @Test
    fun tcpBytesStayWithinTheDevicesCreditAndResumeAfterCredit() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        val up = pattern(3000, 5)
        socket.feed(up)
        Poll.until("1024 bytes of DATA") { r.dataBytes(1) >= 1024 }
        r.settle(300)
        assertEquals("OPEN's receive_limit 1024 caps the phone", 1024, r.dataBytes(1))

        r.credit(1, 2048)
        Poll.until("DATA up to 2048") { r.dataBytes(1) >= 2048 }
        r.settle(300)
        assertEquals(2048, r.dataBytes(1))

        r.credit(1, 3072)
        Poll.until("all 3000 bytes") { r.dataBytes(1) >= 3000 }
        r.settle(150)
        assertArrayEquals(up, r.dataOf(1))
        assertTrue(r.frames(1, Op.DATA).all { it.flags == 0 && it.length in 1..475 })
        r.awaitQuiet()
        r.assertWellFormed(487)
    }

    @Test
    fun socketReadsAreBoundedByCreditAndTheStreamBuffer() {
        val r = rig()
        r.ready()
        val source = pattern(10_000, 6)
        val socket = FakeSocket().apply { feed(source) }
        r.openStream(1, 443, socket)
        Poll.until("1024 bytes of DATA") { r.dataBytes(1) >= 1024 }
        r.settle(300)
        assertTrue("each read asks for at most 4096 bytes: ${socket.largestRead}", socket.largestRead <= 4096)
        assertTrue("reads stop at credit + one 4 KiB buffer: ${socket.bytesRead}", socket.bytesRead <= 1024 + 4096)

        r.credit(1, 1024 + 4096)
        Poll.until("DATA up to 5120") { r.dataBytes(1) >= 5120 }
        r.settle(300)
        assertEquals(5120, r.dataBytes(1))
        assertTrue(socket.bytesRead <= 5120 + 4096)
        assertArrayEquals(source.copyOf(5120), r.dataOf(1))
    }

    /** Both streams credit-eligible with data waiting: DATA alternates between them. */
    @Test
    fun dataRoundRobinsBetweenTwoStreamsWithCredit() {
        val r = rig()
        r.ready()
        val hold = CountDownLatch(1)
        r.connector.plan(443, ConnectPlan(FakeSocket().apply { feed(pattern(1024, 1)) }, hold = hold))
        r.connector.plan(8443, ConnectPlan(FakeSocket().apply { feed(pattern(1024, 2)) }, hold = hold))
        r.open(1, 443)
        r.open(2, 8443)
        Poll.until("both connects started") { r.connector.calls.size == 2 }
        hold.countDown()
        Poll.until("both streams' credit used") { r.dataBytes(1) >= 1024 && r.dataBytes(2) >= 1024 }
        val ids = r.sync().filter { it.op == Op.DATA }.map { it.id }
        assertTrue("DATA alternates: $ids", ids.zipWithNext().all { (a, b) -> a != b })
        assertArrayEquals(pattern(1024, 1), r.dataOf(1))
        assertArrayEquals(pattern(1024, 2), r.dataOf(2))
    }

    // ---- EOF, FIN, CLOSE and RESET -----------------------------------------------------------

    @Test
    fun tcpEofBecomesFinWithTheExactCountAfterAllData() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        val up = pattern(1500, 11)
        socket.feed(up)
        socket.feedEof()
        Poll.until("1024 bytes of DATA") { r.dataBytes(1) >= 1024 }
        r.settle(300)
        assertTrue("no FIN while 476 bytes still wait for credit", r.frames(1, Op.FIN).isEmpty())

        r.credit(1, 2048)
        val fin = r.await("FIN") { it.op == Op.FIN && it.id == 1 }
        assertArrayEquals(le32(1500), fin.body)
        assertEquals(F_BOTH, fin.flags)
        assertTrue("FIN follows every DATA", r.frames(1, Op.DATA).all { it.seq < fin.seq })
        assertArrayEquals(up, r.dataOf(1))
        r.settle(200)
        assertEquals(1, r.frames(1, Op.FIN).size)
        assertTrue(r.sync().none { it.op == Op.DATA && it.seq > fin.seq })
        assertFalse("FIN is a half-close", socket.closed)

        r.data(1, byteArrayOf(1, 2, 3)) // the device may still send
        Poll.until("reverse bytes to TCP") { socket.writtenBytes.size == 3 }
    }

    @Test
    fun deviceFinShutsTcpOutputOnlyAfterQueuedWrites() {
        val r = rig()
        r.ready()
        val socket = FakeSocket().apply { holdWrites(true) }
        r.openStream(1, 443, socket)
        val down = pattern(300, 2)
        r.data(1, down)
        r.send(Op.FIN, 1, le32(300))
        Thread.sleep(200)
        assertEquals("output stays open while bytes are queued", 0, socket.shutdownOutputs)

        socket.holdWrites(false)
        Poll.until("shutdownOutput") { socket.shutdownOutputs == 1 }
        assertArrayEquals(down, socket.writtenBytes)
        assertEquals("shutdownOutput comes after every write", "shutdownOutput", socket.events.last())
        assertFalse(socket.closed)

        socket.feed(byteArrayOf(9, 8, 7))
        r.await("reverse DATA after the device's FIN") { it.op == Op.DATA && it.id == 1 }
        assertArrayEquals(byteArrayOf(9, 8, 7), r.dataOf(1))
        Thread.sleep(150)
        assertEquals(1, socket.shutdownOutputs)
    }

    @Test
    fun deviceCloseClosesTheSocketAndLateFramesStayRetired() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.send(Op.CLOSE, 1, le16(1))
        val ack = r.await("CLOSE_ACK") { it.op == Op.CLOSE_ACK && it.id == 1 }
        assertEquals(0, ack.length)
        assertEquals(F_BOTH, ack.flags)
        Poll.until("socket closed") { socket.closed }

        r.send(Op.CLOSE, 1, le16(1))
        r.await("CLOSE_ACK for the late CLOSE") { it.op == Op.CLOSE_ACK && it.id == 1 && it.sent.index > ack.sent.index }
        r.data(1, byteArrayOf(1))
        r.credit(1, 2048)
        r.settle(250)
        val after = r.sync().filter { it.id == 1 && it.sent.index > ack.sent.index }
        assertTrue("only CLOSE_ACKs for a retired ID: $after", after.all { it.op == Op.CLOSE_ACK })
        assertEquals(1, r.connector.callsTo(443))
        assertTrue(r.link.disconnects.isEmpty())
        assertTrue(r.ends.isEmpty())
    }

    /** CLOSE is control, not credit-controlled: it still works with zero credit and data queued. */
    @Test
    fun zeroCreditStreamCanStillBeClosed() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        socket.feed(pattern(3000, 7))
        Poll.until("credit used up") { r.dataBytes(1) >= 1024 }
        Thread.sleep(150)
        r.send(Op.CLOSE, 1, le16(1))
        val ack = r.await("CLOSE_ACK") { it.op == Op.CLOSE_ACK && it.id == 1 }
        Poll.until("socket closed") { socket.closed }
        r.settle(200)
        assertEquals("queued bytes are discarded", 1024, r.dataBytes(1))
        assertTrue(r.sync().none { it.id == 1 && it.sent.index > ack.sent.index })
    }

    @Test
    fun socketReadErrorResetsOnlyItsStream() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        val s2 = r.openStream(2, 8443)
        s1.failReads()
        val reset = r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }
        assertArrayEquals(le16(11), reset.body)
        assertEquals(F_BOTH, reset.flags)
        Poll.until("socket 1 closed") { s1.closed }

        r.data(2, byteArrayOf(5, 6, 7))
        Poll.until("stream 2 to TCP") { s2.writtenBytes.size == 3 }
        s2.feed(byteArrayOf(1, 2))
        r.await("stream 2 DATA") { it.op == Op.DATA && it.id == 2 }
        assertFalse(s2.closed)

        r.send(Op.CLOSE, 1, le16(1)) // a crossing CLOSE is still acknowledged
        r.await("CLOSE_ACK 1") { it.op == Op.CLOSE_ACK && it.id == 1 }
        r.settle(200)
        assertEquals("never reconnected", 1, r.connector.callsTo(443))
        assertTrue(r.sync().none { it.id == 1 && it.sent.index > reset.sent.index && it.op != Op.CLOSE_ACK })
        assertTrue(r.link.disconnects.isEmpty())
        assertEquals(mapOf(PipeError.TCP_IO to 1), r.engine.currentStats.resets)
    }

    @Test
    fun socketWriteErrorResetsOnlyItsStream() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        val s2 = r.openStream(2, 8443)
        s1.failWrites()
        r.data(1, pattern(100, 1))
        val reset = r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }
        assertArrayEquals(le16(11), reset.body)
        Poll.until("socket 1 closed") { s1.closed }

        r.data(2, byteArrayOf(5, 6, 7))
        Poll.until("stream 2 to TCP") { s2.writtenBytes.size == 3 }
        assertFalse(s2.closed)
        r.settle(200)
        assertEquals(1, r.connector.callsTo(443))
        assertEquals(1, r.sync().count { it.op == Op.RESET })
    }

    @Test
    fun deviceResetDuringABlockedWriteClosesTheSocketWithoutReply() {
        val r = rig()
        r.ready()
        val s1 = FakeSocket().apply { holdWrites(true) }
        r.openStream(1, 443, s1)
        val s2 = r.openStream(2, 8443)
        r.data(1, pattern(100, 1))
        Poll.until("writer blocked in write") { s1.writeCalls >= 1 }
        r.awaitQuiet(extraMs = 100)
        val mark = r.link.sent.size

        r.send(Op.RESET, 1, le16(14))
        Poll.until("socket 1 closed") { s1.closed }
        r.settle(300)
        assertTrue("RESET needs no reply", r.sync().none { it.id == 1 && it.sent.index >= mark })

        r.data(2, byteArrayOf(4, 5))
        Poll.until("stream 2 unaffected") { s2.writtenBytes.size == 2 }
        assertEquals(1, r.connector.callsTo(443))
    }

    // ---- Cancellation while connecting and stale generations ---------------------------------

    @Test
    fun closeDuringConnectIsAcknowledgedAndTheLateSocketIsClosed() {
        val r = rig()
        r.ready()
        val hold = CountDownLatch(1)
        val plan = r.connector.plan(443, ConnectPlan(hold = hold))
        r.open(1, 443)
        Poll.until("connect started") { r.connector.callsTo(443) == 1 }

        r.send(Op.CLOSE, 1, le16(1))
        r.await("CLOSE_ACK") { it.op == Op.CLOSE_ACK && it.id == 1 }
        hold.countDown()
        Poll.until("late socket closed") { plan.returned && plan.socket.closed }
        r.settle(300)
        assertTrue("no OPEN_OK/OPEN_ERR after CLOSE_ACK", r.sync().none { it.id == 1 && (it.op == Op.OPEN_OK || it.op == Op.OPEN_ERR) })
        assertEquals(1, r.connector.callsTo(443))

        r.openStream(2, 8443)
        assertTrue(r.link.disconnects.isEmpty())
    }

    @Test
    fun resetDuringConnectClosesTheLateSocketWithoutReply() {
        val r = rig()
        r.ready()
        val hold = CountDownLatch(1)
        val plan = r.connector.plan(443, ConnectPlan(hold = hold))
        r.open(1, 443)
        Poll.until("connect started") { r.connector.callsTo(443) == 1 }

        r.send(Op.RESET, 1, le16(14))
        hold.countDown()
        Poll.until("late socket closed") { plan.returned && plan.socket.closed }
        r.settle(300)
        assertTrue("nothing for a stream the device reset", r.sync().none { it.id == 1 })
        assertEquals(1, r.connector.callsTo(443))
    }

    /**
     * A fresh BLE session while the previous session's connect still hangs: the new session has
     * its own channel and restarts inner sequences at 0, its own stream 1 works, and the stale
     * connect's socket is closed without anything reaching either link. Nothing is replayed.
     */
    @Test
    fun aNewSessionDropsALateConnectFromThePreviousOne() {
        val r = rig()
        val old = r.ready()
        val hold = CountDownLatch(1)
        val stale = r.connector.plan(443, ConnectPlan(hold = hold))
        r.open(1, 443)
        Poll.until("old connect started") { r.connector.callsTo(443) == 1 }
        r.awaitQuiet()
        val oldValues = old.link.sent.size
        r.engine.linkLost(old.token)
        r.awaitEnd(old)

        val fresh = r.ready()
        assertEquals(0L, r.sync(fresh).single { it.op == Op.HELLO_ACK }.seq)
        val socket = FakeSocket()
        r.connector.plan(8443, ConnectPlan(socket))
        r.open(1, 8443)
        val ok = r.await("OPEN_OK on the new session", fresh) { it.op == Op.OPEN_OK && it.id == 1 }
        assertEquals(1L, ok.seq)
        assertEquals(2L, ok.counter)

        hold.countDown()
        Poll.until("stale socket closed") { stale.returned && stale.socket.closed }
        r.settle(300)
        assertEquals("nothing more on the old link", oldValues, old.link.sent.size)
        assertEquals("no stale OPEN_OK/OPEN_ERR on the new link", 2, r.sync(fresh).size)
        assertFalse(socket.closed)
        assertEquals(1, r.connector.callsTo(443))
        assertEquals(1, r.connector.callsTo(8443))
    }

    // ---- end, session failure ----------------------------------------------------------------

    @Test
    fun endClosesEverySocketAndSilencesTheLink() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        val hold = CountDownLatch(1)
        val late = r.connector.plan(8443, ConnectPlan(hold = hold))
        r.open(2, 8443)
        Poll.until("connect 2 started") { r.connector.callsTo(8443) == 1 }
        r.awaitQuiet(extraMs = 100)
        val values = r.link.sent.size

        r.engine.end(r.token)
        Poll.until("socket 1 closed") { s1.closed }
        hold.countDown()
        Poll.until("late socket 2 closed") { late.returned && late.socket.closed }
        r.settle(250)
        assertEquals(values, r.link.sent.size)
        assertEquals(PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true), r.awaitEnd().end)
        val stale = r.engine.currentStats.staleCallbacks
        r.data(1, byteArrayOf(1))
        Poll.until("a write after end is stale") { r.engine.currentStats.staleCallbacks == stale + 1 }
    }

    @Test
    fun deviceSessionResetEndsEverythingWithoutAReply() {
        val r = rig()
        r.ready()
        val s1 = r.openStream(1, 443)
        r.awaitQuiet(extraMs = 100)
        val mark = r.link.sent.size
        r.send(Op.RESET, 0, le16(13))
        Poll.until("disconnect") { r.link.disconnects.isNotEmpty() }
        Poll.until("socket closed") { s1.closed }
        r.settle(250)
        assertEquals("no RESET back to a device that reset the session", mark, r.link.sent.size)
        assertEquals(PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.DEADLINE, unsettled = false, authenticated = true, ready = true), r.awaitEnd().end)
    }

    // ---- Submission and replay ---------------------------------------------------------------

    /** notify() refused OPEN_OK: never resent, the session fails, the socket closes, the connect is not retried. */
    @Test
    fun aRefusedOpenOkIsNeverResentAndItsSocketCloses() {
        val r = rig()
        r.ready()
        r.link.outcome = { s ->
            if (r.sync().any { it.sent === s && it.op == Op.OPEN_OK }) FakeLink.Outcome.REFUSED else FakeLink.Outcome.OK
        }
        val socket = FakeSocket()
        r.connector.plan(443, ConnectPlan(socket))
        r.open(1, 443)
        Poll.until("disconnect") { r.link.disconnects.isNotEmpty() }
        Poll.until("socket closed") { socket.closed }
        r.settle(300)

        val refused = r.sync().single { it.op == Op.OPEN_OK }
        assertEquals("never resent and nothing after it", refused.sent.index + 1, r.link.sent.size)
        assertEquals(1, r.connector.callsTo(443))
        assertEquals(PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true), r.awaitEnd().end)
    }

    // ---- The device may react as soon as the stack has taken a frame -------------------------

    /**
     * The P4 enters Open on OPEN_OK and may send DATA right away; it can have OPEN_OK as soon as
     * the stack took the notification, which may be before notify() returns to the engine. That
     * DATA is legal and must be accepted.
     */
    @Test
    fun dataArrivingWhileOpenOkIsStillInsideNotifyIsAccepted() {
        val r = rig()
        r.ready()
        val socket = FakeSocket()
        r.connector.plan(443, ConnectPlan(socket))
        val fired = AtomicBoolean()
        r.link.duringNotify = { s ->
            if (r.sync().any { it.sent === s && it.op == Op.OPEN_OK && it.id == 1 } && fired.compareAndSet(false, true)) {
                thread(name = "p4-write") { r.data(1, byteArrayOf(1, 2, 3)) }.join(300)
            }
        }
        r.open(1, 443)
        Poll.until("bytes to TCP") { socket.writtenBytes.size == 3 }
        assertTrue(fired.get())
        assertArrayEquals(byteArrayOf(1, 2, 3), socket.writtenBytes)
        assertTrue(r.link.disconnects.isEmpty())
        assertTrue(r.ends.isEmpty())
    }

    /** Same for CREDIT: the device may use a new limit as soon as the CREDIT notification is taken. */
    @Test
    fun creditIsUsableAsSoonAsItsNotificationIsTaken() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        val extra = AtomicBoolean()
        val sentExtra = AtomicInteger()
        r.link.duringNotify = { s ->
            val credit = r.sync().firstOrNull { it.sent === s && it.op == Op.CREDIT && it.id == 1 }
            // Only the engine thread calls this. The amount is set before the flag the poll below
            // reads, so the poll can never see the flag with the amount still 0.
            if (credit != null && !extra.get()) {
                sentExtra.set(minOf(500L, u32(credit.body, 0) - grantOf(r, 1)).toInt())
                extra.set(true)
                thread(name = "p4-write") { r.data(1, pattern(sentExtra.get(), 8)) }.join(300)
            }
        }
        // Use up OPEN_OK's whole grant first, so the extra bytes are legal only under the new CREDIT.
        val grant = grantOf(r, 1).toInt()
        r.data(1, pattern(grant, 4))
        Poll.until("device DATA on the new credit written") { extra.get() && socket.writtenBytes.size == grant + sentExtra.get() }
        assertTrue(sentExtra.get() > 0)
        assertArrayEquals(pattern(grant, 4) + pattern(sentExtra.get(), 8), socket.writtenBytes)
        assertTrue(r.link.disconnects.isEmpty())
        assertTrue(r.ends.isEmpty())
    }

    private fun pattern(n: Int, salt: Int): ByteArray = ByteArray(n) { ((it * 31 + salt) % 251).toByte() }

    /** The receive limit stream [id]'s OPEN_OK granted the P4. */
    private fun grantOf(r: SecureRig, id: Int): Long = u32(r.frames(id, Op.OPEN_OK).single().body, 0)

    private companion object {
        /** OPEN_OK's initial window (PipeWire.INITIAL_LIMIT); later CREDITs grant consumed + 4096. */
        const val GRANT = 1024L

        /** The fixture phone nonce for the handshake, then phone nonce u64 0x1112131415161718 (LE) for HELLO_ACK. */
        val GOLDEN_RANDOM: (ByteArray) -> Unit get() = FakeRandom(PipeFixture.bytes("phone_nonce_hex"), PHONE_NONCE)

        /** 2001:db8::1 (RFC 3849). */
        val DOC_V6 = hex("20010db8000000000000000000000001")

        val RELAY_NAME = ascii("relay.example.com")

        /** Golden hex normalised; `SSSSSSSS` stands for the frame's own sequence number. */
        fun golden(text: String, seq: Long = 0): String =
            text.replace(" ", "").replace("|", "").lowercase().replace("ssssssss", toHex(le32(seq)))
    }
}
