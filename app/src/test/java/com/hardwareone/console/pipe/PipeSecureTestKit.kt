package com.hardwareone.console.pipe

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * Stage-4 harness for the PipeEngine tests (owned by PipeEngineSecureTest; PipeEngineTest and
 * PipeEngineRoutesTest use it read-only). The engine runs with its real threads; everything around
 * it is fake and written from the firmware sources, never from the Android implementation:
 *
 *  - [FakeP4]: the P4 secure responder of BLE_PhoneSecureChannel.h (HELLO → HELLO_ACK with its own
 *    X25519 key and nonce, CONFIRM checked under K[0:32] and nonce(0, 0), CONFIRM_ACK under
 *    K[32:64] and nonce(1, 0), DATA sealed with direction 1 from counter 1 and opened with
 *    direction 0 and strictly increasing unsigned counters), using BouncyCastle's low-level
 *    X25519, HMAC-SHA256 and ChaCha20-Poly1305 with a hand-written RFC 5869 HKDF ([P4Crypto]).
 *    PipeSecureChannel is never called. Its inner framer is PipeTestKit's [Device] (the P4 core's
 *    `Session::peek`), started at P = V − 25 like `pipe.start(mtu, nonce, now, kSecureOverhead)`.
 *  - [FakeLink]: the GATT server's view of one admitted link. It records every submitted value
 *    with timestamps, lets a test choose what each notify call does (OK / REFUSED / UNCERTAIN /
 *    throw / block for N ms) and whether and how its completion arrives, and counts disconnects.
 *  - [FakeConnector], [FakeGate], [FakeSocket]: the TCP side, in memory, keyed by port.
 *  - [SecureRig]: one engine plus all of the above. `ready()` drives a session to READY (begin,
 *    subscribed, HELLO answered, CONFIRM_ACK, the P4's inner HELLO, HELLO_ACK completed), `sync()`
 *    decrypts every phone value with the fake P4 and decodes the inner frames with PipeTestKit's
 *    [WireFrame], and `send()` frames, seals and writes P4 messages. Its engine thread and watchdog
 *    can be injected, and `suspendFor()` moves the engine clock without real time passing.
 *  - [HeldScheduler]: a real watchdog that holds back timers of chosen delays until the test fires them.
 *
 * Fixture values come from hardwareone-idf `tools/ble_secure/fixtures/phone_secure_channel_v1_vectors.json`
 * ([PipeFixture]). Destinations are documentation-only (RFC 5737, RFC 3849, example.com).
 */

/** The firmware's golden MTU-247 transcript, `/pipe/phone_secure_channel_v1_vectors.json`. */
object PipeFixture {
    private val json: JSONObject by lazy {
        PipeFixture::class.java.getResourceAsStream("/pipe/phone_secure_channel_v1_vectors.json")!!
            .use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
    }

    fun string(name: String): String = json.getString(name)

    fun bytes(name: String): ByteArray = hex(json.getString(name))

    fun long(name: String): Long = json.getLong(name)

    val psk: ByteArray get() = bytes("psk_hex")
}

/**
 * Reference primitives for the fake P4, matching System_BleSecureCrypto.h: raw RFC 7748 X25519,
 * HMAC-SHA256, RFC 5869 HKDF written out by hand (scHkdf), the `dir(u32 BE) ‖ counter(u64 BE)`
 * nonce (scNonce) and ChaCha20-Poly1305-IETF without AAD, `ciphertext ‖ tag`.
 */
object P4Crypto {
    const val LABEL = "HW1-PIPE-v1"

    fun publicKey(secret: ByteArray): ByteArray = ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) }

    /** crypto_scalarmult: null when the result is all zero (a low-order point). */
    fun sharedSecret(secret: ByteArray, peer: ByteArray): ByteArray? {
        val out = ByteArray(32)
        X25519.scalarMult(secret, 0, peer, 0, out, 0)
        return if (out.all { it == 0.toByte() }) null else out
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun nonce(dir: Int, counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(dir).putLong(counter).array()

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    /** Opens `ciphertext ‖ tag`; null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(sealed.size))
        return try {
            val n = c.processBytes(sealed, 0, sealed.size, out, 0)
            c.doFinal(out, n)
            out
        } catch (e: InvalidCipherTextException) {
            null
        }
    }
}

/**
 * The P4 end of HW1-PIPE-v1, transcribed from `hw1phone::SecureChannel` (BLE_PhoneSecureChannel.h)
 * plus the inner framer the GATT client starts after CONFIRM_ACK (BLE_PhoneGatt.cpp `poll`).
 *
 * [privateKey] / [handshakeNonce] fix the device's ephemeral key and nonce (the fixture's); null
 * draws fresh ones like `crypto_kx_keypair` and `randombytes_buf`. [checksConfirm] false makes an
 * impostor that never learned the PSK: it answers CONFIRM_ACK under its own keys without
 * verifying CONFIRM (the real firmware verifies and, failing, sends REJECT 05 02; see [SecureRig]).
 */
class FakeP4(
    mtu: Int,
    private val psk: ByteArray,
    private val privateKey: ByteArray? = null,
    private val handshakeNonce: ByteArray? = null,
    private val checksConfirm: Boolean = true,
) {
    enum class Phase { AWAIT_HELLO, AWAIT_CONFIRM, READY, FAILED }

    sealed class Rx {
        /** The handshake answer to write back (HELLO_ACK, then CONFIRM_ACK). */
        class Reply(val value: ByteArray) : Rx()

        /** One authenticated phone DATA value: its outer counter and the inner frame. */
        class Data(val counter: Long, val plaintext: ByteArray) : Rx()

        /** `error()`: the firmware wipes its keys and stops the session. */
        data object Error : Rx()
    }

    private val rng = SecureRandom()
    private val rxKey = ByteArray(32) // phone → P4: K[0:32]
    private val txKey = ByteArray(32) // P4 → phone: K[32:64]

    var phase = Phase.AWAIT_HELLO
        private set

    /** The failure happened while the P4 was still in its Secure phase (it then sends REJECT 05 02). */
    var failedInHandshake = false
        private set

    /** Last accepted phone counter (unsigned); the next must be above it. */
    var lastRxCounter = 0L
        private set

    /** Counter of the next P4 → phone DATA; starts at 1 and may be moved forward to make gaps. */
    var txCounter = 1L

    /** Every phone DATA counter the P4 accepted, in order. */
    val phoneCounters = CopyOnWriteArrayList<Long>()

    /** The P4's inner pipe framer, at P = V − 25 until the phone's HELLO_ACK chooses less. */
    val inner = Device(specValue(mtu) - 25)

    @Synchronized
    fun receive(value: ByteArray): Rx {
        if (value.isEmpty()) return error()
        val type = value[0].toInt() and 0xff
        if (type == 0x01 && phase == Phase.AWAIT_HELLO && value.size == 49) return hello(value)
        if (type == 0x03 && phase == Phase.AWAIT_CONFIRM && value.size == 19) return confirm(value)
        if (type != 0x10 || phase != Phase.READY || value.size < 25) return error()
        val counter = ByteBuffer.wrap(value, 1, 8).long
        if (java.lang.Long.compareUnsigned(counter, lastRxCounter) <= 0) return error()
        val plain = P4Crypto.open(rxKey, P4Crypto.nonce(0, counter), value.copyOfRange(9, value.size)) ?: return error()
        lastRxCounter = counter
        phoneCounters += counter
        return Rx.Data(counter, plain)
    }

    /** One P4 → phone DATA value for [plain] at the next counter (`encrypt`). */
    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        check(phase == Phase.READY) { "the P4 seals DATA only once ready" }
        check(txCounter != -1L) { "the firmware never emits counter 2^64 - 1" }
        val counter = txCounter++
        return ByteBuffer.allocate(9).put(0x10.toByte()).putLong(counter).array() +
            P4Crypto.seal(txKey, P4Crypto.nonce(1, counter), plain)
    }

    private fun hello(value: ByteArray): Rx {
        val secret = privateKey?.copyOf() ?: ByteArray(32).also(rng::nextBytes)
        val pub = P4Crypto.publicKey(secret)
        val nonce = handshakeNonce?.copyOf() ?: ByteArray(16).also(rng::nextBytes)
        val shared = P4Crypto.sharedSecret(secret, value.copyOfRange(1, 33)) ?: return error()
        // ikm = shared ‖ PSK, salt = phoneNonce ‖ deviceNonce, info = "HW1-PIPE-v1", 64 bytes.
        val keys = P4Crypto.hkdf(shared + psk, value.copyOfRange(33, 49) + nonce, ascii(P4Crypto.LABEL), 64)
        keys.copyInto(rxKey, 0, 0, 32)
        keys.copyInto(txKey, 0, 32, 64)
        phase = Phase.AWAIT_CONFIRM
        return Rx.Reply(byteArrayOf(0x02) + pub + nonce)
    }

    private fun confirm(value: ByteArray): Rx {
        if (checksConfirm) {
            val plain = P4Crypto.open(rxKey, P4Crypto.nonce(0, 0), value.copyOfRange(1, 19))
            if (plain == null || !plain.contentEquals(ascii("ok"))) return error()
        }
        phase = Phase.READY
        return Rx.Reply(byteArrayOf(0x04) + P4Crypto.seal(txKey, P4Crypto.nonce(1, 0), ascii("ok")))
    }

    private fun error(): Rx {
        if (phase != Phase.FAILED) failedInHandshake = phase != Phase.READY
        phase = Phase.FAILED
        rxKey.fill(0)
        txKey.fill(0)
        return Rx.Error
    }
}

/**
 * The admitted link as the engine sees it. Every [notify] is recorded as a [Sent] (value, start
 * time on nanoTime and on the engine clock, return time, result, completion). [outcome] decides
 * each call: [Outcome.OK], [Outcome.REFUSED], [Outcome.UNCERTAIN], [Outcome.THROW], or any of them
 * after blocking ([Outcome.block]). An OK submission is completed with the status [completion]
 * returns, [completionDelayMs] later from a separate "binder" thread (or inside the call when
 * [completeInline]); null leaves it to the test ([complete]). Nothing else ever completes.
 */
class FakeLink(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : PipeLink {
    class Sent(val index: Int, val value: ByteArray, val atNanos: Long, val atMs: Long) {
        @Volatile var returnedNanos = 0L
        @Volatile var result: PipeSubmit? = null
        @Volatile var threw = false
        @Volatile var completedNanos = 0L
        @Volatile var status: Int? = null
        val returned: Boolean get() = returnedNanos != 0L
        override fun toString(): String = "Sent(#$index, ${value.size} B, type 0x%02x, $result)".format(value[0].toInt() and 0xff)
    }

    /** What one notify call does: return [result] (null = throw) after blocking [blockMs]. */
    class Outcome(val result: PipeSubmit?, val blockMs: Long = 0) {
        companion object {
            val OK = Outcome(PipeSubmit.OK)
            val REFUSED = Outcome(PipeSubmit.REFUSED)
            val UNCERTAIN = Outcome(PipeSubmit.UNCERTAIN)
            val THROW = Outcome(null)
            fun block(ms: Long, then: PipeSubmit? = PipeSubmit.OK) = Outcome(then, ms)
        }
    }

    @Volatile var engine: PipeEngine? = null
    @Volatile var token = 0L
    @Volatile var outcome: (Sent) -> Outcome = { Outcome.OK }
    @Volatile var completion: (Sent) -> Int? = { 0 }
    @Volatile var completionDelayMs = 1L
    @Volatile var completeInline = false
    /** Runs inside notify, before the outcome: a P4 may react to a value as soon as the stack has it. */
    @Volatile var duringNotify: (Sent) -> Unit = {}

    val sent = CopyOnWriteArrayList<Sent>()
    /** nanoTime of every disconnect() call. */
    val disconnects = CopyOnWriteArrayList<Long>()
    /** notify calls made while an earlier submission was still inside notify or awaiting its completion. */
    val overlaps = AtomicInteger()
    private val completer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-binder").apply { isDaemon = true } }

    override fun notify(value: ByteArray): PipeSubmit {
        val s = synchronized(sent) {
            Sent(sent.size, value.copyOf(), System.nanoTime(), clock()).also { sent += it }
        }
        if (sent.any { it !== s && (!it.returned || (it.result == PipeSubmit.OK && it.completedNanos == 0L)) }) overlaps.incrementAndGet()
        duringNotify(s)
        val o = outcome(s)
        if (o.blockMs > 0) Thread.sleep(o.blockMs)
        val result = o.result
        if (result == PipeSubmit.OK) {
            val status = completion(s)
            if (status != null) {
                if (completeInline) complete(s, status)
                else completer.schedule({ complete(s, status) }, completionDelayMs, TimeUnit.MILLISECONDS)
            }
        }
        s.result = result
        s.threw = result == null
        s.returnedNanos = System.nanoTime()
        if (result == null) throw IllegalStateException("synthetic notify failure")
        return result
    }

    /** Delivers onNotificationSent for [s], as the platform would, tagged with [forToken]. */
    fun complete(s: Sent, status: Int = 0, forToken: Long = token) {
        s.status = status
        s.completedNanos = System.nanoTime()
        engine?.onNotificationSent(forToken, status)
    }

    override fun disconnect() {
        disconnects += System.nanoTime()
    }

    fun close() {
        completer.shutdownNow()
    }
}

/** One phone → P4 value the fake P4 decrypted: its submission, outer counter and inner frame. */
class PhoneFrame(val sent: FakeLink.Sent, val counter: Long, val frame: WireFrame) {
    val op: Int get() = frame.op
    val id: Int get() = frame.id
    val seq: Long get() = frame.seq
    val flags: Int get() = frame.flags
    val length: Int get() = frame.length
    val body: ByteArray get() = frame.body
    val bytes: ByteArray get() = frame.bytes
    override fun toString(): String = "PhoneFrame(#${sent.index} ctr=$counter op=0x%02x id=$id seq=$seq len=$length)".format(op)
}

/** One onEnd call, with what was true when it arrived. */
class EndRecord(
    val end: PipeEnd,
    val atNanos: Long,
    /** engine.active inside the callback. */
    val activeAtEnd: Boolean,
    /** disconnect() calls on that session's link before the callback. */
    val disconnectsAtEnd: Int,
    /** Values submitted on that session's link before the callback. */
    val sentAtEnd: Int,
)

/** Bounded polling (the engine runs on real threads). */
object Poll {
    fun until(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() - deadline > 0) fail("timed out after $timeoutMs ms waiting for $what")
            Thread.sleep(2)
        }
    }
}

