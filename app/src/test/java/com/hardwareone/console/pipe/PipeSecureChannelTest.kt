package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeSecureChannel.Failure
import com.hardwareone.console.pipe.PipeSecureChannel.Rx
import com.hardwareone.console.pipe.PipeSecureChannel.State
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/**
 * HW1-PIPE-v1, phone side, against the firmware's golden transcript (copied verbatim from
 * hardwareone-idf `tools/ble_secure/fixtures/phone_secure_channel_v1_vectors.json`) and the
 * negative cases of `test/host/phone_secure_channel_harness.cpp`, seen from the initiator.
 *
 * Expected values come from the JSON or from the reference helpers at the bottom, which use
 * BouncyCastle's low-level X25519, HMac and ChaCha20-Poly1305 directly and build nonces with a
 * ByteBuffer. Nothing here calls PipeSecureChannel's own nonce or key schedule for an expectation.
 *
 * Harness cases with no initiator counterpart: CONFIRM sent before the local reply completion
 * (`replySubmitted`) is a responder-only gate.
 */
class PipeSecureChannelTest {
    private val vectors: JSONObject =
        javaClass.getResourceAsStream("/pipe/phone_secure_channel_v1_vectors.json")!!
            .use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }

    private fun fx(name: String): ByteArray = hex(vectors.getString(name))

    private val psk get() = fx("psk_hex")
    private val p2dKey get() = fx("phone_to_p4_key_hex")
    private val d2pKey get() = fx("p4_to_phone_key_hex")

    // ---- Channel set-up through the public API, with the fixture's entropy ---------------------

    private fun channel(key: ByteArray = psk) =
        PipeSecureChannel(key, FakeRandom(fx("phone_nonce_hex")), fx("phone_private_key_hex"))

    private fun awaitingHelloAck() = channel().also { it.hello() }

    private fun awaitingConfirmAck() = awaitingHelloAck().also { assertReply(it.receive(fx("hello_ack_hex"))) }

    private fun established() = awaitingConfirmAck().also {
        assertSame(Rx.Established, it.receive(fx("confirm_ack_hex")))
        assertEquals(State.ESTABLISHED, it.state)
    }

    private fun assertReply(rx: Rx): ByteArray {
        assertTrue("expected Reply, got $rx", rx is Rx.Reply)
        return (rx as Rx.Reply).value
    }

    private fun assertData(rx: Rx): ByteArray {
        assertTrue("expected Data, got $rx", rx is Rx.Data)
        return (rx as Rx.Data).plaintext
    }

    private fun assertInvalid(ch: PipeSecureChannel, rx: Rx, failure: Failure = Failure.AUTH) {
        assertSame(Rx.Invalid, rx)
        assertEquals(State.FAILED, ch.state)
        assertEquals(failure, ch.failure)
        assertWiped(ch)
    }

    /**
     * Every byte array the channel holds is zero. Call it straight after the failing call: a later
     * receive() fails (and wipes) again, which would hide a path that skipped the wipe.
     */
    private fun assertWiped(ch: PipeSecureChannel, message: String = "") {
        val held = heldFields(ch)
        assertTrue("$message: expected the key arrays", held.size >= 4)
        for ((name, array) in held) assertTrue("$message: $name not zeroed", array.all { it == 0.toByte() })
    }

    /** A receive() or seal() result as text, so each table row can name what it must return. */
    private fun outcome(result: Any?): String = when (result) {
        null -> "null"
        is Rx.Rejected -> "Rejected(${result.reason})"
        is Rx.Reply, is Rx.Data -> result.javaClass.simpleName
        else -> result.toString()
    }

    /** Proves a failed channel stays failed: the next well-formed P4 frame is refused too. */
    private fun assertTerminal(ch: PipeSecureChannel, failure: Failure = Failure.AUTH) {
        assertSame(Rx.Invalid, ch.receive(p4Frame(1_000, ascii("late"))))
        assertNull(ch.seal(ascii("late")))
        assertEquals(State.FAILED, ch.state)
        assertEquals(failure, ch.failure)
    }

    private fun flipped(bytes: ByteArray, index: Int, mask: Int = 1) =
        bytes.copyOf().also { it[index] = (it[index].toInt() xor mask).toByte() }

    // ---- Golden transcript ---------------------------------------------------------------------

    @Test
    fun theGoldenTranscriptMatchesTheFirmwareFixtureByteForByte() {
        val random = FakeRandom(fx("phone_nonce_hex"))
        val ch = PipeSecureChannel(psk, random, fx("phone_private_key_hex"))
        assertEquals(State.NEW, ch.state)

        val hello = ch.hello()
        assertEquals(vectors.getString("hello_hex"), toHex(hello))
        assertEquals(PipeSecureChannel.HELLO_SIZE, hello.size)
        assertEquals(vectors.getString("phone_public_key_hex"), toHex(hello.copyOfRange(1, 33)))
        assertEquals(vectors.getString("phone_nonce_hex"), toHex(hello.copyOfRange(33, 49)))
        assertEquals("an injected key leaves only the nonce to draw", 1, random.calls)
        assertEquals(State.AWAIT_HELLO_ACK, ch.state)

        val confirm = assertReply(ch.receive(fx("hello_ack_hex")))
        assertEquals(vectors.getString("confirm_hex"), toHex(confirm))
        assertEquals(PipeSecureChannel.CONFIRM_SIZE, confirm.size)
        assertEquals(State.AWAIT_CONFIRM_ACK, ch.state)

        assertSame(Rx.Established, ch.receive(fx("confirm_ack_hex")))
        assertEquals(State.ESTABLISHED, ch.state)
        assertNull(ch.failure)

        val inbound = fx("p4_to_phone_data_hex")
        assertEquals(vectors.getString("p4_to_phone_plaintext_hello_hex"), toHex(assertData(ch.receive(inbound))))
        val outbound = ch.seal(fx("phone_to_p4_plaintext_hello_ack_hex"))!!
        assertEquals(vectors.getString("phone_to_p4_data_hex"), toHex(outbound))

        // Both first DATA frames use counter 1, and the overhead is exactly 25 bytes.
        val counter = vectors.getLong("data_counter_each_direction")
        assertEquals(counter, ByteBuffer.wrap(inbound, 1, 8).long)
        assertEquals(counter, ByteBuffer.wrap(outbound, 1, 8).long)
        assertEquals(fx("p4_to_phone_plaintext_hello_hex").size + 25, inbound.size)
        assertEquals(fx("phone_to_p4_plaintext_hello_ack_hex").size + 25, outbound.size)

        // The next send is counter 2 under the fixture phone → P4 key.
        val next = ch.seal(ascii("next"))!!
        assertEquals(2L, ByteBuffer.wrap(next, 1, 8).long)
        assertArrayEquals(ascii("next"), phoneOpen(next, expectedCounter = 2))
    }

    @Test
    fun aGeneratedKeyIsDrawnBeforeTheNonce() {
        val random = FakeRandom(fx("phone_private_key_hex"), fx("phone_nonce_hex"))
        val ch = PipeSecureChannel(psk, random)
        assertEquals(vectors.getString("hello_hex"), toHex(ch.hello()))
        assertEquals(2, random.calls)
        assertEquals(vectors.getString("confirm_hex"), toHex(assertReply(ch.receive(fx("hello_ack_hex")))))
        assertSame(Rx.Established, ch.receive(fx("confirm_ack_hex")))
    }

    @Test
    fun theFixtureKeysRecomputeIndependently() {
        assertEquals(vectors.getString("label_ascii"), PipeSecureChannel.LABEL)
        val phonePriv = fx("phone_private_key_hex")
        val devicePriv = fx("device_private_key_hex")
        assertEquals(vectors.getString("phone_public_key_hex"), toHex(refPublic(phonePriv)))
        assertEquals(vectors.getString("device_public_key_hex"), toHex(refPublic(devicePriv)))
        assertEquals(vectors.getString("device_public_key_hex"), toHex(fx("hello_ack_hex").copyOfRange(1, 33)))
        assertEquals(vectors.getString("device_nonce_hex"), toHex(fx("hello_ack_hex").copyOfRange(33, 49)))

        val shared = refShared(phonePriv, fx("device_public_key_hex"))
        assertEquals(vectors.getString("shared_secret_hex"), toHex(shared))
        assertEquals(vectors.getString("shared_secret_hex"), toHex(refShared(devicePriv, fx("phone_public_key_hex"))))

        val keys = refKeys(shared, psk, PipeSecureChannel.LABEL)
        assertEquals(vectors.getString("phone_to_p4_key_hex"), toHex(keys.copyOfRange(0, 32)))
        assertEquals(vectors.getString("p4_to_phone_key_hex"), toHex(keys.copyOfRange(32, 64)))

        // The reference AEAD and framing reproduce every libsodium-made frame in the fixture.
        assertEquals(vectors.getString("confirm_hex"), toHex(confirmFrame(p2dKey)))
        assertEquals(vectors.getString("confirm_ack_hex"), toHex(confirmAckFrame(d2pKey)))
        assertEquals(vectors.getString("p4_to_phone_data_hex"), toHex(p4Frame(1, fx("p4_to_phone_plaintext_hello_hex"))))
        assertArrayEquals(fx("phone_to_p4_plaintext_hello_ack_hex"), phoneOpen(fx("phone_to_p4_data_hex"), expectedCounter = 1))
    }

    @Test
    fun theReferenceHkdfMatchesRfc5869() {
        // RFC 5869 test case 1, as pinned by the firmware harness for scHkdf.
        val ikm = bytes(22, 0x0b)
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(refHkdf(ikm, salt, info, 42)),
        )
    }

    @Test
    fun noncesAreBigEndianDirectionThenCounter() {
        // scNonce(1, 0x0102030405060708) from the firmware harness.
        assertEquals("000000010102030405060708", toHex(PipeSecureChannel.nonce(1, 0x0102030405060708L)))
        assertEquals("00000000ffffffffffffffff", toHex(PipeSecureChannel.nonce(0, -1L)))
        assertEquals("000000018000000000000000", toHex(PipeSecureChannel.nonce(1, Long.MIN_VALUE)))
        assertEquals(0, PipeSecureChannel.DIR_P2D)
        assertEquals(1, PipeSecureChannel.DIR_D2P)
        assertEquals(25, PipeSecureChannel.OVERHEAD)
        assertEquals(49, PipeSecureChannel.HELLO_SIZE)
        assertEquals(19, PipeSecureChannel.CONFIRM_SIZE)
        assertEquals("HW1-PIPE-v1", PipeSecureChannel.LABEL)
    }

    // ---- Handshake: wrong keys -----------------------------------------------------------------

    @Test
    fun aWrongPskSendsADifferentConfirmAndFailsAtConfirmAck() {
        val wrong = ByteArray(32).also { it[0] = 2 } // the harness's wrong PSK
        val ch = channel(wrong)
        ch.hello()
        val confirm = assertReply(ch.receive(fx("hello_ack_hex")))
        val wrongKeys = refKeys(fx("shared_secret_hex"), wrong, PipeSecureChannel.LABEL)
        assertEquals(toHex(confirmFrame(wrongKeys.copyOfRange(0, 32))), toHex(confirm))
        assertNotEquals(vectors.getString("confirm_hex"), toHex(confirm))
        assertInvalid(ch, ch.receive(fx("confirm_ack_hex")))
        assertTerminal(ch)
    }

    @Test
    fun keysDerivedWithTheConsoleLabelAreRejected() {
        val console = refKeys(fx("shared_secret_hex"), psk, "HW1-SC-v1")
        // The phone's own CONFIRM is not the console one either.
        assertNotEquals(toHex(confirmFrame(console.copyOfRange(0, 32))), vectors.getString("confirm_hex"))
        val consoleAck = confirmAckFrame(console.copyOfRange(32, 64))
        assertNotEquals(vectors.getString("confirm_ack_hex"), toHex(consoleAck))
        val ch = awaitingConfirmAck()
        assertInvalid(ch, ch.receive(consoleAck))
        assertTerminal(ch)
    }

    @Test
    fun confirmAckMustOpenToExactlyOkUnderTheP4Key() {
        val cases = listOf(
            "plaintext OK" to (byteArrayOf(4) + refAead(true, d2pKey, refNonce(1, 0), ascii("OK"))),
            "phone-direction nonce" to (byteArrayOf(4) + refAead(true, d2pKey, refNonce(0, 0), ascii("ok"))),
            "phone → P4 key" to confirmAckFrame(p2dKey),
            "reflected CONFIRM" to (byteArrayOf(4) + fx("confirm_hex").copyOfRange(1, 19)),
        )
        for ((name, frame) in cases) {
            assertEquals(name, 19, frame.size)
            val ch = awaitingConfirmAck()
            assertSame(name, Rx.Invalid, ch.receive(frame))
            assertEquals(name, State.FAILED, ch.state)
            assertEquals(name, Failure.AUTH, ch.failure)
            assertWiped(ch, name)
        }
    }

    @Test
    fun everyFlippedByteOfConfirmAckIsRejected() {
        val ack = fx("confirm_ack_hex")
        for (mask in intArrayOf(0x01, 0x80)) {
            for (i in ack.indices) {
                val ch = awaitingConfirmAck()
                assertSame("byte $i ^ $mask", Rx.Invalid, ch.receive(flipped(ack, i, mask)))
                assertEquals(State.FAILED, ch.state)
                assertEquals(Failure.AUTH, ch.failure)
                assertWiped(ch, "byte $i ^ $mask")
                assertTerminal(ch)
            }
        }
    }

    // ---- Handshake: framing --------------------------------------------------------------------

    @Test
    fun helloAckMustBeExactly49Bytes() {
        val ack = fx("hello_ack_hex")
        for (frame in listOf(ack.copyOf(48), ack + byteArrayOf(0), ByteArray(0), byteArrayOf(2))) {
            val ch = awaitingHelloAck()
            assertInvalid(ch, ch.receive(frame))
            assertTerminal(ch)
        }
    }

    @Test
    fun confirmAckMustBeExactly19Bytes() {
        val ack = fx("confirm_ack_hex")
        for (frame in listOf(ack.copyOf(18), ack + byteArrayOf(0), ByteArray(0), byteArrayOf(4))) {
            val ch = awaitingConfirmAck()
            assertInvalid(ch, ch.receive(frame))
            assertTerminal(ch)
        }
    }

    @Test
    fun aWrongTypeInEitherHandshakePhaseIsInvalid() {
        val helloAck = fx("hello_ack_hex")
        val confirmAck = fx("confirm_ack_hex")
        val dataFrame = fx("p4_to_phone_data_hex")
        val whileAwaitingHelloAck = listOf(
            helloAck.copyOf().also { it[0] = 0x01 },
            helloAck.copyOf().also { it[0] = 0x03 },
            helloAck.copyOf().also { it[0] = 0x04 },
            helloAck.copyOf().also { it[0] = 0x05 }, // REJECT type, but not 2 bytes
            helloAck.copyOf().also { it[0] = 0x10 },
            fx("hello_hex"),
            confirmAck,
            dataFrame,
        )
        for (frame in whileAwaitingHelloAck) {
            val ch = awaitingHelloAck()
            assertInvalid(ch, ch.receive(frame))
        }
        val whileAwaitingConfirmAck = listOf(
            confirmAck.copyOf().also { it[0] = 0x02 },
            confirmAck.copyOf().also { it[0] = 0x03 },
            confirmAck.copyOf().also { it[0] = 0x05 },
            confirmAck.copyOf().also { it[0] = 0x10 },
            fx("confirm_hex"),
            helloAck, // a repeated HELLO_ACK
            dataFrame,
        )
        for (frame in whileAwaitingConfirmAck) {
            val ch = awaitingConfirmAck()
            assertInvalid(ch, ch.receive(frame))
        }
    }

    @Test
    fun lowOrderDevicePublicKeysFailWithoutThrowing() {
        val lowOrder = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000", // all-zero
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800", // order 8
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157", // order 8
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p - 1
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p (= 0)
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p + 1 (= 1)
        )
        for (point in lowOrder) {
            // The reference ladder confirms each point really yields an all-zero shared secret.
            assertEquals(point, "00".repeat(32), toHex(refShared(fx("phone_private_key_hex"), hex(point))))
            val frame = fx("hello_ack_hex").also { hex(point).copyInto(it, 1) }
            val ch = awaitingHelloAck()
            val rx = try {
                ch.receive(frame)
            } catch (e: Exception) {
                fail("receive threw for $point: $e"); return
            }
            assertInvalid(ch, rx)
            assertTerminal(ch)
        }
    }

    // ---- REJECT --------------------------------------------------------------------------------

    @Test
    fun rejectInEitherHandshakePhaseEndsTheChannel() {
        for (ch in listOf(awaitingHelloAck(), awaitingConfirmAck())) {
            val rx = ch.receive(hex("0502"))
            assertTrue("expected Rejected, got $rx", rx is Rx.Rejected)
            assertEquals(2, (rx as Rx.Rejected).reason)
            assertEquals(State.FAILED, ch.state)
            assertEquals(Failure.AUTH, ch.failure)
            assertWiped(ch, "REJECT")
            assertTerminal(ch)
        }
        // The reason is an unsigned byte.
        val high = awaitingHelloAck().receive(hex("05ff"))
        assertEquals(255, (high as Rx.Rejected).reason)
        // Only exactly two bytes are a REJECT.
        for (frame in listOf(hex("05"), hex("050200"))) {
            val ch = awaitingConfirmAck()
            assertInvalid(ch, ch.receive(frame))
        }
    }

    @Test
    fun rejectAfterEstablishmentIsInvalid() {
        val ch = established()
        assertInvalid(ch, ch.receive(hex("0502")))
        assertTerminal(ch)
    }

    // ---- Before establishment ------------------------------------------------------------------

    @Test
    fun dataBeforeEstablishmentIsInvalid() {
        val frame = fx("p4_to_phone_data_hex")
        for (ch in listOf(channel(), awaitingHelloAck(), awaitingConfirmAck())) {
            assertInvalid(ch, ch.receive(frame))
            assertTerminal(ch)
        }
        // A NEW channel accepts nothing at all, not even a well-formed HELLO_ACK.
        val fresh = channel()
        assertInvalid(fresh, fresh.receive(fx("hello_ack_hex")))
    }

    @Test
    fun sealBeforeEstablishmentReturnsNull() {
        val fresh = channel()
        assertNull(fresh.seal(ascii("early")))
        assertEquals(State.NEW, fresh.state)
        val hello = awaitingHelloAck()
        assertNull(hello.seal(ascii("early")))
        assertEquals(State.AWAIT_HELLO_ACK, hello.state)
        val confirm = awaitingConfirmAck()
        assertNull(confirm.seal(ascii("early")))
        assertEquals(State.AWAIT_CONFIRM_ACK, confirm.state)
        assertNull(confirm.failure)
    }

    @Test
    fun helloIsOnlyForANewChannel() {
        val ch = awaitingHelloAck()
        try {
            ch.hello()
            fail("a second hello() must throw")
        } catch (expected: IllegalStateException) {
        }
    }

    // ---- DATA counters -------------------------------------------------------------------------

    @Test
    fun counterZeroIsNeverAccepted() {
        val ch = established()
        assertInvalid(ch, ch.receive(p4Frame(0, ascii("zero"))))
    }

    @Test
    fun replayedAndRegressedCountersAreRejected() {
        val plain = byteArrayOf(1, 2, 3, 4)
        val replay = established()
        val first = p4Frame(1, plain)
        assertArrayEquals(plain, assertData(replay.receive(first)))
        assertInvalid(replay, replay.receive(first))
        assertTerminal(replay)

        val regress = established()
        assertArrayEquals(plain, assertData(regress.receive(p4Frame(5, plain))))
        assertInvalid(regress, regress.receive(p4Frame(3, plain)))
        assertTerminal(regress)
    }

    @Test
    fun countersMaySkipButNotGoBackAcrossTheGap() {
        val plain = byteArrayOf(1, 2, 3)
        val ch = established()
        assertArrayEquals(plain, assertData(ch.receive(p4Frame(7, plain))))
        assertArrayEquals(plain, assertData(ch.receive(p4Frame(9, plain))))
        assertInvalid(ch, ch.receive(p4Frame(8, plain)))
        assertTerminal(ch)
    }

    @Test
    fun receiveCountersCompareUnsigned() {
        val plain = ascii("u64")
        val ch = established()
        val ascending = longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE, U64_MAX - 1, U64_MAX) // 2^63−1, 2^63, 2^64−2, 2^64−1
        for (counter in ascending) {
            assertArrayEquals("counter ${counter.toULong()}", plain, assertData(ch.receive(p4Frame(counter, plain))))
        }
        assertInvalid(ch, ch.receive(p4Frame(U64_MAX, plain)))

        // Nothing can follow 2^64 − 1.
        for (next in longArrayOf(1, 5, Long.MAX_VALUE, Long.MIN_VALUE, U64_MAX - 1)) {
            val top = established()
            assertArrayEquals(plain, assertData(top.receive(p4Frame(U64_MAX, plain))))
            assertInvalid(top, top.receive(p4Frame(next, plain)))
        }
    }

    @Test
    fun afterTwoToTheSixtyThirdASmallCounterIsRejected() {
        val plain = ascii("sign")
        val ch = established()
        assertArrayEquals(plain, assertData(ch.receive(p4Frame(Long.MIN_VALUE, plain))))
        assertInvalid(ch, ch.receive(p4Frame(5, plain)))
    }

    // ---- DATA framing --------------------------------------------------------------------------

    @Test
    fun malformedDataAfterAnAcceptedFrameFailsTheChannel() {
        val plain = byteArrayOf(1, 2, 3, 4)
        val first = p4Frame(1, plain)
        val second = p4Frame(2, plain)
        val cases = listOf(
            "replay" to first,
            "tag flip" to flipped(second, second.size - 1),
            "ciphertext flip" to flipped(second, 9),
            "counter flip" to flipped(second, 8), // counter 3 with counter 2's tag
            "counter zeroed" to second.copyOf().also { it[8] = 0 },
            "plaintext bytes" to plain,
            "HELLO" to fx("hello_hex"),
            "HELLO_ACK" to fx("hello_ack_hex"),
            "CONFIRM_ACK" to fx("confirm_ack_hex"),
            "24 bytes" to second.copyOf(24),
            "type 0x11" to second.copyOf().also { it[0] = 0x11 },
            "type 0x00" to second.copyOf().also { it[0] = 0x00 },
            "empty" to ByteArray(0),
        )
        for ((name, frame) in cases) {
            val ch = established()
            assertArrayEquals(name, plain, assertData(ch.receive(first)))
            assertSame(name, Rx.Invalid, ch.receive(frame))
            assertEquals(name, State.FAILED, ch.state)
            assertEquals(name, Failure.AUTH, ch.failure)
            assertWiped(ch, name)
            // Even the next correct frame is refused once failed.
            assertSame(name, Rx.Invalid, ch.receive(second))
            assertNull(name, ch.seal(plain))
        }
    }

    @Test
    fun aHandshakeFailureLeavesTheChannelFailed() {
        val ch = awaitingHelloAck()
        assertInvalid(ch, ch.receive(fx("hello_ack_hex").copyOf(48)))
        // The real HELLO_ACK, CONFIRM_ACK and DATA are all refused afterwards.
        assertSame(Rx.Invalid, ch.receive(fx("hello_ack_hex")))
        assertSame(Rx.Invalid, ch.receive(fx("confirm_ack_hex")))
        assertSame(Rx.Invalid, ch.receive(fx("p4_to_phone_data_hex")))
        assertEquals(State.FAILED, ch.state)
        assertEquals(Failure.AUTH, ch.failure)
        assertNull(ch.seal(ascii("x")))
    }

    @Test
    fun payloadsRoundTripBothWaysAtHarnessSizes() {
        for (n in intArrayOf(0, 36, 216, 219, 487)) {
            val plain = bytes(n, 0x51)
            val ch = established()
            assertArrayEquals("rx $n", plain, assertData(ch.receive(p4Frame(1, plain))))
            val sealed = ch.seal(plain)!!
            assertEquals(n + PipeSecureChannel.OVERHEAD, sealed.size)
            assertArrayEquals("tx $n", plain, phoneOpen(sealed, expectedCounter = 1))
            ch.wipe()
            assertEquals(State.FAILED, ch.state)
            assertNull(ch.seal(byteArrayOf(1)))
        }
    }

    // ---- TX exhaustion and wipe ----------------------------------------------------------------

    @Test
    fun theTxCounterStopsBeforeTwoToTheSixtyFourMinusOne() {
        val plain = ascii("last")
        val ch = established()
        ch.setCountersForTest(tx = U64_MAX - 1, rx = 0)
        val last = ch.seal(plain)!!
        assertEquals("10" + "fffffffffffffffe" + toHex(refAead(true, p2dKey, refNonce(0, U64_MAX - 1), plain)), toHex(last))
        assertNull(ch.seal(plain))
        assertEquals(State.FAILED, ch.state)
        assertEquals(Failure.EXHAUSTED, ch.failure)
        assertWiped(ch, "EXHAUSTED")
        // Exhaustion is not relabelled as an auth failure by later input.
        assertTerminal(ch, Failure.EXHAUSTED)
    }

    @Test
    fun wipeZeroesEverythingHeldAndEndsTheChannel() {
        val callerPsk = psk
        val callerKey = fx("phone_private_key_hex")
        val ch = PipeSecureChannel(callerPsk, FakeRandom(fx("phone_nonce_hex")), callerKey)
        // Both inputs are copies: changing the caller's arrays changes nothing.
        callerPsk.fill(0x33)
        callerKey.fill(0x44)
        ch.hello()
        assertEquals(vectors.getString("confirm_hex"), toHex(assertReply(ch.receive(fx("hello_ack_hex")))))
        assertSame(Rx.Established, ch.receive(fx("confirm_ack_hex")))

        val held = heldArrays(ch)
        assertTrue("expected key arrays", held.size >= 4)
        assertTrue(held.any { it.contentEquals(psk) })
        assertTrue(held.any { it.contentEquals(d2pKey) })

        ch.wipe()
        assertEquals(State.FAILED, ch.state)
        assertNull("wipe() leaves failure unset", ch.failure)
        assertWiped(ch, "wipe()")
        assertNull(ch.seal(ascii("after wipe")))
        assertTrue(callerPsk.all { it == 0x33.toByte() })
        // A receive on the wiped channel records an auth failure.
        assertSame(Rx.Invalid, ch.receive(p4Frame(1, ascii("x"))))
        assertEquals(Failure.AUTH, ch.failure)
    }

    @Test
    fun thePrivateKeyAndSharedSecretAreNotKeptAfterHelloAck() {
        val ch = awaitingConfirmAck()
        val held = heldArrays(ch)
        for (secret in listOf(fx("phone_private_key_hex"), fx("shared_secret_hex"))) {
            assertFalse(held.any { it.contentEquals(secret) })
        }
    }

    /** One way into FAILED: [act] on a channel from [setup] must return [expect] and wipe at once. */
    private class FailPath(
        val name: String,
        val expect: String,
        val failure: Failure,
        val setup: () -> PipeSecureChannel,
        val act: (PipeSecureChannel) -> Any?,
    )

    @Test
    fun everyMoveToFailedWipesTheKeysAtOnce() {
        val helloAck = fx("hello_ack_hex")
        val confirmAck = fx("confirm_ack_hex")
        val first = p4Frame(1, ascii("x"))
        val lowOrder = { point: String -> helloAck.copyOf().also { hex(point).copyInto(it, 1) } }
        val paths = listOf(
            FailPath("NEW: any frame", "Invalid", Failure.AUTH, { channel() }) { it.receive(helloAck) },
            FailPath("HELLO_ACK: REJECT", "Rejected(2)", Failure.AUTH, ::awaitingHelloAck) { it.receive(hex("0502")) },
            FailPath("HELLO_ACK: 48 bytes", "Invalid", Failure.AUTH, ::awaitingHelloAck) { it.receive(helloAck.copyOf(48)) },
            FailPath("HELLO_ACK: type 04", "Invalid", Failure.AUTH, ::awaitingHelloAck) {
                it.receive(helloAck.copyOf().also { f -> f[0] = 0x04 })
            },
            FailPath("HELLO_ACK: all-zero key", "Invalid", Failure.AUTH, ::awaitingHelloAck) {
                it.receive(lowOrder("00".repeat(32)))
            },
            FailPath("HELLO_ACK: order-8 key", "Invalid", Failure.AUTH, ::awaitingHelloAck) {
                it.receive(lowOrder("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"))
            },
            FailPath("CONFIRM_ACK: REJECT", "Rejected(2)", Failure.AUTH, ::awaitingConfirmAck) { it.receive(hex("0502")) },
            FailPath("CONFIRM_ACK: 18 bytes", "Invalid", Failure.AUTH, ::awaitingConfirmAck) {
                it.receive(confirmAck.copyOf(18))
            },
            FailPath("CONFIRM_ACK: type 02", "Invalid", Failure.AUTH, ::awaitingConfirmAck) {
                it.receive(confirmAck.copyOf().also { f -> f[0] = 0x02 })
            },
            FailPath("CONFIRM_ACK: tag flip", "Invalid", Failure.AUTH, ::awaitingConfirmAck) {
                it.receive(flipped(confirmAck, confirmAck.size - 1))
            },
            FailPath("ESTABLISHED: REJECT", "Invalid", Failure.AUTH, ::established) { it.receive(hex("0502")) },
            FailPath("ESTABLISHED: tag flip", "Invalid", Failure.AUTH, ::established) {
                it.receive(flipped(first, first.size - 1))
            },
            FailPath("ESTABLISHED: replay", "Invalid", Failure.AUTH, { established().also { assertData(it.receive(first)) } }) {
                it.receive(first)
            },
            FailPath("ESTABLISHED: 24 bytes", "Invalid", Failure.AUTH, ::established) { it.receive(first.copyOf(24)) },
            FailPath("ESTABLISHED: type 11", "Invalid", Failure.AUTH, ::established) {
                it.receive(first.copyOf().also { f -> f[0] = 0x11 })
            },
            FailPath("TX exhausted", "null", Failure.EXHAUSTED, {
                established().also { it.setCountersForTest(tx = U64_MAX, rx = 0) }
            }) { it.seal(ascii("x")) },
        )
        for (path in paths) {
            val ch = path.setup()
            assertTrue("${path.name}: the PSK is held before", heldArrays(ch).any { it.contentEquals(psk) })
            assertEquals(path.name, path.expect, outcome(path.act(ch)))
            assertEquals(path.name, State.FAILED, ch.state)
            assertEquals(path.name, path.failure, ch.failure)
            assertWiped(ch, path.name)
        }
    }

    // ---- Reference crypto (BouncyCastle primitives, independent of PipeSecureChannel) ----------

    /** Every instance byte-array field of [ch], by name. */
    private fun heldFields(ch: PipeSecureChannel): List<Pair<String, ByteArray>> =
        PipeSecureChannel::class.java.declaredFields
            .filter { it.type == ByteArray::class.java && !Modifier.isStatic(it.modifiers) }
            .map { it.isAccessible = true; it.name to it.get(ch) as ByteArray }

    private fun heldArrays(ch: PipeSecureChannel): List<ByteArray> = heldFields(ch).map { it.second }

    private fun refNonce(dir: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()

    private fun refPublic(priv: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(priv, 0, it, 0) }

    /** The raw RFC 7748 ladder: no all-zero check, so low-order inputs return zeros. */
    private fun refShared(priv: ByteArray, pub: ByteArray): ByteArray =
        ByteArray(32).also { X25519.scalarMult(priv, 0, pub, 0, it, 0) }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (part in parts) mac.update(part, 0, part.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    /** RFC 5869 extract-then-expand over HMAC-SHA256. */
    private fun refHkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            block = hmac(prk, block, info, byteArrayOf(i.toByte()))
            out.write(block)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** HW1-PIPE-v1 key schedule from the shared secret: kP2D ‖ kD2P. */
    private fun refKeys(shared: ByteArray, key: ByteArray, label: String): ByteArray =
        refHkdf(shared + key, fx("phone_nonce_hex") + fx("device_nonce_hex"), ascii(label), 64)

    private fun refAead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    private fun confirmFrame(p2d: ByteArray) = byteArrayOf(3) + refAead(true, p2d, refNonce(0, 0), ascii("ok"))

    private fun confirmAckFrame(d2p: ByteArray) = byteArrayOf(4) + refAead(true, d2p, refNonce(1, 0), ascii("ok"))

    /** A P4 → phone DATA frame under the fixture P4 → phone key. */
    private fun p4Frame(counter: Long, plain: ByteArray): ByteArray =
        ByteBuffer.allocate(9).put(0x10.toByte()).putLong(counter).array() + refAead(true, d2pKey, refNonce(1, counter), plain)

    /** Opens a phone → P4 DATA frame as the P4 would, checking its counter. */
    private fun phoneOpen(frame: ByteArray, expectedCounter: Long): ByteArray {
        assertEquals(0x10, frame[0].toInt())
        val counter = ByteBuffer.wrap(frame, 1, 8).long
        assertEquals(expectedCounter, counter)
        return refAead(false, p2dKey, refNonce(0, counter), frame.copyOfRange(9, frame.size))
    }

    private companion object {
        /** 0xFFFFFFFFFFFFFFFF as a raw Long. */
        val U64_MAX = 0xFFFF_FFFF_FFFF_FFFFuL.toLong()
    }
}
