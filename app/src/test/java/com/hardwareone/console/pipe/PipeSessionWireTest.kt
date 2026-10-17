package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeSession.Rx
import com.hardwareone.console.pipe.PipeSession.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Wire format and session-level rules of [PipeSession]: framing, fragmentation, sequence numbers,
 * HELLO, stream-0 control and fail-closed handling. Expected bytes come from the firmware P4 core
 * (BLE_PhonePipeCore.h), its host harnesses, the v1 snapshot in the Android handoff, the golden
 * frames of the protocol report (§6) and the firmware's secure-channel fixture JSON, never from
 * the phone's own encoder. Destinations are RFC 5737 / RFC 3849 documentation addresses and names
 * under .example.
 *
 * Stage 4 carries every inner frame as one HW1-PIPE-v1 secure DATA value, so a session is sized by
 * P = V − 25 (DECISIONS R8), passed in as `maxFrame`. The inner protocol itself is unchanged, so
 * the pre-stage-4 plaintext goldens still run, at the frame size they were recorded with (old
 * MTU 23 → frame 20, old MTU 503 → frame 500); the "Stage-4 sizing" group checks P itself.
 */
class PipeSessionWireTest {

    // ---- Fixed values -------------------------------------------------------------------------

    @Test
    fun opcodeAndErrorValuesMatchProtocolTables() {
        val ops = mapOf(
            PipeOp.HELLO to 0x01, PipeOp.HELLO_ACK to 0x02, PipeOp.OPEN to 0x10, PipeOp.OPEN_OK to 0x11,
            PipeOp.OPEN_ERR to 0x12, PipeOp.DATA to 0x20, PipeOp.CREDIT to 0x21, PipeOp.FIN to 0x22,
            PipeOp.CLOSE to 0x23, PipeOp.CLOSE_ACK to 0x24, PipeOp.RESET to 0x25, PipeOp.PING to 0x30,
            PipeOp.PONG to 0x31,
        )
        assertEquals(ops.size, PipeOp.entries.size)
        for ((op, code) in ops) assertEquals(op.name, code, op.code)
        assertEquals(SPEC_ERRORS.size, PipeError.entries.size)
        SPEC_ERRORS.forEachIndexed { i, error -> assertEquals(error.name, i + 1, error.code) }
    }

    @Test
    fun serviceAndCharacteristicUuidsMatchFirmware() {
        assertEquals("b7e10000-6f7d-4b2e-a8c4-3d9f5021c6a0", PipeWire.SERVICE_UUID.toString())
        assertEquals("b7e10001-6f7d-4b2e-a8c4-3d9f5021c6a0", PipeWire.RX_UUID.toString())
        assertEquals("b7e10002-6f7d-4b2e-a8c4-3d9f5021c6a0", PipeWire.TX_UUID.toString())
    }

    /** Firmware `valueLimit`: 0 below MTU 23, MTU−3 up to 515, then capped at 512. */
    @Test
    fun valueLimitFollowsAttMtuWith512Cap() {
        val expected = mapOf(
            0 to 0, 22 to 0, 23 to 20, 63 to 60, 64 to 61, 244 to 241, 247 to 244, 514 to 511, 515 to 512,
            516 to 512, 517 to 512,
        )
        for ((mtu, value) in expected) {
            assertEquals("MTU $mtu", value, PipeWire.valueLimit(mtu))
            assertEquals("MTU $mtu (contract)", value, specValue(mtu))
        }
    }

    /**
     * DECISIONS R8: an ATT MTU below 64 is refused, so there is no frame size; from 64 up,
     * P = min(MTU−3, 512) − 25 (25 = secure DATA type, counter and tag). Firmware-001 X1 table:
     * 64/244/247/517 → 36/216/219/487.
     */
    @Test
    fun frameLimitIsValueMinusSecureOverheadFromMtu64() {
        val expected = mapOf(
            0 to 0, 22 to 0, 23 to 0, 47 to 0, 63 to 0, 64 to 36, 65 to 37, 244 to 216, 247 to 219, 514 to 486,
            515 to 487, 516 to 487, 517 to 487, 0xffff to 487,
        )
        for ((mtu, frame) in expected) {
            assertEquals("MTU $mtu", frame, PipeWire.frameLimit(mtu))
            assertEquals("MTU $mtu (contract)", frame, specFrame(mtu))
        }
        assertEquals(64, PipeWire.MIN_MTU)
        assertEquals(25, PipeWire.SECURE_OVERHEAD)
    }

    @Test
    fun sessionFramesAtItsMaxFrameUntilHello() {
        for (maxFrame in listOf(20, 36, 61, 216, 219, 241, 244, 487, 500, 512)) {
            val s = PipeRig(maxFrame = maxFrame).session
            assertEquals("maxFrame $maxFrame", maxFrame, s.value)
            assertEquals("maxFrame $maxFrame", State.AWAIT_HELLO, s.state)
            assertNull("maxFrame $maxFrame", s.error)
        }
    }

    /**
     * maxFrame must lie in the inner max_value range 20..512 (the engine never builds a session for
     * MTU < 64, so P is 36..487 in practice). Outside it the session is FAILED from construction
     * with BAD_PROTOCOL: it accepts nothing and never sends, not even a HELLO_ACK.
     */
    @Test
    fun maxFrameBelow20OrAbove512FailsAtConstruction() {
        for (maxFrame in listOf(0, 19, 513, 517, 0xffff)) {
            val rig = PipeRig(maxFrame = maxFrame)
            val s = rig.session
            assertEquals("maxFrame $maxFrame", State.FAILED, s.state)
            assertEquals("maxFrame $maxFrame", PipeError.BAD_PROTOCOL, s.error)
            assertNotEquals("maxFrame $maxFrame", Rx.ACCEPTED, deliver(s, rawFrame(Op.HELLO, 0, 0, F_BOTH, helloBody(20))))
            assertEquals("maxFrame $maxFrame", 0, s.peek(ByteArray(512)))
            assertFalse("maxFrame $maxFrame", s.tick(1))
            assertFalse("maxFrame $maxFrame", s.helloAckSent)
            assertEquals("maxFrame $maxFrame", 0, s.liveStreams)
        }
    }

    /** 20 and 512 are both usable: the smallest session fragments everything, the largest takes 512-byte frames. */
    @Test
    fun maxFrame20And512AreTheInclusiveBounds() {
        val small = PipeRig(maxFrame = 20)
        assertEquals(State.AWAIT_HELLO, small.session.state)
        assertEquals(listOf(20, 20, 16), small.hello().map { it.size })
        small.open(1)
        assertEquals(Rx.ACCEPTED, deliver(small.session, small.device.frame(Op.DATA, 1, 0, bytes(8, 0x5a))))
        assertEquals(Rx.BAD_LENGTH, deliver(small.session, small.device.frame(Op.DATA, 1, 0, bytes(9, 0x5a))))
        assertFailed(small.session, PipeError.BAD_PROTOCOL)

        val large = PipeRig(maxFrame = 512)
        assertEquals(State.AWAIT_HELLO, large.session.state)
        assertEquals(listOf(32), large.hello().map { it.size })
        large.open(1)
        assertEquals(512, large.session.value)
        assertEquals(Rx.ACCEPTED, deliver(large.session, large.device.frame(Op.DATA, 1, 0, bytes(500, 0x5a))))
        assertEquals(1000, large.session.tcpRead(1, bytes(1000, 0x42), 0, 1000))
        assertEquals(listOf(512, 512), drain(large.session).map { it.size })
        assertEquals(Rx.BAD_LENGTH, deliver(large.session, large.device.frame(Op.DATA, 1, 0, bytes(501, 0x5a))))
        assertFailed(large.session, PipeError.BAD_PROTOCOL)
    }

    // ---- Golden frames (protocol report §6) ---------------------------------------------------