/** What one connect call does: return [socket], throw [error], optionally after [hold] opens. */
class ConnectPlan(
    val socket: FakeSocket = FakeSocket(),
    val error: PipeError? = null,
    val hold: CountDownLatch? = null,
) {
    @Volatile var returned = false
}

class ConnectCall(val target: PipeTarget, val timeoutMs: Long, val thread: Thread, val plan: ConnectPlan)

/**
 * Plans are queued per port and used once each, in order; a connect with no plan left gets a
 * fresh socket. Every call is recorded with its target, timeout, thread and plan.
 */
class FakeConnector : PipeConnector {
    private val queued = ConcurrentHashMap<Int, ConcurrentLinkedQueue<ConnectPlan>>()
    private val all = CopyOnWriteArrayList<ConnectPlan>()
    val calls = CopyOnWriteArrayList<ConnectCall>()

    fun plan(port: Int, plan: ConnectPlan): ConnectPlan {
        all += plan
        queued.getOrPut(port) { ConcurrentLinkedQueue() }.add(plan)
        return plan
    }

    fun callsTo(port: Int): Int = calls.count { it.target.port == port }

    fun releaseAll() {
        all.forEach { p -> p.hold?.let { while (it.count > 0) it.countDown() } }
    }

    override fun connect(target: PipeTarget, timeoutMs: Long): PipeSocket {
        val plan = queued[target.port]?.poll() ?: ConnectPlan().also { all += it }
        calls += ConnectCall(target, timeoutMs, Thread.currentThread(), plan)
        plan.hold?.let { if (!it.await(10, TimeUnit.SECONDS)) throw PipeConnectException(PipeError.CONNECT_TIMEOUT) }
        plan.error?.let { throw PipeConnectException(it) }
        plan.returned = true
        return plan.socket
    }
}

