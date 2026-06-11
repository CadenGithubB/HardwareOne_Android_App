package com.hardwareone.console.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Test

/**
 * Prints canonical interop test vectors (fixed inputs → expected bytes) so the firmware
 * implementation can be validated byte-for-byte against the app. Run:
 *   ./gradlew testDebugUnitTest --tests "*SecureChannelVectorsTest"
 * and read system-out from the testDebugUnitTest results XML.
 */
class SecureChannelVectorsTest {

    @Test
    fun printVectors() {
        val passphrase = "test-passphrase"
        val appEphPriv = ByteArray(32) { (it + 1).toByte() }        // 01,02,...,20
        val devEphPriv = ByteArray(32) { (it + 0x21).toByte() }     // 21,22,...,40
        val appNonce = ByteArray(16) { (0xA0 + it).toByte() }       // A0..AF
        val devNonce = ByteArray(16) { (0xB0 + it).toByte() }       // B0..BF

        val appPriv = X25519PrivateKeyParameters(appEphPriv, 0)
        val devPriv = X25519PrivateKeyParameters(devEphPriv, 0)
        val appPub = appPriv.generatePublicKey().encoded
        val devPub = devPriv.generatePublicKey().encoded

        val ss = ByteArray(32)
        X25519Agreement().apply { init(appPriv); calculateAgreement(X25519PublicKeyParameters(devPub, 0), ss, 0) }

        val psk = SecureChannel.derivePsk(passphrase)
        val info = "HW1-SC-v1".toByteArray(Charsets.US_ASCII)
        val okm = SecureChannel.hkdf(ss + psk, appNonce + devNonce, info, 64)
        val kC2D = okm.copyOfRange(0, 32)
        val kD2C = okm.copyOfRange(32, 64)

        fun nonce(dir: Int, ctr: Long) = ByteArray(12).also { n ->
            n[0] = (dir ushr 24).toByte(); n[1] = (dir ushr 16).toByte()
            n[2] = (dir ushr 8).toByte(); n[3] = dir.toByte()
            for (i in 0 until 8) n[4 + i] = (ctr ushr (56 - 8 * i)).toByte()
        }
        fun longBE(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

        val confirm = SecureChannel.aeadSeal(kC2D, nonce(0, 0), "ok".toByteArray())
        val confirmAck = SecureChannel.aeadSeal(kD2C, nonce(1, 0), "ok".toByteArray())
        val dataCt = SecureChannel.aeadSeal(kC2D, nonce(0, 1), "help".toByteArray())

        println("=== HW1-SC-v1 TEST VECTORS ===")
        println("passphrase         = \"$passphrase\"")
        println("PSK (PBKDF2)       = ${hex(psk)}")
        println("appEphPriv         = ${hex(appEphPriv)}")
        println("appEphPub          = ${hex(appPub)}")
        println("devEphPriv         = ${hex(devEphPriv)}")
        println("devEphPub          = ${hex(devPub)}")
        println("appNonce           = ${hex(appNonce)}")
        println("devNonce           = ${hex(devNonce)}")
        println("X25519 shared (ss) = ${hex(ss)}")
        println("K_c2d              = ${hex(kC2D)}")
        println("K_d2c              = ${hex(kD2C)}")
        println("HELLO       (c->d) = ${hex(byteArrayOf(0x01) + appPub + appNonce)}")
        println("HELLO_ACK   (d->c) = ${hex(byteArrayOf(0x02) + devPub + devNonce)}")
        println("CONFIRM     (c->d) = ${hex(byteArrayOf(0x03) + confirm)}")
        println("CONFIRM_ACK (d->c) = ${hex(byteArrayOf(0x04) + confirmAck)}")
        println("DATA \"help\" ctr=1  = ${hex(byteArrayOf(0x10) + longBE(1) + dataCt)}")
        println("=== END VECTORS ===")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
