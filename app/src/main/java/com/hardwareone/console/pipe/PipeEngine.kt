package com.hardwareone.console.pipe

import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** What one notification submission did, as far as the platform can tell (android-002 §2). */
enum class PipeSubmit {
    /** The stack took it; onNotificationSent follows. */
    OK,
    /** Definitely not submitted: refused before anything reached the stack. */
    REFUSED,
    /** Unknown: it may still be submitted later (ERROR_UNKNOWN after the gatt-thread wait, dead binder). */
    UNCERTAIN,
}

/** The BLE link of one admitted session, as the GATT server exposes it to the engine. */
interface PipeLink {
    /** Submits one TX notification. A binder call that can block (up to ~2 s on some builds). */
    fun notify(value: ByteArray): PipeSubmit
    /** Ends the BLE connection (best effort). */
    fun disconnect()
}

/** One stream's TCP socket. Blocking calls; [close] unblocks them. */
interface PipeSocket : Closeable {
    /** The connected peer address, for re-checking the VPN route; null if unknown. */
    val remote: java.net.InetAddress? get() = null
    /** Reads at most [len] bytes; -1 at EOF. */
    fun read(b: ByteArray, off: Int, len: Int): Int
    /** Writes some of the bytes (at least one) and returns how many the socket accepted. */
    fun write(b: ByteArray, off: Int, len: Int): Int
    fun shutdownOutput()
}

class PipeConnectException(val error: PipeError) : IOException("pipe connect failed (${error.code})")

/** Opens the single TCP connection an OPEN allows: resolve, check the route, connect. No retries. */
fun interface PipeConnector {
    fun connect(target: PipeTarget, timeoutMs: Long): PipeSocket
}

/** What an OPEN must pass before any DNS or TCP. */
interface PipeGate {
    /** The configured destination, compared byte for byte. */
    fun allowed(target: PipeTarget): Boolean
    /** The configured VPN prerequisite currently holds. */
    fun vpnAvailable(): Boolean
    /** The VPN still routes [address] (checked again when a connect completes). */
    fun routes(address: java.net.InetAddress): Boolean = true
}

/** Why a session ended: decides the 30 s cooldown (R14 / A-5) and what the UI says. */
enum class PipeEndKind {
    /** Invalid authentication or crypto, a received REJECT, or the peer let the handshake expire. */
    AUTH,
    /** Link loss, Bluetooth off, a notification refused/failed/timed out, a local stall or resources. */
    TRANSPORT,
    /** An authenticated inner-protocol failure, detected by either side. */
    PROTOCOL,
    /** Stop, Forget, a secret change or service shutdown. */
    CANCELLED,
}

/** Reported once per session, after its teardown. */
data class PipeEnd(
    val token: Long,
    val kind: PipeEndKind,
    val error: PipeError?,
    /** A submission was still unsettled: the GATT server instance must be retired (FW-5). */
    val unsettled: Boolean,
    /** CONFIRM_ACK had verified. */
    val authenticated: Boolean,
    /** HELLO_ACK's notification had completed (the phone's local readiness). */
    val ready: Boolean,
)

/**
 * One session, for the qualification log (row 10), reported with its [PipeEnd]. [corr] is the
 * device's pipe HELLO nonce in hex (null before the inner HELLO); times are on the engine clock.
 */
data class PipeSessionRecord(
    val end: PipeEnd,
    val corr: String?,
    val readyAt: Long?,
    val endedAt: Long,
    /** Callbacks dropped during this session because they matched no current submission or token. */
    val staleCallbacks: Int,
)

/**
 * One stream, for the qualification log (row 10): keyed by [corr] and [id] like the P4's F3 record.
 * [result] is `open_ok` or `open_err:<code>`; [end] is `close:<0|1>`, `reset:<code>`, `session_end`,
 * or `none` for a stream refused before it opened. No target, address or content.
 */
data class PipeStreamRecord(
    val corr: String?,
    val id: Int,
    val openToResultMs: Long?,
    val result: String,
    val end: String,
    val bytesToDevice: Long,
    val bytesFromDevice: Long,
    val peerReset: Boolean,
    val peerClose: Boolean,
)

/** Counters for the settings screen. States, reason codes and sizes only, never endpoints or bytes. */
data class PipeStats(
    /** idle | handshake | secure | ready | ending */
    val stage: String = "idle",
    /** The current (or last) session's corr, as `phonepipe stats` prints it; null before the inner HELLO. */
    val corr: String? = null,
    val mtu: Int = 0,
    /** Inner frame limit in use: P until HELLO_ACK, then the negotiated value. */
    val frame: Int = 0,
    val liveStreams: Int = 0,
    /** OPENs the device sent in the current session. */
    val opens: Int = 0,
    val sessions: Int = 0,
    val opened: Int = 0,
    /** OPEN_ERR sent, by code. */
    val refused: Map<PipeError, Int> = emptyMap(),
    /** Stream RESETs Android sent, by code. */
    val resets: Map<PipeError, Int> = emptyMap(),
    val framesIn: Long = 0,
    val framesOut: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    /** Reassembled OPEN to OPEN_OK submission, for the A-1 margin. */
    val lastOpenOkMs: Long? = null,
    /** Reassembled OPEN to OPEN_ERR, queued. */
    val lastOpenErrMs: Long? = null,
    /** Streams the device ended with RESET / CLOSE. */
    val peerResets: Int = 0,
    val peerCloses: Int = 0,
    /** When the last stream outcome happened, on the engine clock; null = none yet. */
    val lastOutcomeAt: Long? = null,
    val lastEnd: PipeEndKind? = null,
    val lastError: PipeError? = null,
    /** Callbacks dropped because they belonged to no current session or submission. */
    val staleCallbacks: Int = 0,
)