/** The allowlist ([allow]), VPN prerequisite ([vpn]) and tunnel routes ([routed]), recorded. */
class FakeGate : PipeGate {
    @Volatile var allow: (PipeTarget) -> Boolean = { true }
    @Volatile var vpn = true
    @Volatile var routed: (InetAddress) -> Boolean = { true }
    val seen = CopyOnWriteArrayList<PipeTarget>()
    val vpnChecks = AtomicInteger()

    override fun allowed(target: PipeTarget): Boolean {
        seen += target
        return allow(target)
    }

    override fun vpnAvailable(): Boolean {
        vpnChecks.incrementAndGet()
        return vpn
    }

    override fun routes(address: InetAddress): Boolean = routed(address)
}

/**
 * In-memory TCP socket. [read] blocks until bytes, EOF, a failure or [close] (which makes it
 * throw). [write] accepts at most [writeChunk] bytes per call and can be held (slow reader) or made
 * to fail. [remote] is the connected address the engine re-checks against the tunnel routes.
 */
class FakeSocket(override val remote: InetAddress? = null) : PipeSocket {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val inbound = ArrayDeque<Byte>()
    private val written = ByteArrayOutputStream()
    private var eof = false
    private var readFailure: IOException? = null
    private var writeFailure: IOException? = null
    private var writesHeld = false

