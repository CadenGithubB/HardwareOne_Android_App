package com.hardwareone.console.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Verifies the app-side [SecureChannel] (initiator) against an in-test simulation of the
 * firmware (responder) built from the same documented primitives. This proves the protocol
 * logic and byte framing are internally consistent; true interop is confirmed against the
 * real firmware once its side lands.
 */
class SecureChannelTest {

    /** Minimal responder mirroring the spec — stands in for the ESP32. */
    private class DeviceSim(private val psk: ByteArray) {
        private val random = SecureRandom()
        private lateinit var kC2D: ByteArray
        private lateinit var kD2C: ByteArray
        private var sendCounter = 1L
        private var recvCounter = 0L

        fun onHello(hello: ByteArray): ByteArray {
            require(hello[0] == SecureChannel.T_HELLO)
            val appPub = hello.copyOfRange(1, 33)
            val appNonce = hello.copyOfRange(33, 49)
            val priv = X25519PrivateKeyParameters(random)
            val devPub = priv.generatePublicKey().encoded
            val devNonce = ByteArray(16).also { random.nextBytes(it) }
            val ss = ByteArray(32)
            X25519Agreement().apply {
                init(priv)
                calculateAgreement(X25519PublicKeyParameters(appPub, 0), ss, 0)
            }
            val okm = SecureChannel.hkdf(ss + psk, appNonce + devNonce, INFO, 64)
            kC2D = okm.copyOfRange(0, 32)
            kD2C = okm.copyOfRange(32, 64)
            return byteArrayOf(SecureChannel.T_HELLO_ACK) + devPub + devNonce
        }

        fun onConfirm(confirm: ByteArray): ByteArray? {
            require(confirm[0] == SecureChannel.T_CONFIRM)
            val pt = SecureChannel.aeadOpen(kC2D, nonce(SecureChannel.DIR_C2D, 0), confirm.copyOfRange(1, confirm.size))
            if (pt == null || String(pt) != "ok") return null
            return byteArrayOf(SecureChannel.T_CONFIRM_ACK) + SecureChannel.aeadSeal(kD2C, nonce(SecureChannel.DIR_D2C, 0), "ok".toByteArray())
        }

        fun decrypt(data: ByteArray): ByteArray? {
            val ctr = beLong(data, 1)
            if (ctr <= recvCounter) return null
            val pt = SecureChannel.aeadOpen(kC2D, nonce(SecureChannel.DIR_C2D, ctr), data.copyOfRange(9, data.size)) ?: return null
            recvCounter = ctr
            return pt
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val ctr = sendCounter++
            return byteArrayOf(SecureChannel.T_DATA) + longBE(ctr) + SecureChannel.aeadSeal(kD2C, nonce(SecureChannel.DIR_D2C, ctr), plaintext)
        }
    }

    @Test
    fun handshakeAndDataRoundTrip() {
        val psk = SecureChannel.derivePsk("correct horse battery staple")
        val app = SecureChannel(psk)
        val dev = DeviceSim(psk)

        val confirmAck = dev.onConfirm(app.onHelloAck(dev.onHello(app.hello()))!!)!!
        assertTrue(app.onConfirmAck(confirmAck))
        assertEquals(SecureChannel.State.ESTABLISHED, app.state)

        // app → device
        val cmd = "help --verbose".toByteArray()
        assertArrayEquals(cmd, dev.decrypt(app.encrypt(cmd)))
        // device → app (multi-line reply with embedded newline)
        val reply = "line1\nline2".toByteArray()
        assertArrayEquals(reply, app.decrypt(dev.encrypt(reply)))
    }

    @Test
    fun wrongPskFailsAtConfirm() {
        val dev = DeviceSim(SecureChannel.derivePsk("the-real-secret"))
        val app = SecureChannel(SecureChannel.derivePsk("a-different-secret"))
        // Handshake messages still exchange, but derived keys differ → CONFIRM won't open.
        val helloAck = dev.onHello(app.hello())
        val confirm = app.onHelloAck(helloAck)!!
        assertNull(dev.onConfirm(confirm)) // device can't decrypt the app's CONFIRM
    }

    @Test
    fun replayedFrameRejected() {
        val psk = SecureChannel.derivePsk("pw")
        val app = SecureChannel(psk)
        val dev = DeviceSim(psk)
        app.onConfirmAck(dev.onConfirm(app.onHelloAck(dev.onHello(app.hello()))!!)!!)

        val frame = app.encrypt("status".toByteArray())
        assertArrayEquals("status".toByteArray(), dev.decrypt(frame))
        assertNull(dev.decrypt(frame)) // same counter again → rejected
    }

    @Test
    fun tamperedCiphertextRejected() {
        val psk = SecureChannel.derivePsk("pw")
        val app = SecureChannel(psk)
        val dev = DeviceSim(psk)
        app.onConfirmAck(dev.onConfirm(app.onHelloAck(dev.onHello(app.hello()))!!)!!)

        val frame = dev.encrypt("secret".toByteArray())
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte() // flip a tag byte
        assertNull(app.decrypt(frame))
    }

    @Test
    fun deterministicPskFromPassphrase() {
        assertArrayEquals(SecureChannel.derivePsk("hunter2"), SecureChannel.derivePsk("hunter2"))
        assertFalse(SecureChannel.derivePsk("hunter2").contentEquals(SecureChannel.derivePsk("hunter3")))
    }

    companion object {
        private val INFO = "HW1-SC-v1".toByteArray(Charsets.US_ASCII)

        private fun nonce(dir: Int, counter: Long): ByteArray = ByteArray(12).also { n ->
            n[0] = (dir ushr 24).toByte(); n[1] = (dir ushr 16).toByte()
            n[2] = (dir ushr 8).toByte(); n[3] = dir.toByte()
            for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        }

        private fun longBE(v: Long): ByteArray =
            ByteArray(8).also { for (i in 0 until 8) it[i] = (v ushr (56 - 8 * i)).toByte() }

        private fun beLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
            return v
        }
    }
}
