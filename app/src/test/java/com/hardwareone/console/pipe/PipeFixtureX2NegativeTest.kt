package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeAttPolicy.Action
import com.hardwareone.console.pipe.PipeAttPolicy.Decision
import com.hardwareone.console.pipe.PipeSecureChannel.Failure
import com.hardwareone.console.pipe.PipeSecureChannel.Rx
import com.hardwareone.console.pipe.PipeSecureChannel.State
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Firmware fixture **X2** (`phone_pipe_v1_negative.json`, 40 vectors, copied verbatim from hardwareone-idf
 * `tools/ble_secure/fixtures/`; its SHA-256 is pinned below) imported against the Android side of HW1-PIPE-v1.
 *
 * X2 has two kinds of vector ([Row.source]):
 *  - `contract` (4, P4 → phone): production P4 bytes that Android **must reject** under the agreed rule named in
 *    `rule`. Each one is delivered to a real [PipeEngine] ([SecureRig]) at the point it belongs, and to a bare
 *    [PipeSecureChannel]; the session must end as [PipeEndKind.AUTH] with no REJECT, no further notification and
 *    one disconnect. AUTH is the one kind on which `PipeRuntime.onSessionEnd` (PhonePipeService.kt) charges the
 *    30 s cooldown (R14, `android_auth_cooldown`), but that charge is NOT exercised here or by any JVM test:
 *    PipeRuntime needs an Android Context, and PipeCooldownTest covers only the [PipeCooldown] class. This file
 *    proves the [PipeEnd] the charge rule reads, not the charge.
 *  - `p4_production` (36): what the production P4 adapter decided about a value. Most are phone → P4 values, so each
 *    is classified for Android in [ROWS]: a value Android's encoder never produces (asserted against Android's code
 *    paths), an Android reaction the vector depends on (its OPEN_OK window, its 0x11 policy, its own stream-0 RESET),
 *    the P4's reply bytes (`p4_sends_hex`) fed back to Android where that makes sense, or the Android counterpart of
 *    a P4-side event (link loss, expiry attribution).
 *
 * Every pipe-stage vector continues the MTU-247 session of X1 (`phone_pipe_v1_sessions.json`, also verbatim) after
 * the phone's pipe HELLO_ACK: phone counter 1 and inner sequence 0 are used, and so are P4 counter 1 and inner
 * sequence 0 (X2 `base.pipe`). The engine tests replay X1 frames 0–6 byte for byte ([x1Prefix]) and then deliver
 * the vector's own P4 values verbatim; any further P4 value is sealed by the kit's [FakeP4] at the next counter.
 * Android's entropy is X1's (phone key, handshake nonce, pipe nonce), so every Android value is comparable byte for
 * byte with the fixture.
 *
 * [ROWS] pins every vector's metadata (direction, source, stage, MTU, `expected`, cooldown, `session_continues`,
 * `p4_error`, `rule`) and names the tests that assert Android's side, so a regenerated fixture that changes an
 * outcome or adds a vector fails [theX2FixtureIsTheFirmwareFileAndEveryVectorIsClassified] until it is reviewed.
 *
 * Timing: three tests wait on real deadlines (two 4.5 s handshake expiries, one 2 s reassembly deadline); the rest
 * are sub-second. No endpoint, address or secret appears beyond the fixtures' synthetic values.
 */
class PipeFixtureX2NegativeTest {
    private val rigs = CopyOnWriteArrayList<SecureRig>()

    @After
    fun closeRigs() {
        rigs.forEach { it.close() }
    }

    // ---- Fixtures --------------------------------------------------------------------------------

    private object X2 {
        const val PATH = "/pipe/phone_pipe_v1_negative.json"
        const val SHA256 = "7b2722a4e6d1590785615068ea529eb661fa49d23b2eed5893a0a063f7588460"
        val raw: ByteArray by lazy { resource(PATH) }
        val json: JSONObject by lazy { JSONObject(String(raw, Charsets.UTF_8)) }
        val vectors: List<JSONObject> by lazy { json.getJSONArray("vectors").let { a -> List(a.length()) { a.getJSONObject(it) } } }

        fun vector(id: String): JSONObject = vectors.single { it.getString("id") == id }
        fun frames(id: String): List<JSONObject> = framesOf(vector(id))
        fun valueHex(id: String, i: Int): String = frames(id)[i].also { assertEquals(i, it.getInt("i")) }.getString("value_hex")
        fun value(id: String, i: Int): ByteArray = hex(valueHex(id, i))
        fun innerHex(id: String, i: Int): String = frames(id)[i].getString("inner_hex")
        fun bodyHex(id: String, i: Int): String = frames(id)[i].getString("body_hex")
        fun sends(id: String): List<ByteArray> = vector(id).getJSONArray("p4_sends_hex").let { a -> List(a.length()) { hex(a.getString(it)) } }
    }

    private object X1 {
        const val PATH = "/pipe/phone_pipe_v1_sessions.json"
        const val SHA256 = "19ce9539fdd676eefa3ad624625a6772a399c6e3662efe7c7bdf8585bfc758c9"
        val raw: ByteArray by lazy { resource(PATH) }
        val json: JSONObject by lazy { JSONObject(String(raw, Charsets.UTF_8)) }
        val mtu247: JSONObject by lazy {
            json.getJSONArray("sessions").let { a -> List(a.length()) { a.getJSONObject(it) } }.single { it.getInt("att_mtu") == 247 }
        }

        fun bytes(name: String): ByteArray = hex(json.getString(name))
        fun valueHex(i: Int): String = framesOf(mtu247)[i].also { assertEquals(i, it.getInt("i")) }.getString("value_hex")
        fun value(i: Int): ByteArray = hex(valueHex(i))
        fun innerHex(i: Int): String = framesOf(mtu247)[i].getString("inner_hex")
        val psk: ByteArray get() = bytes("psk_hex")
        val p4PipeNonce: ByteArray get() = hex(json.getJSONObject("pipe_nonces").getString("p4_hex"))
        val phonePipeNonce: ByteArray get() = hex(json.getJSONObject("pipe_nonces").getString("phone_hex"))
    }

    /**
     * One X2 vector as this file reads it: its pinned metadata, what Android must do, and the tests (methods of this
     * class) that assert it. [cooldown] is `auth_cooldown` (the P4's charge) for `p4_production` and
     * `android_auth_cooldown` for `contract`; [continues] and [p4Error] (the adapter's end reason, "None" while the
     * session continues) are null for `contract`.
     */
    private class Row(
        val id: String,
        val direction: String,
        val source: String,
        val stage: String,
        val mtu: Int,
        val expected: String,
        val cooldown: Boolean,
        val continues: Boolean?,
        val p4Error: String?,
        val rule: String?,
        val android: String,
        val tests: List<String>,
    )