    @Volatile var writeChunk = Int.MAX_VALUE

    @Volatile var closed = false
        private set

    @Volatile var shutdownOutputs = 0
        private set

    @Volatile var bytesRead = 0L
        private set

    @Volatile var largestRead = 0
        private set

    @Volatile var writeCalls = 0
        private set

    /** "write:n", "shutdownOutput" and "close", in call order. */
    val events = CopyOnWriteArrayList<String>()

    val writtenBytes: ByteArray get() = lock.withLock { written.toByteArray() }

    fun feed(bytes: ByteArray) = lock.withLock {
        bytes.forEach { inbound.addLast(it) }
        changed.signalAll()
    }

    fun feedEof() = lock.withLock {
        eof = true
        changed.signalAll()
    }

    fun failReads() = lock.withLock {
        readFailure = IOException("synthetic read failure")
        changed.signalAll()
    }

    fun failWrites() = lock.withLock {
        writeFailure = IOException("synthetic write failure")
        changed.signalAll()
    }

    fun holdWrites(hold: Boolean) = lock.withLock {
        writesHeld = hold
        changed.signalAll()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
        if (len > largestRead) largestRead = len
        while (!closed && readFailure == null && inbound.isEmpty() && !eof) changed.await(20, TimeUnit.MILLISECONDS)
        val failure = readFailure
        when {
            closed -> throw IOException("socket closed")
            failure != null -> throw failure
            inbound.isNotEmpty() -> {
                val n = minOf(len, inbound.size)
                for (i in 0 until n) b[off + i] = inbound.removeFirst()
                bytesRead += n
                n
            }
            else -> -1
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
        writeCalls++
        while (writesHeld && !closed && writeFailure == null) changed.await(20, TimeUnit.MILLISECONDS)
        val failure = writeFailure
        when {
            closed -> throw IOException("socket closed")
            failure != null -> throw failure
            else -> {
                val n = minOf(len, writeChunk)
                written.write(b, off, n)
                events += "write:$n"
                n
            }
        }
    }

    override fun shutdownOutput() = lock.withLock {
        if (closed) throw IOException("socket closed")
        shutdownOutputs++
        events += "shutdownOutput"
    }

    override fun close() = lock.withLock {
        if (!closed) events += "close"
        closed = true
        changed.signalAll()
    }
}

/**
 * A real single-thread scheduler, used as the engine's watchdog, that holds back every task
 * scheduled with a delay [hold] accepts: the test then decides when, and whether, that timer
 * fires ([fire] runs it on the scheduler's own thread, as the timer would). Everything else is
 * the real scheduler.
 */
class HeldScheduler(
    private val inner: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pipe-watchdog").apply { isDaemon = true } },
) : ScheduledExecutorService by inner {
    class Held(val delayMs: Long, val task: Runnable)

    /** Which delays (ms) to hold; checked when each task is scheduled. */
    @Volatile var hold: (delayMs: Long) -> Boolean = { false }

    /** Held tasks in the order they were scheduled. */
    val held = CopyOnWriteArrayList<Held>()

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val ms = unit.toMillis(delay)
        if (!hold(ms)) return inner.schedule(command, delay, unit)
        held += Held(ms, command)
        return inner.schedule(Runnable {}, 1, TimeUnit.DAYS) // a live future the engine may ignore
    }

