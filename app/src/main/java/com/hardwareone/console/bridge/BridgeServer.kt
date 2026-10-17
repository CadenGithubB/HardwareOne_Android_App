package com.hardwareone.console.bridge

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The hotspot-facing listener. Accepts TLS connections from the ESP32, admits exactly two POST
 * routes with bounded heads and bodies, forwards each as one request to the configured relay
 * through [RelayClient], and streams the reply back without buffering it. One request per
 * connection; the connection is closed afterwards.
 *
 * Nothing here retries: a request that was sent and not answered is reported as an unknown
 * outcome to both the device (502 or an SSE error event) and the observer.
 */
class BridgeServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val interfaceName: String?,
    identity: ServerIdentity,
    private val relay: RelayClient,
    private val limits: BridgeLimits,
    private val observer: BridgeObserver,
) : Closeable {
    private val serverContext = BridgeTls.serverContext(identity)
    @Volatile private var serverSocket: SSLServerSocket? = null
    private val running = AtomicBoolean(false)
    private val connectionSlots = Semaphore(limits.maxConcurrentConnections)
    private val routeSlots = BridgeRoute.entries.associateWith { Semaphore(1) }
    private val pool = Executors.newCachedThreadPool(daemonThreads("hw1-bridge"))
    private val timers = Executors.newSingleThreadScheduledExecutor(daemonThreads("hw1-bridge-timer"))
    private val active: MutableSet<Connection> = Collections.synchronizedSet(HashSet())

    /** Port actually bound (useful when constructed with port 0). -1 before [start]. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    val listeningAddress: String get() = bindAddress.hostAddress

    /** Binds and starts accepting. Throws [IOException] if the address/port cannot be bound. */
    fun start() {
        val ss = serverContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        BridgeTls.configure(ss)
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(bindAddress, port), 8)
        } catch (e: IOException) {
            runCatching { ss.close() }
            throw e
        }
        serverSocket = ss
        running.set(true)
        notify { observer.onListener(ListenerState.Listening(bindAddress.hostAddress, ss.localPort, interfaceName)) }
        pool.execute { acceptLoop(ss) }
    }

    override fun close() {
        if (!running.getAndSet(false) && serverSocket == null) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(active) { active.toList() }.forEach { it.close() }
        pool.shutdownNow()
        timers.shutdownNow()
        notify { observer.onListener(ListenerState.Stopped) }
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept() as SSLSocket
            } catch (e: IOException) {
                if (running.getAndSet(false)) notify { observer.onListener(ListenerState.Failed("Listener stopped unexpectedly (${e.javaClass.simpleName}).")) }
                break
            }
            // Like the relay: beyond the bound, close without replying rather than queue.
            if (!connectionSlots.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            val connection = Connection(socket)
            active += connection
            try {
                pool.execute {
                    try {
                        connection.run()
                    } finally {
                        active -= connection
                        connectionSlots.release()
                    }
                }
            } catch (e: RejectedExecutionException) {
                active -= connection
                connectionSlots.release()
                runCatching { socket.close() }
            }
        }
    }

    private fun notify(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }

    /** One accepted device connection: exactly one request, then close. */
    private inner class Connection(private val device: SSLSocket) : Closeable {
        private val peer: String = device.inetAddress?.hostAddress ?: "?"
        private val startedAt = System.currentTimeMillis()
        private val closed = AtomicBoolean(false)
        @Volatile private var upstream: UpstreamResponse? = null
        @Volatile private var deadlineHit = false
        @Volatile private var deviceGone = false
        @Volatile private var route: BridgeRoute? = null
        @Volatile private var forwarded = 0L
        private val timerTasks = ArrayList<ScheduledFuture<*>>(2)

        fun run() {
            notify { observer.onConnectionOpened(peer) }
            var record: RequestRecord? = null
            try {
                record = handle()
            } catch (t: Throwable) {
                record = record(
                    if (upstream != null) RequestOutcome.OUTCOME_UNKNOWN else RequestOutcome.REJECTED_LOCALLY,
                    if (upstream != null) FailureKind.OUTCOME_UNKNOWN else FailureKind.DEVICE_REQUEST_INVALID,
                    null, "Internal bridge error (${t.javaClass.simpleName}).",
                )
            } finally {
                close()
                notify { observer.onConnectionClosed(peer) }
                record?.let { r -> notify { observer.onRequest(r) } }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(timerTasks) { timerTasks.forEach { it.cancel(false) } }
            upstream?.close()
            runCatching { device.close() }
        }

        private fun handle(): RequestRecord {
            device.soTimeout = limits.deviceHandshakeTimeoutMs
            try {
                device.startHandshake()
            } catch (e: IOException) {
                return record(
                    RequestOutcome.REJECTED_LOCALLY, FailureKind.DEVICE_TLS_FAILED, null,
                    "A device at $peer connected but the TLS handshake failed (${e.javaClass.simpleName}). " +
                        "The device must trust the bridge CA (llmhttpca) and have a valid clock.",
                )
            }
            val input = BufferedInputStream(device.inputStream, 8192)
            val output = device.outputStream

            device.soTimeout = limits.deviceHeaderTimeoutMs
            val headBytes = try {
                HttpHead.readHead(input, limits.maxHeaderBytes)
            } catch (e: HttpHeadException) {
                return reject(output, 431, "Request header fields too large", "Device request head exceeded ${limits.maxHeaderBytes} bytes.")
            } catch (e: SocketTimeoutException) {
                return reject(output, 408, "Request timeout", "Device connected but sent no request within ${limits.deviceHeaderTimeoutMs} ms.")
            } catch (e: IOException) {
                return record(RequestOutcome.REJECTED_LOCALLY, FailureKind.DEVICE_REQUEST_INVALID, null, "Device closed before sending a request.")
            } ?: return record(RequestOutcome.REJECTED_LOCALLY, FailureKind.DEVICE_REQUEST_INVALID, null, "Device closed before sending a request.")

            val request = try {
                HttpHead.parseRequest(headBytes, limits.maxHeaderLines)
            } catch (e: HttpHeadException) {
                return reject(output, 400, "Bad request", "Malformed device request: ${e.message}")
            }
            val route = BridgeRoute.forPath(request.target)
            if (request.method != "POST") return reject(output, 405, "Method not allowed", "Device used ${request.method}; only POST is forwarded.")
            if (route == null) return reject(output, 404, "Not found", "Device requested an unknown path; only /v1/chat/completions and /v1/terminal are forwarded. Check llmhttpurl ends with /v1.")
            this.route = route
            if (request.headers["host"].isNullOrEmpty()) return reject(output, 400, "Bad request", "Device request had no Host header.")
            val authorization = request.headers["authorization"] ?: ""
            if (!AUTHORIZATION.matches(authorization)) {
                return reject(output, 401, "Unauthorized", "Device sent no bearer token; set llmhttptoken on the device to the relay's device token.")
            }
            if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                return reject(output, 415, "Unsupported media type", "Device request was not application/json.")
            }
            if (request.headers.containsKey("transfer-encoding")) return reject(output, 400, "Bad request", "Chunked device requests are not accepted.")
            val lengthText = request.headers["content-length"] ?: return reject(output, 411, "Length required", "Device request had no Content-Length.")
            if (!CONTENT_LENGTH.matches(lengthText)) return reject(output, 400, "Bad request", "Device Content-Length is malformed.")
            val length = lengthText.toInt()
            val maxBody = if (route == BridgeRoute.CHAT) limits.maxChatBodyBytes else limits.maxTerminalBodyBytes
            if (length !in 1..maxBody) return reject(output, 413, "Content too large", "Device body of $length bytes exceeds the ${route.name.lowercase()} limit of $maxBody.")

            device.soTimeout = limits.deviceBodyTimeoutMs
            val body = ByteArray(length)
            try {
                readFully(input, body)
            } catch (e: SocketTimeoutException) {
                return reject(output, 408, "Request timeout", "Device did not finish sending its body within ${limits.deviceBodyTimeoutMs} ms.")
            } catch (e: IOException) {
                return record(RequestOutcome.REJECTED_LOCALLY, FailureKind.DEVICE_REQUEST_INVALID, null, "Device closed while sending its body.")
            }

            val slot = routeSlots.getValue(route)
            if (!slot.tryAcquire()) {
                return reject(output, 503, "Busy", "Another ${route.name.lowercase()} request is still in flight; the device must wait for it to finish.", FailureKind.BUSY)
            }
            try {
                return forward(route, authorization, body, input, output)
            } finally {
                slot.release()
            }
        }

        private fun forward(route: BridgeRoute, authorization: String, body: ByteArray, input: InputStream, output: OutputStream): RequestRecord {
            val total = if (route == BridgeRoute.CHAT) limits.chatTotalTimeoutMs else limits.terminalTotalTimeoutMs
            armDeadline(total)
            val now = System.currentTimeMillis()
            val up = try {
                relay.open(route, authorization, body)
            } catch (e: UpstreamException) {
                val detail = e.message ?: e.kind.name
                notify { observer.onRelay(RelayState.fromFailure(e.kind, detail, now)) }
                val outcome = when {
                    deadlineHit -> RequestOutcome.OUTCOME_UNKNOWN
                    e.kind == FailureKind.OUTCOME_UNKNOWN -> RequestOutcome.OUTCOME_UNKNOWN
                    else -> RequestOutcome.FAILED_BEFORE_SEND
                }
                val message = if (outcome == RequestOutcome.OUTCOME_UNKNOWN) {
                    "Bridge: relay did not answer; agent outcome unknown"
                } else {
                    "Bridge: home relay unavailable (${e.kind.name.lowercase().replace('_', ' ')})"
                }
                sendError(output, 502, message)
                return record(outcome, if (deadlineHit) FailureKind.TIMED_OUT else e.kind, 502, detail)
            }
            upstream = up
            if (closed.get()) {
                // Deadline or shutdown raced the connect; the request may have been admitted upstream.
                return record(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.TIMED_OUT, null, "Request closed while connecting to the relay; outcome unknown.")
            }
            val head = up.head
            notify {
                observer.onRelay(
                    when (head.status) {
                        401, 403 -> RelayState(
                            RelayReachability.AUTH_REJECTED,
                            "The relay rejected the device token (HTTP ${head.status}). Set llmhttptoken on the device to the relay's device-token.txt.",
                            now,
                        )
                        else -> RelayState(RelayReachability.REACHABLE, "Relay answered HTTP ${head.status}.", now)
                    },
                )
            }

            val streaming = route == BridgeRoute.CHAT && head.status == 200
            val expectedLength: Long
            if (streaming) {
                if (head.mediaType() != "text/event-stream") {
                    sendError(output, 502, "Bridge: relay returned an unexpected content type")
                    return record(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.RELAY_ERROR, 502, "Relay answered 200 without text/event-stream.")
                }
                expectedLength = -1
            } else {
                val cap = if (route == BridgeRoute.TERMINAL && head.status == 200) limits.maxTerminalReplyBytes else limits.maxErrorReplyBytes
                val length = head.contentLength
                if (head.isChunked || length == null || length > cap) {
                    sendError(output, 502, "Bridge: relay reply had unsupported framing")
                    return record(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.RELAY_ERROR, 502, "Relay reply lacked an acceptable Content-Length (status ${head.status}).")
                }
                expectedLength = length
            }

            val mediaType = head.mediaType()?.takeIf { it == "text/event-stream" || it == "application/json" } ?: "application/json"
            val responseHead = buildString {
                append("HTTP/1.1 ").append(head.status).append(' ').append(reasonFor(head.status)).append("\r\n")
                append("Content-Type: ").append(mediaType).append("\r\n")
                if (!streaming) append("Content-Length: ").append(expectedLength).append("\r\n")
                append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            try {
                output.write(responseHead)
                output.flush()
            } catch (e: IOException) {
                return record(RequestOutcome.CANCELLED_BY_DEVICE, FailureKind.CANCELLED_BY_DEVICE, head.status, "Device went away before the reply head could be sent.")
            }

            watchDevice(input)
            // The firmware ends a reply whose bytes stop for 60 s, but ignores SSE comment lines. While
            // the relay is silent (the agent is still thinking) the bridge therefore sends one comment
            // per interval and gives the relay up to upstreamIdleTimeoutMs in total. Heartbeats are
            // not relay bytes: they are neither counted nor inspected for the end-of-turn marker.
            // JSON (Terminal) replies carry no heartbeat; their 30 s deadline applies.
            val heartbeat = streaming && limits.upstreamHeartbeatIntervalMs in 1 until limits.upstreamIdleTimeoutMs
            up.setReadTimeout(if (heartbeat) limits.upstreamHeartbeatIntervalMs else limits.upstreamIdleTimeoutMs)
            val buffer = ByteArray(4096)
            val tail = StreamTail()
            var copied = 0L
            var silentMs = 0L
            var upstreamDone = false
            while (!upstreamDone) {
                val want = if (streaming) buffer.size else minOf(buffer.size.toLong(), expectedLength - copied).toInt()
                if (want <= 0) break
                val n = try {
                    up.input.read(buffer, 0, want)
                } catch (e: SocketTimeoutException) {
                    if (!heartbeat) return streamFailed(output, streaming, copied, head.status, e)
                    silentMs += limits.upstreamHeartbeatIntervalMs
                    if (silentMs >= limits.upstreamIdleTimeoutMs) return streamFailed(output, streaming, copied, head.status, e)
                    try {
                        output.write(HEARTBEAT)
                        output.flush()
                    } catch (e2: IOException) {
                        return record(RequestOutcome.CANCELLED_BY_DEVICE, FailureKind.CANCELLED_BY_DEVICE, head.status, "Device dropped the connection while the relay was silent, after $copied bytes; the agent may still be acting.")
                    }
                    continue
                } catch (e: IOException) {
                    return streamFailed(output, streaming, copied, head.status, e)
                }
                silentMs = 0
                if (n < 0) {
                    // relay.py always ends a finished chat with `data: [DONE]` and then closes.
                    // A close without it means the relay (or its gateway) broke mid-turn.
                    if (streaming && tail.endsWithDone()) { upstreamDone = true; continue }
                    return streamFailed(output, streaming && !tail.hasErrorEvent(), copied, head.status, IOException(if (streaming) "Relay closed before [DONE]" else "Relay closed before the full reply"))
                }
                copied += n
                forwarded = copied
                if (streaming) tail.append(buffer, n)
                if (streaming && copied > limits.maxReplyBytes) {
                    return streamFailed(output, streaming, copied, head.status, IOException("Relay reply exceeded ${limits.maxReplyBytes} bytes"))
                }
                try {
                    output.write(buffer, 0, n)
                    output.flush()
                } catch (e: IOException) {
                    return record(RequestOutcome.CANCELLED_BY_DEVICE, FailureKind.CANCELLED_BY_DEVICE, head.status, "Device dropped the connection mid-reply after $copied bytes; the agent may still be acting.")
                }
                if (!streaming && copied >= expectedLength) upstreamDone = true
            }
            return record(RequestOutcome.COMPLETED, null, head.status, "Forwarded ${route.name.lowercase()} reply (HTTP ${head.status}, $copied bytes).")
        }

        /** The relay side broke after the head was accepted: tell the device, label it honestly. */
        private fun streamFailed(output: OutputStream, streaming: Boolean, copied: Long, status: Int, cause: IOException): RequestRecord {
            val (outcome, kind, detail) = when {
                deviceGone -> Triple(RequestOutcome.CANCELLED_BY_DEVICE, FailureKind.CANCELLED_BY_DEVICE, "Device closed the connection after $copied bytes; the relay connection was closed. The agent may still be acting.")
                deadlineHit -> Triple(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.TIMED_OUT, "Bridge deadline reached after $copied bytes; agent outcome unknown.")
                cause is SocketTimeoutException -> Triple(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.OUTCOME_UNKNOWN, "Relay stream stalled for ${limits.upstreamIdleTimeoutMs / 1000} s after $copied bytes; agent outcome unknown.")
                else -> Triple(RequestOutcome.OUTCOME_UNKNOWN, FailureKind.OUTCOME_UNKNOWN, "Relay stream ended early after $copied bytes (${cause.message ?: cause.javaClass.simpleName}); agent outcome unknown.")
            }
            if (streaming && !deviceGone) {
                runCatching {
                    output.write("event: error\ndata: {\"error\":{\"message\":\"Bridge: relay stream ended; agent outcome unknown\"}}\n\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            }
            return record(outcome, kind, status, detail)
        }

        /** Blocks on the device socket so a device that goes away mid-reply releases the relay promptly. */
        private fun watchDevice(input: InputStream) {
            try {
                pool.execute {
                    try {
                        device.soTimeout = 0
                        input.read()
                    } catch (_: IOException) {
                    }
                    if (!closed.get()) {
                        deviceGone = true
                        upstream?.close()
                    }
                }
            } catch (_: RejectedExecutionException) {
            }
        }

        private fun armDeadline(totalMs: Long) {
            try {
                val soft = timers.schedule({
                    deadlineHit = true
                    upstream?.close() // unblock the copy loop so it can still tell the device
                }, totalMs, TimeUnit.MILLISECONDS)
                val hard = timers.schedule({ close() }, totalMs + 3_000, TimeUnit.MILLISECONDS)
                synchronized(timerTasks) { timerTasks += soft; timerTasks += hard }
            } catch (_: RejectedExecutionException) {
            }
        }

        private fun reject(output: OutputStream, status: Int, reason: String, detail: String, kind: FailureKind = FailureKind.DEVICE_REQUEST_INVALID): RequestRecord {
            sendError(output, status, reason)
            return record(RequestOutcome.REJECTED_LOCALLY, kind, status, detail)
        }

        private fun sendError(output: OutputStream, status: Int, message: String) {
            val body = "{\"error\":{\"message\":\"${message.replace('"', '\'')}\"}}".toByteArray(Charsets.US_ASCII)
            val head = "HTTP/1.1 $status ${reasonFor(status)}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            runCatching {
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }

        private fun record(outcome: RequestOutcome, failure: FailureKind?, status: Int?, detail: String) = RequestRecord(
            route = route,
            deviceAddress = peer,
            startedAtMs = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            outcome = outcome,
            failure = failure,
            httpStatus = status,
            detail = detail,
            bytesForwarded = forwarded,
        )
    }

    /** The last few bytes forwarded, enough to recognise the relay's end-of-turn marker. */
    private class StreamTail {
        private val bytes = ByteArray(64)
        private var length = 0
        fun append(src: ByteArray, n: Int) {
            if (n >= bytes.size) { System.arraycopy(src, n - bytes.size, bytes, 0, bytes.size); length = bytes.size; return }
            val keep = minOf(length, bytes.size - n)
            System.arraycopy(bytes, length - keep, bytes, 0, keep)
            System.arraycopy(src, 0, bytes, keep, n)
            length = keep + n
        }
        private fun text() = String(bytes, 0, length, Charsets.ISO_8859_1)
        fun endsWithDone(): Boolean = text().trimEnd('\r', '\n', ' ').endsWith("data: [DONE]")
        fun hasErrorEvent(): Boolean = text().contains("event: error")
    }

    private companion object {
        /** An SSE comment: ignored by every SSE parser, including the firmware's, but it is bytes. */
        val HEARTBEAT = ": keep-alive\n\n".toByteArray(Charsets.US_ASCII)
        val AUTHORIZATION = Regex("Bearer [\\x21-\\x7e]{1,1024}")
        val CONTENT_LENGTH = Regex("[0-9]{1,6}")

        fun reasonFor(status: Int): String = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 411 -> "Length Required"; 413 -> "Content Too Large"
            415 -> "Unsupported Media Type"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Status"
        }

        fun readFully(input: InputStream, target: ByteArray) {
            var off = 0
            while (off < target.size) {
                val n = input.read(target, off, target.size - off)
                if (n < 0) throw IOException("EOF before body complete")
                off += n
            }
        }

        fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}