    /** The kit's device encoder reproduces the firmware-generated fixtures it stands in for. */
    @Test
    fun deviceKitReproducesFirmwareFixtures() {
        val narrow = Device(20)
        assertEquals(
            listOf(
                norm("01 01 0000 00000000 0800 01 00 | 0807060504030201"),
                norm("01 01 0000 01000000 0400 02 00 | 02001400"),
            ),
            narrow.frames(Op.HELLO, 0, helloBody(20)).map { toHex(it) },
        )
        assertEquals(
            listOf(
                norm("01 10 0100 02000000 0800 01 00 | 00040000bb010104"),
                norm("01 10 0100 03000000 0400 02 00 | c0000201"),
            ),
            narrow.frames(Op.OPEN, 1, openBody(ADDR_IPV4, TEST_IPV4, 443)).map { toHex(it) },
        )
        narrow.seq = 0x0a
        assertEquals(
            listOf(
                norm("01 20 0100 0a000000 0800 00 00 | 3333333333333333"),
                norm("01 20 0100 0b000000 0800 00 00 | 3333333333333333"),
                norm("01 20 0100 0c000000 0400 00 00 | 33333333"),
            ),
            narrow.frames(Op.DATA, 1, bytes(20, 0x33)).map { toHex(it) },
        )
        val wide = Device(500)
        wide.seq = 2
        assertEquals(
            listOf(norm("01 10 0200 02000000 1500 03 00 | 00040000 fb20 03 0d 72656c61792e6578616d706c65")),
            wide.frames(Op.OPEN, 2, openBody(ADDR_DNS, ascii("relay.example"), 8443)).map { toHex(it) },
        )
        wide.seq = 0x0c
        assertEquals(listOf(norm("01 24 0500 0c000000 0000 03 00")), wide.frames(Op.CLOSE_ACK, 5).map { toHex(it) })
    }

