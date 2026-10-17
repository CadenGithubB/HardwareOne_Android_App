package com.hardwareone.console.pipe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch

/**
 * Engine behaviour around VPN route changes and session teardown, through the stage-4 secure
 * channel ([SecureRig]): a stream whose address leaves the tunnel ends with RESET 5 (and one whose
 * connect was still in flight never gets OPEN_OK), streams that are still routed are untouched, a
 * silent end() sends nothing but disconnects, and the encrypted RESET of an authority change keeps
 * the 30 ms notification pacing. Addresses are RFC 5737 documentation addresses.
 */
class PipeEngineRoutesTest {
    private val relay = InetAddress.getByName("198.51.100.10")
    private val other = InetAddress.getByName("203.0.113.20")
    private val r = SecureRig()

    @After
    fun tearDown() = r.close()

    @Test
    fun anOpenStreamWhoseAddressLeavesTheTunnelIsReset() {
        r.ready()
        val socket = r.openStream(1, 443, FakeSocket(relay))
        r.gate.routed = { false }
        r.engine.recheckRoutes { r.gate.routes(it) }
        val reset = r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }
        assertEquals(toHex(le16(PipeError.VPN_UNAVAILABLE.code)), toHex(reset.body))
        Poll.until("socket closed") { socket.closed }
        assertEquals(mapOf(PipeError.VPN_UNAVAILABLE to 1), r.engine.currentStats.resets)
        assertEquals("ready", r.engine.currentStats.stage)
        assertTrue(r.ends.isEmpty())
    }

    @Test
    fun onlyTheStreamWhoseAddressLeftTheTunnelIsReset() {
        r.ready()
        val gone = r.openStream(1, 443, FakeSocket(relay))
        val kept = r.openStream(2, 8443, FakeSocket(other))
        r.engine.recheckRoutes { it != relay }
        r.await("RESET 1") { it.op == Op.RESET && it.id == 1 }
        Poll.until("socket 1 closed") { gone.closed }
        r.data(2, byteArrayOf(3, 1, 4))
        Poll.until("stream 2 still delivers") { kept.writtenBytes.size == 3 }
        r.settle(150)
        assertTrue(r.frames(2, Op.RESET).isEmpty())
        assertFalse(kept.closed)
    }

    @Test
    fun recheckLeavesStreamsThatAreStillRoutedAlone() {
        r.ready()
        val socket = r.openStream(1, 443, FakeSocket(relay))
        r.engine.recheckRoutes { true }
        r.settle(300)
        assertTrue(r.frames(1, Op.RESET).isEmpty())
        assertFalse(socket.closed)
        assertTrue(r.engine.currentStats.resets.isEmpty())
    }

    @Test
    fun aConnectThatCompletesAfterItsRouteVanishedIsNeverPublished() {
        r.ready()
        val release = CountDownLatch(1)
        val plan = r.connector.plan(443, ConnectPlan(FakeSocket(relay), hold = release))
        r.open(1, 443)
        Poll.until("connect started") { r.connector.callsTo(443) == 1 }
        r.gate.routed = { false }
        r.engine.recheckRoutes { r.gate.routes(it) } // the socket is not published yet: nothing to check
        release.countDown()
        val err = r.await("OPEN_ERR 5") { it.op == Op.OPEN_ERR && it.id == 1 }
        assertEquals(toHex(le16(PipeError.VPN_UNAVAILABLE.code)), toHex(err.body))
        Poll.until("socket closed") { plan.returned && plan.socket.closed }
        r.settle(150)
        assertTrue("no OPEN_OK for an unrouted stream", r.frames(1, Op.OPEN_OK).isEmpty())
        assertEquals(mapOf(PipeError.VPN_UNAVAILABLE to 1), r.engine.currentStats.refused)
    }

    /** end() (Bluetooth off, the CCCD rules of R3) is silent even when READY: no RESET, one disconnect. */
    @Test
    fun aSilentEndSendsNoResetButDisconnects() {
        r.ready()
        r.awaitQuiet()
        val before = r.link.sent.size
        r.engine.end(r.token)
        Poll.until("disconnect") { r.link.disconnects.size == 1 }
        Thread.sleep(PipeEngine.NOTIFY_INTERVAL_MS + PipeEngine.RESET_GRACE_MS)
        assertEquals("no frame after end()", before, r.link.sent.size)
        assertEquals(1, r.link.disconnects.size)
        assertEquals(PipeEndKind.TRANSPORT, r.awaitEnd().end.kind)
    }

    /** The encrypted RESET 14 of cancel() waits out the 30 ms gap after the previous notification. */
    @Test
    fun theFinalResetKeepsTheNotificationPacing() {
        r.ready(link = FakeLink(r.clock).apply { completeInline = true })
        r.awaitQuiet()
        val before = r.link.sent.size
        r.send(Op.PING, 0, le32(5))
        Poll.until("PONG returned") { r.link.sent.size > before && r.link.sent[before].returned }
        // Its completion came inside the call; give the engine a moment to settle it, well inside 30 ms.
        Thread.sleep(5)
        r.engine.cancel()
        r.awaitEnd()
        val frames = r.sync()
        val pong = frames.single { it.op == Op.PONG }
        val reset = frames.single { it.op == Op.RESET && it.id == 0 }
        assertEquals(toHex(le16(PipeError.CANCELLED.code)), toHex(reset.body))
        val gapMs = (reset.sent.atNanos - pong.sent.atNanos) / 1_000_000.0
        assertTrue("RESET $gapMs ms after the previous notification", gapMs >= PipeEngine.NOTIFY_INTERVAL_MS - 1.0)
        assertTrue("disconnect after the RESET", r.link.disconnects.single() > reset.sent.atNanos)
    }
}
