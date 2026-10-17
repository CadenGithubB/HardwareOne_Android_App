package com.hardwareone.console.pipe

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two [PipeEngine] rules around the inner HELLO_ACK and the best-effort RESET, driven through the
 * public engine API over the firmware's MTU-247 golden handshake
 * (`phone_secure_channel_v1_vectors.json`).
 *
 * - **The best-effort RESET is never waited for** (DECISIONS R7, R10). The disconnect follows
 *   [PipeEngine.RESET_GRACE_MS] after the RESET call started, from the watchdog thread, even while
 *   that binder call is still blocked, and the session then ends unsettled so the server instance is
 *   retired. A call that returns after the end changes nothing, even when a later session has begun.
 * - **Readiness is the whole HELLO_ACK** (FW-3: READY is local readiness). With a small offer the
 *   HELLO_ACK takes several frames, and the stage, the reported [PipeEnd.ready] and so the stored
 *   "last authenticated" time only turn ready once its LAST fragment's notification completed.
 *
 * Expected values come from the fixture JSON and from the reference sealer at the bottom, which
 * calls BouncyCastle's ChaCha20-Poly1305 directly with a ByteBuffer nonce, never from
 * [PipeSecureChannel]. The engine runs on real threads and its own millisecond clock, so the
 * timing checks use bounds with generous margins.
 */
class PipeEngineResetAndReadinessTest {
    private val engines = ArrayList<PipeEngine>()
    private val links = ArrayList<Link>()

    @After
    fun stopEngines() {
        links.forEach { it.release.countDown() }
        engines.forEach { it.shutdown() }
    }

    // ---- The best-effort RESET (R7, R10) --------------------------------------------------------

    /**
     * cancel() once READY submits the encrypted stream-0 RESET(14) (inner seq 1, counter 2), and
     * that call then blocks in the binder. The link is disconnected RESET_GRACE_MS after the call
     * started and the session ends CANCELLED and unsettled, both while the call is still blocked.
     * No session is admitted while the engine thread is still inside that call; once it returns,
     * the next one is, and the late OK neither settles nor occupies its one submission slot.
     */
    @Test
    fun aResetCallBlockedInTheBinderNeverDelaysTheDisconnectOrTheEnd() {
        val rig = Rig()
        rig.ready()
        rig.link.blockAt = 3
        rig.engine.cancel()
        rig.waitUntil("RESET submitted") { rig.link.calls.size == 4 }
        val reset = rig.link.calls[3]
        assertEquals(toHex(toP4(2, rawFrame(Op.RESET, 0, 1, F_BOTH, le16(PipeError.CANCELLED.code)))), toHex(reset.value))

        rig.waitUntil("disconnect while the RESET call is blocked") { rig.link.disconnects.isNotEmpty() }
        val disconnectMs = ms(reset.startedNanos, rig.link.disconnects.single())
        assertTrue("disconnect $disconnectMs ms after the RESET call started", disconnectMs in 150.0..1_000.0)
        rig.waitUntil("PipeEnd while the RESET call is blocked") { rig.ends.isNotEmpty() }
        assertEquals(
            listOf(PipeEnd(1L, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = true, authenticated = true, ready = true)),
            rig.ends.toList(),
        )
        assertFalse("the RESET call is still blocked", reset.returned)
        assertFalse(rig.engine.active)
        assertEquals("idle", rig.engine.currentStats.stage)

        // No session while the engine thread is still inside the old call (its timers could not run).
        val second = rig.newLink()
        assertFalse("refused while the RESET call is blocked", rig.engine.begin(second, 247, vector("psk_hex"), 2L))
        rig.link.release.countDown()
        rig.waitUntil("the old RESET call returned") { reset.returned }
        rig.waitUntil("admitted once the call returned") { rig.engine.begin(second, 247, vector("psk_hex"), 2L) }
        rig.engine.subscribed(2L)
        rig.waitUntil("the new session's HELLO") { second.calls.size == 1 }
        // Same injected private key; the FakeRandom has run out, so the nonce repeats its last pattern.
        assertEquals("01" + vectorHex("phone_public_key_hex") + "1112131415161718".repeat(2), toHex(second.calls[0].value))
        assertEquals("nothing more on the old link", 4, rig.link.calls.size)
        assertEquals(1, rig.link.disconnects.size)
        assertEquals("the late return reports nothing", 1, rig.ends.size)
        assertEquals("handshake", rig.engine.currentStats.stage)
    }

    /**
     * The usual case: the RESET call returns OK at once and its completion arrives. The disconnect
     * still waits out RESET_GRACE_MS from the call, so the value can leave, and the end is settled.
     */
    @Test
    fun anAcceptedResetGetsItsGraceBeforeTheDisconnectAndItsCompletionSettlesTheEnd() {
        val rig = Rig()
        rig.ready()
        rig.engine.cancel()
        rig.waitUntil("RESET returned") { rig.link.calls.size == 4 && rig.link.calls[3].returned }
        val reset = rig.link.calls[3]
        assertEquals(toHex(toP4(2, rawFrame(Op.RESET, 0, 1, F_BOTH, le16(PipeError.CANCELLED.code)))), toHex(reset.value))
        rig.engine.onNotificationSent(1L, 0)
        rig.waitUntil("PipeEnd") { rig.ends.isNotEmpty() }
        assertEquals(
            listOf(PipeEnd(1L, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true)),
            rig.ends.toList(),
        )
        val disconnectMs = ms(reset.startedNanos, rig.link.disconnects.single())
        assertTrue("disconnect $disconnectMs ms after the RESET call started", disconnectMs in 150.0..1_000.0)
        assertEquals(4, rig.link.calls.size)
    }

