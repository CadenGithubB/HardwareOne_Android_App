package com.hardwareone.console.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * App-side (initiator) of **HardwareOne Secure Channel v1** — app-layer encryption over
 * the existing REQUEST/RESPONSE GATT characteristics, so the BLE link itself needs no
 * pairing/bonding/encryption.
 *
 * Primitives: X25519 · HKDF-SHA256 · ChaCha20-Poly1305-IETF (12B nonce / 16B tag).
 * PSK (32 bytes) = PBKDF2-HMAC-SHA256(passphrase, salt="HW1-SC-v1", 100000 iters).
 *
 * Handshake (once per connection, before login):
 *   1. App → HELLO       = 0x01 ‖ appEphPub(32) ‖ appNonce(16)
 *   2. Dev → HELLO_ACK   = 0x02 ‖ devEphPub(32) ‖ devNonce(16)
 *   3. ss = X25519(appEphPriv, devEphPub)
 *   4. K = HKDF(ikm = ss‖PSK, salt = appNonce‖devNonce, info="HW1-SC-v1", L=64)
 *      K_c2d = K[0:32]   K_d2c = K[32:64]
 *   5. App → CONFIRM     = 0x03 ‖ AEAD(K_c2d, nonce(c2d,0), "ok")
 *      Dev → CONFIRM_ACK = 0x04 ‖ AEAD(K_d2c, nonce(d2c,0), "ok")   → established
 * Data: 0x10 ‖ counter(8,BE) ‖ AEAD(K_dir, nonce = dirTag(4,BE)‖counter(8,BE), plaintext).
 * Counters: 0 = CONFIRM; data starts at 1; strictly monotonic per direction (replay guard).
 */
class SecureChannel(private val psk: ByteArray) {

    enum class State { NEW, AWAIT_HELLO_ACK, AWAIT_CONFIRM_ACK, ESTABLISHED, FAILED }

    var state: State = State.NEW
        private set

    private val random = SecureRandom()
    private lateinit var ephPriv: X25519PrivateKeyParameters
    private lateinit var appNonce: ByteArray
    private lateinit var kC2D: ByteArray // app → device
    private lateinit var kD2C: ByteArray // device → app
    private var sendCounter = 1L // 0 is reserved for CONFIRM
    private var recvCounter = 0L // highest accepted device data counter

    /** Build the HELLO message to write to REQUEST. */
    fun hello(): ByteArray {
        ephPriv = X25519PrivateKeyParameters(random)
        appNonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        state = State.AWAIT_HELLO_ACK
        return byteArrayOf(T_HELLO) + ephPriv.generatePublicKey().encoded + appNonce
    }

    /** Process HELLO_ACK; returns the CONFIRM message to send, or null on failure. */
    fun onHelloAck(msg: ByteArray): ByteArray? {
        if (state != State.AWAIT_HELLO_ACK || msg.size != 1 + 32 + NONCE_LEN || msg[0] != T_HELLO_ACK) {
            state = State.FAILED
            return null
        }
        val devPub = msg.copyOfRange(1, 33)
        val devNonce = msg.copyOfRange(33, 33 + NONCE_LEN)

        val ss = ByteArray(32)
        X25519Agreement().apply {
            init(ephPriv)
            calculateAgreement(X25519PublicKeyParameters(devPub, 0), ss, 0)
        }
        val okm = hkdf(ikm = ss + psk, salt = appNonce + devNonce, info = INFO, length = 64)
        kC2D = okm.copyOfRange(0, 32)
        kD2C = okm.copyOfRange(32, 64)

        state = State.AWAIT_CONFIRM_ACK
        return byteArrayOf(T_CONFIRM) + aeadSeal(kC2D, nonce(DIR_C2D, 0), CONFIRM_PT)
    }

    /** Process CONFIRM_ACK; returns true once the channel is established. */
    fun onConfirmAck(msg: ByteArray): Boolean {
        if (state != State.AWAIT_CONFIRM_ACK || msg.isEmpty() || msg[0] != T_CONFIRM_ACK) {
            state = State.FAILED
            return false
        }
        val pt = aeadOpen(kD2C, nonce(DIR_D2C, 0), msg.copyOfRange(1, msg.size))
        if (pt == null || !pt.contentEquals(CONFIRM_PT)) {
            state = State.FAILED
            return false
        }
        state = State.ESTABLISHED
        return true
    }

    /** True if [msg] is a DATA frame (vs a handshake message). */
    fun isData(msg: ByteArray): Boolean = msg.isNotEmpty() && msg[0] == T_DATA

    /** Encrypt a command into a DATA message to write to REQUEST. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(state == State.ESTABLISHED) { "channel not established" }
        val ctr = sendCounter++
        return byteArrayOf(T_DATA) + longBE(ctr) + aeadSeal(kC2D, nonce(DIR_C2D, ctr), plaintext)
    }

    /** Decrypt a DATA message from RESPONSE; null on bad/replayed frame. */
    fun decrypt(msg: ByteArray): ByteArray? {
        if (state != State.ESTABLISHED || msg.size < 1 + 8 || msg[0] != T_DATA) return null
        val ctr = beLong(msg, 1)
        if (ctr <= recvCounter) return null // replay / out of order
        val pt = aeadOpen(kD2C, nonce(DIR_D2C, ctr), msg.copyOfRange(9, msg.size)) ?: return null
        recvCounter = ctr
        return pt
    }

