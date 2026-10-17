package com.hardwareone.console.pipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream-ID and 32-bit byte-counter exhaustion (handoff: "ID/counter exhaustion"). Nothing may
 * wrap: the last stream ID is 0xFFFF (Q12: IDs are "never reused or wrapped"), and credit stops
 * before `consumed + 4096` would exceed 2^32. Counters are advanced by reflection because no API
 * moves 4 GiB through a unit test. The rigs use frame 512; nothing here depends on the size.
 */
class PipeExhaustionTest {
    private fun setLong(target: Any, field: String, value: Long) {
        val f = target.javaClass.getDeclaredField(field)
        f.isAccessible = true
        f.setLong(target, value)
    }

    @Test
    fun theLastStreamIdIsUsableAndNothingCanFollowIt() {
        // Reusing 0xFFFF, or wrapping around to 1, are both a non-increasing OPEN.
        for (next in listOf(0xFFFF, 1)) {
            val rig = PipeRig()
            rig.hello()
            rig.open(0xFFFF) // Q12: the first ID may be any nonzero value, the last one included
            assertTrue(rig.session.find(0xFFFF)?.live == true)
            // Close it so a slot is free; any further OPEN can only reuse or go backwards.
            assertEquals(PipeSession.Rx.ACCEPTED, rig.device.send(rig.session, Op.CLOSE, 0xFFFF, le16(1)))
            assertEquals(listOf(Op.CLOSE_ACK), drain(rig.session).map { it.op })
            assertEquals("OPEN $next", PipeSession.Rx.REJECTED, rig.device.send(rig.session, Op.OPEN, next, openBody(ADDR_IPV4, TEST_IPV4, 443)))
            assertEquals("OPEN $next", PipeSession.State.FAILED, rig.session.state)
            assertEquals("OPEN $next", PipeError.BAD_PROTOCOL, rig.session.error)
            assertEquals("OPEN $next: admit not consulted", 1, rig.admitted.size)
        }
    }

    @Test
    fun aFrameForAnIdAboveTheHighestOpenedIsASessionError() {
        val rig = PipeRig()
        rig.hello()
        rig.open(7)
        assertEquals(PipeSession.Rx.REJECTED, rig.device.send(rig.session, Op.CREDIT, 8, le32(2048)))
        assertEquals(PipeError.BAD_PROTOCOL, rig.session.error)
    }

    @Test
    fun creditStopsBeforeTheConsumedCounterCouldWrap() {
        val rig = PipeRig()
        rig.hello()
        rig.open(1)
        val s = rig.session.find(1)!!
        // Pretend almost 4 GiB already flowed device → relay.
        val near = PipeWire.U32_MAX - PipeWire.WINDOW - 10
        for (f in listOf("received", "consumed", "creditedConsumed", "receiveLimit", "queuedLimit")) setLong(s, f, near)
        // 100 more bytes arrive and are written to TCP.
        setLong(s, "receiveLimit", near + 100)
        assertEquals(PipeSession.Rx.ACCEPTED, rig.device.send(rig.session, Op.DATA, 1, bytes(100, 7)))
        rig.session.downConsumed(1, 100)
        // A grant of consumed + 4096 would exceed 0xFFFFFFFF: the session fails instead.
        rig.session.tick(10_000)
        assertEquals(PipeSession.State.FAILED, rig.session.state)
        assertEquals(PipeError.FLOW_VIOLATION, rig.session.error)
        val frames = drain(rig.session)
        assertTrue("no CREDIT may go out with a wrapped value: $frames", frames.none { it.op == Op.CREDIT })
        assertNull(rig.session.find(1))
    }

    @Test
    fun theDevicesCreditCannotExceedThe32BitRange() {
        val rig = PipeRig()
        rig.hello()
        rig.open(1)
        val s = rig.session.find(1)!!
        setLong(s, "sent", PipeWire.U32_MAX - 100)
        setLong(s, "peerLimit", PipeWire.U32_MAX - 100)
        // sent + 4096 is beyond 2^32 - 1, but the wire value can only be <= 0xFFFFFFFF, which is
        // within the allowance: accepted, and nothing overflows.
        assertEquals(PipeSession.Rx.ACCEPTED, rig.device.send(rig.session, Op.CREDIT, 1, le32(PipeWire.U32_MAX)))
        assertEquals(PipeWire.U32_MAX, s.peerLimit)
    }
}