    /** A RESET whose submission is uncertain disconnects without the grace and ends unsettled (R10). */
    @Test
    fun anUncertainResetDisconnectsAtOnceAndEndsUnsettled() {
        val rig = Rig()
        rig.ready()
        rig.link.outcomes[3] = PipeSubmit.UNCERTAIN
        rig.engine.cancel()
        rig.waitUntil("PipeEnd") { rig.ends.isNotEmpty() }
        assertEquals(
            listOf(PipeEnd(1L, PipeEndKind.CANCELLED, PipeError.CANCELLED, unsettled = true, authenticated = true, ready = true)),
            rig.ends.toList(),
        )
        val reset = rig.link.calls[3]
        assertEquals(toHex(toP4(2, rawFrame(Op.RESET, 0, 1, F_BOTH, le16(PipeError.CANCELLED.code)))), toHex(reset.value))
        val afterReturnMs = ms(reset.returnedNanos, rig.link.disconnects.single())
        assertTrue("disconnect $afterReturnMs ms after the RESET call returned", afterReturnMs < PipeEngine.RESET_GRACE_MS - 50.0)
        assertEquals(4, rig.link.calls.size)
    }

    // ---- Readiness is the whole HELLO_ACK (FW-3) ------------------------------------------------

    /**
     * R9: a P4 offering max_value 20 (sending its HELLO in two fragments at that limit) gets a
     * HELLO_ACK of three inner frames of 20, 20 and 16 bytes (8 + 8 + 4 body bytes), inner seq 0–2,
     * outer counters 1–3. Each waits for the previous completion; the stage is "secure" until the
     * third completes, then "ready".
     */
    @Test
    fun aFragmentedHelloAckIsReadyOnlyAfterItsLastFragmentCompletes() {
        val rig = Rig()
        rig.secure()
        rig.sendFragmentedHello(offer = 20)
        val expected = listOf(
            "01 02 0000 00000000 0800 01 00 | 0102030405060708",
            "01 02 0000 01000000 0800 00 00 | 1112131415161718",
            "01 02 0000 02000000 0400 02 00 | 0200 1400",
        )
        for ((i, inner) in expected.withIndex()) {
            val index = 2 + i
            rig.waitUntil("HELLO_ACK fragment ${i + 1} returned") { rig.link.calls.size == index + 1 && rig.link.calls[index].returned }
            assertEquals("fragment ${i + 1}", toHex(toP4(i + 1L, hex(inner))), toHex(rig.link.calls[index].value))
            assertEquals("stage before fragment ${i + 1} completes", "secure", rig.engine.currentStats.stage)
            rig.engine.onNotificationSent(1L, 0)
        }
        rig.waitUntil("ready") { rig.engine.currentStats.stage == "ready" }
        assertEquals(listOf("handshake", "secure", "ready"), rig.stages.toList())
        assertEquals(20, rig.engine.currentStats.frame)
        assertEquals(5, rig.link.calls.size)
    }

    /**
     * A session that ends after the first HELLO_ACK fragment completed, with the second one
     * submitted, was never ready: PipeEnd.ready is false and the stage never said "ready". The
     * second fragment's completion during the teardown settles it.
     */
    @Test
    fun aSessionEndingBeforeTheLastHelloAckFragmentCompletesWasNeverReady() {
        val rig = Rig()
        rig.secure()
        rig.sendFragmentedHello(offer = 20)
        rig.waitUntil("HELLO_ACK fragment 1 returned") { rig.link.calls.size == 3 && rig.link.calls[2].returned }
        rig.engine.onNotificationSent(1L, 0)
        rig.waitUntil("HELLO_ACK fragment 2 returned") { rig.link.calls.size == 4 && rig.link.calls[3].returned }
        assertEquals("secure", rig.engine.currentStats.stage)
        rig.engine.end(1L)
        rig.engine.onNotificationSent(1L, 0)
        rig.waitUntil("PipeEnd") { rig.ends.isNotEmpty() }
        assertEquals(
            listOf(PipeEnd(1L, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = false)),
            rig.ends.toList(),
        )
        assertEquals(listOf("handshake", "secure", "ending", "idle"), rig.stages.toList())
        assertEquals("no third fragment and no RESET", 4, rig.link.calls.size)
    }

    // ---- Rig ------------------------------------------------------------------------------------

    /** One notify call as the link saw it. */
    private class Call(val value: ByteArray, val startedNanos: Long) {
        @Volatile var returnedNanos = 0L
        val returned: Boolean get() = returnedNanos != 0L
    }