/**
 * Runs one admitted pipe session at a time: the HW1-PIPE-v1 handshake ([PipeSecureChannel]), then
 * the inner protocol ([PipeSession]) with every inner frame carried as one secure DATA value, and
 * the per-stream sockets.
 *
 * Threads. Bluetooth callbacks only copy their value and queue an event; one engine thread
 * processes events in order, submits notifications and drives timers, so a reaction the P4 may act
 * on (OPEN_OK, CREDIT) is accepted before any later RX value is processed. The engine lock is never
 * held across the notify binder call. A separate watchdog thread enforces the 2 s completion
 * deadline, which starts before the call, so a call stuck in the binder still ends the session.
 * Each stream has connect/reader/writer threads that block only on their own socket.
 *
 * Every submission is tied to one session token and is settled by its completion or a definite
 * refusal; a session that ends with one unsettled reports it ([PipeEnd.unsettled]) so the server
 * instance is retired and a late platform callback can never complete a later session's frame.
 * Socket results carry the session and target generation and are dropped (closing the socket) when
 * stale, so nothing crosses a reconnect or a destination change.
 */
class PipeEngine(
    private val connector: PipeConnector,
    private val gate: PipeGate,
    private val onStats: (PipeStats) -> Unit = {},
    /** Called once per session after its teardown, off the engine lock. */
    private val onEnd: (PipeEnd) -> Unit = {},
    /** Stage changes (handshake, secure, ready, ending, idle) as they happen, off the lock. */
    private val onStage: (token: Long, stage: String) -> Unit = { _, _ -> },
    /**
     * Work just started that must not stall in a CPU suspend (a handshake, a stream's connect, a
     * teardown): called synchronously, off the lock, so the app can take a wake lock at once.
     */
    private val onWake: () -> Unit = {},
    /** Every finished stream. Called under the engine lock, so it must only hand the record off. */
    private val onStreamRecord: (PipeStreamRecord) -> Unit = {},
    /** Every finished session, just before [onEnd], off the lock. */
    private val onSessionRecord: (PipeSessionRecord) -> Unit = {},
    /** Milliseconds on a suspend-inclusive clock (elapsedRealtime in the app). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    /** Test hook: the next channel's X25519 private key; null draws one from [random]. */
    private val privateKey: () -> ByteArray? = { null },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pipe-engine").apply { isDaemon = true } },
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pipe-watchdog").apply { isDaemon = true } },
) {
    private enum class Phase { IDLE, HANDSHAKE, PIPE, ENDING }

    private class Submission(val token: Long, val startedAt: Long, val handshake: Boolean, val helloAck: Boolean, val openOk: Int, val reset: Boolean)

    private sealed class Event(val token: Long) {
        class Rx(token: Long, val value: ByteArray) : Event(token)
        /** [at]: when the completion arrived, for the 2 s bound (R10). */
        class Sent(token: Long, val status: Int, val at: Long) : Event(token)
        class Lost(token: Long) : Event(token)
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private var phase = Phase.IDLE
    private var token = 0L
    private var link: PipeLink? = null
    private var channel: PipeSecureChannel? = null
    private var session: PipeSession? = null
    /** Bumped per session and at teardown; socket results from another generation are stale. */
    private var generation = 0L
    /** Bumped by [retarget]; socket results for the old destination are stale. */
    private var targetGeneration = 0L
    private var valueLimit = 0
    private var frameLimit = 0
    /** The next handshake message to notify (HELLO, then CONFIRM). */
    private var handshakeTx: ByteArray? = null
    /** When the CCCD response went out; 0 = not yet (nothing may be notified before it). */
    private var subscribedAt = 0L
    /** A handshake step of ours was slow, so a handshake expiry is not the peer's fault (A-5). */
    private var localStall = false
    private var ready = false
    private var readyAt: Long? = null
    private var sessionStale0 = 0
    private var sessionCorr: String? = null
    /** When each stream's OPEN_OK was accepted by the stack, for its record. */
    private val openOkAt = HashMap<Int, Long>()
    /** Inside [PipeLink.notify]. */
    private var submitting: Submission? = null
    /** The engine thread is inside a notify call, of any session: kept across toIdleLocked. */
    private var inCall = false
    /** Submitted; onNotificationSent pending. */
    private var awaiting: Submission? = null
    private var lastSubmitAt = Long.MIN_VALUE / 2
    private var pumpQueued = false
    private var drainQueued = false
    private val events = ArrayDeque<Event>()
    private var ticker: ScheduledFuture<*>? = null
    private var resetUnsafe = false

    // Teardown of the current session (phase ENDING).
    private var ending: PipeEnd? = null
    private var teardownStarted = false
    private var resetFrame: ByteArray? = null
    private var resetPending = false
    private var disconnected = false
    /** The disconnect step ran; the session finishes once nothing is outstanding. */
    private var settleArmed = false
    private val closing = ArrayList<StreamIo>(2)

    private val ios = HashMap<Int, StreamIo>()
    /** Connect threads still running, current or stale; bounds threads a peer can make us start. */
    private var connecting = 0
    private val frame = ByteArray(PipeWire.VALUE_CAP)
    private var stats = PipeStats()
    private var reportedStage = "idle"

    val currentStats: PipeStats get() = lock.withLock { stats }

    /** A session exists (from admission until its teardown finished). */
    val active: Boolean get() = lock.withLock { phase != Phase.IDLE }

    /**
     * The admitted candidate's CCCD enable, before its ATT response: a fresh secure channel and
     * HELLO. false = not possible now (a previous session is still tearing down, or the engine
     * thread is still inside an earlier session's notify, MTU too small); answer 0x11. [psk] is copied.
     */
    fun begin(newLink: PipeLink, linkMtu: Int, psk: ByteArray, newToken: Long): Boolean {
        lock.withLock {
            // A session's timers and events run on the engine thread: none while it is blocked.
            if (phase != Phase.IDLE || inCall || executor.isShutdown || newToken == 0L) return false
            val p = PipeWire.frameLimit(linkMtu)
            if (p < PipeWire.MIN_VALUE) return false
            val ch = PipeSecureChannel(psk, random, privateKey())
            val hello = runCatching { ch.hello() }.getOrNull()
            if (hello == null) {
                ch.wipe()
                return false
            }
            generation++
            phase = Phase.HANDSHAKE
            token = newToken
            link = newLink
            channel = ch
            session = null
            valueLimit = PipeWire.valueLimit(linkMtu)
            frameLimit = p
            handshakeTx = hello
            subscribedAt = 0
            localStall = false
            ready = false
            readyAt = null
            sessionCorr = null
            sessionStale0 = stats.staleCallbacks
            openOkAt.clear()
            submitting = null
            awaiting = null
            events.clear()
            resetUnsafe = false
            ending = null
            teardownStarted = false
            resetFrame = null
            resetPending = false
            disconnected = false
            settleArmed = false
            stats = stats.copy(stage = "handshake", corr = null, mtu = linkMtu, frame = p, liveStreams = 0, opens = 0, sessions = stats.sessions + 1)
            ticker = runCatching { executor.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        }
        afterChange()
        return true
    }

    /**
     * The CCCD response went out ([responseMs] = how long sending it took): the 4.5 s handshake
     * clock starts and HELLO is submitted.
     */
    fun subscribed(forToken: Long, responseMs: Long = 0) {
        lock.withLock {
            if (forToken != token || phase != Phase.HANDSHAKE) return
            subscribedAt = clock()
            if (responseMs > STALL_MS) localStall = true
        }
        requestPump(0)
    }

    /** One RX write command for [forToken]. The value is copied here; the binder array is never kept. */
    fun onWrite(forToken: Long, value: ByteArray?) = enqueue(Event.Rx(forToken, value?.copyOf() ?: ByteArray(0)))

    /** onNotificationSent from the admitted session's own server instance and device. */
    fun onNotificationSent(forToken: Long, status: Int) = enqueue(Event.Sent(forToken, status, clock()))

    /** The admitted device's link went down; ordered after that instance's earlier callbacks. */
    fun linkLost(forToken: Long) = enqueue(Event.Lost(forToken))

    /**
     * Ends the session now, silently: no frame is sent (Bluetooth off, the CCCD rules of R3, a
     * failed link-holder registration). [forToken] null ends whichever session exists.
     */
    fun end(forToken: Long?, kind: PipeEndKind = PipeEndKind.TRANSPORT, error: PipeError = PipeError.CANCELLED) {
        lock.withLock {
            if (forToken != null && forToken != token) return
            terminateLocked(kind, error, allowReset = false)
        }
        afterChange()
    }

    /** An authority change (Stop, Forget, new secret): best-effort encrypted RESET 14, then disconnect. */
    fun cancel() {
        lock.withLock { terminateLocked(PipeEndKind.CANCELLED, PipeError.CANCELLED, allowReset = true) }
        afterChange()
    }

    /** VPN lost or no longer routes: end every stream (the authenticated pipe itself stays up, F5). */
    fun vpnLost() = resetStreams(PipeError.VPN_UNAVAILABLE)

    /**
     * The destination changed (FW-3), as one step under the engine lock: [swap] installs the new
     * allowlist, the target generation moves on, and every live stream ends (OPEN_ERR 4 / RESET 4),
     * its sockets close and any late result for it is dropped. The authenticated channel, stream IDs
     * and sequences carry on, and later OPENs are judged against the new allowlist.
     */
    fun retarget(swap: () -> Unit = {}) {
        lock.withLock {
            swap()
            targetGeneration++
            resetStreamsLocked(PipeError.TARGET_DENIED)
        }
        afterChange()
    }

    /** Ends every live stream with [error] (OPEN_ERR before OPEN_OK, RESET after); the session stays up. */
    fun resetStreams(error: PipeError) {
        lock.withLock { resetStreamsLocked(error) }
        afterChange()
    }

    /**
     * The VPN network is unchanged but its routes may have changed: reset every stream whose
     * connected address the tunnel no longer routes (its bound socket could otherwise fall through
     * to the physical network).
     */
    fun recheckRoutes(stillRouted: (java.net.InetAddress) -> Boolean) {
        lock.withLock {
            val s = session ?: return
            if (phase != Phase.PIPE) return
            for (io in ios.values) {
                val address = io.remote ?: continue
                if (!stillRouted(address)) resetStreamLocked(s, io.id, PipeError.VPN_UNAVAILABLE)
            }
        }
        afterChange()
    }

    /** Service stop: best-effort RESET 14, wait briefly for the teardown, then stop every thread. */
    fun shutdown() {
        cancel()
        lock.withLock {
            val until = clock() + SHUTDOWN_WAIT_MS
            while (phase != Phase.IDLE) {
                val left = until - clock()
                if (left <= 0) break
                changed.await(left, TimeUnit.MILLISECONDS)
            }
        }
        end(null, PipeEndKind.CANCELLED)
        finishNow()
        executor.shutdownNow()
        watchdog.shutdownNow()
    }

    // ---- events --------------------------------------------------------------------------

    private fun enqueue(e: Event) {
        var overflow = false
        var schedule = false
        lock.withLock {
            val wanted = e.token == token && (phase == Phase.HANDSHAKE || phase == Phase.PIPE || (phase == Phase.ENDING && e is Event.Sent))
            if (!wanted) {
                stats = stats.copy(staleCallbacks = stats.staleCallbacks + 1)
                return
            }
            if (events.size >= MAX_EVENTS) {
                overflow = true
                terminateLocked(PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES, allowReset = false)
            } else {
                events.addLast(e)
                if (!drainQueued) {
                    drainQueued = true
                    schedule = true
                }
            }
        }
        if (overflow) afterChange()
        if (schedule && runCatching { executor.execute { drain() } }.isFailure) lock.withLock { drainQueued = false }
    }

    private fun drain() {
        while (true) {
            var settled = false
            val handled = lock.withLock {
                val e = events.pollFirst()
                if (e == null) {
                    drainQueued = false
                    false
                } else {
                    handleLocked(e)
                    settled = e is Event.Sent && phase == Phase.ENDING
                    true
                }
            }
            if (!handled) return
            afterChange()
            if (settled) finishIfSettled(force = false)
        }
    }

    private fun handleLocked(e: Event) {
        if (e.token != token) return
        when (e) {
            is Event.Rx -> rxLocked(e.value)
            is Event.Sent -> sentLocked(e.status, e.at)
            is Event.Lost -> {
                disconnected = true
                terminateLocked(PipeEndKind.TRANSPORT, PipeError.CANCELLED, allowReset = false)
            }
        }
    }

    private fun rxLocked(value: ByteArray) {
        if (phase != Phase.HANDSHAKE && phase != Phase.PIPE) return
        val ch = channel ?: return
        stats = stats.copy(framesIn = stats.framesIn + 1)
        when (val r = ch.receive(value)) {
            is PipeSecureChannel.Rx.Reply ->
                if (phase == Phase.HANDSHAKE && handshakeTx == null) handshakeTx = r.value
                else terminateLocked(PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, allowReset = false)
            PipeSecureChannel.Rx.Established -> {
                if (phase != Phase.HANDSHAKE) {
                    terminateLocked(PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, allowReset = false)
                    return
                }
                // The inner HELLO deadline starts here, at CONFIRM_ACK verification (R5).
                phase = Phase.PIPE
                session = PipeSession(frameLimit, clock(), ::admit, random, ::outcomeLocked, ::streamEndLocked)
            }
            is PipeSecureChannel.Rx.Data -> {
                val s = session
                if (phase != Phase.PIPE || s == null) {
                    terminateLocked(PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, allowReset = false)
                    return
                }
                s.receive(r.plaintext, r.plaintext.size, clock())
                r.plaintext.fill(0)
                if (s.state == PipeSession.State.FAILED) terminateLocked(PipeEndKind.PROTOCOL, s.error, allowReset = true)
            }
            is PipeSecureChannel.Rx.Rejected -> terminateLocked(PipeEndKind.AUTH, PipeError.NOT_AUTHORIZED, allowReset = false)
            PipeSecureChannel.Rx.Invalid -> terminateLocked(PipeEndKind.AUTH, PipeError.BAD_PROTOCOL, allowReset = false)
        }
    }

    private fun sentLocked(status: Int, at: Long) {
        val a = awaiting
        if (a == null || a.token != token) {
            stats = stats.copy(staleCallbacks = stats.staleCallbacks + 1)
            return
        }
        awaiting = null
        if (phase == Phase.ENDING) return // settles a submission; the teardown decides what is next
        if (!PipeAttPolicy.notificationAccepted(status)) {
            terminateLocked(PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, allowReset = false)
            return
        }
        // Settled, but too late: terminal even if the watchdog has not run yet (R10).
        if (at - a.startedAt >= NOTIFY_COMPLETION_MS) {
            terminateLocked(PipeEndKind.TRANSPORT, PipeError.DEADLINE, allowReset = false)
            return
        }
        if (a.handshake && clock() - a.startedAt > STALL_MS) localStall = true
        if (a.helloAck && !ready) {
            ready = true
            readyAt = clock()
        }
    }

    // ---- submission ----------------------------------------------------------------------

    private fun requestPump(delayMs: Long) {
        lock.withLock {
            if (pumpQueued || executor.isShutdown) return
            pumpQueued = true
        }
        if (runCatching { executor.schedule({ pump() }, delayMs, TimeUnit.MILLISECONDS) }.isFailure) lock.withLock { pumpQueued = false }
    }

    /**
     * Submits at most one value (engine thread only): the next handshake message, or the next inner
     * frame sealed as secure DATA. The inner frame is accepted (sequence and bytes consumed) only
     * once the stack took it; anything else ends the session without a resend. The next submission
     * waits for this one's completion and the 30 ms gap.
     */
    private fun pump() {
        var sub: Submission? = null
        var value: ByteArray? = null
        var l: PipeLink? = null
        var wait = 0L
        var changedState = false
        lock.withLock {
            pumpQueued = false
            val before = phase
            try {
                if (phase != Phase.HANDSHAKE && phase != Phase.PIPE) return@withLock
                if (submitting != null || awaiting != null || subscribedAt == 0L) return@withLock
                val current = link ?: return@withLock
                wait = lastSubmitAt + NOTIFY_INTERVAL_MS - clock()
                if (wait > 0) return@withLock
                var helloAck = false
                var openOk = 0
                val v: ByteArray
                if (phase == Phase.HANDSHAKE) {
                    v = handshakeTx ?: return@withLock
                } else {
                    val s = session ?: return@withLock
                    val n = s.peek(frame)
                    if (n == 0) return@withLock // idle, or a failure inside peek that afterChange handles
                    if (n > frameLimit) {
                        terminateLocked(PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, allowReset = false)
                        return@withLock
                    }
                    val op = frame[1].toInt() and 0xff
                    // Ready only once the whole HELLO_ACK has gone: its LAST fragment.
                    helloAck = op == PipeOp.HELLO_ACK.code && (frame[10].toInt() and PipeWire.LAST) != 0
                    if (op == PipeOp.OPEN_OK.code) openOk = PipeWire.get16(frame, 2)
                    v = channel?.seal(frame.copyOf(n)) ?: run {
                        // Counter exhaustion is local, never an authentication failure.
                        terminateLocked(PipeEndKind.TRANSPORT, PipeError.NO_RESOURCES, allowReset = false)
                        return@withLock
                    }
                }
                // An oversized value would be truncated silently and still reported as sent.
                if (v.size > valueLimit) {
                    terminateLocked(PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, allowReset = false)
                    return@withLock
                }
                val now = clock()
                val s = Submission(token, now, phase == Phase.HANDSHAKE, helloAck, openOk, reset = false)
                submitting = s
                inCall = true
                lastSubmitAt = now
                sub = s
                value = v
                l = current
            } finally {
                // Only a pass that ended the session (or found it failed) needs the follow-up.
                changedState = phase != before || (phase == Phase.PIPE && session?.state == PipeSession.State.FAILED)
            }
        }
        if (wait > 0) {
            requestPump(wait)
            return
        }
        val s = sub
        if (s == null) {
            // Nothing to send: return quietly. Every event that can make a submission possible
            // (a completion, an RX value, subscribed(), a socket or credit change, the tick)
            // requests the next pump itself, so re-arming here would only spin.
            if (changedState) afterChange(requestNext = false)
            return
        }
        armWatchdog(s)
        val result = try {
            l!!.notify(value!!)
        } catch (e: Exception) {
            PipeSubmit.UNCERTAIN
        }
        lock.withLock {
            inCall = false
            submittedLocked(s, result)
        }
        afterChange(requestNext = false)
    }

    private fun submittedLocked(sub: Submission, result: PipeSubmit) {
        if (submitting === sub) submitting = null
        if (sub.token != token || (phase != Phase.HANDSHAKE && phase != Phase.PIPE)) {
            // The session ended while the call was blocked. A submitted value may still settle
            // during the teardown; an uncertain one never can.
            if (sub.token == token && phase == Phase.ENDING) when (result) {
                PipeSubmit.OK -> awaiting = sub
                PipeSubmit.UNCERTAIN -> ending = ending?.copy(unsettled = true)
                PipeSubmit.REFUSED -> {}
            }
            return
        }
        val now = clock()
        if (now - sub.startedAt >= NOTIFY_COMPLETION_MS) {
            // A return after the deadline never revives the session or restarts the budget.
            terminateLocked(PipeEndKind.TRANSPORT, PipeError.DEADLINE, allowReset = false, unsettled = result != PipeSubmit.REFUSED)
            return
        }
        when (result) {
            PipeSubmit.OK -> {
                awaiting = sub
                if (sub.handshake) {
                    handshakeTx = null
                } else {
                    val s = session!!
                    val wasFailed = s.state == PipeSession.State.FAILED
                    s.accept()
                    if (wasFailed || s.state == PipeSession.State.FAILED) resetUnsafe = true
                    stats = stats.copy(framesOut = stats.framesOut + 1)
                    if (sub.openOk != 0) s.find(sub.openOk)?.let {
                        stats = stats.copy(lastOpenOkMs = now - it.openedAt)
                        openOkAt[sub.openOk] = now
                    }
                }
            }
            PipeSubmit.REFUSED -> terminateLocked(PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, allowReset = false)
            PipeSubmit.UNCERTAIN -> terminateLocked(PipeEndKind.TRANSPORT, PipeError.BAD_PROTOCOL, allowReset = false, unsettled = true)
        }
    }

    private fun armWatchdog(sub: Submission) {
        runCatching { watchdog.schedule({ deadline(sub) }, NOTIFY_COMPLETION_MS, TimeUnit.MILLISECONDS) }
    }

    /** No completion 2 s after the call started: end the session from here (the engine thread may be blocked). */
    private fun deadline(sub: Submission) {
        var l: PipeLink? = null
        lock.withLock {
            if (sub.token != token || (submitting !== sub && awaiting !== sub)) return
            // A completion that arrived in time but is still queued settles it (the drain runs next).
            if (awaiting === sub && events.any { it is Event.Sent && it.at - sub.startedAt < NOTIFY_COMPLETION_MS }) return
            if (phase == Phase.ENDING) {
                ending = ending?.copy(unsettled = true)
                return
            }
            l = link
            disconnected = true
            terminateLocked(PipeEndKind.TRANSPORT, PipeError.DEADLINE, allowReset = false, unsettled = true)
        }
        runCatching { l?.disconnect() }
        afterChange()
    }

    // ---- timers --------------------------------------------------------------------------

    private fun tick() {
        lock.withLock {
            val now = clock()
            when (phase) {
                Phase.HANDSHAKE -> if (subscribedAt > 0 && now - subscribedAt >= HANDSHAKE_MS) {
                    // Charged only when the peer owed the next message and nothing of ours stalled.
                    val local = localStall || submitting != null || awaiting != null || handshakeTx != null ||
                        events.any { it is Event.Rx } // the reply came in time and waits behind this tick
                    terminateLocked(if (local) PipeEndKind.TRANSPORT else PipeEndKind.AUTH, PipeError.DEADLINE, allowReset = false)
                }
                Phase.PIPE -> {
                    val s = session ?: return@withLock
                    s.tick(now)
                    if (s.state == PipeSession.State.FAILED) terminateLocked(PipeEndKind.PROTOCOL, s.error, allowReset = true)
                }
                else -> {}
            }
        }
        afterChange()
    }

    // ---- teardown ------------------------------------------------------------------------

    /**
     * Ends the session (idempotent; the first reason wins). An encrypted stream-0 RESET is prepared
     * only for an inner-protocol failure or a cancellation, while the channel is healthy and no
     * submission is outstanding (R7): never after a crypto, transport or uncertain failure.
     */
    private fun terminateLocked(kind: PipeEndKind, error: PipeError?, allowReset: Boolean, unsettled: Boolean = false) {
        if (phase == Phase.IDLE || phase == Phase.ENDING) return
        val s = session
        val ch = channel
        val authenticated = phase == Phase.PIPE
        val quiet = submitting == null && awaiting == null && !resetUnsafe && !unsettled && !disconnected
        if (s != null && s.state != PipeSession.State.FAILED) s.fail(error ?: PipeError.CANCELLED)
        var reset: ByteArray? = null
        if (allowReset && quiet && s != null && ch != null && ch.state == PipeSecureChannel.State.ESTABLISHED && s.helloAckSent) {
            val n = s.failureFrame(frame)
            if (n in PipeWire.HEADER..frameLimit) reset = ch.seal(frame.copyOf(n))?.takeIf { it.size <= valueLimit }
        }
        ch?.wipe()
        phase = Phase.ENDING
        sessionCorr = s?.deviceNonce?.let(::hexOf)
        ending = PipeEnd(token, kind, s?.error ?: error, unsettled, authenticated, ready)
        resetFrame = reset
        resetPending = reset != null
        teardownStarted = false
        ticker?.cancel(false)
        ticker = null
        events.removeAll { it !is Event.Sent }
        generation++
        closing.addAll(ios.values)
        ios.clear()
        stats = stats.copy(stage = "ending", lastEnd = kind, lastError = s?.error ?: error, liveStreams = 0)
        changed.signalAll()
    }

    private fun startTeardown(reset: ByteArray?, l: PipeLink?, delay: Long) {
        if (reset != null && l != null) {
            // The teardown never waits on the RESET's binder call: a bounded backstop disconnects
            // and settles this session even if the call blocks (R7, R10).
            val t = lock.withLock { token }
            schedule(delay + NOTIFY_COMPLETION_MS) {
                if (lock.withLock { token == t && phase == Phase.ENDING }) {
                    lock.withLock { if (submitting != null) ending = ending?.copy(unsettled = true) }
                    disconnectAndSettle(l)
                }
            }
            val run = Runnable {
                val sub = lock.withLock {
                    Submission(token, clock(), handshake = false, helloAck = false, openOk = 0, reset = true).also {
                        submitting = it
                        inCall = true
                        lastSubmitAt = it.startedAt
                    }
                }
                // Give the RESET a moment to leave, but never wait for it (R7): the disconnect runs
                // RESET_GRACE_MS after the call started, from the watchdog, even if it is still blocked.
                val step = { if (lock.withLock { token == sub.token && phase == Phase.ENDING }) disconnectAndSettle(l) }
                schedule(RESET_GRACE_MS, step)
                val r = try {
                    l.notify(reset)
                } catch (e: Exception) {
                    PipeSubmit.UNCERTAIN
                }
                val mine = lock.withLock {
                    if (submitting === sub) submitting = null
                    inCall = false
                    // The backstop may already have finished this session while the call was
                    // blocked (inCall kept any new one out): a late result must then touch nothing.
                    (sub.token == token && phase == Phase.ENDING).also {
                        if (it) when (r) {
                            PipeSubmit.OK -> awaiting = sub
                            PipeSubmit.UNCERTAIN -> ending = ending?.copy(unsettled = true)
                            PipeSubmit.REFUSED -> {}
                        }
                    }
                }
                // Give the RESET a moment to leave, but never wait for it.
                if (mine && r != PipeSubmit.OK) step()
            }
            if (runCatching { executor.schedule(run, delay, TimeUnit.MILLISECONDS) }.isFailure) disconnectAndSettle(l)
        } else {
            disconnectAndSettle(l)
        }
    }

    private fun disconnectAndSettle(l: PipeLink?) {
        var t = 0L
        val needed = lock.withLock {
            if (phase != Phase.ENDING || settleArmed) return
            t = token
            resetPending = false
            settleArmed = true
            val n = !disconnected
            disconnected = true
            n
        }
        if (needed) runCatching { l?.disconnect() }
        // An outstanding completion may still settle (17 releases held ones before the disconnect).
        // The forced finish is this session's own: a later session's teardown keeps its full wait.
        if (!finishIfSettled(force = false)) schedule(SETTLE_MS) { finishIfSettled(force = true, forToken = t) }
    }

    /** Back to IDLE once nothing is outstanding (or [force]): reports the end. true = finished. */
    private fun finishIfSettled(force: Boolean, forToken: Long? = null): Boolean {
        var end: PipeEnd? = null
        var record: PipeSessionRecord? = null
        lock.withLock {
            if (phase != Phase.ENDING || (forToken != null && forToken != token)) return true
            if (!settleArmed) return false
            val outstanding = submitting != null || awaiting != null
            if (outstanding && !force) return false
            end = ending?.let { finalEndLocked(it, outstanding) }
            record = end?.let { recordLocked(it) }
            toIdleLocked()
        }
        record?.let { r -> runCatching { onSessionRecord(r) } }
        end?.let { e -> runCatching { onEnd(e) } }
        afterChange()
        return true
    }

    private fun finishNow() {
        var record: PipeSessionRecord? = null
        val end = lock.withLock {
            if (phase != Phase.ENDING) return
            val e = ending?.let { finalEndLocked(it, submitting != null || awaiting != null) }
            record = e?.let { recordLocked(it) }
            toIdleLocked()
            e
        }
        record?.let { r -> runCatching { onSessionRecord(r) } }
        end?.let { runCatching { onEnd(it) } }
        publish()
    }

    private fun finalEndLocked(e: PipeEnd, outstanding: Boolean): PipeEnd = e.copy(unsettled = e.unsettled || outstanding)

    private fun recordLocked(e: PipeEnd) = PipeSessionRecord(e, sessionCorr, readyAt, clock(), stats.staleCallbacks - sessionStale0)

    /** A stream left the session: its record, with this session's corr (under the lock). */
    private fun streamEndLocked(e: PipeSession.StreamEnd) {
        val okAt = openOkAt.remove(e.id)
        val refused = e.how.startsWith("open_err")
        // OPEN_OK accepted (or under way when the stream ended); OPEN_ERR; or neither, when the
        // device closed or reset it, or the session ended, before any answer.
        val result = when {
            e.okSubmitted || okAt != null -> "open_ok"
            refused -> e.how
            else -> "none"
        }
        val record = PipeStreamRecord(
            corr = session?.deviceNonce?.let(::hexOf),
            id = e.id,
            openToResultMs = when (result) {
                "open_ok" -> okAt?.let { it - e.openedAt }
                "none" -> null
                else -> clock() - e.openedAt
            },
            result = result,
            end = if (refused) "none" else e.how,
            bytesToDevice = e.bytesToDevice,
            bytesFromDevice = e.bytesFromDevice,
            peerReset = e.byPeer && e.how.startsWith("reset"),
            peerClose = e.byPeer && e.how.startsWith("close"),
        )
        runCatching { onStreamRecord(record) }
    }

    private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun toIdleLocked() {
        phase = Phase.IDLE
        token = 0
        link = null
        channel = null
        session = null
        handshakeTx = null
        submitting = null
        awaiting = null
        events.clear()
        ending = null
        resetFrame = null
        resetPending = false
        teardownStarted = false
        settleArmed = false
        stats = stats.copy(stage = "idle", liveStreams = 0, frame = 0)
        changed.signalAll()
    }

    /** Teardown timers run on the watchdog thread: the engine thread may be blocked in a binder call. */
    private fun schedule(delayMs: Long, block: () -> Unit) {
        if (runCatching { watchdog.schedule(block, delayMs, TimeUnit.MILLISECONDS) }.isFailure) block()
    }

    // ---- shared --------------------------------------------------------------------------

    private fun admit(target: PipeTarget): PipeError? {
        // Called from PipeSession.receive, under the lock. No DNS or TCP here.
        return when {
            !gate.allowed(target) -> PipeError.TARGET_DENIED
            !gate.vpnAvailable() -> PipeError.VPN_UNAVAILABLE
            connecting >= MAX_CONNECTS -> PipeError.NO_RESOURCES
            else -> null
        }
    }

    private fun resetStreamsLocked(error: PipeError) {
        val s = session ?: return
        if (phase != Phase.PIPE) return
        for (st in s.streams) if (st.live) resetStreamLocked(s, st.id, error)
    }

    /** OPEN_ERR before OPEN_OK has gone out, RESET after (counted through [outcomeLocked]). */
    private fun resetStreamLocked(s: PipeSession, id: Int, error: PipeError) {
        s.resetStream(id, error)
    }

    /** Every stream outcome, counted once where the session decides it (F3, A-1). */
    private fun outcomeLocked(what: PipeSession.Outcome, error: PipeError?, openedAt: Long) {
        val now = clock()
        stats = when (what) {
            PipeSession.Outcome.OPEN_ERR -> {
                val e = error ?: PipeError.BAD_PROTOCOL
                stats.copy(refused = stats.refused + (e to ((stats.refused[e] ?: 0) + 1)), lastOpenErrMs = now - openedAt)
            }
            PipeSession.Outcome.LOCAL_RESET -> {
                val e = error ?: PipeError.BAD_PROTOCOL
                stats.copy(resets = stats.resets + (e to ((stats.resets[e] ?: 0) + 1)))
            }
            PipeSession.Outcome.PEER_RESET -> stats.copy(peerResets = stats.peerResets + 1)
            PipeSession.Outcome.PEER_CLOSE -> stats.copy(peerCloses = stats.peerCloses + 1)
        }.copy(lastOutcomeAt = now)
    }

    /**
     * After any state change: start/stop stream I/O, wake socket threads, catch a session failure
     * raised off the engine thread, start a teardown, report the stage, then submit the next value.
     */
    private fun afterChange(requestNext: Boolean = true) {
        val start = ArrayList<StreamIo>(2)
        val stop = ArrayList<StreamIo>(2)
        var teardown: (() -> Unit)? = null
        var stageReport: Pair<Long, String>? = null
        lock.withLock {
            val s = session
            if (phase == Phase.PIPE && s != null && s.state == PipeSession.State.FAILED) {
                terminateLocked(PipeEndKind.PROTOCOL, s.error, allowReset = true)
            }
            if (phase == Phase.PIPE && s != null) {
                for (st in s.streams) {
                    if (st.state == PipeSession.StreamState.CONNECTING && !ios.containsKey(st.id)) {
                        val io = StreamIo(generation, targetGeneration, st.id, st.target!!, st.openedAt)
                        ios[st.id] = io
                        connecting++
                        start += io
                    }
                }
                val it = ios.values.iterator()
                while (it.hasNext()) {
                    val io = it.next()
                    if (s.find(io.id) == null) {
                        stop += io
                        it.remove()
                    }
                }
                stats = stats.copy(stage = if (ready) "ready" else "secure", frame = s.value, liveStreams = s.liveStreams, opens = s.opens)
                if (stats.corr == null) s.deviceNonce?.let { stats = stats.copy(corr = hexOf(it)) }
            }
            stop += closing
            closing.clear()
            if (phase == Phase.ENDING && !teardownStarted) {
                teardownStarted = true
                val l = link
                val reset = resetFrame
                resetFrame = null
                val delay = (lastSubmitAt + NOTIFY_INTERVAL_MS - clock()).coerceAtLeast(0)
                teardown = { startTeardown(reset, l, delay) }
            }
            if (stats.stage != reportedStage) {
                reportedStage = stats.stage
                stageReport = token to stats.stage
            }
            changed.signalAll()
        }
        // Keep the CPU up from a stream's OPEN (or a handshake, or a teardown) before anything blocks.
        if (start.isNotEmpty() || stageReport?.second == "handshake" || stageReport?.second == "ending") runCatching { onWake() }
        stop.forEach { it.close() }
        start.forEach { it.start() }
        // Before the teardown: a teardown with nothing outstanding finishes inside this call and
        // reports "idle" (and the PipeEnd) itself, which must not overtake this "ending".
        stageReport?.let { (t, st) -> runCatching { onStage(t, st) } }
        teardown?.invoke()
        publish()
        if (requestNext) requestPump(0)
    }

    private fun publish() {
        val snapshot = lock.withLock { stats }
        runCatching { onStats(snapshot) }
    }

    private fun current(gen: Long, tgen: Long, id: Int): PipeSession? =
        session?.takeIf { phase == Phase.PIPE && gen == generation && tgen == targetGeneration && it.find(id) != null }

    /** Socket owner for one stream of one session and destination generation. */
    private inner class StreamIo(val gen: Long, val tgen: Long, val id: Int, val target: PipeTarget, val openedAt: Long) {
        @Volatile private var socket: PipeSocket? = null
        @Volatile private var closed = false

        val remote: java.net.InetAddress? get() = socket?.remote

        fun start() {
            thread(name = "pipe-io-$id", isDaemon = true) { runIo() }
        }

        fun close() {
            closed = true
            socket?.let { runCatching { it.close() } }
        }

        private fun alive(): Boolean = !closed && current(gen, tgen, id)?.find(id)?.state == PipeSession.StreamState.OPEN

        private fun runIo() {
            val sock = try {
                val remaining = PipeWire.CONNECT_DEADLINE_MS - (clock() - openedAt)
                if (remaining <= 0) throw PipeConnectException(PipeError.CONNECT_TIMEOUT)
                connector.connect(target, remaining)
            } catch (e: Exception) {
                val error = (e as? PipeConnectException)?.error ?: PipeError.CONNECT_FAILED
                lock.withLock {
                    connecting--
                    current(gen, tgen, id)?.connectFailed(id, error)
                }
                afterChange()
                return
            }
            var unrouted = false
            val published = lock.withLock {
                connecting--
                val s = current(gen, tgen, id)
                // The routes may have changed while connecting (recheckRoutes skips sockets not yet
                // published): never publish a stream whose address has left the tunnel.
                unrouted = s != null && !closed && sock.remote?.let { gate.routes(it) } == false
                if (unrouted) s?.connectFailed(id, PipeError.VPN_UNAVAILABLE)
                if (!closed && !unrouted && s != null && s.connected(id, clock())) {
                    socket = sock
                    stats = stats.copy(opened = stats.opened + 1)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                // Cancelled or stale while connecting: close the new socket, publish nothing.
                runCatching { sock.close() }
                afterChange()
                return
            }
            afterChange()
            if (closed) runCatching { sock.close() }
            thread(name = "pipe-out-$id", isDaemon = true) { writeLoop(sock) }
            readLoop(sock)
        }

        private fun readLoop(sock: PipeSocket) {
            val buf = ByteArray(PipeWire.WINDOW)
            while (true) {
                val space = lock.withLock {
                    while (alive() && current(gen, tgen, id)!!.upSpace(id) == 0) changed.await(WAIT_MS, TimeUnit.MILLISECONDS)
                    if (!alive()) 0 else current(gen, tgen, id)!!.upSpace(id)
                }
                if (space == 0) return
                val n = try {
                    sock.read(buf, 0, space)
                } catch (e: IOException) {
                    fail(PipeError.TCP_IO)
                    return
                }
                lock.withLock {
                    val s = current(gen, tgen, id) ?: return
                    if (closed) return
                    if (n < 0) s.tcpEof(id) else {
                        s.tcpRead(id, buf, 0, n)
                        stats = stats.copy(bytesUp = stats.bytesUp + n)
                    }
                }
                afterChange()
                if (n < 0) return
            }
        }

        private fun writeLoop(sock: PipeSocket) {
            val buf = ByteArray(PipeWire.WINDOW)
            while (true) {
                var shut = false
                val n = lock.withLock {
                    var count = 0
                    while (alive()) {
                        val s = current(gen, tgen, id)!!
                        count = s.downPeek(id, buf)
                        if (count > 0) break
                        if (s.shutdownDue(id)) { shut = true; break }
                        changed.await(WAIT_MS, TimeUnit.MILLISECONDS)
                    }
                    if (alive()) count else -1
                }
                if (n < 0) return
                if (shut) {
                    try {
                        sock.shutdownOutput()
                    } catch (e: IOException) {
                        fail(PipeError.TCP_IO)
                        return
                    }
                    lock.withLock { current(gen, tgen, id)?.outputShutdown(id) }
                    return
                }
                var off = 0
                while (off < n) {
                    val wrote = try {
                        sock.write(buf, off, n - off)
                    } catch (e: IOException) {
                        fail(PipeError.TCP_IO)
                        return
                    }
                    if (wrote <= 0) { fail(PipeError.TCP_IO); return }
                    off += wrote
                    lock.withLock {
                        current(gen, tgen, id)?.downConsumed(id, wrote)
                        stats = stats.copy(bytesDown = stats.bytesDown + wrote)
                    }
                    afterChange()
                    if (closed) return
                }
            }
        }

        private fun fail(error: PipeError) {
            lock.withLock {
                if (closed) return
                val s = current(gen, tgen, id) ?: return
                resetStreamLocked(s, id, error)
            }
            afterChange()
        }
    }

    companion object {
        const val TICK_MS = 50L
        const val WAIT_MS = 250L
        /** onNotificationSent must arrive this long after the submit call started (R10). */
        const val NOTIFY_COMPLETION_MS = 2_000L
        /** Minimum gap between submissions on the stage-4 image (Q10); one is outstanding at a time. */
        const val NOTIFY_INTERVAL_MS = 30L
        /** Android's local handshake target from its CCCD answer, inside the P4's 5 s (R5). */
        const val HANDSHAKE_MS = 4_500L
        /** A handshake step of ours slower than this makes a handshake expiry a local stall (A-5). */
        const val STALL_MS = 1_000L
        const val RESET_GRACE_MS = 200L
        /** How long a teardown waits for an outstanding completion before calling it unsettled. */
        const val SETTLE_MS = 300L
        const val SHUTDOWN_WAIT_MS = 600L
        /** Queued Bluetooth events per session; more means the engine stalled (resources). */
        const val MAX_EVENTS = 64
        /** Live plus abandoned connect attempts (DNS cannot be interrupted). */
        const val MAX_CONNECTS = 4
    }
}
