package com.hardwareone.console.pipe

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * The phone (initiator) side of **HW1-PIPE-v1**, the app-layer secure channel that carries the
 * relay pipe over an unbonded BLE link. Wire-identical to the firmware responder
 * (`BLE_PhoneSecureChannel.h`, primitives from `System_BleSecureCrypto.h`). It follows the console
 * channel's key schedule but shares no code with `security/SecureChannel`: the label, the
 * unsigned counters and the failure rules are the pipe's own.
 *
 * Handshake, once per BLE session:
 *   1. phone → HELLO       = 01 ‖ phonePub(32) ‖ phoneNonce(16)
 *   2. P4    → HELLO_ACK   = 02 ‖ devicePub(32) ‖ deviceNonce(16)
 *   3. shared = X25519(phonePriv, devicePub); a low-order point or all-zero result is an auth failure
 *   4. K = HKDF-SHA256(ikm = shared ‖ PSK, salt = phoneNonce ‖ deviceNonce, info = "HW1-PIPE-v1", L = 64)
 *      kP2D = K[0:32] (phone → P4), kD2P = K[32:64] (P4 → phone)
 *   5. phone → CONFIRM     = 03 ‖ AEAD(kP2D, nonce(0, 0), "ok")
 *      P4    → CONFIRM_ACK = 04 ‖ AEAD(kD2P, nonce(1, 0), "ok")   → ESTABLISHED
 * Until established the P4 may instead answer REJECT = 05 ‖ reason.
 *
 * DATA = `10 ‖ counter(u64 BE) ‖ ciphertext ‖ tag(16)`: ChaCha20-Poly1305-IETF without AAD, nonce
 * `dir(u32 BE) ‖ counter(u64 BE)`. Counters are unsigned 64-bit. A received counter must be above
 * the last accepted one (gaps allowed) and is committed only after its tag verifies. Sent counters
 * start at 1 and never reach 2^64 − 1; exhaustion ends the channel as [Failure.EXHAUSTED].
 *
 * Every receive-side failure is terminal ([Failure.AUTH]) and wipes the key material, so one
 * instance serves exactly one session. Not thread-safe: the owner serialises all calls.
 *
 * Zeroing is best effort: [wipe] (and every move to FAILED) fills each key, PSK and private-key
 * array this object holds, but on the JVM BouncyCastle's key objects and the garbage collector
 * may still hold copies.
 */