    companion object {
        private const val V247 = 244
        private const val P247 = 219

        private fun resource(path: String): ByteArray =
            (PipeFixtureX2NegativeTest::class.java.getResourceAsStream(path) ?: throw AssertionError("missing test resource $path"))
                .use { it.readBytes() }

        private fun framesOf(v: JSONObject): List<JSONObject> = v.getJSONArray("frames").let { a -> List(a.length()) { a.getJSONObject(it) } }

        private fun sha256(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The wire counter of a secure DATA value (u64 big-endian after the type byte). */
        private fun counterOf(value: ByteArray): Long = ByteBuffer.wrap(value, 1, 8).long

        private fun ms(fromNanos: Long, toNanos: Long): Double = (toNanos - fromNanos) / 1_000_000.0

        /** PSK of the X1 passphrase through Android's own entry rule and derivation. */
        private val syntheticPsk: ByteArray by lazy {
            PipeSecret.derivePsk((PipeSecret.normalize(X1.json.getString("passphrase_utf8")) as PipeSecret.Result.Ok).bytes)
        }

        /** `wrong_passphrase` frame 3 note: "sealed with keys from WrongPipe!456". */
        private const val WRONG_PASSPHRASE = "WrongPipe!456"

        private val wrongPsk: ByteArray by lazy {
            PipeSecret.derivePsk((PipeSecret.normalize(WRONG_PASSPHRASE) as PipeSecret.Result.Ok).bytes)
        }

        // Test names, referenced from ROWS and checked by reflection.
        private const val T_META = "theX2FixtureIsTheFirmwareFileAndEveryVectorIsClassified"
        private const val T_X1 = "pipeVectorsContinueTheMtu247X1SessionWhichIsTheGolden"
        private const val T_C_TAMPERED_ACK = "contractTamperedConfirmAckEndsAuthWithNoRejectAndADisconnect"
        private const val T_C_REJECT = "contractP4RejectInEitherHandshakePhaseEndsAuthWithNoFurtherValue"
        private const val T_C_ZERO_KEY = "contractZeroDevicePublicKeyEndsAuthBeforeAnyConfirm"
        private const val T_C_REPLAY = "contractReplayedP4DataAfterReadyEndsAuthWithNoReset"
        private const val T_C_CHANNEL = "contractVectorsFailThePipeSecureChannelForGood"
        private const val T_P4_REJECT = "theP4RejectThatEndsEachHandshakeVectorEndsTheAndroidSessionAsAuth"
        private const val T_WRONG = "aWrongPassphraseMakesAndroidSendExactlyTheVectorsConfirm"
        private const val T_CONSOLE = "theConsoleDomainConfirmIsNothingThePipeChannelCanDerive"
        private const val T_TAMPERED = "tamperedVectorsDifferFromAndroidsOwnValuesInExactlyTheLastBit"
        private const val T_PUBKEY = "androidsPublicKeyIsNeverZeroOrLowOrder"
        private const val T_HELLO = "androidsFirstValueIsTheFullHelloAndItNeverSendsReject"
        private const val T_EARLY_DATA = "androidSealsNoDataBeforeConfirmAck"
        private const val T_PEER_SILENT = "androidAnswersHelloAckAtOnceAndAMissingConfirmAckIsAPeerExpiry"
        private const val T_LOCAL_STALL = "aHelloAckThatNeverArrivesIsAPeerExpiryOnAndroid"
        private const val T_LOSS_BEFORE_ACK = "linkLossBeforeHelloAckIsTransport"
        private const val T_LOSS_AFTER_CONFIRM = "linkLossAfterConfirmIsTransport"
        private const val T_MTU = "anMtuBelow64IsRefusedBeforeAnySession"
        private const val T_0X11 = "everyAndroid0x11CccdAnswerStartsNothing"
        private const val T_ENCODER = "androidsEncoderReproducesEveryPhoneDataValueBelowTheCounterLimit"
        private const val T_P4_VALUES = "androidOpensEveryP4DataValueInTheVectors"
        private const val T_TX_COUNTERS = "androidSendCountersRiseByOneAndNeverRepeat"
        private const val T_RX_COUNTERS = "androidReceiveCountersFollowTheVectorsRules"
        private const val T_MAX = "androidNeverEmitsCounterTwoToTheSixtyFourMinusOne"
        private const val T_NO_PLAIN = "androidSendsNoHelloPlaintextOrShortValueAfterTheHandshake"
        private const val T_WINDOW = "androidStopsAtTheP4WindowWithTheVectorsOwnDataValues"
        private const val T_OPEN_OK = "androidGrantsOpenOk1024AndNoCreditAheadOfP4Data"
        private const val T_VPN = "aDownVpnGivesTheVectorsOpenErr5AndTheSessionContinues"
        private const val T_LATE_CLOSE = "aFailedConnectGivesOpenErr7AndAndroidNeverSendsTheLateClose"
        private const val T_RESET0 = "androidsOwnStreamZeroResetIsTheVectorsValue"
        private const val T_SERVICE_CHANGED = "linkLossAfterServiceChangedIsTransportWithNoReset"

        private const val P2D = "phone_to_p4"
        private const val D2P = "p4_to_phone"
        private const val PROD = "p4_production"
        private const val CONTRACT = "contract"
        private const val HS = "handshake"
        private const val PIPE = "pipe"
        private const val TRANSPORT = "transport"
        private const val REJECT_COOLDOWN = "REJECT, teardown, cooldown"
        private const val TEARDOWN_COOLDOWN = "teardown, cooldown"
        private const val TEARDOWN_NO_COOLDOWN = "teardown, no cooldown"

        private fun prod(id: String, stage: String, expected: String, cooldown: Boolean, continues: Boolean, p4Error: String, android: String, vararg tests: String, mtu: Int = 247) =
            Row(id, P2D, PROD, stage, mtu, expected, cooldown, continues, p4Error, null, android, tests.toList())

        private fun contract(id: String, expected: String, rule: String, stage: String, android: String, vararg tests: String) =
            Row(id, D2P, CONTRACT, stage, 247, expected, true, null, null, rule, android, tests.toList())

        /** All 40 vectors in fixture order. */
        private val ROWS: List<Row> = listOf(
            prod("wrong_passphrase", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Android, given the wrong passphrase, sends exactly the vector's CONFIRM; the P4's REJECT then ends Android AUTH (NOT_AUTHORIZED).",
                T_WRONG, T_P4_REJECT),
            prod("console_domain", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: the vector is the console derivation (reference-checked); the pipe salt and HKDF info are HW1-PIPE-v1, so even the console PSK gives a different CONFIRM. P4 REJECT → AUTH.",
                T_CONSOLE, T_P4_REJECT),
            prod("tampered_confirm", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's CONFIRM is the deterministic golden value, one bit away from the vector. P4 REJECT → AUTH.",
                T_TAMPERED, T_P4_REJECT),
            prod("zero_public_key", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's public key is X25519(clamped scalar), never zero, and the P4's shared secret with it is never zero. P4 REJECT → AUTH.",
                T_PUBKEY, T_P4_REJECT),
            prod("low_order_public_key", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: as zero_public_key, for the order-8 point. P4 REJECT → AUTH.",
                T_PUBKEY, T_P4_REJECT),
            prod("phone_sends_reject", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's first value is always the 49-byte HELLO and no value of Android's starts with 05 (R14). P4 REJECT → AUTH.",
                T_HELLO, T_P4_REJECT),
            prod("short_hello", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's HELLO is always 49 bytes; the vector is it minus the last byte. P4 REJECT → AUTH.",
                T_HELLO, T_P4_REJECT),
            prod("data_before_confirm", HS, REJECT_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: seal() is null until CONFIRM_ACK verifies, and the engine sends only HELLO and CONFIRM before it. P4 REJECT → AUTH.",
                T_EARLY_DATA, T_P4_REJECT),
            prod("peer_silent_after_hello_ack", HS, "teardown, cooldown, no REJECT", true, false, "Unauthorized",
                "Android answers HELLO_ACK with CONFIRM well inside the 1 s stall bound; if no CONFIRM_ACK follows, Android's own 4.5 s expiry is a peer expiry: AUTH (DEADLINE), no REJECT, before the P4's 5 s.",
                T_PEER_SILENT),
            prod("secure_expiry_local_stall", HS, "teardown, no cooldown (A-5: the P4 owed the next step)", false, false, "Deadline",
                "Android counterpart: HELLO completed and no HELLO_ACK arrives, so Android (which owed nothing) ends AUTH (DEADLINE) at 4.5 s. Each side attributes locally (A-5); Android charges where the P4 does not.",
                T_LOCAL_STALL),
            prod("hello_ack_completion_timeout", HS, TEARDOWN_NO_COOLDOWN, false, false, "Deadline",
                "Android counterpart: the P4's teardown reaches Android as link loss before HELLO_ACK: TRANSPORT (CANCELLED), no cooldown, nothing more sent.",
                T_LOSS_BEFORE_ACK),
            prod("disconnect_during_handshake", HS, TEARDOWN_NO_COOLDOWN, false, false, "Connect",
                "Android counterpart: HELLO_ACK → X1 CONFIRM, then link loss: TRANSPORT (CANCELLED), no cooldown, nothing more sent.",
                T_LOSS_AFTER_CONFIRM),
            prod("mtu_63", TRANSPORT, "no CCCD write, teardown, no cooldown", false, false, "Connect",
                "Android counterpart: a CCCD enable at MTU 63 gets 0x0d and admits nothing; PipeEngine.begin refuses MTU 63 and accepts 64.",
                T_MTU, mtu = 63),
            prod("cccd_refused_0x11", TRANSPORT, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Android's own decision: 0x11 for another admitted device, cooldown or not ready, and a blocked engine (begin() false while a session exists) answers 0x11; the refused link gets nothing.",
                T_0X11),
            prod("counter_gap_allowed", PIPE, "both accepted; P4 answers each PING", false, true, "None",
                "Encoder parity at counters 7 and 9; Android itself never gaps (counters +1); the P4 PONGs decrypt on Android; RX mirror accepts gaps. Engine feed N/A: Android PINGs only with a live stream.",
                T_ENCODER, T_P4_VALUES, T_TX_COUNTERS, T_RX_COUNTERS),
            prod("counter_regression_after_gap", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's counter only rises by one; encoder parity at 7, 9, 8; RX mirror rejects 8 after 9 (AUTH).",
                T_ENCODER, T_P4_VALUES, T_TX_COUNTERS, T_RX_COUNTERS),
            prod("replayed_counter", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: the same inner frame sealed again gets the next counter and a different value; Android never resubmits (R10).",
                T_ENCODER, T_P4_VALUES, T_TX_COUNTERS),
            prod("counter_2_pow_63", PIPE, "accepted", false, true, "None",
                "Encoder parity at 2^63 (unsigned), then 2^63+1; RX mirror accepts 2^63 and then refuses a small counter.",
                T_ENCODER, T_P4_VALUES, T_MAX, T_RX_COUNTERS),
            prod("counter_max_once", PIPE, "first accepted, second: teardown, cooldown", true, false, "Unauthorized",
                "Never emitted: seal() at 2^64−1 returns null (EXHAUSTED) and the engine then ends TRANSPORT (NO_RESOURCES) with nothing sent; RX mirror accepts 2^64−1 once (A-6).",
                T_MAX, T_P4_VALUES, T_RX_COUNTERS),
            prod("tampered_data_tag", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: Android's counter-2 PING is replayed_counter frame 0, one bit away from the vector.",
                T_TAMPERED),
            prod("plaintext_after_handshake", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: the vector is the inner PING in clear; every post-handshake Android value is 0x10 DATA the reference P4 opens.",
                T_NO_PLAIN, T_WINDOW),
            prod("secure_hello_after_ready", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: hello() throws on an established channel; one HELLO per session, first.",
                T_NO_PLAIN, T_HELLO),
            prod("truncated_data", PIPE, TEARDOWN_COOLDOWN, true, false, "Unauthorized",
                "Never emitted: the vector is Android's own counter-2 value cut to 24 bytes; Android's values are inner + 25 ≥ 37 bytes.",
                T_NO_PLAIN, T_WINDOW),
            prod("oversize_value", PIPE, "teardown, no cooldown (refused before decryption)", false, false, "Protocol",
                "Never emitted: V(247) = 244 and P = 219; Android's fullest values are exactly 244 bytes (the vector is 245).",
                T_WINDOW),
            prod("inner_sequence_gap", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Never emitted: Android's inner sequence is contiguous from 0 in every engine run (consumed only at accept); encoder parity for the vector's bytes.",
                T_ENCODER, T_WINDOW),
            prod("inner_version_2", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Never emitted: every Android inner frame has version 1; encoder parity.",
                T_ENCODER, T_WINDOW),
            prod("frame_for_future_stream", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Never emitted: Android addresses only stream 0 or a stream the P4 opened; encoder parity.",
                T_ENCODER, T_WINDOW),
            prod("phone_reset_stream_0", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Deadline",
                "Android's own value: an inner failure (2 s reassembly DEADLINE) makes Android send exactly this RESET 13 at counter 2, then disconnect; PROTOCOL end, no cooldown on either side.",
                T_RESET0, T_ENCODER),
            prod("open_ok_window_4097", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Never emitted: Android's OPEN_OK to the vector's OPEN is X1's window 1024 (credit_beyond frame 1), within 1..4096.",
                T_OPEN_OK, T_ENCODER, T_P4_VALUES),
            prod("open_ok_window_4096", PIPE, "accepted", false, true, "None",
                "Android sends 1024, not 4096; both are inside the P4's 1..4096.",
                T_OPEN_OK, T_ENCODER, T_P4_VALUES),
            prod("credit_beyond_sent_plus_4096", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Flow",
                "Never emitted: no CREDIT without P4 data; after 600 P4 bytes every CREDIT ≤ 600 + 4096 and the last is exactly 4696.",
                T_OPEN_OK, T_ENCODER, T_P4_VALUES),
            prod("credit_repeat_tolerated", PIPE, "accepted", false, true, "None",
                "Never emitted (tolerated anyway): Android's CREDITs strictly increase and none repeats the current limit.",
                T_OPEN_OK, T_ENCODER, T_P4_VALUES),
            prod("receive_window_overflow", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Flow",
                "Never emitted: with the vector's 1025 bytes queued, Android sends frames 2–5 byte for byte, then 196 bytes (not 197), and waits for the P4's CREDIT.",
                T_WINDOW, T_ENCODER, T_P4_VALUES),
            prod("open_err_vpn", PIPE, "stream ends with Vpn, session continues", false, true, "None",
                "Android's own value: VPN down → exactly the vector's OPEN_ERR 5, no connect, the session stays READY (answers a PING).",
                T_VPN, T_ENCODER, T_P4_VALUES),
            prod("late_close_for_ended_stream", PIPE, "CLOSE_ACK, session continues", false, true, "None",
                "Android's OPEN_ERR 7 is the vector's frame 1; Android never sends the late CLOSE; the P4's CLOSE_ACK for the retired ID is discarded and the session stays READY.",
                T_LATE_CLOSE, T_ENCODER, T_P4_VALUES),
            prod("service_changed_after_ready", PIPE, TEARDOWN_NO_COOLDOWN, false, false, "Protocol",
                "Android counterpart: the P4's teardown reaches Android as link loss at READY: TRANSPORT (CANCELLED), no RESET, no cooldown.",
                T_SERVICE_CHANGED),
            contract("tampered_confirm_ack", "disconnect, no REJECT", "R14 invalid authentication", HS,
                "Engine: AUTH (BAD_PROTOCOL), nothing after CONFIRM, one disconnect. Channel: Invalid, FAILED/AUTH, terminal.",
                T_C_TAMPERED_ACK, T_C_CHANNEL),
            contract("p4_reject", "disconnect", "R14 REJECT counts only during the handshake", HS,
                "Engine, after HELLO and after CONFIRM: AUTH (NOT_AUTHORIZED), nothing more, one disconnect. Channel: Rejected(2), FAILED/AUTH.",
                T_C_REJECT, T_C_CHANNEL),
            contract("zero_device_public_key", "disconnect", "R4 low-order or zero X25519 is an authentication failure", HS,
                "Engine: AUTH (BAD_PROTOCOL) with no CONFIRM, one disconnect. Channel: Invalid, FAILED/AUTH.",
                T_C_ZERO_KEY, T_C_CHANNEL),
            contract("replayed_p4_data", "disconnect", "R6 strict increase, R14 crypto failure", PIPE,
                "Engine after X1's HELLO_ACK: AUTH (BAD_PROTOCOL), authenticated and ready, no RESET (R7), one disconnect. Channel: Invalid, terminal.",
                T_C_REPLAY, T_C_CHANNEL),
        )
    }

    // ---- Rigs and helpers ------------------------------------------------------------------------

    /** An engine with X1's entropy on both sides; [psk] is Android's, the fake P4 always has X1's. */
    private fun rig(psk: ByteArray = X1.psk): SecureRig = SecureRig(
        mtu = 247,
        psk = psk,
        p4Psk = X1.psk,
        random = FakeRandom(X1.bytes("phone_nonce_hex"), X1.phonePipeNonce),
        phoneKey = X1.bytes("phone_private_key_hex"),
        p4Key = X1.bytes("device_private_key_hex"),
        p4Nonce = X1.bytes("device_nonce_hex"),
        p4InnerNonce = X1.p4PipeNonce,
    ).also {
        // Every P4 handshake value in this file is a fixture value, written by the test.
        it.autoHandshake = false
        rigs += it
    }

    /** X1 frame 0 (the CCCD enable, answered 0x00) at MTU 247, then Android's HELLO, which must be X1 frame 1. */
    private fun admit(r: SecureRig) {
        assertTrue("CCCD enable admitted", r.begin())
        r.subscribe()
        Poll.until("HELLO") { r.link.sent.size == 1 }
        assertEquals("HELLO", X1.valueHex(1), toHex(r.link.sent[0].value))
        r.awaitQuiet()
    }

    /** Writes one P4 value and waits until Android has submitted [values] values in all, every one completed. */
    private fun writeAndAwait(r: SecureRig, value: ByteArray, values: Int) {
        r.write(value)
        Poll.until("$values phone values") { r.link.sent.size == values }
        r.awaitQuiet()
    }

    /**
     * The MTU-247 X1 session through the phone's pipe HELLO_ACK (frames 0–6), every Android value checked against
     * X1. Afterwards the fake P4 seals at counter 2 and inner sequence 1, where X2's pipe vectors continue.
     */
    private fun x1Prefix(r: SecureRig) {
        admit(r)
        writeAndAwait(r, X1.value(2), 2)
        assertEquals("CONFIRM", X1.valueHex(3), toHex(r.link.sent[1].value))
        r.write(X1.value(4))
        r.awaitStage("secure")
        writeAndAwait(r, X1.value(5), 3)
        assertEquals("pipe HELLO_ACK", X1.valueHex(6), toHex(r.link.sent[2].value))
        r.awaitStage("ready")
        assertEquals(X1.innerHex(6), r.sync().single().frame.hexString)
        r.p4.txCounter = 2
        r.p4.inner.seq = 1
    }

    /**
     * The session's PipeEnd is [expected] in full, reported once with the engine idle, after exactly [values]
     * submissions (nothing after the vector frame unless counted), never a REJECT, and [disconnects] disconnect()
     * calls, all before the PipeEnd.
     */
    private fun assertEnded(r: SecureRig, expected: PipeEnd, values: Int, disconnects: Int, timeoutMs: Long = 7_000): EndRecord {
        val end = r.awaitEnd(timeoutMs = timeoutMs)
        Thread.sleep(250)
        r.sync()
        assertEquals(expected, end.end)
        assertEquals("PipeEnd once", 1, r.endsOf().size)
        assertFalse("the engine is idle when PipeEnd arrives", end.activeAtEnd)
        assertEquals("values submitted before PipeEnd", values, end.sentAtEnd)
        assertEquals("values submitted in all", values, r.link.sent.size)
        assertNoReject(r)
        assertEquals("disconnect() before PipeEnd", disconnects, end.disconnectsAtEnd)
        assertEquals("disconnect() in all", disconnects, r.link.disconnects.size)
        return end
    }

    /** R14: Android never sends REJECT (type 05); PipeSecureChannel has no encoder for it. */
    private fun assertNoReject(r: SecureRig) {
        for (s in r.link.sent) assertNotEquals("value #${s.index} is a REJECT", 0x05, s.value[0].toInt() and 0xff)
    }

    /**
     * Everything Android sent in a pipe-stage run is legal for the P4: X1's HELLO then CONFIRM, then only secure DATA
     * the reference P4 opens at counters 1, 2, 3, … with inner sequences 0, 1, 2, …, every value ≤ V = 244 and inner
     * frame ≤ P = 219, version 1, legal framing (kit `assertWellFormed`), no REJECT, and only stream 0 or a stream
     * the P4 opened ([opened]).
     */
    private fun assertPipeValuesWellFormed(r: SecureRig, opened: Set<Int>) {
        r.assertWellFormed(P247)
        val values = r.link.sent.map { it.value }
        assertEquals("HELLO first", X1.valueHex(1), toHex(values[0]))
        assertEquals("CONFIRM second", X1.valueHex(3), toHex(values[1]))
        for ((i, v) in values.withIndex()) {
            if (i < 2) continue
            assertEquals("value #$i is secure DATA", 0x10, v[0].toInt() and 0xff)
            assertTrue("value #$i carries a whole inner header", v.size >= PipeSecureChannel.OVERHEAD + PipeWire.HEADER)
            assertTrue("value #$i within V", v.size <= V247)
        }
        assertNoReject(r)
        for (f in r.sync()) {
            assertEquals("$f version", 1, f.frame.version)
            assertTrue("$f addresses stream 0 or one the P4 opened", f.id == 0 || f.id in opened)
        }
    }

    private fun cccd(value: ByteArray, mtu: Int = 247, otherAdmitted: Boolean = false, coolingDown: Boolean = false, ready: Boolean = true) =
        PipeAttPolicy.cccdWrite(
            isCccd = true, responseNeeded = true, preparedWrite = false, offset = 0, value = value, admitted = false,
            otherAdmitted = otherAdmitted, coolingDown = coolingDown, ready = ready, mtu = mtu,
        )

    /** Moves the engine's live channel counters, under the engine lock, as PipeExhaustionTest does for streams. */
    private fun setEngineCounters(engine: PipeEngine, tx: Long, rx: Long) {
        val lock = PipeEngine::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(engine) as ReentrantLock
        val channel = PipeEngine::class.java.getDeclaredField("channel").apply { isAccessible = true }
        lock.withLock { (channel.get(engine) as PipeSecureChannel).setCountersForTest(tx, rx) }
    }

    private fun channel(psk: ByteArray = X1.psk, privateKey: ByteArray = X1.bytes("phone_private_key_hex")) =
        PipeSecureChannel(psk, FakeRandom(X1.bytes("phone_nonce_hex")), privateKey)

    private fun awaitingHelloAck(psk: ByteArray = X1.psk) = channel(psk).also { assertEquals(X1.valueHex(1), toHex(it.hello())) }

    private fun awaitingConfirmAck() = awaitingHelloAck().also { assertEquals(X1.valueHex(3), toHex(reply(it.receive(X1.value(2))))) }

    private fun established() = awaitingConfirmAck().also { assertSame(Rx.Established, it.receive(X1.value(4))) }

    /** X1 frames 5 and 6 through the channel: P4 counter 1 consumed, phone counter 1 used. */
    private fun afterX1Prefix() = established().also {
        assertEquals(X1.innerHex(5), toHex(data(it.receive(X1.value(5)))))
        assertEquals(X1.valueHex(6), toHex(it.seal(hex(X1.innerHex(6)))!!))
    }

    private fun reply(rx: Rx): ByteArray {
        assertTrue("expected Reply, got $rx", rx is Rx.Reply)
        return (rx as Rx.Reply).value
    }

    private fun data(rx: Rx): ByteArray {
        assertTrue("expected Data, got $rx", rx is Rx.Data)
        return (rx as Rx.Data).plaintext
    }

    /** FAILED with [failure]; nothing can be sealed and the genuine [next] value cannot revive it. */
    private fun assertFailedForGood(ch: PipeSecureChannel, failure: Failure, next: ByteArray) {
        assertEquals(State.FAILED, ch.state)
        assertEquals(failure, ch.failure)
        assertNull("nothing is sealed after the failure", ch.seal(ByteArray(PipeWire.HEADER)))
        assertSame("the genuine next value cannot revive the channel", Rx.Invalid, ch.receive(next))
        assertEquals("the first failure stays", failure, ch.failure)
    }

    /** (index, xor) of every differing byte of two equal-length values. */
    private fun diff(a: ByteArray, b: ByteArray): List<Pair<Int, Int>> {
        assertEquals("lengths", a.size, b.size)
        return a.indices.filter { a[it] != b[it] }.map { it to ((a[it].toInt() xor b[it].toInt()) and 0xff) }
    }

    // ---- The fixture itself ----------------------------------------------------------------------

    /**
     * The resource is the firmware's X2 file (SHA-256 as in firmware-007.md) with exactly the 40 vectors of [ROWS],
     * in order; each vector's direction, source, stage, MTU, `expected`, cooldown flag, `session_continues`,
     * `p4_phase`, `p4_error`, `rule` and key set are the ones this file was written against. Every test a row names
     * exists, and every test here (except this one and the X1 check) covers at least one vector.
     */
    @Test
    fun theX2FixtureIsTheFirmwareFileAndEveryVectorIsClassified() {
        assertEquals(X2.SHA256, sha256(X2.raw))
        assertEquals(ROWS.map { it.id }, X2.vectors.map { it.getString("id") })
        val prodKeys = setOf("id", "direction", "source", "stage", "att_mtu", "description", "expected", "p4_sends_hex",
            "session_continues", "p4_phase", "auth_cooldown", "p4_error", "frames")
        val contractKeys = setOf("id", "direction", "source", "stage", "att_mtu", "description", "expected",
            "android_auth_cooldown", "rule", "frames")
        for (row in ROWS) {
            val v = X2.vector(row.id)
            val id = row.id
            assertEquals(id, row.direction, v.getString("direction"))
            assertEquals(id, row.source, v.getString("source"))
            assertEquals(id, row.stage, v.getString("stage"))
            assertEquals(id, row.mtu, v.getInt("att_mtu"))
            assertEquals(id, row.expected, v.getString("expected"))
            if (row.source == CONTRACT) {
                assertEquals(id, contractKeys, v.keySet())
                assertEquals(id, row.cooldown, v.getBoolean("android_auth_cooldown"))
                assertEquals(id, row.rule, v.getString("rule"))
                assertEquals("$id: one P4 → phone value", 1, framesOf(v).size)
                assertEquals(id, D2P, framesOf(v).single().getString("dir"))
            } else {
                assertEquals(id, prodKeys, v.keySet())
                assertEquals(id, row.cooldown, v.getBoolean("auth_cooldown"))
                assertEquals(id, row.continues, v.getBoolean("session_continues"))
                assertEquals(id, if (row.continues!!) "pipe" else "retiring", v.getString("p4_phase"))
                assertEquals(id, row.p4Error, v.getString("p4_error"))
            }
            assertTrue("$id names a test", row.tests.isNotEmpty())
            for (t in row.tests) assertNotNull("$id → $t is a @Test", javaClass.getMethod(t).getAnnotation(Test::class.java))
        }
        assertEquals(listOf("tampered_confirm_ack", "p4_reject", "zero_device_public_key", "replayed_p4_data"),
            ROWS.filter { it.source == CONTRACT }.map { it.id })
        val named = ROWS.flatMap { it.tests }.toSet() + T_META + T_X1
        val declared = javaClass.declaredMethods.filter { it.getAnnotation(Test::class.java) != null }.map { it.name }.toSet()
        assertEquals("every test covers a vector", declared, named)
    }

    /**
     * X2 `base.pipe` says the pipe vectors continue X1's MTU-247 session: X1 (verbatim, SHA-256 as in firmware-004.md)
     * frames 1–6 are the existing golden transcript, the handshake vectors start with X1's CCCD value, HELLO and
     * HELLO_ACK, the stream vectors' OPEN is X1 frame 7 and their OPEN_OK 1024 is X1 frame 8, `replayed_p4_data` is
     * X1 frame 5 again, `tampered_confirm_ack` is X1 frame 4 with its last bit flipped and `zero_device_public_key`
     * is X1 frame 2 with the key zeroed.
     */
    @Test
    fun pipeVectorsContinueTheMtu247X1SessionWhichIsTheGolden() {
        assertEquals(X1.SHA256, sha256(X1.raw))
        val golden = mapOf(1 to "hello_hex", 2 to "hello_ack_hex", 3 to "confirm_hex", 4 to "confirm_ack_hex",
            5 to "p4_to_phone_data_hex", 6 to "phone_to_p4_data_hex")
        for ((i, name) in golden) assertEquals("X1 frame $i = golden $name", PipeFixture.string(name), X1.valueHex(i))
        for (name in listOf("psk_hex", "phone_private_key_hex", "device_private_key_hex", "phone_nonce_hex", "device_nonce_hex",
            "phone_to_p4_key_hex", "p4_to_phone_key_hex")) assertEquals(name, PipeFixture.string(name), X1.json.getString(name))
        assertEquals(toHex(syntheticPsk), X1.json.getString("psk_hex"))

        for (id in listOf("wrong_passphrase", "console_domain", "tampered_confirm", "data_before_confirm", "peer_silent_after_hello_ack",
            "hello_ack_completion_timeout", "disconnect_during_handshake")) {
            for (i in 0..2) assertEquals("$id frame $i", X1.valueHex(i), X2.valueHex(id, i))
            assertEquals("$id: the P4 wrote X1's HELLO_ACK", X1.valueHex(2), toHex(X2.sends(id)[0]))
        }
        for (i in 0..1) assertEquals("secure_expiry_local_stall frame $i", X1.valueHex(i), X2.valueHex("secure_expiry_local_stall", i))
        for (id in listOf("zero_public_key", "low_order_public_key", "phone_sends_reject", "short_hello", "cccd_refused_0x11")) {
            assertEquals("$id frame 0", X1.valueHex(0), X2.valueHex(id, 0))
        }
        for (id in listOf("open_ok_window_4097", "open_ok_window_4096", "credit_beyond_sent_plus_4096", "credit_repeat_tolerated",
            "receive_window_overflow", "open_err_vpn", "late_close_for_ended_stream")) {
            assertEquals("$id OPEN = X1 frame 7", X1.valueHex(7), X2.valueHex(id, 0))
        }
        for (id in listOf("credit_beyond_sent_plus_4096", "credit_repeat_tolerated", "receive_window_overflow")) {
            assertEquals("$id OPEN_OK 1024 = X1 frame 8", X1.valueHex(8), X2.valueHex(id, 1))
        }
        assertEquals(X1.valueHex(5), X2.valueHex("replayed_p4_data", 0))
        assertEquals(listOf(18 to 1), diff(X1.value(4), X2.value("tampered_confirm_ack", 0)))
        val zeroed = X1.value(2).also { it.fill(0, 1, 33) }
        assertEquals(toHex(zeroed), X2.valueHex("zero_device_public_key", 0))
    }

    // ---- (a) The four contract vectors: Android must reject these P4 bytes ------------------------

    /**
     * `tampered_confirm_ack` (R14): after X1's HELLO and CONFIRM, the production CONFIRM_ACK with its last tag bit
     * flipped ends the session as AUTH (BAD_PROTOCOL; the kind PipeRuntime charges, the charge not exercised here),
     * unauthenticated and not ready, with nothing sent after CONFIRM (no REJECT) and the link disconnected once.
     */
    @Test
    fun contractTamperedConfirmAckEndsAuthWithNoRejectAndADisconnect() {
        val r = rig()
        admit(r)
        writeAndAwait(r, X1.value(2), 2)
        assertEquals(X1.valueHex(3), toHex(r.link.sent[1].value))
        r.write(X2.value("tampered_confirm_ack", 0))
        val end = assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = false, ready = false), values = 2, disconnects = 1)
        assertEquals(PipeEndKind.AUTH, end.end.kind)
        assertFalse("never reached secure", r.stages.any { it.first == r.token && it.second == "secure" })
    }

    /**
     * `p4_reject` (R14: REJECT counts during the handshake): REJECT 05 02 while Android awaits HELLO_ACK, and again
     * while it awaits CONFIRM_ACK, ends the session as AUTH (NOT_AUTHORIZED) with nothing more sent and one disconnect.
     */
    @Test
    fun contractP4RejectInEitherHandshakePhaseEndsAuthWithNoFurtherValue() {
        val reject = X2.value("p4_reject", 0)
        assertEquals("0502", toHex(reject))
        val expectedEnd = { r: SecureRig -> PipeEnd(r.token, PipeEndKind.AUTH, PipeError.NOT_AUTHORIZED, unsettled = false, authenticated = false, ready = false) }

        val afterHello = rig()
        admit(afterHello)
        afterHello.write(reject)
        assertEnded(afterHello, expectedEnd(afterHello), values = 1, disconnects = 1)

        val afterConfirm = rig()
        admit(afterConfirm)
        writeAndAwait(afterConfirm, X1.value(2), 2)
        afterConfirm.write(reject)
        assertEnded(afterConfirm, expectedEnd(afterConfirm), values = 2, disconnects = 1)
    }

    /**
     * `zero_device_public_key` (R4): a HELLO_ACK whose X25519 result is all zero ends the session as AUTH
     * (BAD_PROTOCOL) at once: Android sends no CONFIRM, no REJECT, and disconnects once.
     */
    @Test
    fun contractZeroDevicePublicKeyEndsAuthBeforeAnyConfirm() {
        val r = rig()
        admit(r)
        r.write(X2.value("zero_device_public_key", 0))
        assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = false, ready = false), values = 1, disconnects = 1)
    }

    /**
     * `replayed_p4_data` (R6 strict increase, R14): after X1's session reached READY (frames 0–6), the P4's counter-1
     * DATA (its pipe HELLO) delivered again ends the session as AUTH (BAD_PROTOCOL), authenticated and ready, with
     * no encrypted RESET (R7: a crypto failure is silent), nothing more sent and one disconnect.
     */
    @Test
    fun contractReplayedP4DataAfterReadyEndsAuthWithNoReset() {
        val r = rig()
        x1Prefix(r)
        r.write(X2.value("replayed_p4_data", 0))
        assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, unsettled = false, authenticated = true, ready = true), values = 3, disconnects = 1)
        assertPipeValuesWellFormed(r, emptySet())
    }

    /**
     * The four contract vectors at the [PipeSecureChannel] level: `tampered_confirm_ack` and
     * `zero_device_public_key` → Rx.Invalid, `p4_reject` → Rx.Rejected(2) in both handshake phases,
     * `replayed_p4_data` → Rx.Invalid; each leaves the channel FAILED with Failure.AUTH, refusing to seal and
     * refusing the genuine next X1 value.
     */
    @Test
    fun contractVectorsFailThePipeSecureChannelForGood() {
        awaitingConfirmAck().let { ch ->
            assertSame(Rx.Invalid, ch.receive(X2.value("tampered_confirm_ack", 0)))
            assertFailedForGood(ch, Failure.AUTH, X1.value(4))
        }
        for ((ch, next) in listOf(awaitingHelloAck() to X1.value(2), awaitingConfirmAck() to X1.value(4))) {
            val rx = ch.receive(X2.value("p4_reject", 0))
            assertTrue("Rejected, got $rx", rx is Rx.Rejected)
            assertEquals(2, (rx as Rx.Rejected).reason)
            assertFailedForGood(ch, Failure.AUTH, next)
        }
        awaitingHelloAck().let { ch ->
            assertSame(Rx.Invalid, ch.receive(X2.value("zero_device_public_key", 0)))
            assertFailedForGood(ch, Failure.AUTH, X1.value(2))
        }
        afterX1Prefix().let { ch ->
            assertSame(Rx.Invalid, ch.receive(X2.value("replayed_p4_data", 0)))
            assertFailedForGood(ch, Failure.AUTH, X1.value(7))
        }
    }

    // ---- (b) Handshake p4_production vectors -----------------------------------------------------

    /**
     * Android's reaction to the P4 bytes (`p4_sends_hex`) of the eight vectors the P4 ends with REJECT 05 02: Android
     * sends X1's HELLO, answers the P4's HELLO_ACK (where there is one) with its own CONFIRM (X1's, or for
     * `wrong_passphrase` with Android holding that passphrase, exactly the vector's), so the values Android has sent
     * when the REJECT arrives are exactly HELLO (types `01`) or HELLO and CONFIRM (`01 03`), and the REJECT then ends
     * the session as AUTH (NOT_AUTHORIZED) with nothing more sent and one disconnect.
     *
     * Where the vector's offending value sits in Android's slot, the test states how the two differ, each in a form
     * that fails if Android sent the vector's value: the zero and order-8 keys differ from Android's HELLO only in
     * the key bytes 1–32 (same type, length and nonce); `short_hello` is Android's HELLO minus its last byte;
     * `phone_sends_reject` is a type-05 value where Android's is type 01; `tampered_confirm` differs from Android's
     * CONFIRM in exactly bit 0 of byte 18; `console_domain` is a CONFIRM of the same type and length with different
     * bytes; `data_before_confirm`'s frame 3 is a type-10 DATA value where Android's is its type-03 CONFIRM, and no
     * Android value in the run is type 10.
     */
    @Test
    fun theP4RejectThatEndsEachHandshakeVectorEndsTheAndroidSessionAsAuth() {
        val afterHello = listOf("zero_public_key", "low_order_public_key", "phone_sends_reject", "short_hello")
        val afterConfirm = listOf("wrong_passphrase", "console_domain", "tampered_confirm", "data_before_confirm")
        val type = { v: ByteArray -> v[0].toInt() and 0xff }
        for (id in afterHello + afterConfirm) {
            val sends = X2.sends(id)
            assertEquals("$id ends in REJECT 05 02", "0502", toHex(sends.last()))
            assertEquals("$id: P4 values", if (id in afterHello) 1 else 2, sends.size)
            val r = rig(psk = if (id == "wrong_passphrase") wrongPsk else X1.psk)
            admit(r)
            if (id in afterHello) {
                val hello = r.link.sent[0].value
                val vector = X2.value(id, 1)
                when (id) {
                    "zero_public_key", "low_order_public_key" -> {
                        assertEquals("$id frame 1 kind", "secure_hello", X2.frames(id)[1].getString("kind"))
                        val differing = diff(hello, vector).map { it.first }
                        assertTrue("$id: Android's key differs from the vector's", differing.isNotEmpty())
                        assertTrue("$id: only key bytes 1..32 differ, got $differing", differing.all { it in 1..32 })
                    }
                    "short_hello" -> {
                        assertEquals("$id frame 1 kind", "secure_hello", X2.frames(id)[1].getString("kind"))
                        assertEquals("$id: Android's HELLO", 49, hello.size)
                        assertEquals("$id: the vector", 48, vector.size)
                        assertEquals("$id: the vector is Android's HELLO minus its last byte", toHex(hello.copyOf(48)), toHex(vector))
                    }
                    "phone_sends_reject" -> {
                        assertEquals("$id frame 1 kind", "secure_reject", X2.frames(id)[1].getString("kind"))
                        assertEquals("$id: the vector is a REJECT", 0x05, type(vector))
                        assertEquals("$id: Android's first value is a HELLO", 0x01, type(hello))
                    }
                    else -> throw AssertionError("unclassified $id")
                }
            }
            for (value in sends.dropLast(1)) {
                assertEquals("$id: HELLO_ACK", X1.valueHex(2), toHex(value))
                writeAndAwait(r, value, 2)
                val confirm = r.link.sent[1].value
                val vector = X2.value(id, 3)
                when (id) {
                    "wrong_passphrase" -> assertEquals("$id CONFIRM", toHex(vector), toHex(confirm))
                    "console_domain" -> {
                        assertEquals("$id CONFIRM", X1.valueHex(3), toHex(confirm))
                        assertEquals("$id frame 3 kind", "secure_confirm", X2.frames(id)[3].getString("kind"))
                        assertEquals("$id: same type", type(confirm), type(vector))
                        assertEquals("$id: same length", confirm.size, vector.size)
                        assertNotEquals("$id: Android's CONFIRM is not the vector's", toHex(vector), toHex(confirm))
                    }
                    "tampered_confirm" -> {
                        assertEquals("$id CONFIRM", X1.valueHex(3), toHex(confirm))
                        assertEquals("$id frame 3 kind", "secure_confirm", X2.frames(id)[3].getString("kind"))
                        assertEquals("$id: one bit apart", listOf(18 to 1), diff(confirm, vector))
                    }
                    "data_before_confirm" -> {
                        assertEquals("$id CONFIRM", X1.valueHex(3), toHex(confirm))
                        assertEquals("$id frame 3 kind", "data", X2.frames(id)[3].getString("kind"))
                        assertEquals("$id: the vector's frame 3 is secure DATA", 0x10, type(vector))
                        assertEquals("$id: Android's value in that slot is a CONFIRM", 0x03, type(confirm))
                    }
                    else -> throw AssertionError("unclassified $id")
                }
            }
            val before = r.link.sent.size
            assertEquals("$id: Android's value types when the REJECT arrives",
                if (id in afterHello) listOf(0x01) else listOf(0x01, 0x03), r.link.sent.map { type(it.value) })
            r.write(sends.last())
            assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.NOT_AUTHORIZED, unsettled = false, authenticated = false, ready = false), values = before, disconnects = 1)
            assertTrue("$id: no secure DATA in the run", r.link.sent.none { type(it.value) == 0x10 })
        }
    }

    /**
     * `wrong_passphrase`: the note names the passphrase. Taken through Android's entry rule and PBKDF2
     * ([PipeSecret]), it gives a CONFIRM equal to the vector's frame 3 after X1's HELLO and HELLO_ACK, so the P4's
     * verdict (REJECT, cooldown) is about bytes Android really sends with a wrong secret. The X1 passphrase gives
     * X1's PSK through the same path.
     */
    @Test
    fun aWrongPassphraseMakesAndroidSendExactlyTheVectorsConfirm() {
        assertTrue(X2.frames("wrong_passphrase")[3].getString("note").endsWith(WRONG_PASSPHRASE))
        assertEquals(X1.json.getString("psk_hex"), toHex(syntheticPsk))
        assertNotEquals(toHex(syntheticPsk), toHex(wrongPsk))
        val ch = awaitingHelloAck(wrongPsk)
        assertEquals(X2.valueHex("wrong_passphrase", 3), toHex(reply(ch.receive(X2.value("wrong_passphrase", 2)))))
    }

    /**
     * `console_domain`: the vector's CONFIRM is the console derivation (PBKDF2 and HKDF info "HW1-SC-v1", recomputed
     * here with reference code). Android's pipe salt and HKDF info are "HW1-PIPE-v1", its PSK of the X1 passphrase
     * is X1's, and even handed the console PSK the pipe channel's CONFIRM is not the vector's; with the pipe PSK it
     * is X1's.
     */
    @Test
    fun theConsoleDomainConfirmIsNothingThePipeChannelCanDerive() {
        val passphrase = X1.json.getString("passphrase_utf8").toByteArray(Charsets.UTF_8)
        val consolePsk = PKCS5S2ParametersGenerator(SHA256Digest()).run {
            init(passphrase, ascii("HW1-SC-v1"), 100_000)
            (generateDerivedMacParameters(256) as KeyParameter).key
        }
        val salt = X1.bytes("phone_nonce_hex") + X1.bytes("device_nonce_hex")
        val keys = P4Crypto.hkdf(X1.bytes("shared_secret_hex") + consolePsk, salt, ascii("HW1-SC-v1"), 64)
        val consoleConfirm = byteArrayOf(3) + P4Crypto.seal(keys.copyOf(32), P4Crypto.nonce(0, 0), ascii("ok"))
        assertEquals("the vector is the console derivation", X2.valueHex("console_domain", 3), toHex(consoleConfirm))

        assertEquals("HW1-PIPE-v1", PipeSecret.SALT)
        assertEquals("HW1-PIPE-v1", PipeSecureChannel.LABEL)
        assertEquals(X1.json.getString("psk_hex"), toHex(syntheticPsk))
        assertNotEquals(toHex(consolePsk), toHex(syntheticPsk))
        val withConsolePsk = reply(awaitingHelloAck(consolePsk).receive(X1.value(2)))
        assertNotEquals(X2.valueHex("console_domain", 3), toHex(withConsolePsk))
        assertEquals(X1.valueHex(3), toHex(reply(awaitingHelloAck().receive(X1.value(2)))))
    }

    /**
     * `tampered_confirm` and `tampered_data_tag`: Android's values are deterministic for X1's entropy. Its CONFIRM is
     * X1 frame 3 and its counter-2 PING (sequence 1, cookie 09080706) is `replayed_counter` frame 0; each tampered
     * vector differs from Android's value in exactly bit 0 of the last tag byte.
     */
    @Test
    fun tamperedVectorsDifferFromAndroidsOwnValuesInExactlyTheLastBit() {
        val confirm = reply(awaitingHelloAck().receive(X2.value("tampered_confirm", 2)))
        assertEquals(X1.valueHex(3), toHex(confirm))
        assertEquals(listOf(18 to 1), diff(confirm, X2.value("tampered_confirm", 3)))

        val ch = afterX1Prefix()
        val ping = ch.seal(hex(X2.innerHex("replayed_counter", 0)))!!
        assertEquals(X2.valueHex("replayed_counter", 0), toHex(ping))
        assertEquals(listOf(ping.size - 1 to 1), diff(ping, X2.value("tampered_data_tag", 0)))
    }

    /**
     * `zero_public_key` and `low_order_public_key`: Android's HELLO carries X25519(private key, 9) from the clamped
     * scalar. The reference is [Rfc7748], the RFC 7748 §5 ladder written out in this file over BigInteger, so it
     * shares no code with BouncyCastle (which both [PipeSecureChannel] and the kit's [P4Crypto] use); it is first
     * checked against X1's own key material (both public keys in X1 frames 1 and 2, and the shared secret from either
     * side), which the firmware tooling produced. Against that reference, for the fixture key, all-zero, all-0xff and
     * 16 random keys, Android's public key is the ladder's, it is neither the zero key nor the vector's order-8 point,
     * and the P4's shared secret with it (fixture device key) is never all zero, while the ladder gives the all-zero
     * secret for both vector keys (they really are the zero and a low-order point). The vectors keep X1's phone nonce,
     * so the key is their only difference.
     */
    @Test
    fun androidsPublicKeyIsNeverZeroOrLowOrder() {
        val phoneKey = X1.bytes("phone_private_key_hex")
        val deviceKey = X1.bytes("device_private_key_hex")
        val phonePub = X1.value(1).copyOfRange(1, 33)
        val devicePub = X1.value(2).copyOfRange(1, 33)
        assertEquals("ladder: X1 phone public key", toHex(phonePub), toHex(Rfc7748.publicKey(phoneKey)))
        assertEquals("ladder: X1 device public key", toHex(devicePub), toHex(Rfc7748.publicKey(deviceKey)))
        assertEquals("ladder: X1 shared secret (phone side)", X1.json.getString("shared_secret_hex"), toHex(Rfc7748.x25519(phoneKey, devicePub)))
        assertEquals("ladder: X1 shared secret (device side)", X1.json.getString("shared_secret_hex"), toHex(Rfc7748.x25519(deviceKey, phonePub)))

        val zero = X2.value("zero_public_key", 1)
        val lowOrder = X2.value("low_order_public_key", 1)
        assertEquals("00".repeat(32), toHex(zero.copyOfRange(1, 33)))
        for (v in listOf(zero, lowOrder)) {
            assertEquals(toHex(X1.value(1).copyOfRange(33, 49)), toHex(v.copyOfRange(33, 49)))
            assertEquals("the vector key gives the all-zero secret", "00".repeat(32), toHex(Rfc7748.x25519(deviceKey, v.copyOfRange(1, 33))))
        }
        val rng = SecureRandom()
        val keys = listOf(phoneKey, ByteArray(32), bytes(32, 0xff)) + List(16) { ByteArray(32).also(rng::nextBytes) }
        for (key in keys) {
            val hello = channel(privateKey = key).hello()
            val pub = hello.copyOfRange(1, 33)
            assertEquals("Android's public key for ${toHex(key)}", toHex(Rfc7748.publicKey(key)), toHex(pub))
            assertNotEquals(toHex(zero.copyOfRange(1, 33)), toHex(pub))
            assertNotEquals(toHex(lowOrder.copyOfRange(1, 33)), toHex(pub))
            assertNotEquals("the P4's X25519 with Android's key is nonzero", "00".repeat(32), toHex(Rfc7748.x25519(deviceKey, pub)))
        }
    }

    /**
     * RFC 7748 §5 X25519 (decodeScalar25519 clamping, u masked to 255 bits, the constant-structure Montgomery ladder
     * with a24 = 121665, inversion by z^(p−2)) over BigInteger: a test-only reference that shares no code with
     * BouncyCastle. Not constant-time; used only on synthetic keys. A low-order u gives z = 0 and the all-zero output.
     */
    private object Rfc7748 {
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val A24: BigInteger = BigInteger.valueOf(121_665)
        private val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

        fun publicKey(k: ByteArray): ByteArray = x25519(k, BASE)

        fun x25519(k: ByteArray, u: ByteArray): ByteArray {
            require(k.size == 32 && u.size == 32)
            val clamped = k.copyOf().also {
                it[0] = (it[0].toInt() and 248).toByte()
                it[31] = ((it[31].toInt() and 127) or 64).toByte()
            }
            val scalar = decode(clamped)
            val x1 = decode(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }).mod(P)
            var x2 = BigInteger.ONE
            var z2 = BigInteger.ZERO
            var x3 = x1
            var z3 = BigInteger.ONE
            var swap = false
            for (t in 254 downTo 0) {
                val bit = scalar.testBit(t)
                if (swap != bit) {
                    x2 = x3.also { x3 = x2 }
                    z2 = z3.also { z3 = z2 }
                }
                swap = bit
                val a = x2.add(z2).mod(P)
                val aa = a.multiply(a).mod(P)
                val b = x2.subtract(z2).mod(P)
                val bb = b.multiply(b).mod(P)
                val e = aa.subtract(bb).mod(P)
                val c = x3.add(z3).mod(P)
                val d = x3.subtract(z3).mod(P)
                val da = d.multiply(a).mod(P)
                val cb = c.multiply(b).mod(P)
                x3 = da.add(cb).pow(2).mod(P)
                z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
                x2 = aa.multiply(bb).mod(P)
                z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
            }
            if (swap) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
        }

        private fun decode(le: ByteArray): BigInteger = BigInteger(1, le.reversedArray())

        private fun encode(n: BigInteger): ByteArray {
            val be = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return (ByteArray(32 - be.size) + be).reversedArray()
        }
    }

    /**
     * `phone_sends_reject` and `short_hello` (and one HELLO per session for `secure_hello_after_ready`): Android's
     * first value is always the 49-byte HELLO (X1 frame 1; `short_hello` is it minus its last byte), and a full X1
     * session's values are HELLO, CONFIRM, then DATA only: never a REJECT, never a second HELLO.
     */
    @Test
    fun androidsFirstValueIsTheFullHelloAndItNeverSendsReject() {
        val hello = channel().hello()
        assertEquals(PipeSecureChannel.HELLO_SIZE, hello.size)
        assertEquals(49, hello.size)
        assertEquals(X1.valueHex(1), toHex(hello))
        assertEquals(toHex(hello.copyOf(48)), X2.valueHex("short_hello", 1))
        assertEquals("0502", X2.valueHex("phone_sends_reject", 1))

        val r = rig()
        x1Prefix(r)
        r.send(Op.PING, 0, hex("09080706"))
        r.await("PONG") { it.op == Op.PONG }
        r.awaitQuiet()
        assertEquals(listOf(0x01, 0x03, 0x10, 0x10), r.link.sent.map { it.value[0].toInt() and 0xff })
        assertPipeValuesWellFormed(r, emptySet())
    }

    /**
     * `data_before_confirm`: [PipeSecureChannel.seal] returns null (state unchanged, no failure) in NEW,
     * AWAIT_HELLO_ACK and AWAIT_CONFIRM_ACK, and the engine sends only HELLO and CONFIRM until CONFIRM_ACK verifies.
     */
    @Test
    fun androidSealsNoDataBeforeConfirmAck() {
        val inner = hex(X2.innerHex("replayed_counter", 0))
        val ch = channel()
        assertNull(ch.seal(inner))
        assertEquals(State.NEW, ch.state)
        ch.hello()
        assertNull(ch.seal(inner))
        assertEquals(State.AWAIT_HELLO_ACK, ch.state)
        reply(ch.receive(X1.value(2)))
        assertNull(ch.seal(inner))
        assertEquals(State.AWAIT_CONFIRM_ACK, ch.state)
        assertNull(ch.failure)

        val r = rig()
        admit(r)
        writeAndAwait(r, X1.value(2), 2)
        r.settle(300)
        assertEquals(listOf(0x01, 0x03), r.link.sent.map { it.value[0].toInt() and 0xff })
        assertEquals("handshake", r.engine.currentStats.stage)
    }

    /**
     * `peer_silent_after_hello_ack` (the P4 charges because the phone owed CONFIRM): Android owes nothing for long —
     * its CONFIRM to the P4's HELLO_ACK is submitted well inside the 1 s local-stall bound. If no CONFIRM_ACK then
     * arrives, Android's own 4.5 s expiry from its CCCD answer is a peer expiry (A-5): AUTH (DEADLINE), no REJECT,
     * one disconnect, before the P4's 5 s.
     */
    @Test
    fun androidAnswersHelloAckAtOnceAndAMissingConfirmAckIsAPeerExpiry() {
        val r = rig()
        admit(r)
        val at = System.nanoTime()
        writeAndAwait(r, X2.sends("peer_silent_after_hello_ack").single(), 2)
        assertEquals(X1.valueHex(3), toHex(r.link.sent[1].value))
        val delay = ms(at, r.link.sent[1].atNanos)
        assertTrue("CONFIRM $delay ms after HELLO_ACK", delay < PipeEngine.STALL_MS)
        val end = assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.DEADLINE, unsettled = false, authenticated = false, ready = false), values = 2, disconnects = 1)
        val after = ms(r.conn.subscribedAtNanos, end.atNanos)
        assertTrue("expiry $after ms after the CCCD answer", after >= PipeEngine.HANDSHAKE_MS - 10 && after < 4_950.0)
    }

    /**
     * `secure_expiry_local_stall` (the P4 could not submit HELLO_ACK and charges nothing): on Android the HELLO went
     * out and completed and no HELLO_ACK ever arrives, so Android owed nothing and its 4.5 s expiry ends as a peer
     * expiry: AUTH (DEADLINE, the charged kind), no REJECT, one disconnect, before the P4's 5 s cap.
     */
    @Test
    fun aHelloAckThatNeverArrivesIsAPeerExpiryOnAndroid() {
        assertTrue(X2.sends("secure_expiry_local_stall").isEmpty())
        val r = rig()
        admit(r)
        val end = assertEnded(r, PipeEnd(r.token, PipeEndKind.AUTH, PipeError.DEADLINE, unsettled = false, authenticated = false, ready = false), values = 1, disconnects = 1)
        val after = ms(r.conn.subscribedAtNanos, end.atNanos)
        assertTrue("expiry $after ms after the CCCD answer", after >= PipeEngine.HANDSHAKE_MS - 10 && after < 4_950.0)
    }

    /**
     * `hello_ack_completion_timeout` (the P4 tears down when its HELLO_ACK write never completes): Android sees the
     * link go before any HELLO_ACK: TRANSPORT (CANCELLED), no cooldown, nothing more sent, no disconnect of its own.
     */
    @Test
    fun linkLossBeforeHelloAckIsTransport() {
        val r = rig()
        admit(r)
        r.engine.linkLost(r.token)
        assertEnded(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = false, ready = false), values = 1, disconnects = 0)
    }

    /**
     * `disconnect_during_handshake`: the P4's HELLO_ACK (`p4_sends_hex`) gets X1's CONFIRM, then the link drops:
     * TRANSPORT (CANCELLED), no cooldown, nothing more sent.
     */
    @Test
    fun linkLossAfterConfirmIsTransport() {
        val r = rig()
        admit(r)
        writeAndAwait(r, X2.sends("disconnect_during_handshake").single(), 2)
        assertEquals(X1.valueHex(3), toHex(r.link.sent[1].value))
        r.engine.linkLost(r.token)
        assertEnded(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = false, ready = false), values = 2, disconnects = 0)
    }

    /**
     * `mtu_63` (the P4 never writes the CCCD below 64): Android's own check agrees. A CCCD enable (X1's 01 00) at MTU
     * 63 gets 0x0d and admits nothing, at 64 it admits; P = 0 at 63 and 36 at 64; PipeEngine.begin refuses MTU 63
     * (nothing sent) and accepts 64.
     */
    @Test
    fun anMtuBelow64IsRefusedBeforeAnySession() {
        assertEquals(0, X2.frames("mtu_63").size)
        val enable = X1.value(0)
        assertEquals(Decision(PipeAttPolicy.ATT_INVALID_ATTRIBUTE_LENGTH, Action.NONE), cccd(enable, mtu = 63))
        assertEquals(Decision(PipeAttPolicy.ATT_SUCCESS, Action.ADMIT), cccd(enable, mtu = 64))
        assertEquals(0, PipeWire.frameLimit(63))
        assertEquals(36, PipeWire.frameLimit(64))
        val r = rig()
        assertFalse(r.begin(mtu = 63))
        assertFalse(r.engine.active)
        r.subscribe()
        Thread.sleep(150)
        assertTrue(r.link.sent.isEmpty())
        assertTrue(r.begin(mtu = 64))
        assertTrue(r.engine.active)
    }

    /**
     * `cccd_refused_0x11` (the P4 tears down without cooldown when its enable gets 0x11): Android answers 0x11 and
     * admits nothing for another admitted device, a running cooldown and a not-ready pipe; an engine that already has
     * a session refuses begin() (the GATT server then answers 0x11) and the refused link receives nothing while the
     * first session carries on.
     */
    @Test
    fun everyAndroid0x11CccdAnswerStartsNothing() {
        val enable = X2.value("cccd_refused_0x11", 0)
        assertEquals("0100", toHex(enable))
        val refused = Decision(PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES, Action.NONE)
        assertEquals(0x11, PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES)
        assertEquals(refused, cccd(enable, otherAdmitted = true))
        assertEquals(refused, cccd(enable, coolingDown = true))
        assertEquals(refused, cccd(enable, ready = false))
        assertEquals(Decision(PipeAttPolicy.ATT_SUCCESS, Action.ADMIT), cccd(enable))

        val r = rig()
        admit(r)
        val second = FakeLink(r.clock)
        assertFalse(r.engine.begin(second, 247, X1.psk, 0x7777L))
        r.engine.subscribed(0x7777L)
        Thread.sleep(150)
        assertTrue(second.sent.isEmpty())
        assertTrue(second.disconnects.isEmpty())
        assertEquals(1, r.link.sent.size)
        assertEquals("handshake", r.engine.currentStats.stage)
        assertTrue(r.ends.isEmpty())
    }

    // ---- (b) Pipe p4_production vectors ----------------------------------------------------------

    /**
     * Encoder parity: every phone → P4 DATA value in X2 that has a decoded inner frame is exactly what Android's
     * [PipeSecureChannel.seal] produces for that inner frame at that counter (after X1's prefix), so each P4 verdict
     * is about bytes Android's crypto layer would emit; what Android must never emit is then a matter of the inner
     * frame or the counter, asserted elsewhere. The JSON `counter` equals the wire counter. Only counter 2^64−1
     * (`counter_max_once` frames 0 and 2) cannot be reproduced: seal() refuses it (EXHAUSTED).
     */
    @Test
    fun androidsEncoderReproducesEveryPhoneDataValueBelowTheCounterLimit() {
        val reproduced = ArrayList<String>()
        val refused = ArrayList<String>()
        for (v in X2.vectors) {
            for (f in framesOf(v)) {
                if (f.getString("dir") != P2D || f.getString("kind") != "data" || !f.has("inner_hex")) continue
                val key = "${v.getString("id")}/${f.getInt("i")}"
                val value = hex(f.getString("value_hex"))
                val counter = java.lang.Long.parseUnsignedLong(f.get("counter").toString())
                assertEquals("$key counter", counter, counterOf(value))
                val ch = afterX1Prefix()
                ch.setCountersForTest(tx = counter, rx = 1)
                val sealed = ch.seal(hex(f.getString("inner_hex")))
                if (counter == -1L) {
                    assertNull(key, sealed)
                    assertEquals(key, Failure.EXHAUSTED, ch.failure)
                    refused += key
                } else {
                    assertEquals(key, toHex(value), toHex(sealed!!))
                    reproduced += key
                }
            }
        }
        assertEquals(listOf("counter_max_once/0", "counter_max_once/2"), refused)
        assertEquals(
            listOf(
                "counter_gap_allowed/0", "counter_gap_allowed/2", "counter_regression_after_gap/0", "counter_regression_after_gap/2",
                "counter_regression_after_gap/4", "replayed_counter/0", "counter_2_pow_63/0", "inner_sequence_gap/0", "inner_version_2/0",
                "frame_for_future_stream/0", "phone_reset_stream_0/0", "open_ok_window_4097/1", "open_ok_window_4096/1",
                "credit_beyond_sent_plus_4096/1", "credit_beyond_sent_plus_4096/2", "credit_repeat_tolerated/1", "credit_repeat_tolerated/2",
                "receive_window_overflow/1", "receive_window_overflow/2", "receive_window_overflow/3", "receive_window_overflow/4",
                "receive_window_overflow/5", "receive_window_overflow/6", "open_err_vpn/1", "late_close_for_ended_stream/1",
                "late_close_for_ended_stream/2",
            ),
            reproduced,
        )
    }

    /**
     * The P4's own DATA values in the pipe vectors (PONGs, the OPEN, a CLOSE_ACK; every `p4_sends_hex` entry of a pipe
     * vector is one of them) open on Android's channel, after X1's prefix and at their counter, to exactly the
     * vector's decoded inner frame.
     */
    @Test
    fun androidOpensEveryP4DataValueInTheVectors() {
        val opened = ArrayList<String>()
        for (v in X2.vectors) {
            val values = ArrayList<String>()
            for (f in framesOf(v)) {
                if (f.getString("dir") != D2P || f.getString("kind") != "data" || !f.has("inner_hex")) continue
                val key = "${v.getString("id")}/${f.getInt("i")}"
                val value = hex(f.getString("value_hex"))
                val counter = f.getLong("counter")
                assertEquals(key, counter, counterOf(value))
                val ch = afterX1Prefix()
                ch.setCountersForTest(tx = 2, rx = counter - 1)
                assertEquals(key, f.getString("inner_hex"), toHex(data(ch.receive(value))))
                values += toHex(value)
                opened += key
            }
            if (v.getString("source") == PROD && v.getString("stage") == PIPE) {
                for (s in X2.sends(v.getString("id"))) assertTrue("${v.getString("id")} sends a framed value", toHex(s) in values)
            }
        }
        assertEquals(
            listOf(
                "counter_gap_allowed/1", "counter_gap_allowed/3", "counter_regression_after_gap/1", "counter_regression_after_gap/3",
                "replayed_counter/1", "counter_2_pow_63/1", "counter_max_once/1", "open_ok_window_4097/0", "open_ok_window_4096/0",
                "credit_beyond_sent_plus_4096/0", "credit_repeat_tolerated/0", "receive_window_overflow/0", "open_err_vpn/0",
                "late_close_for_ended_stream/0", "late_close_for_ended_stream/3",
            ),
            opened,
        )
    }

    /**
     * `counter_gap_allowed`, `counter_regression_after_gap`, `replayed_counter`: after X1's prefix Android's send
     * counter only rises by one (2, 3, 4, 5); sealing the same inner frame again gives the next counter and a new
     * value, so Android never gaps, regresses or repeats (it also never resubmits a value, R10). The first is
     * `replayed_counter` frame 0. Engine runs assert contiguous counters from 1 ([assertPipeValuesWellFormed]).
     */
    @Test
    fun androidSendCountersRiseByOneAndNeverRepeat() {
        val ch = afterX1Prefix()
        val ping = hex(X2.innerHex("replayed_counter", 0))
        val values = List(4) { ch.seal(ping)!! }
        assertEquals(listOf(2L, 3L, 4L, 5L), values.map { counterOf(it) })
        assertEquals(X2.valueHex("replayed_counter", 0), toHex(values[0]))
        assertEquals(4, values.map { toHex(it) }.toSet().size)
    }

    /**
     * The same counter rules on Android's receive side, with P4 values sealed by reference code under X1's P4 → phone
     * key (the sealer reproduces `counter_gap_allowed` frame 1): counters 7 then 9 are accepted and 8 then fails; 2^63
     * is accepted after counter 1 and a small counter then fails; 2^64−1 is accepted once and nothing follows it
     * (A-6). Every failure is Rx.Invalid with Failure.AUTH.
     */
    @Test
    fun androidReceiveCountersFollowTheVectorsRules() {
        val key = X1.bytes("p4_to_phone_key_hex")
        val pong = hex(X2.innerHex("counter_gap_allowed", 1))
        fun p4Value(counter: Long) = ByteBuffer.allocate(9).put(0x10.toByte()).putLong(counter).array() + P4Crypto.seal(key, P4Crypto.nonce(1, counter), pong)
        assertEquals(X2.valueHex("counter_gap_allowed", 1), toHex(p4Value(2)))

        afterX1Prefix().let { ch ->
            assertEquals(toHex(pong), toHex(data(ch.receive(p4Value(7)))))
            assertEquals(toHex(pong), toHex(data(ch.receive(p4Value(9)))))
            assertSame(Rx.Invalid, ch.receive(p4Value(8)))
            assertEquals(Failure.AUTH, ch.failure)
        }
        afterX1Prefix().let { ch ->
            data(ch.receive(p4Value(Long.MIN_VALUE)))
            assertSame(Rx.Invalid, ch.receive(p4Value(3)))
            assertEquals(Failure.AUTH, ch.failure)
        }
        afterX1Prefix().let { ch ->
            data(ch.receive(p4Value(-1L)))
            assertSame(Rx.Invalid, ch.receive(p4Value(-1L)))
            assertEquals(Failure.AUTH, ch.failure)
        }
    }

    /**
     * `counter_max_once` and `counter_2_pow_63`: Android's last send counter is 2^64−2; the next seal() returns null
     * and fails the channel as EXHAUSTED (never AUTH), as does a channel already at 2^64−1. 2^63 is an ordinary
     * counter: the vector's value, then 2^63+1. In the engine, exhaustion at the P4's PING ends the session as
     * TRANSPORT (NO_RESOURCES): no PONG, no RESET, one disconnect, no cooldown (FW-4).
     */
    @Test
    fun androidNeverEmitsCounterTwoToTheSixtyFourMinusOne() {
        val inner = hex(X2.innerHex("counter_max_once", 0))
        afterX1Prefix().let { ch ->
            ch.setCountersForTest(tx = -2L, rx = 1)
            assertEquals("fffffffffffffffe", toHex(ch.seal(inner)!!.copyOfRange(1, 9)))
            assertNull(ch.seal(inner))
            assertEquals(State.FAILED, ch.state)
            assertEquals(Failure.EXHAUSTED, ch.failure)
        }
        afterX1Prefix().let { ch ->
            ch.setCountersForTest(tx = -1L, rx = 1)
            assertNull(ch.seal(inner))
            assertEquals(Failure.EXHAUSTED, ch.failure)
        }
        afterX1Prefix().let { ch ->
            ch.setCountersForTest(tx = Long.MIN_VALUE, rx = 1)
            val ping = hex(X2.innerHex("counter_2_pow_63", 0))
            assertEquals(X2.valueHex("counter_2_pow_63", 0), toHex(ch.seal(ping)!!))
            assertEquals("8000000000000001", toHex(ch.seal(ping)!!.copyOfRange(1, 9)))
        }

        val r = rig()
        x1Prefix(r)
        setEngineCounters(r.engine, tx = -1L, rx = 1L)
        r.send(Op.PING, 0, hex("09080706"))
        assertEnded(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES, unsettled = false, authenticated = true, ready = true), values = 3, disconnects = 1)
    }

    /**
     * `plaintext_after_handshake`, `secure_hello_after_ready`, `truncated_data`: on an established channel hello()
     * throws (state unchanged); the plaintext vector is the inner PING that Android seals into `replayed_counter`
     * frame 0 (type 0x10, inner + 25 bytes); the truncated vector is that value cut to 24 bytes; the HELLO vector is
     * X1's HELLO again. Engine runs assert every post-handshake value is DATA the P4 opens ([T_WINDOW]).
     */
    @Test
    fun androidSendsNoHelloPlaintextOrShortValueAfterTheHandshake() {
        val ch = afterX1Prefix()
        assertThrows(IllegalStateException::class.java) { ch.hello() }
        assertEquals(State.ESTABLISHED, ch.state)
        val plain = X2.value("plaintext_after_handshake", 0)
        assertEquals(X2.innerHex("replayed_counter", 0), toHex(plain))
        val sealed = ch.seal(plain)!!
        assertEquals(X2.valueHex("replayed_counter", 0), toHex(sealed))
        assertEquals(0x10, sealed[0].toInt())
        assertEquals(plain.size + PipeSecureChannel.OVERHEAD, sealed.size)
        assertEquals(toHex(sealed.copyOf(24)), X2.valueHex("truncated_data", 0))
        assertEquals(X1.valueHex(1), X2.valueHex("secure_hello_after_ready", 0))
    }

    /**
     * `receive_window_overflow` with `oversize_value`, `inner_sequence_gap`, `inner_version_2`,
     * `frame_for_future_stream`, `plaintext_after_handshake`, `truncated_data`: after X1's prefix the vector's OPEN
     * (window 1024) arrives with the vector's 1025 upload bytes already queued on the socket. Android sends X1's
     * OPEN_OK, then the vector's frames 2–5 byte for byte (207-byte DATA, the fullest values: exactly V = 244, inner
     * P = 219), then 196 bytes (not the vector's 197) and nothing more; the P4's CREDIT then releases the last byte.
     * Every value is well formed for the P4.
     */
    @Test
    fun androidStopsAtTheP4WindowWithTheVectorsOwnDataValues() {
        val id = "receive_window_overflow"
        val upload = (2..6).map { hex(X2.bodyHex(id, it)) }.reduce { a, b -> a + b }
        assertEquals(1025, upload.size)
        assertEquals(V247, PipeWire.valueLimit(247))
        assertEquals(P247, PipeWire.frameLimit(247))
        assertEquals(V247 + 1, X2.value("oversize_value", 0).size)

        val r = rig()
        x1Prefix(r)
        val socket = FakeSocket().apply { feed(upload) }
        r.connector.plan(443, ConnectPlan(socket))
        r.write(X2.value(id, 0))
        Poll.until("1024 bytes up") { r.dataBytes(1) == 1024 }
        r.settle(300)
        assertEquals("OPEN_OK + 5 DATA after HELLO_ACK", 9, r.link.sent.size)
        assertEquals("OPEN_OK", X2.valueHex(id, 1), toHex(r.link.sent[3].value))
        for (k in 2..5) assertEquals("DATA = vector frame $k", X2.valueHex(id, k), toHex(r.link.sent[k + 2].value))
        val last = r.sync()[6]
        assertEquals(7L, last.counter)
        assertEquals(toHex(rawFrame(Op.DATA, 1, 6, 0, upload.copyOfRange(828, 1024))), last.frame.hexString)
        assertEquals(12 + 196 + 25, r.link.sent[8].value.size)
        assertEquals(X2.value(id, 6).size - 1, r.link.sent[8].value.size)
        assertEquals(V247, r.link.sent.maxOf { it.value.size })
        assertEquals(P247, r.sync().maxOf { it.frame.size })
        assertEquals(1024, r.dataBytes(1))

        r.p4.txCounter = 3
        r.p4.inner.seq = 2
        r.credit(1, 2048)
        Poll.until("the last byte") { r.dataBytes(1) == 1025 }
        r.awaitQuiet()
        val tail = r.sync().last()
        assertEquals(8L, tail.counter)
        assertEquals(toHex(rawFrame(Op.DATA, 1, 7, 0, upload.copyOfRange(1024, 1025))), tail.frame.hexString)
        assertTrue(r.ends.isEmpty())
        assertPipeValuesWellFormed(r, setOf(1))
    }

    /**
     * `open_ok_window_4097`, `open_ok_window_4096`, `credit_beyond_sent_plus_4096`, `credit_repeat_tolerated`: to the
     * vectors' OPEN Android answers OPEN_OK with X1's window 1024 (`credit_beyond_sent_plus_4096` frame 1, inside
     * 1..4096), never the 4096 or 4097 values. With no P4 DATA it sends no CREDIT; after 600 P4 bytes reach TCP,
     * its CREDITs strictly increase (none repeats the current limit), stay at most 600 + 4096 (the P4's sent + 4096)
     * and end at exactly 4696 (Q11).
     */
    @Test
    fun androidGrantsOpenOk1024AndNoCreditAheadOfP4Data() {
        assertEquals(1024L, PipeWire.INITIAL_LIMIT)
        assertEquals(4096, PipeWire.WINDOW)
        val r = rig()
        x1Prefix(r)
        val socket = FakeSocket()
        r.connector.plan(443, ConnectPlan(socket))
        writeAndAwait(r, X2.value("credit_beyond_sent_plus_4096", 0), 4)
        val openOk = toHex(r.link.sent[3].value)
        assertEquals(X2.valueHex("credit_beyond_sent_plus_4096", 1), openOk)
        assertNotEquals(X2.valueHex("open_ok_window_4096", 1), openOk)
        assertNotEquals(X2.valueHex("open_ok_window_4097", 1), openOk)
        assertEquals(toHex(rawFrame(Op.OPEN_OK, 1, 1, F_BOTH, le32(1024))), r.sync().last().frame.hexString)
        r.settle(300)
        assertEquals("no CREDIT without P4 data", 4, r.link.sent.size)

        val down = ByteArray(600) { (it * 7 + 3).toByte() }
        r.p4.txCounter = 3
        r.p4.inner.seq = 2
        r.data(1, down)
        Poll.until("600 bytes to TCP and CREDIT 4696") { socket.writtenBytes.size == 600 && r.credits(1).lastOrNull() == 4696L }
        r.settle(200)
        val credits = r.credits(1)
        assertTrue("credits $credits", credits.isNotEmpty())
        assertEquals(4696L, credits.last())
        for ((a, b) in (listOf(1024L) + credits).zipWithNext()) assertTrue("credits $credits strictly increase", b > a)
        assertTrue("credits $credits ≤ P4 sent + 4096", credits.all { it <= 600L + 4096L })
        assertEquals(toHex(down), toHex(socket.writtenBytes))
        assertTrue(r.ends.isEmpty())
        assertPipeValuesWellFormed(r, setOf(1))
    }

    /**
     * `open_err_vpn`: with the VPN prerequisite down, the vector's OPEN gets exactly the vector's OPEN_ERR 5 (counter
     * 2, sequence 1) with no connect attempt; the session stays READY (no PipeEnd) and answers the P4's next PING.
     */
    @Test
    fun aDownVpnGivesTheVectorsOpenErr5AndTheSessionContinues() {
        val r = rig()
        x1Prefix(r)
        r.gate.vpn = false
        writeAndAwait(r, X2.value("open_err_vpn", 0), 4)
        assertEquals(X2.valueHex("open_err_vpn", 1), toHex(r.link.sent[3].value))
        r.settle(300)
        assertEquals(4, r.link.sent.size)
        assertTrue(r.connector.calls.isEmpty())
        assertEquals(mapOf(PipeError.VPN_UNAVAILABLE to 1), r.engine.currentStats.refused)
        r.p4.txCounter = 3
        r.p4.inner.seq = 2
        r.send(Op.PING, 0, hex("0a0b0c0d"))
        r.await("PONG") { it.op == Op.PONG }
        r.awaitQuiet()
        assertTrue(r.ends.isEmpty())
        assertEquals("ready", r.engine.currentStats.stage)
        assertPipeValuesWellFormed(r, setOf(1))
    }

    /**
     * `late_close_for_ended_stream`: a failed connect makes Android answer the vector's OPEN with exactly the vector's
     * OPEN_ERR 7; Android then never sends the vector's CLOSE (the phone never initiates CLOSE). The P4's CLOSE_ACK
     * from `p4_sends_hex` (counter 3) for the retired ID is discarded: no reply, no PipeEnd, and the session answers
     * the next PING.
     */
    @Test
    fun aFailedConnectGivesOpenErr7AndAndroidNeverSendsTheLateClose() {
        val id = "late_close_for_ended_stream"
        val r = rig()
        x1Prefix(r)
        r.connector.plan(443, ConnectPlan(error = PipeError.CONNECT_FAILED))
        writeAndAwait(r, X2.value(id, 0), 4)
        assertEquals(X2.valueHex(id, 1), toHex(r.link.sent[3].value))
        r.settle(300)
        assertEquals("no CLOSE after OPEN_ERR", 4, r.link.sent.size)
        assertTrue(r.sync().none { it.op == Op.CLOSE })
        assertEquals(mapOf(PipeError.CONNECT_FAILED to 1), r.engine.currentStats.refused)

        val closeAck = X2.sends(id)[1]
        assertEquals(X2.valueHex(id, 3), toHex(closeAck))
        r.write(closeAck)
        r.settle(300)
        assertEquals(4, r.link.sent.size)
        assertTrue(r.ends.isEmpty())
        r.p4.txCounter = 4
        r.p4.inner.seq = 3
        r.send(Op.PING, 0, hex("0a0b0c0d"))
        r.await("PONG") { it.op == Op.PONG }
        r.awaitQuiet()
        assertEquals("ready", r.engine.currentStats.stage)
        assertPipeValuesWellFormed(r, setOf(1))
    }

    /**
     * `phone_reset_stream_0` is Android's own production value: after X1's prefix the P4 starts an OPEN (FIRST
     * fragment only, counter 2, sequence 1) and goes silent; after the 2 s reassembly deadline Android sends its
     * best-effort stream-0 RESET 13 (DEADLINE), which is exactly the vector's value, then disconnects. The P4 records
     * teardown without cooldown; Android ends PROTOCOL (DEADLINE), which charges none either.
     */
    @Test
    fun androidsOwnStreamZeroResetIsTheVectorsValue() {
        val r = rig()
        x1Prefix(r)
        val fragment = rawFrame(Op.OPEN, 1, 1, F_FIRST, openBody(ADDR_IPV4, hex("c000020a"), 443).copyOf(6))
        val at = System.nanoTime()
        r.sendFrame(fragment)
        assertEnded(r, PipeEnd(r.token, PipeEndKind.PROTOCOL, PipeError.DEADLINE, unsettled = false, authenticated = true, ready = true), values = 4, disconnects = 1)
        assertEquals(X2.valueHex("phone_reset_stream_0", 0), toHex(r.link.sent[3].value))
        assertEquals(X2.innerHex("phone_reset_stream_0", 0), r.sync().last().frame.hexString)
        assertTrue("RESET after the reassembly deadline", ms(at, r.link.sent[3].atNanos) >= PipeWire.REASSEMBLY_DEADLINE_MS - 10)
        assertPipeValuesWellFormed(r, emptySet())
    }

    /**
     * `service_changed_after_ready`: the P4 ends a READY session on Service Changed; Android sees link loss at READY:
     * TRANSPORT (CANCELLED), authenticated and ready, no RESET (R7: nothing after a disconnect), no cooldown.
     */
    @Test
    fun linkLossAfterServiceChangedIsTransportWithNoReset() {
        assertEquals(0, X2.frames("service_changed_after_ready").size)
        val r = rig()
        x1Prefix(r)
        r.engine.linkLost(r.token)
        assertEnded(r, PipeEnd(r.token, PipeEndKind.TRANSPORT, PipeError.CANCELLED, unsettled = false, authenticated = true, ready = true), values = 3, disconnects = 0)
        assertPipeValuesWellFormed(r, emptySet())
    }
}
