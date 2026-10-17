package com.hardwareone.console.pipe

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The qualification log's inputs (firmware-003 row 10): every stream and session the engine
 * reports, keyed by `corr` (the P4's pipe HELLO nonce, as on the wire) and stream id, with times,
 * sizes and codes only. Also the exact JSON field sets of the lines [PipeLog] writes, so the
 * user's export lines up with the P4's F3 records.
 */
class PipeLogRecordsTest {
    private val rigs = ArrayList<SecureRig>()

    @After
    fun closeRigs() {
        rigs.forEach { it.close() }
    }

    private fun rig(): SecureRig = SecureRig().also { rigs += it }

    /** corr is the P4's inner HELLO nonce in wire order: P4_NONCE = u64 0x0102030405060708, LE. */
    private val corr = toHex(P4_NONCE)

    @Test
    fun aClosedStreamRecordsItsOpenResultEndBytesAndCorr() {
        val r = rig()
        r.ready()
        val socket = r.openStream(1, 443)
        r.data(1, ByteArray(40) { it.toByte() })
        Poll.until("40 bytes to TCP") { socket.writtenBytes.size == 40 }
        socket.feed(ByteArray(30) { (it + 1).toByte() })
        Poll.until("30 bytes to the P4") { r.dataBytes(1) == 30 }
        r.awaitQuiet()
        r.send(Op.CLOSE, 1, le16(0))
        r.await("CLOSE_ACK") { it.op == Op.CLOSE_ACK && it.id == 1 }
        Poll.until("stream record") { r.streamRecords.isNotEmpty() }
        val rec = r.streamRecords.single()
        assertEquals(corr, rec.corr)
        assertEquals("the diagnostics show the same corr", corr, r.engine.currentStats.corr)
        assertEquals(1, rec.id)
        assertEquals("open_ok", rec.result)
        assertEquals("close:0", rec.end)
        assertEquals(30L, rec.bytesToDevice)
        assertEquals(40L, rec.bytesFromDevice)
        assertTrue(rec.peerClose)
        assertFalse(rec.peerReset)
        val ms = rec.openToResultMs
        assertNotNull("OPEN to OPEN_OK time", ms)
        assertTrue("OPEN to OPEN_OK $ms ms", ms!! in 0..2_500)
    }

    @Test
    fun aRefusedOpenRecordsOpenErrAndNoEnd() {
        val r = rig()
        r.ready()
        r.gate.allow = { false }
        r.open(1, 443)
        r.await("OPEN_ERR") { it.op == Op.OPEN_ERR && it.id == 1 }
        Poll.until("stream record") { r.streamRecords.isNotEmpty() }
        val rec = r.streamRecords.single()
        assertEquals("open_err:${PipeError.TARGET_DENIED.code}", rec.result)
        assertEquals("none", rec.end)
        assertEquals(0L, rec.bytesToDevice)
        assertEquals(0L, rec.bytesFromDevice)
        assertNotNull(rec.openToResultMs)
        assertEquals(corr, rec.corr)
    }

    @Test
    fun aPeerResetAndALocalTcpFailureAreToldApart() {
        val r = rig()
        r.ready()
        r.openStream(1, 443)
        r.send(Op.RESET, 1, le16(PipeError.CANCELLED.code))
        val tcp = r.openStream(2, 443)
        tcp.failReads()
        r.await("RESET 2") { it.op == Op.RESET && it.id == 2 }
        Poll.until("two stream records") { r.streamRecords.size == 2 }
        val byId = r.streamRecords.associateBy { it.id }
        assertEquals("reset:${PipeError.CANCELLED.code}", byId[1]!!.end)
        assertTrue(byId[1]!!.peerReset)
        assertEquals("reset:${PipeError.TCP_IO.code}", byId[2]!!.end)
        assertFalse(byId[2]!!.peerReset)
        assertEquals("open_ok", byId[2]!!.result)
    }

    @Test
    fun aStreamLiveAtTheSessionEndIsRecordedAsSessionEndAndTheSessionRecordFollows() {
        val r = rig()
        val c = r.ready()
        r.openStream(1, 443)
        r.awaitQuiet()
        r.engine.linkLost(c.token)
        r.awaitEnd()
        Poll.until("session record") { r.sessionRecords.isNotEmpty() }
        assertEquals("session_end", r.streamRecords.single().end)
        val s = r.sessionRecords.single()
        assertEquals(r.ends.single().end, s.end)
        assertEquals(corr, s.corr)
        val readyAt = s.readyAt
        assertNotNull("ready time", readyAt)
        assertTrue("ended after ready", s.endedAt >= readyAt!!)
        assertEquals(PipeEndKind.TRANSPORT, s.end.kind)
    }

    @Test
    fun aSessionThatNeverReachedTheInnerHelloHasNoCorrAndNoReadyTime() {
        val r = rig()
        r.autoHandshake = false
        assertTrue(r.begin())
        r.subscribe()
        Poll.until("HELLO submitted") { r.link.sent.isNotEmpty() }
        r.engine.end(r.token)
        r.awaitEnd()
        Poll.until("session record") { r.sessionRecords.isNotEmpty() }
        val s = r.sessionRecords.single()
        assertNull(s.corr)
        assertNull(s.readyAt)
        assertTrue(r.streamRecords.isEmpty())
    }

    // ---- JSON lines ---------------------------------------------------------------------------

    @Test
    fun runLineCarriesTheLastExitReasonOrNull() {
        val none = PipeLog.runLine(5, "2.6.3-pipetest")
        assertEquals(setOf("t", "elapsed_ms", "app", "last_exit"), none.keySet())
        assertTrue(none.isNull("last_exit"))
        val exit = JSONObject().put("reason", "low_memory").put("code", 3).put("importance", 125).put("at_wall_ms", 1_760_000_000_000L)
        val line = PipeLog.runLine(6, "2.6.3-pipetest", exit)
        assertEquals("low_memory", line.getJSONObject("last_exit").getString("reason"))
    }

    @Test
    fun sessionLineHasExactlyTheAgreedFields() {
        val end = PipeEnd(7, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = false, ready = false)
        val line = PipeLog.sessionLine(PipeSessionRecord(end, null, null, 1234, 2), retiredInstanceCallbacks = 1)
        assertEquals(
            setOf("t", "corr", "ready_ms", "end_ms", "end_kind", "end_code", "authenticated", "unsettled", "stale_callbacks", "retired_instance_callbacks"),
            line.keySet(),
        )
        assertEquals("session", line.getString("t"))
        assertTrue(line.isNull("corr"))
        assertTrue(line.isNull("ready_ms"))
        assertEquals(1234L, line.getLong("end_ms"))
        assertEquals("auth", line.getString("end_kind"))
        assertEquals(1, line.getInt("end_code"))
        assertEquals(2, line.getInt("stale_callbacks"))
        assertEquals(1L, line.getLong("retired_instance_callbacks"))
    }

    @Test
    fun streamLineHasExactlyTheAgreedFields() {
        val line = PipeLog.streamLine(PipeStreamRecord("0102030405060708", 2, 41, "open_ok", "close:1", 30, 40, peerReset = false, peerClose = true))
        assertEquals(
            setOf("t", "corr", "id", "open_to_result_ms", "result", "end", "bytes_to_p4", "bytes_from_p4", "peer_reset", "peer_close"),
            line.keySet(),
        )
        val expected = JSONObject("""{"t":"stream","corr":"0102030405060708","id":2,"open_to_result_ms":41,"result":"open_ok","end":"close:1","bytes_to_p4":30,"bytes_from_p4":40,"peer_reset":false,"peer_close":true}""")
        assertTrue("$line", line.similar(expected))
    }

    @Test
    fun countersLineCarriesPerCodeCountsAndNoDestination() {
        val stats = PipeStats(
            sessions = 3, opened = 5,
            refused = mapOf(PipeError.VPN_UNAVAILABLE to 2, PipeError.CONNECT_TIMEOUT to 1),
            resets = mapOf(PipeError.TCP_IO to 1),
            peerResets = 1, peerCloses = 4, staleCallbacks = 2,
            lastEnd = PipeEndKind.TRANSPORT, lastError = PipeError.CANCELLED,
        )
        val line = PipeLog.countersLine(stats, retiredInstanceCallbacks = 3, elapsedMs = 99)
        assertEquals("counters", line.getString("t"))
        assertEquals(2, line.getJSONObject("open_err").getInt("5"))
        assertEquals(1, line.getJSONObject("open_err").getInt("8"))
        assertEquals(1, line.getJSONObject("reset").getInt("11"))
        assertEquals(3, line.getInt("sessions"))
        assertEquals(5, line.getInt("streams_opened"))
        assertEquals(4, line.getInt("peer_closes"))
        assertEquals(3L, line.getLong("retired_instance_callbacks"))
        assertEquals("transport", line.getString("last_end_kind"))
        assertFalse("no destination anywhere", line.toString().contains("192.0.2"))
    }
}