    /**
     * A [PipeLink] that records every notify call and disconnect. Call [blockAt] (0-based) blocks
     * until [release] opens (10 s at most); [outcomes] overrides a call's result (default OK).
     * Completions are delivered by the test.
     */
    private class Link : PipeLink {
        val calls = CopyOnWriteArrayList<Call>()
        val disconnects = CopyOnWriteArrayList<Long>()
        val outcomes = ConcurrentHashMap<Int, PipeSubmit>()
        val release = CountDownLatch(1)
        @Volatile var blockAt = -1

        override fun notify(value: ByteArray): PipeSubmit {
            val call = Call(value.copyOf(), System.nanoTime())
            val index = calls.size
            calls += call
            if (index == blockAt) release.await(10, TimeUnit.SECONDS)
            val result = outcomes[index] ?: PipeSubmit.OK
            call.returnedNanos = System.nanoTime()
            return result
        }

        override fun disconnect() {
            disconnects += System.nanoTime()
        }
    }

    /**
     * One engine at ATT MTU 247 (P = 219) with the fixture's private key and phone nonce, then the
     * session nonce 0x18…0x11 that the fixture HELLO_ACK carries. Session token 1.
     */
    private inner class Rig {
        val link = newLink()
        val ends = CopyOnWriteArrayList<PipeEnd>()
        val stages = CopyOnWriteArrayList<String>()
        val engine = PipeEngine(
            connector = { _, _ -> throw PipeConnectException(PipeError.CONNECT_FAILED) },
            gate = object : PipeGate {
                override fun allowed(target: PipeTarget) = true
                override fun vpnAvailable() = true
            },
            onEnd = { ends += it },
            onStage = { _, stage -> stages += stage },
            random = FakeRandom(vector("phone_nonce_hex"), hex("1112131415161718")),
            privateKey = { vector("phone_private_key_hex") },
        ).also { engines += it }

        fun newLink(): Link = Link().also { links += it }

        fun waitUntil(what: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
            val until = System.nanoTime() + timeoutMs * 1_000_000
            while (!condition()) {
                if (System.nanoTime() > until) throw AssertionError("timed out waiting for: $what")
                Thread.sleep(2)
            }
        }

        /** The golden handshake up to CONFIRM_ACK, every notification completed with status 0. */
        fun secure() {
            assertTrue(engine.begin(link, 247, vector("psk_hex"), 1L))
            engine.subscribed(1L)
            waitUntil("HELLO") { link.calls.size == 1 && link.calls[0].returned }
            assertEquals(vectorHex("hello_hex"), toHex(link.calls[0].value))
            engine.onNotificationSent(1L, 0)
            engine.onWrite(1L, vector("hello_ack_hex"))
            waitUntil("CONFIRM") { link.calls.size == 2 && link.calls[1].returned }
            assertEquals(vectorHex("confirm_hex"), toHex(link.calls[1].value))
            engine.onNotificationSent(1L, 0)
            engine.onWrite(1L, vector("confirm_ack_hex"))
        }

        /** [secure], then the fixture's inner HELLO (offer 219) and its one-frame HELLO_ACK, completed. */
        fun ready() {
            assertEquals("reference sealer", vectorHex("phone_to_p4_data_hex"), toHex(toP4(1, vector("phone_to_p4_plaintext_hello_ack_hex"))))
            secure()
            engine.onWrite(1L, vector("p4_to_phone_data_hex"))
            waitUntil("HELLO_ACK") { link.calls.size == 3 && link.calls[2].returned }
            assertEquals(vectorHex("phone_to_p4_data_hex"), toHex(link.calls[2].value))
            engine.onNotificationSent(1L, 0)
            waitUntil("ready") { engine.currentStats.stage == "ready" }
        }

        /** The P4's inner HELLO offering [offer], framed at that limit, one DATA value (counter 1, 2, …) per fragment. */
        fun sendFragmentedHello(offer: Int) {
            val frames = Device(offer).frames(Op.HELLO, 0, helloBody(offer, nonce = hex("0102030405060708")))
            for ((i, f) in frames.withIndex()) engine.onWrite(1L, fromP4(i + 1L, f))
        }
    }

    // ---- Reference crypto (independent of PipeSecureChannel) ------------------------------------

    private fun vectorHex(name: String): String = toHex(vector(name))

    private fun ms(fromNanos: Long, toNanos: Long): Double = (toNanos - fromNanos) / 1_000_000.0

    /** `10 ‖ counter(u64 BE) ‖ ChaCha20-Poly1305(key, dir(u32 BE) ‖ counter(u64 BE), no AAD)`. */
    private fun sealData(key: ByteArray, dir: Int, counter: Long, plain: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(plain.size))
        c.doFinal(out, c.processBytes(plain, 0, plain.size, out, 0))
        return ByteBuffer.allocate(9).put(0x10).putLong(counter).array() + out
    }

    private fun toP4(counter: Long, inner: ByteArray) = sealData(vector("phone_to_p4_key_hex"), 0, counter, inner)

    private fun fromP4(counter: Long, inner: ByteArray) = sealData(vector("p4_to_phone_key_hex"), 1, counter, inner)
}