class PipeSecureChannel(
    psk: ByteArray,
    /** Fills the array from a cryptographic RNG: the private key (unless injected), then the nonce. */
    private val random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    /** Ephemeral X25519 private key for fixtures (copied); null draws a fresh one in [hello]. */
    privateKey: ByteArray? = null,
) {
    enum class State { NEW, AWAIT_HELLO_ACK, AWAIT_CONFIRM_ACK, ESTABLISHED, FAILED }

    /** Why the channel is FAILED: any receive-side failure, or the TX counter running out. */
    enum class Failure { AUTH, EXHAUSTED }

    /** The outcome of one received GATT value. */
    sealed class Rx {
        /** Send [value] (the CONFIRM) to the P4. */
        class Reply(val value: ByteArray) : Rx()

        /** CONFIRM_ACK verified: DATA may flow both ways. */
        data object Established : Rx()

        /** One authenticated DATA payload. */
        class Data(val plaintext: ByteArray) : Rx()

        /** The P4 refused the handshake (REJECT); the channel is FAILED. */
        class Rejected(val reason: Int) : Rx()

        /** Anything else: the channel is FAILED. */
        data object Invalid : Rx()
    }

    var state = State.NEW
        private set
    var failure: Failure? = null
        private set

    private val psk = psk.copyOf().also { require(it.size == KEY) { "PSK must be $KEY bytes" } }
    private val injected = privateKey != null
    private val secret = privateKey?.copyOf()?.also { require(it.size == KEY) { "private key must be $KEY bytes" } }
        ?: ByteArray(KEY)
    private val phoneNonce = ByteArray(NONCE)
    private val p2d = ByteArray(KEY) // phone → P4
    private val d2p = ByteArray(KEY) // P4 → phone
    private var txCounter = 1L // next counter to send; 0 is CONFIRM's
    private var rxCounter = 0L // last accepted, compared unsigned; 0 is CONFIRM_ACK's

    /** The HELLO to write first. Only once, on a NEW channel. */
    fun hello(): ByteArray {
        check(state == State.NEW) { "hello() needs a new channel" }
        if (!injected) random(secret)
        val pub = X25519PrivateKeyParameters(secret, 0).generatePublicKey().encoded
        random(phoneNonce)
        val out = ByteArray(HELLO_SIZE)
        out[0] = T_HELLO
        pub.copyInto(out, 1)
        phoneNonce.copyInto(out, 1 + KEY)
        state = State.AWAIT_HELLO_ACK
        return out
    }

    /** Handle one value from the P4. Never throws; anything unexpected fails the channel. */
    fun receive(value: ByteArray): Rx = try {
        when (state) {
            State.AWAIT_HELLO_ACK -> rejection(value) ?: helloAck(value)
            State.AWAIT_CONFIRM_ACK -> rejection(value) ?: confirmAck(value)
            State.ESTABLISHED -> data(value)
            State.NEW, State.FAILED -> invalid()
        }
    } catch (e: Exception) {
        invalid()
    }

    /** One DATA frame for [plaintext] (`plaintext.size + OVERHEAD` bytes), or null unless ESTABLISHED. */
    fun seal(plaintext: ByteArray): ByteArray? {
        if (state != State.ESTABLISHED) return null
        val counter = txCounter
        if (counter == U64_MAX) {
            fail(Failure.EXHAUSTED)
            return null
        }
        txCounter = counter + 1
        val out = ByteArray(plaintext.size + OVERHEAD)
        out[0] = T_DATA
        for (i in 0 until 8) out[1 + i] = (counter ushr (8 * (7 - i))).toByte()
        encrypt(p2d, nonce(DIR_P2D, counter), plaintext, out, 9)
        return out
    }

    /** Zero every key, PSK and private-key array held here and end the channel ([failure] unchanged). */
    fun wipe() {
        psk.fill(0)
        secret.fill(0)
        phoneNonce.fill(0)
        p2d.fill(0)
        d2p.fill(0)
        state = State.FAILED
    }

    internal fun setCountersForTest(tx: Long, rx: Long) {
        txCounter = tx
        rxCounter = rx
    }

    private fun rejection(value: ByteArray): Rx? {
        if (value.size != REJECT_SIZE || value[0] != T_REJECT) return null
        fail(Failure.AUTH)
        return Rx.Rejected(value[1].toInt() and 0xff)
    }

    private fun helloAck(value: ByteArray): Rx {
        if (value.size != HELLO_SIZE || value[0] != T_HELLO_ACK) return invalid()
        val shared = ByteArray(KEY)
        val agreed = try {
            X25519Agreement().run {
                init(X25519PrivateKeyParameters(secret, 0))
                calculateAgreement(X25519PublicKeyParameters(value, 1), shared, 0)
            }
            true
        } catch (e: IllegalStateException) {
            false // BouncyCastle refuses a low-order point (all-zero result)
        } finally {
            secret.fill(0)
        }
        if (!agreed || isZero(shared)) {
            shared.fill(0)
            return invalid()
        }
        val ikm = shared + psk
        shared.fill(0)
        val salt = phoneNonce + value.copyOfRange(1 + KEY, HELLO_SIZE)
        val okm = ByteArray(2 * KEY)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(ikm, salt, LABEL.toByteArray(Charsets.US_ASCII)))
            generateBytes(okm, 0, okm.size)
        }
        ikm.fill(0)
        okm.copyInto(p2d, 0, 0, KEY)
        okm.copyInto(d2p, 0, KEY, 2 * KEY)
        okm.fill(0)
        val confirm = ByteArray(CONFIRM_SIZE)
        confirm[0] = T_CONFIRM
        encrypt(p2d, nonce(DIR_P2D, 0), OK, confirm, 1)
        state = State.AWAIT_CONFIRM_ACK
        return Rx.Reply(confirm)
    }

    private fun confirmAck(value: ByteArray): Rx {
        if (value.size != CONFIRM_SIZE || value[0] != T_CONFIRM_ACK) return invalid()
        val plain = decrypt(d2p, nonce(DIR_D2P, 0), value, 1) ?: return invalid()
        if (!plain.contentEquals(OK)) return invalid()
        state = State.ESTABLISHED
        return Rx.Established
    }

    private fun data(value: ByteArray): Rx {
        if (value.size < OVERHEAD || value[0] != T_DATA) return invalid()
        var counter = 0L
        for (i in 1..8) counter = (counter shl 8) or (value[i].toLong() and 0xff)
        if (java.lang.Long.compareUnsigned(counter, rxCounter) <= 0) return invalid()
        val plain = decrypt(d2p, nonce(DIR_D2P, counter), value, 9) ?: return invalid()
        rxCounter = counter // only after the tag verified
        return Rx.Data(plain)
    }

    private fun invalid(): Rx {
        fail(Failure.AUTH)
        return Rx.Invalid
    }

    private fun fail(reason: Failure) {
        if (failure == null) failure = reason
        wipe()
    }

    private fun cipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray) =
        ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), TAG * 8, nonce)) }

    /** Writes `ciphertext ‖ tag` of [plain] into [out] at [offset]. */
    private fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, out: ByteArray, offset: Int) {
        val c = cipher(true, key, nonce)
        val n = c.processBytes(plain, 0, plain.size, out, offset)
        c.doFinal(out, offset + n)
    }

    /** Opens `ciphertext ‖ tag` from [offset] to the end of [input]; null when the tag fails. */
    private fun decrypt(key: ByteArray, nonce: ByteArray, input: ByteArray, offset: Int): ByteArray? {
        val length = input.size - offset
        val out = ByteArray(length - TAG)
        return try {
            val c = cipher(false, key, nonce)
            val n = c.processBytes(input, offset, length, out, 0)
            c.doFinal(out, n)
            out
        } catch (e: InvalidCipherTextException) {
            out.fill(0) // BouncyCastle may have written unauthenticated plaintext
            null
        }
    }

    private fun isZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) acc = acc or b.toInt()
        return acc == 0
    }

    companion object {
        /** DATA type byte, counter and tag: `size(DATA) = size(plaintext) + OVERHEAD`. */
        const val OVERHEAD = 25
        const val HELLO_SIZE = 49
        const val CONFIRM_SIZE = 19
        const val LABEL = "HW1-PIPE-v1"
        /** Nonce direction tags: phone → P4 and P4 → phone. */
        const val DIR_P2D = 0
        const val DIR_D2P = 1

        /** `dir(u32 BE) ‖ counter(u64 BE)`, the 12-byte ChaCha20-Poly1305-IETF nonce. */
        fun nonce(dir: Int, counter: Long): ByteArray {
            val out = ByteArray(12)
            for (i in 0 until 4) out[i] = (dir ushr (8 * (3 - i))).toByte()
            for (i in 0 until 8) out[4 + i] = (counter ushr (8 * (7 - i))).toByte()
            return out
        }

        private const val KEY = 32
        private const val NONCE = 16
        private const val TAG = 16
        private const val REJECT_SIZE = 2
        private const val U64_MAX = -1L // 0xFFFFFFFFFFFFFFFF
        private const val T_HELLO: Byte = 0x01
        private const val T_HELLO_ACK: Byte = 0x02
        private const val T_CONFIRM: Byte = 0x03
        private const val T_CONFIRM_ACK: Byte = 0x04
        private const val T_REJECT: Byte = 0x05
        private const val T_DATA: Byte = 0x10
        private val OK = byteArrayOf('o'.code.toByte(), 'k'.code.toByte())
    }
}