    // --- Device→app message framing & reassembly (added with the BLE-reliability fix) ----------
    //
    // The device now prefixes each decrypted device→app frame with a 5-byte header:
    //   [ver=0x01][msgId(2, little-endian)][fragIdx][fragCount][payload…]
    // A complete message is the concatenated payloads of all `fragCount` fragments that share a
    // `msgId`, in `fragIdx` order. `fragCount == 1` is the common single-frame case. A dropped
    // frame leaves a msgId permanently incomplete — its partial is expired by [REASM_TTL_MS] and the
    // caller's reply timeout re-requests the page (the device resends it under a fresh msgId).
    // app→device is unchanged (still one unframed command per frame; we add no header outbound).

    private class Reasm(val fragCount: Int, val startedMs: Long) {
        val parts = arrayOfNulls<ByteArray>(fragCount)
        var have = 0
    }
    private val reasm = HashMap<Int, Reasm>()

    /**
     * Parse one decrypted device→app frame and reassemble. Returns the complete message bytes once
     * its last fragment arrives, else null (fragment buffered, or the frame is invalid/duplicate).
     * [nowMs] is a monotonic clock used to expire partials whose missing fragment never arrives.
     */
    fun reassemble(plaintext: ByteArray, nowMs: Long): ByteArray? {
        if (reasm.isNotEmpty()) reasm.values.removeAll { nowMs - it.startedMs > REASM_TTL_MS }

        if (plaintext.size < FRAME_HEADER_LEN || plaintext[0] != FRAME_VER) return null
        val msgId = (plaintext[1].toInt() and 0xff) or ((plaintext[2].toInt() and 0xff) shl 8)
        val fragIdx = plaintext[3].toInt() and 0xff
        val fragCount = plaintext[4].toInt() and 0xff
        if (fragCount < 1 || fragIdx >= fragCount) return null
        val payload = plaintext.copyOfRange(FRAME_HEADER_LEN, plaintext.size)
        if (fragCount == 1) return payload // single-frame message — no buffering needed

        var buf = reasm[msgId]
        if (buf == null || buf.fragCount != fragCount) {
            buf = Reasm(fragCount, nowMs).also { reasm[msgId] = it }
        }
        if (buf.parts[fragIdx] == null) { buf.parts[fragIdx] = payload; buf.have++ }
        if (buf.have < fragCount) return null

        reasm.remove(msgId)
        val out = ByteArray(buf.parts.sumOf { it?.size ?: 0 })
        var off = 0
        for (p in buf.parts) {
            p ?: return null
            System.arraycopy(p, 0, out, off, p.size); off += p.size
        }
        return out
    }

    private fun nonce(dir: Int, counter: Long): ByteArray = ByteArray(12).also { n ->
        n[0] = (dir ushr 24).toByte(); n[1] = (dir ushr 16).toByte()
        n[2] = (dir ushr 8).toByte(); n[3] = dir.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    }

    companion object {
        const val T_HELLO: Byte = 0x01
        const val T_HELLO_ACK: Byte = 0x02
        const val T_CONFIRM: Byte = 0x03
        const val T_CONFIRM_ACK: Byte = 0x04
        const val T_REJECT: Byte = 0x05
        const val T_DATA: Byte = 0x10

        // Device→app frame header: [ver][msgId lo][msgId hi][fragIdx][fragCount].
        const val FRAME_VER: Byte = 0x01
        private const val FRAME_HEADER_LEN = 5
        private const val REASM_TTL_MS = 5_000L

        // SC_REJECT reason byte (payload[0]): the device is telling us why it won't open the
        // secure channel, instead of going silent and leaving the app to time out.
        const val REJECT_NO_PASSPHRASE: Byte = 0x01 // device has no `blesecret` configured
        const val REJECT_AUTH_FAILED: Byte = 0x02   // wrong passphrase, or tampering/MITM
        const val DIR_C2D = 0x00000000
        const val DIR_D2C = 0x00000001
        private const val NONCE_LEN = 16
        private val INFO = "HW1-SC-v1".toByteArray(Charsets.US_ASCII)
        private val CONFIRM_PT = "ok".toByteArray(Charsets.US_ASCII)
        private val PSK_SALT = "HW1-SC-v1".toByteArray(Charsets.US_ASCII)

        /** PSK = PBKDF2-HMAC-SHA256(passphrase, "HW1-SC-v1", 100000) → 32 bytes. */
        fun derivePsk(passphrase: String): ByteArray {
            val gen = PKCS5S2ParametersGenerator(SHA256Digest())
            gen.init(passphrase.toByteArray(Charsets.UTF_8), PSK_SALT, 100_000)
            return (gen.generateDerivedMacParameters(256) as KeyParameter).key
        }

        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val gen = HKDFBytesGenerator(SHA256Digest())
            gen.init(HKDFParameters(ikm, salt, info))
            return ByteArray(length).also { gen.generateBytes(it, 0, length) }
        }

        internal fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
            val c = ChaCha20Poly1305()
            c.init(true, AEADParameters(KeyParameter(key), 128, nonce))
            val out = ByteArray(c.getOutputSize(plaintext.size))
            val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
            c.doFinal(out, n)
            return out
        }

        internal fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce))
            val out = ByteArray(c.getOutputSize(ciphertext.size))
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val m = c.doFinal(out, n)
            out.copyOfRange(0, n + m)
        } catch (_: Exception) {
            null
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