    /**
     * The firmware fixture `phone_secure_channel_v1_vectors.json` (ATT MTU 247, so P = 219): the
     * first inner plaintext each way inside the encrypted transcript. The P4's HELLO offers
     * max_value 219 (0x00db) with nonce bytes 01..08; with phone nonce bytes 11..18 the phone's
     * HELLO_ACK must be the fixture's `phone_to_p4_plaintext_hello_ack_hex`, byte for byte.
     */
    @Test
    fun fixtureInnerHelloGetsTheFixturesHelloAckByteForByte() {
        val mtu = vectorInt("att_mtu")
        assertEquals(247, mtu)
        val p = specFrame(mtu)
        assertEquals(219, p)
        val hello = vector("p4_to_phone_plaintext_hello_hex")
        assertEquals(norm("01 01 0000 00000000 0c00 03 00 | 0102030405060708 02 00 db00"), toHex(hello))
        val random = FakeRandom(hex("1112131415161718"))
        val rig = PipeRig(random = random, maxFrame = p)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, hello))
        val frames = drain(s)
        assertEquals(listOf(toHex(vector("phone_to_p4_plaintext_hello_ack_hex"))), frames.map { it.hexString })
        assertEquals(norm("01 02 0000 00000000 1400 03 00 | 0102030405060708 1112131415161718 02 00 db00"), frames.single().hexString)
        assertEquals("one RNG draw for the phone nonce", 1, random.calls)
        assertEquals(State.READY, s.state)
        assertEquals(219, s.value)
        assertTrue(s.helloAckSent)
        assertEquals(1L, s.rxSequence)
        assertEquals(1L, s.txSequence)
    }

    /**
     * §6 A: HELLO at the old MTU 503 (V=500), run at maxFrame 500; the HELLO_ACK carries the
     * injected phone nonce 0x1112131415161718.
     */
    @Test
    fun goldenHelloAtFrame500GetsExactHelloAck() {
        val rig = PipeRig(maxFrame = 500)
        assertEquals(Rx.ACCEPTED, deliver(rig.session, "01 01 0000 00000000 0c00 03 00 | 0807060504030201 02 00 f401"))
        assertDrains(rig.session, "01 02 0000 00000000 1400 03 00 | 0807060504030201 1817161514131211 02 00 f401")
        assertEquals(State.READY, rig.session.state)
        assertEquals(500, rig.session.value)
    }

    /** §6 B: at frame 20 (the old MTU 23) HELLO arrives as 8+4 bytes and HELLO_ACK leaves as 8+8+4 (flags 01, 00, 02). */
    @Test
    fun goldenHelloAtFrame20IsFragmentedBothWays() {
        val rig = PipeRig(maxFrame = 20)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 00000000 0800 01 00 | 0807060504030201"))
        assertDrains(s)
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 01000000 0400 02 00 | 02001400"))
        assertDrains(
            s,
            "01 02 0000 00000000 0800 01 00 | 0807060504030201",
            "01 02 0000 01000000 0800 00 00 | 1817161514131211",
            "01 02 0000 02000000 0400 02 00 | 02001400",
        )
        assertEquals(20, s.value)
    }

    /**
     * §6 A, the scripted session recorded at MTU 503 (frame 500). The fixture's IPv4 OPEN targets
     * 1.2.3.4; the RFC 5737 address 192.0.2.1 takes its place (only the four host bytes differ).
     * Phone frames are checked byte for byte at the phone's own sequence numbers.
     */
    @Test
    fun goldenSessionAtFrame500() {
        val rig = PipeRig(admit = { t -> if (t.type == ADDR_DNS) PipeError.TARGET_DENIED else null }, maxFrame = 500)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 00000000 0c00 03 00 | 0807060504030201 02 00 f401"))
        assertDrains(s, "01 02 0000 00000000 1400 03 00 | 0807060504030201 1817161514131211 02 00 f401")

        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0100 01000000 0c00 03 00 | 00040000 bb01 01 04 c0000201"))
        assertTrue(s.connected(1, 2))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0200 02000000 1500 03 00 | 00040000 fb20 03 0d 72656c61792e6578616d706c65"))
        assertDrains(
            s,
            "01 11 0100 01000000 0400 03 00 | 00040000",
            "01 12 0200 02000000 0200 03 00 | 0400",
        )
        assertEquals(2, rig.admitted.size)
        assertTarget(rig.admitted[0], ADDR_IPV4, TEST_IPV4, 443)
        assertTarget(rig.admitted[1], ADDR_DNS, ascii("relay.example"), 8443)

        assertEquals(Rx.ACCEPTED, deliver(s, "01 20 0100 03000000 0300 00 00 | aabbcc"))
        val down = ByteArray(16)
        assertEquals(3, s.downPeek(1, down))
        assertArrayEquals(hex("aabbcc"), down.copyOf(3))
        assertEquals(3, s.tcpRead(1, hex("aabbcc"), 0, 3))
        assertDrains(s, "01 20 0100 03000000 0300 00 00 | aabbcc")

        // The fixture's PING cookie 0xdeadbeef, echoed at the phone's next sequence number.
        assertEquals(Rx.ACCEPTED, deliver(s, "01 30 0000 04000000 0400 03 00 | efbeadde"))
        assertDrains(s, "01 31 0000 04000000 0400 03 00 | efbeadde")

        // Production CLOSE carries reason 1 (the P4's normal end of a request); then late copies.
        assertEquals(Rx.ACCEPTED, deliver(s, "01 23 0100 05000000 0200 03 00 | 0100"))
        assertDrains(s, "01 24 0100 05000000 0000 03 00")
        assertNull(s.find(1))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 23 0100 06000000 0200 03 00 | 0100"))
        assertDrains(s, "01 24 0100 06000000 0000 03 00")
        assertEquals(Rx.ACCEPTED, deliver(s, "01 20 0200 07000000 0100 00 00 | 01"))
        assertDrains(s)
        assertEquals(State.READY, s.state)
    }

    /**
     * §6 B, recorded at MTU 23 (frame 20), with the firmware harness `opened()` target
     * 192.0.2.1:443 (§6 C) for stream 1, IPv6 2001:db8::1 for stream 2 and relay.example:8443 for
     * stream 3.
     */
    @Test
    fun goldenSessionAtFrame20() {
        val rig = PipeRig(maxFrame = 20)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 00000000 0800 01 00 | 0807060504030201"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 01000000 0400 02 00 | 02001400"))
        assertDrains(
            s,
            "01 02 0000 00000000 0800 01 00 | 0807060504030201",
            "01 02 0000 01000000 0800 00 00 | 1817161514131211",
            "01 02 0000 02000000 0400 02 00 | 02001400",
        )

        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0100 02000000 0800 01 00 | 00040000bb010104"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0100 03000000 0400 02 00 | c0000201"))
        assertDrains(s) // nothing before the TCP connect
        assertTrue(s.connected(1, 2))
        assertDrains(s, "01 11 0100 03000000 0400 03 00 | 00040000")

        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0200 04000000 0800 01 00 | 00040000bb010210"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0200 05000000 0800 00 00 | 20010db800000000"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0200 06000000 0800 02 00 | 0000000000000001"))
        s.connectFailed(2, PipeError.CONNECT_FAILED)
        assertDrains(s, "01 12 0200 04000000 0200 03 00 | 0700")

        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0300 07000000 0800 01 00 | 00040000fb20030d"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0300 08000000 0800 00 00 | 72656c61792e6578"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0300 09000000 0500 02 00 | 616d706c65"))
        assertEquals(3, rig.admitted.size)
        assertTarget(rig.admitted[0], ADDR_IPV4, TEST_IPV4, 443)
        assertTarget(rig.admitted[1], ADDR_IPV6, hex("20010db8000000000000000000000001"), 443)
        assertTarget(rig.admitted[2], ADDR_DNS, ascii("relay.example"), 8443)

        assertEquals(Rx.ACCEPTED, deliver(s, "01 20 0100 0a000000 0800 00 00 | 3333333333333333"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 20 0100 0b000000 0800 00 00 | 3333333333333333"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 20 0100 0c000000 0400 00 00 | 33333333"))
        assertEquals(20, s.find(1)!!.downBuffered)
        assertEquals(State.READY, s.state)
    }

    /** §6 C, firmware HX `ready()`/`opened()`: P4 nonce bytes 01..08, phone nonce 09 00…00, frame 20 (old MTU 23). */
    @Test
    fun goldenPipeHarnessReadyAndOpenedFixture() {
        val rig = PipeRig(random = FakeRandom(hex("0900000000000000")), maxFrame = 20)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 00000000 0800 01 00 | 0102030405060708"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 01 0000 01000000 0400 02 00 | 02001400"))
        assertDrains(
            s,
            "01 02 0000 00000000 0800 01 00 | 0102030405060708",
            "01 02 0000 01000000 0800 00 00 | 0900000000000000",
            "01 02 0000 02000000 0400 02 00 | 02001400",
        )
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0100 02000000 0800 01 00 | 00040000bb010104"))
        assertEquals(Rx.ACCEPTED, deliver(s, "01 10 0100 03000000 0400 02 00 | c0000201"))
        assertTarget(rig.admitted.single(), ADDR_IPV4, TEST_IPV4, 443)
        assertTrue(s.connected(1, 1))
        assertDrains(s, "01 11 0100 03000000 0400 03 00 | 00040000")
    }

    /**
     * §6 C, firmware GHX: HELLO nonce 51…51 and phone nonce 22…22, now with max_value = P at each
     * stage-4 MTU (64 is the floor, 244 the G2+phone mode, 517 standalone). From P = 36 up a
     * 20-byte HELLO_ACK body always fits one frame, so the ACK is a single FIRST|LAST frame.
     */
    @Test
    fun goldenGattHarnessHelloAtEachStage4Mtu() {
        for ((mtu, p) in listOf(64 to 36, 244 to 216, 247 to 219, 517 to 487)) {
            assertEquals("MTU $mtu", p, specFrame(mtu))
            val rig = PipeRig(random = FakeRandom(hex("2222222222222222")), maxFrame = p)
            val hello = helloBody(p, nonce = hex("5151515151515151"))
            assertEquals("MTU $mtu", Rx.ACCEPTED, rig.device.send(rig.session, Op.HELLO, 0, hello))
            val ack = hex("5151515151515151 2222222222222222 02 00") + le16(p)
            val expected = Device(p).frames(Op.HELLO_ACK, 0, ack).map { toHex(it) }
            assertEquals("MTU $mtu", listOf(toHex(rawFrame(Op.HELLO_ACK, 0, 0, F_BOTH, ack))), expected)
            assertEquals("MTU $mtu", expected, drain(rig.session).map { it.hexString })
            assertEquals("MTU $mtu", p, rig.session.value)
        }
    }

    /** A malformed frame fails the session; the phone's last word is RESET(0) at its next unused sequence. */
    @Test
    fun failureFrameIsStreamZeroResetAtNextUnusedSequence() {
        val rig = ready()
        val s = rig.session
        val out = ByteArray(512)
        assertEquals("a healthy session has no failure frame", 0, s.failureFrame(out))
        // This PING's PONG is queued but never submitted, so its sequence number stays unused.
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        assertEquals(Rx.REJECTED, deliver(s, rig.device.frame(0x40, 0, F_BOTH)))
        assertFailed(s, PipeError.BAD_PROTOCOL)
        val n = s.failureFrame(out)
        assertEquals(norm("01 25 0000 01000000 0200 03 00 | 0100"), toHex(out.copyOf(n)))

        val bad = ready()
        val frame = rawFrame(Op.PING, 0, bad.device.seq, F_BOTH, hex("01020304"), version = 2)
        assertEquals(Rx.REJECTED, deliver(bad.session, frame))
        val m = bad.session.failureFrame(out)
        assertEquals(norm("01 25 0000 01000000 0200 03 00 | 0200"), toHex(out.copyOf(m)))
    }

    /** §3: at frame 20 a 253-byte DNS OPEN is 33 fragments (32×8 + 5) and reassembles intact. */
    @Test
    fun longestDnsOpenAtFrame20ReassemblesFrom33Fragments() {
        val rig = ready(20)
        val name = ("x".repeat(60) + ".").repeat(4) + "a.example"
        assertEquals(253, name.length)
        val frames = rig.device.frames(Op.OPEN, 1, openBody(ADDR_DNS, ascii(name), 8443))
        assertEquals(33, frames.size)
        for (f in frames) assertEquals(Rx.ACCEPTED, deliver(rig.session, f))
        assertTarget(rig.admitted.single(), ADDR_DNS, ascii(name), 8443)
        assertEquals(State.READY, rig.session.state)
    }

    // ---- Value negotiation --------------------------------------------------------------------

    /**
     * R9: HELLO_ACK's max_value is min(the phone's maxFrame, the offer), whatever the two are, and
     * the 20-byte body is framed at that choice: 20 + 20 + 16 bytes at 20, 24 + 20 at 24, one
     * 32-byte frame from 32 up.
     */
    @Test
    fun helloAckChoosesSmallerOfMaxFrameAndOffer() {
        // (phone maxFrame, device offer, chosen max_value, HELLO_ACK frame sizes)
        data class Case(val maxFrame: Int, val offer: Int, val chosen: Int, val frameSizes: List<Int>)
        val one = listOf(32)
        val cases = listOf(
            Case(20, 20, 20, listOf(20, 20, 16)), Case(61, 61, 61, one), Case(241, 241, 241, one),
            Case(244, 244, 244, one), Case(500, 500, 500, one), Case(512, 512, 512, one),
            Case(244, 500, 244, one), Case(61, 512, 61, one), Case(512, 100, 100, one),
            Case(512, 20, 20, listOf(20, 20, 16)), Case(24, 512, 24, listOf(24, 20)),
        )
        for ((maxFrame, offer, chosen, frameSizes) in cases) {
            val what = "maxFrame $maxFrame offer $offer"
            val rig = PipeRig(maxFrame = maxFrame)
            rig.device.value = offer // the device frames HELLO at its own limit, which it offers
            val frames = rig.hello(offer)
            val ack = messagesOf(frames).single()
            assertEquals(what, chosen, u16(ack.body, 18))
            assertEquals(what, 2, ack.body[16].toInt())
            assertEquals(what, 0, ack.body[17].toInt())
            assertEquals(what, chosen, rig.session.value)
            assertEquals(what, frameSizes, frames.map { it.size })
        }
    }

    /** HELLO_ACK is framed at the chosen limit even when the phone's own limit is larger (§6 B bytes at frame 512). */
    @Test
    fun helloAckIsFramedAtChosenValue() {
        val rig = PipeRig(maxFrame = 512)
        rig.device.value = 20
        assertEquals(Rx.ACCEPTED, rig.device.send(rig.session, Op.HELLO, 0, helloBody(20)))
        assertDrains(
            rig.session,
            "01 02 0000 00000000 0800 01 00 | 0807060504030201",
            "01 02 0000 01000000 0800 00 00 | 1817161514131211",
            "01 02 0000 02000000 0400 02 00 | 02001400",
        )
    }

    @Test
    fun inboundFramesAreLimitedToChosenValueAfterHelloAck() {
        val rig = PipeRig(maxFrame = 512)
        rig.device.value = 100
        rig.hello(100)
        rig.open(1)
        val s = rig.session
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.DATA, 1, 0, bytes(88, 7))))
        assertEquals(Rx.BAD_LENGTH, deliver(s, rig.device.frame(Op.DATA, 1, 0, bytes(89, 7))))
        assertFailed(s, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun outboundDataIsFramedAtChosenValue() {
        val rig = PipeRig(maxFrame = 512)
        rig.device.value = 100
        rig.hello(100)
        rig.open(1)
        val s = rig.session
        val up = ByteArray(200) { it.toByte() }
        assertEquals(200, s.tcpRead(1, up, 0, up.size))
        val frames = drain(s)
        assertEquals(listOf(100, 100, 36), frames.map { it.size })
        assertTrue(frames.all { it.op == Op.DATA && it.id == 1 && it.flags == 0 })
        assertEquals(listOf(2L, 3L, 4L), frames.map { it.seq })
        assertArrayEquals(up, frames.map { it.body }.reduce { a, b -> a + b })
    }

    @Test
    fun frameLargerThanLocalValueBeforeHelloIsBadLength() {
        val rig = PipeRig(maxFrame = 20)
        assertEquals(Rx.BAD_LENGTH, deliver(rig.session, rawFrame(Op.HELLO, 0, 0, F_BOTH, helloBody(20))))
        assertFailed(rig.session)
    }

    // ---- Stage-4 sizing (P = V − 25) -----------------------------------------------------------

    /** The engine builds each session with `PipeWire.frameLimit(mtu)`; at MTU 63 there is no session to build. */
    @Test
    fun stage4SessionStartsAtTheFrameLimitOfEachMtu() {
        for ((mtu, p) in listOf(64 to 36, 244 to 216, 247 to 219, 517 to 487)) {
            val s = PipeSession(PipeWire.frameLimit(mtu), 0, { null }, FakeRandom(PHONE_NONCE))
            assertEquals("MTU $mtu", p, s.value)
            assertEquals("MTU $mtu", State.AWAIT_HELLO, s.state)
        }
        val below = PipeSession(PipeWire.frameLimit(63), 0, { null }, FakeRandom(PHONE_NONCE))
        assertEquals(State.FAILED, below.state)
        assertEquals(PipeError.BAD_PROTOCOL, below.error)
    }

    /**
     * R9 at stage-4 sizes: HELLO_ACK carries min(P, offer). One link gives both ends the same P, so
     * the first four rows are the production cases; the rest show the rule holds whichever side is
     * smaller, down to the protocol floor of 20. Each ACK is checked as complete frames.
     */
    @Test
    fun stage4HelloAckChoosesMinOfFrameLimitAndOffer() {
        // (phone P, device offer, chosen max_value)
        val cases = listOf(
            Triple(36, 36, 36), Triple(216, 216, 216), Triple(219, 219, 219), Triple(487, 487, 487),
            Triple(487, 216, 216), Triple(216, 487, 216), Triple(36, 487, 36), Triple(219, 36, 36), Triple(487, 20, 20),
        )
        for ((p, offer, chosen) in cases) {
            val what = "P $p offer $offer"
            val rig = PipeRig(maxFrame = p)
            rig.device.value = offer
            val ack = P4_NONCE + PHONE_NONCE + hex("02 00") + le16(chosen)
            assertEquals(what, Device(chosen).frames(Op.HELLO_ACK, 0, ack).map { toHex(it) }, rig.hello(offer).map { it.hexString })
            assertEquals(what, chosen, rig.session.value)
            assertEquals(what, State.READY, rig.session.state)
        }
        // The 36-byte floor needs no HELLO_ACK fragmentation; only an offer below 32 brings it back.
        assertEquals(listOf(norm("01 02 0000 00000000 1400 03 00 | 0807060504030201 1817161514131211 02 00 2400")),
            PipeRig(maxFrame = 36).hello().map { it.hexString })
    }

    /**
     * At the smallest stage-4 frame (P = 36, 24 body bytes) the longest legal OPEN, a 253-byte DNS
     * name (8 + 253 = 261 body bytes), arrives as 11 fragments: 10 × 24 + 21. Nothing is admitted
     * before the LAST fragment, and the phone's OPEN_OK is one frame.
     */
    @Test
    fun stage4LongestDnsOpenAtFrame36ReassemblesFrom11Fragments() {
        val rig = ready(specFrame(64))
        val s = rig.session
        val name = ("x".repeat(60) + ".").repeat(4) + "a.example"
        assertEquals(253, name.length)
        val body = openBody(ADDR_DNS, ascii(name), 8443)
        assertEquals(261, body.size)
        val frames = rig.device.frames(Op.OPEN, 1, body).map { WireFrame(it) }
        assertEquals(List(10) { 36 } + listOf(33), frames.map { it.size })
        assertEquals(listOf(F_FIRST) + List(9) { 0 } + listOf(F_LAST), frames.map { it.flags })
        assertEquals((1L..11L).toList(), frames.map { it.seq })
        assertArrayEquals(body, frames.map { it.body }.reduce { a, b -> a + b })
        for ((i, f) in frames.withIndex()) {
            assertEquals("fragment $i", Rx.ACCEPTED, deliver(s, f.bytes))
            if (i < 10) assertTrue("admitted before LAST (fragment $i)", rig.admitted.isEmpty())
        }
        assertTarget(rig.admitted.single(), ADDR_DNS, ascii(name), 8443)
        assertEquals(12L, s.rxSequence)
        assertTrue(s.connected(1, 2))
        assertDrains(s, "01 11 0100 01000000 0400 03 00 | 00040000")
        assertEquals(State.READY, s.state)
    }

    /**
     * Every frame is at most P bytes both ways, so a DATA body is at most P − 12 (R8):
     * 24/204/207/475 at MTU 64/244/247/517. The bound applies from construction, before HELLO.
     */
    @Test
    fun stage4FramesNeverExceedTheFrameLimitAtEachMtu() {
        for ((mtu, p, body) in listOf(Triple(64, 36, 24), Triple(244, 216, 204), Triple(247, 219, 207), Triple(517, 487, 475))) {
            val what = "MTU $mtu"
            assertEquals(what, p, specFrame(mtu))
            val early = PipeRig(maxFrame = p)
            assertEquals(what, Rx.BAD_LENGTH, deliver(early.session, rawFrame(Op.DATA, 1, 0, 0, ByteArray(body + 1))))
            assertFailed(early.session, PipeError.BAD_PROTOCOL)

            val rig = opened(p)
            val s = rig.session
            assertEquals(what, p, s.value)
            val down = bytes(body, 0x5a)
            assertEquals(what, Rx.ACCEPTED, deliver(s, rig.device.frame(Op.DATA, 1, 0, down)))
            val copy = ByteArray(4096)
            assertEquals(what, body, s.downPeek(1, copy))
            assertArrayEquals(what, down, copy.copyOf(body))
            val up = ByteArray(1000) { (it * 13).toByte() }
            assertEquals(what, 1000, s.tcpRead(1, up, 0, up.size))
            val sent = drain(s)
            assertEquals(what, List(1000 / body) { p } + listOf(1000 % body + 12), sent.map { it.size })
            assertTrue(what, sent.all { it.op == Op.DATA && it.id == 1 && it.flags == 0 })
            assertArrayEquals(what, up, sent.map { it.body }.reduce { a, b -> a + b })
            assertEquals(what, Rx.BAD_LENGTH, deliver(s, rig.device.frame(Op.DATA, 1, 0, bytes(body + 1, 0x5a))))
            assertFailed(s, PipeError.BAD_PROTOCOL)
        }
    }

    // ---- Header validation --------------------------------------------------------------------

    @Test
    fun framesShorterThanHeaderAreBadLength() {
        val hello = rawFrame(Op.HELLO, 0, 0, F_BOTH, helloBody(512))
        for (n in 0 until 12) {
            val rig = PipeRig(maxFrame = 512)
            assertEquals("size $n", Rx.BAD_LENGTH, rig.session.receive(hello.copyOf(n), n, 1))
            assertFailed(rig.session)
        }
    }

    @Test
    fun lengthFieldMustEqualValueSizeMinusHeader() {
        for (declared in listOf(0, 11, 13, 0xffff)) {
            val rig = PipeRig(maxFrame = 512)
            val frame = rawFrame(Op.HELLO, 0, 0, F_BOTH, helloBody(512), length = declared)
            assertEquals("declared $declared", Rx.BAD_LENGTH, deliver(rig.session, frame))
            assertFailed(rig.session)
        }
        val rig = ready()
        val ping = rawFrame(Op.PING, 0, rig.device.seq, F_BOTH, hex("01020304"), length = 5)
        assertEquals(Rx.BAD_LENGTH, deliver(rig.session, ping))
        assertFailed(rig.session)
    }

    @Test
    fun versionTwoFailsWithUnsupportedVersion() {
        val early = PipeRig(maxFrame = 512)
        assertEquals(Rx.REJECTED, deliver(early.session, rawFrame(Op.HELLO, 0, 0, F_BOTH, helloBody(512), version = 2)))
        assertFailed(early.session, PipeError.UNSUPPORTED_VERSION)

        val rig = ready()
        val ping = rawFrame(Op.PING, 0, rig.device.seq, F_BOTH, hex("01020304"), version = 2)
        assertEquals(Rx.REJECTED, deliver(rig.session, ping))
        assertFailed(rig.session, PipeError.UNSUPPORTED_VERSION)

        for (version in listOf(0, 0xff)) {
            val other = ready()
            val frame = rawFrame(Op.PING, 0, other.device.seq, F_BOTH, hex("01020304"), version = version)
            assertEquals("version $version", Rx.REJECTED, deliver(other.session, frame))
            assertFailed(other.session)
        }
    }

    @Test
    fun nonzeroReservedByteIsRejected() {
        for (reserved in listOf(1, 0x80, 0xff)) {
            val rig = ready()
            val ping = rawFrame(Op.PING, 0, rig.device.seq, F_BOTH, hex("01020304"), reserved = reserved)
            assertEquals("reserved $reserved", Rx.REJECTED, deliver(rig.session, ping))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    @Test
    fun sequenceGapIsRejected() {
        val first = PipeRig(maxFrame = 512)
        assertEquals(Rx.REJECTED, deliver(first.session, rawFrame(Op.HELLO, 0, 1, F_BOTH, helloBody(512))))
        assertFailed(first.session, PipeError.BAD_PROTOCOL)

        val rig = ready()
        rig.device.seq++ // skip one number
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.PING, 0, hex("01020304")))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun duplicateSequenceIsRejected() {
        val rig = ready()
        val ping = rig.device.frame(Op.PING, 0, F_BOTH, hex("01020304"))
        assertEquals(Rx.ACCEPTED, deliver(rig.session, ping))
        assertEquals(Rx.REJECTED, deliver(rig.session, ping))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /** One direction-wide sequence numbers control, both streams and every fragment. */
    @Test
    fun sequenceIsDirectionWideAcrossStreamsAndFragments() {
        val rig = ready(20)
        rig.open(1)
        rig.open(2)
        assertEquals(6L, rig.device.seq) // HELLO 0-1, OPEN 2-3, OPEN 4-5
        assertEquals(6L, rig.session.rxSequence)
        assertEquals(Rx.ACCEPTED, deliver(rig.session, rig.device.frame(Op.DATA, 1, 0, bytes(3, 1))))
        // A per-stream count (stream 2's first DATA numbered 0) is a sequence violation.
        assertEquals(Rx.REJECTED, deliver(rig.session, rawFrame(Op.DATA, 2, 0, 0, bytes(3, 1))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun unknownOpcodesAreRejected() {
        val known = setOf(0x01, 0x02, 0x10, 0x11, 0x12, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x30, 0x31)
        for (op in 0..0xff) {
            if (op in known) continue
            for (id in listOf(0, 1)) {
                val rig = opened()
                assertEquals("op $op id $id", Rx.REJECTED, rig.device.send(rig.session, op, id, hex("00040000")))
                assertFailed(rig.session, PipeError.BAD_PROTOCOL)
            }
        }
    }

    @Test
    fun dataWithAnyFlagIsRejected() {
        for (flags in listOf(F_FIRST, F_LAST, F_BOTH, 0x04, 0x80)) {
            val rig = opened()
            assertEquals("flags $flags", Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.DATA, 1, flags, hex("aabbcc"))))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    @Test
    fun emptyDataIsRejected() {
        val rig = opened()
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.DATA, 1, 0)))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun dataOnStreamZeroIsRejected() {
        val rig = opened()
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.DATA, 0, 0, hex("aabbcc"))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun controlFlagsBeyondFirstAndLastAreRejected() {
        for (flags in listOf(0x04, 0x07, 0x08, 0x40, 0x80, 0x83, 0xff)) {
            val rig = ready()
            assertEquals("flags $flags", Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.PING, 0, flags, hex("01020304"))))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    @Test
    fun orphanContinuationIsRejected() {
        val rig = ready()
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.PING, 0, 0, hex("01020304"))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /**
     * A LAST fragment with no FIRST before it is malformed (firmware `!assembling_` check,
     * BLE_PhonePipeCore.h:209), whatever opcode it carries. In particular a lone LAST-only RESET
     * on stream 0 is not the device's RESET: the error is BAD_PROTOCOL and the phone still owes
     * its best-effort RESET.
     */
    @Test
    fun orphanLastIsRejected() {
        val rig = ready()
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.PING, 0, F_LAST, hex("01020304"))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)

        val early = PipeRig(maxFrame = 512)
        assertEquals(Rx.REJECTED, deliver(early.session, early.device.frame(Op.RESET, 0, F_LAST, le16(5))))
        assertFailed(early.session, PipeError.BAD_PROTOCOL)
        val out = ByteArray(512)
        val n = early.session.failureFrame(out)
        assertEquals(norm("01 25 0000 00000000 0200 03 00 | 0100"), toHex(out.copyOf(n)))
    }

    @Test
    fun firstWhileAssemblingIsRejected() {
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443) // 12 bytes: 8 + 4 at frame 20
        val again = ready(20)
        assertEquals(Rx.ACCEPTED, deliver(again.session, again.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8))))
        assertEquals(Rx.REJECTED, deliver(again.session, again.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8))))
        assertFailed(again.session, PipeError.BAD_PROTOCOL)
        assertTrue(again.admitted.isEmpty())

        val whole = ready(20)
        assertEquals(Rx.ACCEPTED, deliver(whole.session, whole.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8))))
        assertEquals(Rx.REJECTED, deliver(whole.session, whole.device.frame(Op.PING, 0, F_BOTH, hex("01020304"))))
        assertFailed(whole.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun dataInterleavedWithReassemblyIsRejected() {
        val rig = opened(20)
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443)
        assertEquals(Rx.ACCEPTED, deliver(rig.session, rig.device.frame(Op.OPEN, 2, F_FIRST, open.copyOf(8))))
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.DATA, 1, 0, hex("aabbcc"))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        assertEquals(1, rig.admitted.size) // stream 2's OPEN never completed
    }

    @Test
    fun continuationWithDifferentOpcodeIsRejected() {
        val rig = ready(20)
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443)
        assertEquals(Rx.ACCEPTED, deliver(rig.session, rig.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8))))
        assertEquals(Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.CREDIT, 1, F_LAST, open.copyOfRange(8, 12))))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        assertTrue(rig.admitted.isEmpty())
    }

    @Test
    fun continuationWithDifferentStreamIsRejected() {
        val v6 = openBody(ADDR_IPV6, hex("20010db8000000000000000000000001"), 443) // 24 bytes: 8+8+8
        val middle = ready(20)
        assertEquals(Rx.ACCEPTED, deliver(middle.session, middle.device.frame(Op.OPEN, 1, F_FIRST, v6.copyOfRange(0, 8))))
        assertEquals(Rx.REJECTED, deliver(middle.session, middle.device.frame(Op.OPEN, 2, 0, v6.copyOfRange(8, 16))))
        assertFailed(middle.session, PipeError.BAD_PROTOCOL)

        val v4 = openBody(ADDR_IPV4, TEST_IPV4, 443)
        val last = ready(20)
        assertEquals(Rx.ACCEPTED, deliver(last.session, last.device.frame(Op.OPEN, 1, F_FIRST, v4.copyOfRange(0, 8))))
        assertEquals(Rx.REJECTED, deliver(last.session, last.device.frame(Op.OPEN, 2, F_LAST, v4.copyOfRange(8, 12))))
        assertFailed(last.session, PipeError.BAD_PROTOCOL)
        assertTrue(last.admitted.isEmpty())
    }

    /** One 272-byte reassembly buffer: 34 full fragments at frame 20 fill it; the next fragment overflows. */
    @Test
    fun reassemblyBeyond272BytesIsRejected() {
        val rig = ready(20)
        val frames = rig.device.frames(Op.OPEN, 1, bytes(281, 0x61))
        assertEquals(36, frames.size)
        for (i in 0 until 34) assertEquals("fragment $i", Rx.ACCEPTED, deliver(rig.session, frames[i]))
        assertEquals(Rx.REJECTED, deliver(rig.session, frames[34]))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        assertTrue(rig.admitted.isEmpty())
    }

    /** Firmware decoders accept any non-empty split (§6 D: CREDIT split 2+2); so does the phone. */
    @Test
    fun controlMessageSplitAtAnyNonEmptyBoundaryIsAccepted() {
        val rig = opened()
        val s = rig.session
        val credit = le32(2048)
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CREDIT, 1, F_FIRST, credit.copyOfRange(0, 2))))
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CREDIT, 1, F_LAST, credit.copyOfRange(2, 4))))
        assertEquals(2048L, s.find(1)!!.peerLimit)
        val more = le32(3072)
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CREDIT, 1, F_FIRST, more.copyOfRange(0, 1))))
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CREDIT, 1, 0, more.copyOfRange(1, 3))))
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CREDIT, 1, F_LAST, more.copyOfRange(3, 4))))
        assertEquals(3072L, s.find(1)!!.peerLimit)
        assertEquals(State.READY, s.state)
    }

    /**
     * Design choice (the sources leave fragment sizes open, report D14; the firmware encoder never
     * emits one): an empty control body travels only as one FIRST|LAST frame, so an empty fragment
     * of a longer message is refused.
     */
    @Test
    fun emptyControlFragmentsAreRejected() {
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443)
        val emptyFirst = ready(20)
        assertEquals(Rx.REJECTED, deliver(emptyFirst.session, emptyFirst.device.frame(Op.OPEN, 1, F_FIRST)))
        assertFailed(emptyFirst.session, PipeError.BAD_PROTOCOL)

        for (flags in listOf(0, F_LAST)) {
            val rig = ready(20)
            assertEquals(Rx.ACCEPTED, deliver(rig.session, rig.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8))))
            assertEquals("flags $flags", Rx.REJECTED, deliver(rig.session, rig.device.frame(Op.OPEN, 1, flags)))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    /** An empty control message is one 12-byte FIRST|LAST frame in both directions. */
    @Test
    fun emptyControlMessageIsOneFirstLastFrame() {
        val rig = opened()
        val s = rig.session
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.CLOSE, 1, le16(1)))
        assertDrains(s, "01 24 0100 02000000 0000 03 00")
        // The device's own CLOSE_ACK for the now-retired stream is discarded, not an error.
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.CLOSE_ACK, 1, F_BOTH)))
        assertDrains(s)
        assertEquals(State.READY, s.state)
    }

    // ---- Deadlines ----------------------------------------------------------------------------

    @Test
    fun reassemblyMustCompleteWithinTwoSecondsOfFirst() {
        val rig = ready(20)
        val s = rig.session
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443)
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8)), now = 1_000))
        assertTrue(s.tick(2_999))
        assertEquals(State.READY, s.state)
        assertFalse(s.tick(3_000))
        assertFailed(s, PipeError.DEADLINE)
    }

    @Test
    fun reassemblyCompletedInsideDeadlineIsAccepted() {
        val rig = ready(20)
        val s = rig.session
        val open = openBody(ADDR_IPV4, TEST_IPV4, 443)
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.OPEN, 1, F_FIRST, open.copyOf(8)), now = 1_000))
        assertTrue(s.tick(2_999))
        assertEquals(Rx.ACCEPTED, deliver(s, rig.device.frame(Op.OPEN, 1, F_LAST, open.copyOfRange(8, 12)), now = 2_999))
        assertTrue(s.tick(3_000))
        assertTrue(s.tick(4_000))
        assertTarget(rig.admitted.single(), ADDR_IPV4, TEST_IPV4, 443)
    }

    /**
     * R5: the inner 3 s HELLO timer starts at CONFIRM_ACK verification, which is when the engine
     * constructs the session, so the deadline counts from construction (`start`).
     */
    @Test
    fun helloMustArriveWithinThreeSecondsOfConstruction() {
        assertEquals(3_000L, PipeWire.HELLO_DEADLINE_MS)
        val rig = PipeRig(start = 10_000, maxFrame = 512)
        val s = rig.session
        assertTrue(s.tick(12_999))
        assertEquals(State.AWAIT_HELLO, s.state)
        assertFalse(s.tick(13_000))
        assertFailed(s, PipeError.DEADLINE)
        assertNotEquals(Rx.ACCEPTED, rig.device.send(s, Op.HELLO, 0, helloBody(512), now = 13_001))
        assertEquals(0, s.peek(ByteArray(512)))
    }

    @Test
    fun helloInsideDeadlineStopsTheHelloClock() {
        val rig = PipeRig(start = 10_000, maxFrame = 512)
        rig.hello(now = 12_999)
        assertTrue(rig.session.tick(13_000))
        assertTrue(rig.session.tick(60_000))
        assertEquals(State.READY, rig.session.state)
    }

    // ---- HELLO --------------------------------------------------------------------------------

    @Test
    fun phoneIsSilentUntilHelloArrives() {
        val rig = PipeRig(maxFrame = 20)
        val s = rig.session
        val out = ByteArray(512)
        assertEquals(0, s.peek(out))
        assertTrue(s.tick(1_000))
        assertEquals(0, s.peek(out))
        val hello = rig.device.frames(Op.HELLO, 0, helloBody(20))
        assertEquals(Rx.ACCEPTED, deliver(s, hello[0]))
        assertEquals(0, s.peek(out))
        assertEquals(Rx.ACCEPTED, deliver(s, hello[1]))
        assertTrue(s.peek(out) > 0)
    }

    @Test
    fun helloWithZeroNonceIsRejected() {
        assertHelloRejected(helloBody(512, nonce = ByteArray(8)))
    }

    @Test
    fun helloMustOfferExactlyTwoStreams() {
        for (streams in listOf(0, 1, 3, 255)) assertHelloRejected(helloBody(512, maxStreams = streams))
    }

    @Test
    fun helloReservedByteMustBeZero() {
        for (reserved in listOf(1, 0x80)) assertHelloRejected(helloBody(512, reserved = reserved))
    }

    @Test
    fun helloMaxValueOutside20To512IsRejected() {
        for (maxValue in listOf(0, 19, 513, 0xffff)) assertHelloRejected(helloBody(maxValue))
    }

    @Test
    fun helloBodyMustBeTwelveBytes() {
        assertHelloRejected(helloBody(512).copyOf(11))
        assertHelloRejected(helloBody(512) + byteArrayOf(0))
    }

    @Test
    fun helloOnNonzeroStreamIsRejected() {
        assertHelloRejected(helloBody(512), id = 1)
    }

    @Test
    fun secondHelloIsRejected() {
        val rig = ready()
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.HELLO, 0, helloBody(512)))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /** HELLO is accepted only once, even while its HELLO_ACK has not been submitted yet. */
    @Test
    fun repeatedHelloBeforeAckIsSubmittedIsRejected() {
        val rig = PipeRig(maxFrame = 512)
        assertEquals(Rx.ACCEPTED, rig.device.send(rig.session, Op.HELLO, 0, helloBody(512)))
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.HELLO, 0, helloBody(512)))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /**
     * helloAckSent is the engine's gate for its best-effort encrypted RESET (R6/R7: the phone sends
     * nothing before its HELLO_ACK). It turns true only when accept() consumes the HELLO_ACK's LAST
     * fragment: not when HELLO arrives and the ACK is queued, not when peek() hands a fragment out,
     * not after an earlier fragment. Later traffic and a later failure leave it set.
     */
    @Test
    fun helloAckSentTurnsTrueOnlyWhenTheWholeHelloAckIsAccepted() {
        val rig = PipeRig(maxFrame = 20)
        val s = rig.session
        val out = ByteArray(512)
        assertFalse("a new session", s.helloAckSent)
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.HELLO, 0, helloBody(20)))
        assertEquals(State.READY, s.state)
        assertFalse("HELLO_ACK only queued", s.helloAckSent)
        val expected = listOf(
            norm("01 02 0000 00000000 0800 01 00 | 0807060504030201"),
            norm("01 02 0000 01000000 0800 00 00 | 1817161514131211"),
            norm("01 02 0000 02000000 0400 02 00 | 02001400"),
        )
        for ((i, frame) in expected.withIndex()) {
            val n = s.peek(out)
            assertEquals("fragment $i", frame, toHex(out.copyOf(n)))
            assertFalse("fragment $i peeked, not accepted", s.helloAckSent)
            assertTrue(s.accept())
            assertEquals("fragment $i accepted", i == expected.lastIndex, s.helloAckSent)
        }
        rig.device.value = 20
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        assertDrains(s, "01 31 0000 03000000 0400 03 00 | 01020304")
        assertTrue(s.helloAckSent)
        assertEquals(Rx.REJECTED, deliver(s, rig.device.frame(0x40, 0, F_BOTH)))
        assertFailed(s, PipeError.BAD_PROTOCOL)
        assertTrue("a failure keeps it", s.helloAckSent)
        assertEquals(norm("01 25 0000 04000000 0200 03 00 | 0100"), toHex(out.copyOf(s.failureFrame(out))))
    }

    /** A session that fails before its HELLO_ACK is accepted never reports it sent, whatever it had queued or peeked. */
    @Test
    fun helloAckSentStaysFalseWhenTheSessionFailsFirst() {
        val out = ByteArray(512)

        val peeked = PipeRig(maxFrame = 512)
        assertEquals(Rx.ACCEPTED, peeked.device.send(peeked.session, Op.HELLO, 0, helloBody(512)))
        assertTrue(peeked.session.peek(out) > 0)
        assertEquals(Rx.REJECTED, peeked.device.send(peeked.session, Op.HELLO, 0, helloBody(512)))
        assertFalse("nothing is pending after the failure", peeked.session.accept())
        assertFalse(peeked.session.helloAckSent)
        assertFailed(peeked.session, PipeError.BAD_PROTOCOL)

        val queued = PipeRig(maxFrame = 20)
        assertEquals(Rx.ACCEPTED, queued.device.send(queued.session, Op.HELLO, 0, helloBody(20)))
        assertTrue(queued.session.peek(out) > 0)
        assertTrue(queued.session.accept()) // FIRST fragment only
        assertFalse(queued.session.fail(PipeError.CANCELLED))
        assertFalse(queued.session.helloAckSent)

        val silent = PipeRig(start = 0, maxFrame = 512)
        assertFalse(silent.session.tick(3_000))
        assertFailed(silent.session, PipeError.DEADLINE)
        assertFalse(silent.session.helloAckSent)
    }

    @Test
    fun framesBeforeHelloAreRejected() {
        val cases = listOf(
            Triple(Op.PING, 0, hex("01020304")),
            Triple(Op.PONG, 0, hex("01020304")),
            Triple(Op.OPEN, 1, openBody(ADDR_IPV4, TEST_IPV4, 443)),
            Triple(Op.DATA, 1, hex("aabbcc")),
            Triple(Op.CREDIT, 1, le32(2048)),
            Triple(Op.CLOSE, 1, le16(1)),
            Triple(Op.HELLO_ACK, 0, P4_NONCE + PHONE_NONCE + hex("0200 0002")),
        )
        for ((op, id, body) in cases) {
            val rig = PipeRig(maxFrame = 512)
            assertEquals("op $op", Rx.REJECTED, rig.device.send(rig.session, op, id, body))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
            assertTrue(rig.admitted.isEmpty())
        }
    }

    // ---- Stream 0 -----------------------------------------------------------------------------

    /** Every PING gets exactly one PONG echoing its cookie bytes, in order (report D20). */
    @Test
    fun eachPingGetsExactlyOnePongWithItsCookie() {
        val rig = ready()
        val s = rig.session
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("efbeadde")))
        assertDrains(s, "01 31 0000 01000000 0400 03 00 | efbeadde")
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("00000000")))
        assertDrains(
            s,
            "01 31 0000 02000000 0400 03 00 | 01020304",
            "01 31 0000 03000000 0400 03 00 | 00000000",
        )
        assertDrains(s)
    }

    @Test
    fun pingMustBeFourBytesOnStreamZero() {
        for (body in listOf(ByteArray(0), hex("010203"), hex("0102030405"))) {
            val rig = ready()
            assertEquals(toHex(body), Rx.REJECTED, rig.device.send(rig.session, Op.PING, 0, body))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
        val rig = opened()
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.PING, 1, hex("01020304")))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    @Test
    fun unsolicitedPongIsRejected() {
        val rig = ready()
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.PONG, 0, hex("efbeadde")))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /** The phone's own PING (5 s without inbound frames, a stream live) must be answered with its cookie, once. */
    @Test
    fun pongMustEchoTheOutstandingPingCookie() {
        fun pinged(): Pair<PipeRig, ByteArray> {
            val rig = opened()
            assertTrue(rig.session.tick(6_000))
            val ping = drain(rig.session).single()
            assertEquals(Op.PING, ping.op)
            assertEquals(0, ping.id)
            assertEquals(F_BOTH, ping.flags)
            assertEquals(4, ping.length)
            return rig to ping.body
        }

        val (wrong, cookie) = pinged()
        val other = cookie.copyOf()
        other[0] = (other[0].toInt() xor 1).toByte()
        assertEquals(Rx.REJECTED, wrong.device.send(wrong.session, Op.PONG, 0, other, now = 6_001))
        assertFailed(wrong.session, PipeError.BAD_PROTOCOL)

        val (right, echo) = pinged()
        assertEquals(Rx.ACCEPTED, right.device.send(right.session, Op.PONG, 0, echo, now = 6_001))
        assertEquals(State.READY, right.session.state)
        assertEquals(Rx.REJECTED, right.device.send(right.session, Op.PONG, 0, echo, now = 6_002))
        assertFailed(right.session, PipeError.BAD_PROTOCOL)
    }

    /** RESET on stream 0 with a known code ends the session with that code; nothing is sent back. */
    @Test
    fun streamZeroResetEndsSessionWithItsCode() {
        SPEC_ERRORS.forEachIndexed { i, expected ->
            val rig = opened()
            rig.device.send(rig.session, Op.RESET, 0, le16(i + 1))
            assertFailed(rig.session, expected)
            assertNull(rig.session.find(1))
            assertEquals("code ${i + 1}", 0, rig.session.failureFrame(ByteArray(512)))
        }
    }

    @Test
    fun streamZeroResetWithUnknownCodeOrLengthIsProtocolError() {
        for (body in listOf(le16(0), le16(16), le16(0xffff), hex("0500 00"))) {
            val rig = opened()
            assertEquals(toHex(body), Rx.REJECTED, rig.device.send(rig.session, Op.RESET, 0, body))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    @Test
    fun streamMessagesOnStreamZeroAreRejected() {
        val cases = listOf(
            Op.OPEN to openBody(ADDR_IPV4, TEST_IPV4, 443),
            Op.CREDIT to le32(2048),
            Op.FIN to le32(0),
            Op.CLOSE to le16(1),
            Op.CLOSE_ACK to ByteArray(0),
        )
        for ((op, body) in cases) {
            val rig = opened()
            assertEquals("op $op", Rx.REJECTED, rig.device.send(rig.session, op, 0, body))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
            assertEquals(1, rig.admitted.size)
        }
    }

    // ---- Direction ----------------------------------------------------------------------------

    /** HELLO_ACK, OPEN_OK and OPEN_ERR only ever travel phone → device. */
    @Test
    fun phoneToDeviceMessagesFromDeviceAreRejected() {
        val cases = listOf(
            Triple(Op.HELLO_ACK, 0, P4_NONCE + PHONE_NONCE + hex("0200 0002")),
            Triple(Op.OPEN_OK, 1, le32(1024)),
            Triple(Op.OPEN_ERR, 1, le16(7)),
        )
        for ((op, id, body) in cases) {
            val rig = opened()
            assertEquals("op $op", Rx.REJECTED, rig.device.send(rig.session, op, id, body))
            assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        }
    }

    @Test
    fun openOkFromDeviceWhileConnectingIsRejected() {
        val rig = ready()
        assertEquals(Rx.ACCEPTED, rig.device.send(rig.session, Op.OPEN, 1, openBody(ADDR_IPV4, TEST_IPV4, 443)))
        assertEquals(Rx.REJECTED, rig.device.send(rig.session, Op.OPEN_OK, 1, le32(1024)))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
        assertFalse("a failed session never publishes OPEN_OK", rig.session.connected(1, 2))
        assertDrains(rig.session)
    }

    // ---- Exhaustion ---------------------------------------------------------------------------

    /**
     * The last valid sequence number is 0xFFFFFFFE (report D19). PipeSession offers no way to skip
     * 2^32 frames, so the private counter is set by reflection; everything else is public API.
     */
    @Test
    fun receiveSequenceNeverAcceptsAllOnes() {
        val rig = ready()
        val s = rig.session
        setPrivateLong(s, "rxSequence", 0xFFFF_FFFEL)
        rig.device.seq = 0xFFFF_FFFEL
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        assertEquals(U32_MAX, s.rxSequence)
        assertEquals(Rx.REJECTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        assertFailed(s, PipeError.BAD_PROTOCOL)

        // Nor may the counter wrap back to 0.
        val wrap = ready()
        setPrivateLong(wrap.session, "rxSequence", U32_MAX)
        assertEquals(Rx.REJECTED, deliver(wrap.session, rawFrame(Op.PING, 0, 0, F_BOTH, hex("01020304"))))
        assertFailed(wrap.session, PipeError.BAD_PROTOCOL)
    }

    /** Reflection as above: the phone submits 0xFFFFFFFE, then ends the session rather than use 0xFFFFFFFF. */
    @Test
    fun transmitSequenceStopsBeforeAllOnes() {
        val rig = ready()
        val s = rig.session
        setPrivateLong(s, "txSequence", 0xFFFF_FFFEL)
        assertEquals(Rx.ACCEPTED, rig.device.send(s, Op.PING, 0, hex("01020304")))
        val sent = drain(s)
        assertEquals(1, sent.size)
        assertEquals(Op.PONG, sent[0].op)
        assertEquals(0xFFFF_FFFEL, sent[0].seq)
        rig.device.send(s, Op.PING, 0, hex("05060708"))
        val out = ByteArray(512)
        assertEquals(0, s.peek(out))
        assertEquals(State.FAILED, s.state)
        assertEquals("no sequence number is left for a RESET either", 0, s.failureFrame(out))
    }

    // ---- Fuzz ---------------------------------------------------------------------------------

    /** Firmware HX:55-59: every single-byte mutation of a valid 24-byte DATA frame on an open stream. */
    @Test
    fun byteMutationsOfDataFrameStayBounded() {
        for (offset in 0 until 24) {
            for (value in 0..0xff) {
                val rig = opened()
                val frame = rawFrame(Op.DATA, 1, rig.device.seq, 0, ByteArray(12))
                frame[offset] = value.toByte()
                val result = rig.session.receive(frame, frame.size, 3)
                assertBounded(rig.session, result, "offset $offset value $value")
            }
        }
    }

    /** Seeded structured fuzz: valid-looking headers with random opcode, stream, flags and body, plus stray byte flips. */
    @Test
    fun seededRandomFramesStayBounded() {
        val random = Random(0x5EED)
        val ops = intArrayOf(
            Op.HELLO, Op.HELLO_ACK, Op.OPEN, Op.OPEN_OK, Op.OPEN_ERR, Op.DATA, Op.CREDIT, Op.FIN,
            Op.CLOSE, Op.CLOSE_ACK, Op.RESET, Op.PING, Op.PONG, 0x40,
        )
        for (round in 0 until 400) {
            val rig = opened(if (round % 2 == 0) 20 else 512)
            val s = rig.session
            var now = 10L
            var step = 0
            while (step < 40 && s.state != State.FAILED) {
                step++
                val body = ByteArray(random.nextInt(s.value - 11)).also { random.nextBytes(it) }
                val op = ops[random.nextInt(ops.size)]
                val frame = rawFrame(op, random.nextInt(4), rig.device.seq, random.nextInt(4), body)
                if (random.nextInt(8) == 0) frame[random.nextInt(frame.size)] = random.nextInt(256).toByte()
                val result = s.receive(frame, frame.size, now)
                if (result == Rx.ACCEPTED) rig.device.seq++
                assertBounded(s, result, "round $round step $step")
                now += random.nextInt(60)
                s.tick(now)
                drain(s)
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun ready(maxFrame: Int = 512): PipeRig = PipeRig(maxFrame = maxFrame).also { it.hello() }

    private fun opened(maxFrame: Int = 512): PipeRig = ready(maxFrame).also { it.open(1) }

    private fun norm(fixture: String): String = toHex(hex(fixture))

    private fun assertTarget(target: PipeTarget, type: Int, host: ByteArray, port: Int) {
        assertEquals("address type", type, target.type)
        assertEquals("host", toHex(host), toHex(target.host))
        assertEquals("port", port, target.port)
    }

    private fun assertHelloRejected(body: ByteArray, id: Int = 0) {
        val rig = PipeRig(maxFrame = 512)
        assertEquals("HELLO ${toHex(body)} on stream $id", Rx.REJECTED, rig.device.send(rig.session, Op.HELLO, id, body))
        assertFailed(rig.session, PipeError.BAD_PROTOCOL)
    }

    /** Failed, every stream gone and nothing more to submit; [error] checked when given. */
    private fun assertFailed(s: PipeSession, error: PipeError? = null) {
        assertEquals(State.FAILED, s.state)
        if (error != null) assertEquals(error, s.error)
        assertEquals("live streams after failure", 0, s.liveStreams)
        assertEquals("frames after failure", 0, s.peek(ByteArray(512)))
    }

    /** Storage never exceeds its fixed bounds, and any refused frame has failed the whole session. */
    private fun assertBounded(s: PipeSession, result: Rx, what: String) {
        for (stream in s.streams) {
            assertTrue(what, stream.downBuffered in 0..4096)
            assertTrue(what, stream.upBuffered in 0..4096)
        }
        assertTrue(what, s.queuedControl in 0..8)
        if (result != Rx.ACCEPTED) {
            assertEquals(what, State.FAILED, s.state)
            assertEquals(what, 0, s.liveStreams)
        }
    }

    private fun setPrivateLong(target: Any, name: String, value: Long) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.setLong(target, value)
    }

    private companion object {
        const val U32_MAX = 0xFFFF_FFFFL

        /** Error codes 1..15 in the order of the v1 error table (handoff "EOF, close, errors"). */
        val SPEC_ERRORS = listOf(
            PipeError.BAD_PROTOCOL, PipeError.UNSUPPORTED_VERSION, PipeError.NOT_AUTHORIZED,
            PipeError.TARGET_DENIED, PipeError.VPN_UNAVAILABLE, PipeError.DNS_FAILED, PipeError.CONNECT_FAILED,
            PipeError.CONNECT_TIMEOUT, PipeError.BUSY, PipeError.NO_RESOURCES, PipeError.TCP_IO,
            PipeError.FLOW_VIOLATION, PipeError.DEADLINE, PipeError.CANCELLED, PipeError.DISPLAY_PRIORITY,
        )
    }
}