    /** Runs [h] on the scheduler thread now and waits for it. */
    fun fire(h: Held) {
        inner.submit(h.task).get(5, TimeUnit.SECONDS)
    }
}

/**
 * One [PipeEngine] with a fake P4 per session. [random] and [phoneKey] are the engine's RNG and
 * injected X25519 key; [p4Key], [p4Nonce] fix the P4's handshake entropy and [p4InnerNonce] its
 * inner HELLO nonce. [p4Psk] differs from [psk] to model a mismatched secret; [p4Impostor] makes a
 * peer that answers CONFIRM without checking it. [executor] and [watchdog] are the engine's own
 * two threads, injectable so a test can hold the engine thread or a timer.
 *
 * While [autoHandshake] is on, [sync] writes each P4 handshake reply straight back, and a P4
 * handshake failure is answered with REJECT 05 02 like BLE_PhoneGatt.cpp (`rejectPending_`).
 */
class SecureRig(
    val mtu: Int = 517,
    val psk: ByteArray = PipeFixture.psk,
    val p4Psk: ByteArray = psk,
    random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    phoneKey: ByteArray? = null,
    private val p4Key: ByteArray? = null,
    private val p4Nonce: ByteArray? = null,
    val p4InnerNonce: ByteArray = P4_NONCE,
    private val p4Impostor: Boolean = false,
    val gate: FakeGate = FakeGate(),
    val connector: FakeConnector = FakeConnector(),
    executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pipe-engine").apply { isDaemon = true } },
    watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pipe-watchdog").apply { isDaemon = true } },
) : AutoCloseable {
    /** One admitted session: its token, link and P4, and the phone frames decoded so far. */
    inner class Conn(val token: Long, val mtu: Int, val link: FakeLink, val p4: FakeP4) {
        /** Every phone DATA value the P4 opened, decoded. */
        val frames = CopyOnWriteArrayList<PhoneFrame>()
        /** Handshake values the P4 produced (HELLO_ACK, CONFIRM_ACK), whether or not written back. */
        val replies = CopyOnWriteArrayList<ByteArray>()
        /** Indexes of link values the P4 refused (error()). */
        val p4Errors = CopyOnWriteArrayList<Int>()
        internal var cursor = 0
        @Volatile var subscribedAtNanos = 0L
    }

    private val skewMs = AtomicLong()

    /** The engine clock (elapsedRealtime in the app): real milliseconds plus every [suspendFor]. */
    val clock: () -> Long = { System.nanoTime() / 1_000_000 + skewMs.get() }

    /**
     * A CPU suspend of [ms]: the engine clock jumps forward at once while real time, and with it
     * the engine's scheduled timers (uptime-based in the app), does not move.
     */
    fun suspendFor(ms: Long) {
        skewMs.addAndGet(ms)
    }

    val ends = CopyOnWriteArrayList<EndRecord>()
    /** Every onStage call, as (token, stage). */
    val stages = CopyOnWriteArrayList<Pair<Long, String>>()
    /** onStage and onEnd calls in arrival order: "stage:<name>" and "end:<kind>". */
    val log = CopyOnWriteArrayList<String>()
    /** Runs inside onEnd (to observe what was already torn down when it arrived). */
    @Volatile var onEndHook: (PipeEnd) -> Unit = {}
    val conns = CopyOnWriteArrayList<Conn>()
    @Volatile var autoHandshake = true
    private var nextToken = 0x1000L

    /** Every stream and session record the engine reported (the qualification log's input). */
    val streamRecords = CopyOnWriteArrayList<PipeStreamRecord>()
    val sessionRecords = CopyOnWriteArrayList<PipeSessionRecord>()

    val engine: PipeEngine = PipeEngine(
        connector = connector,
        gate = gate,
        onEnd = { recordEnd(it) },
        onStreamRecord = { streamRecords += it },
        onSessionRecord = { sessionRecords += it },
        onStage = { t, s ->
            stages += t to s
            log += "stage:$s"
        },
        clock = clock,
        random = random,
        privateKey = { phoneKey?.copyOf() },
        executor = executor,
        watchdog = watchdog,
    )

    /** The latest session. */
    val conn: Conn get() = conns.last()
    val link: FakeLink get() = conn.link
    val p4: FakeP4 get() = conn.p4
    val token: Long get() = conn.token

    private fun recordEnd(end: PipeEnd) {
        val c = conns.firstOrNull { it.token == end.token }
        ends += EndRecord(end, System.nanoTime(), engine.active, c?.link?.disconnects?.size ?: -1, c?.link?.sent?.size ?: -1)
        log += "end:${end.kind}"
        onEndHook(end)
    }

    /** A new admitted session (fresh token, link and P4); the engine's answer to the CCCD enable. */
    fun begin(mtu: Int = this.mtu, link: FakeLink = FakeLink(clock), token: Long = nextToken++): Boolean {
        link.engine = engine
        link.token = token
        conns += Conn(token, mtu, link, FakeP4(mtu, p4Psk, p4Key, p4Nonce, checksConfirm = !p4Impostor))
        return engine.begin(link, mtu, psk, token)
    }

    /** The CCCD response went out: the engine may notify from now on. */
    fun subscribe(responseMs: Long = 0, c: Conn = conn) {
        c.subscribedAtNanos = System.nanoTime()
        engine.subscribed(c.token, responseMs)
    }

    /** Begin, subscribe, handshake, the P4's inner HELLO offering [offer], HELLO_ACK completed. */
    fun ready(mtu: Int = this.mtu, offer: Int? = null, link: FakeLink = FakeLink(clock)): Conn {
        assertTrue("begin at MTU $mtu", begin(mtu, link))
        subscribe()
        awaitStage("secure")
        innerHello(offer ?: p4.inner.value)
        awaitStage("ready")
        return conn
    }

    /** The P4's inner HELLO (`Session::start`): its nonce, two streams, max_value [offer]. */
    fun innerHello(offer: Int = p4.inner.value) = send(Op.HELLO, 0, helloBody(offer, p4InnerNonce))

    fun awaitStage(stage: String, timeoutMs: Long = 5_000) =
        Poll.until("stage $stage (now ${engine.currentStats.stage})", timeoutMs) { sync(); engine.currentStats.stage == stage }

    /** Writes one raw RX value for [c]. */
    fun write(value: ByteArray, c: Conn = conn) = engine.onWrite(c.token, value)

    /** Seals one inner frame as the P4's next DATA and writes it. */
    fun sendFrame(frame: ByteArray, c: Conn = conn) = write(c.p4.seal(frame), c)

    /** One P4 message, fragmented by its framer, each fragment sealed and written. */
    fun send(op: Int, id: Int, body: ByteArray = ByteArray(0), c: Conn = conn) {
        for (f in c.p4.inner.frames(op, id, body)) sendFrame(f, c)
    }

    fun open(id: Int, port: Int, type: Int = ADDR_IPV4, host: ByteArray = TEST_IPV4, limit: Long = 1024) =
        send(Op.OPEN, id, openBody(type, host, port, limit))

    /** OPEN that connects; returns the socket once OPEN_OK has been submitted. */
    fun openStream(id: Int, port: Int, socket: FakeSocket = FakeSocket(), type: Int = ADDR_IPV4, host: ByteArray = TEST_IPV4): FakeSocket {
        connector.plan(port, ConnectPlan(socket))
        open(id, port, type, host)
        await("OPEN_OK $id") { it.op == Op.OPEN_OK && it.id == id }
        return socket
    }

    fun data(id: Int, bytes: ByteArray) = send(Op.DATA, id, bytes)

    fun credit(id: Int, limit: Long) = send(Op.CREDIT, id, le32(limit))

    /**
     * Feeds every new link value of [c] to its P4 in order: handshake replies go back to the engine
     * (while [autoHandshake]), DATA is opened and decoded. Returns every decoded phone frame.
     */
    @Synchronized
    fun sync(c: Conn = conn): List<PhoneFrame> {
        while (c.cursor < c.link.sent.size) {
            val s = c.link.sent[c.cursor++]
            when (val rx = c.p4.receive(s.value)) {
                is FakeP4.Rx.Reply -> {
                    c.replies += rx.value
                    if (autoHandshake) write(rx.value, c)
                }
                is FakeP4.Rx.Data -> {
                    val f = WireFrame(rx.plaintext)
                    c.frames += PhoneFrame(s, rx.counter, f)
                    // The P4 frames everything after HELLO_ACK at the phone's chosen max_value.
                    if (f.op == Op.HELLO_ACK && f.size == 32) c.p4.inner.value = u16(f.bytes, 30)
                }
                FakeP4.Rx.Error -> {
                    c.p4Errors += s.index
                    if (autoHandshake && c.p4.failedInHandshake) write(hex("0502"), c)
                }
            }
        }
        return c.frames.toList()
    }

    /** Waits for a decoded phone frame matching [match] on [c]. */
    fun await(what: String, c: Conn = conn, timeoutMs: Long = 5_000, match: (PhoneFrame) -> Boolean): PhoneFrame {
        var found: PhoneFrame? = null
        Poll.until(what, timeoutMs) {
            found = sync(c).firstOrNull(match)
            found != null
        }
        return found!!
    }

    /** Lets [ms] pass and syncs. */
    fun settle(ms: Long = 200): List<PhoneFrame> {
        Thread.sleep(ms)
        return sync()
    }

    /** Waits until every OK submission on [c]'s link has had its completion delivered. */
    fun awaitQuiet(c: Conn = conn, extraMs: Long = 40) {
        Poll.until("link quiet") { c.link.sent.all { it.returned && (it.result != PipeSubmit.OK || it.completedNanos != 0L) } }
        Thread.sleep(extraMs)
        Poll.until("link still quiet") { c.link.sent.all { it.returned && (it.result != PipeSubmit.OK || it.completedNanos != 0L) } }
        sync(c)
    }

    fun awaitEnd(c: Conn = conn, timeoutMs: Long = 5_000): EndRecord {
        Poll.until("PipeEnd for ${c.token}", timeoutMs) {
            sync(c)
            ends.any { it.end.token == c.token }
        }
        return ends.first { it.end.token == c.token }
    }

    fun endsOf(c: Conn = conn): List<EndRecord> = ends.filter { it.end.token == c.token }

    fun frames(id: Int, op: Int, c: Conn = conn): List<PhoneFrame> = sync(c).filter { it.id == id && it.op == op }

    fun credits(id: Int): List<Long> = frames(id, Op.CREDIT).map { u32(it.body, 0) }

    fun dataBytes(id: Int): Int = frames(id, Op.DATA).sumOf { it.length }

    fun dataOf(id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        frames(id, Op.DATA).forEach { out.write(it.body) }
        return out.toByteArray()
    }

    /**
     * Every link value of [c] is a P4-accepted DATA value of at most V bytes carrying one inner
     * frame of at most [frameLimit] bytes, framed legally (PipeTestKit [messagesOf]), with
     * contiguous inner sequence numbers from 0 and outer counters strictly increasing from 1.
     */
    fun assertWellFormed(frameLimit: Int, c: Conn = conn, handshakeValues: Int = 2) {
        val frames = sync(c)
        assertEquals("every DATA value opened by the P4: errors at ${c.p4Errors}", emptyList<Int>(), c.p4Errors.toList())
        assertEquals("values = handshake + DATA", c.link.sent.size, handshakeValues + frames.size)
        val v = specValue(c.mtu)
        for (s in c.link.sent) assertTrue("$s exceeds V=$v", s.value.size <= v)
        for ((i, f) in frames.withIndex()) {
            assertTrue("$f exceeds P=$frameLimit", f.frame.size <= frameLimit)
            assertEquals("$f inner sequence", i.toLong(), f.seq)
            assertEquals("$f outer counter", i + 1L, f.counter)
        }
        messagesOf(frames.map { it.frame })
    }

    override fun close() {
        connector.releaseAll()
        engine.shutdown()
        conns.forEach { it.link.close() }
    }
}
