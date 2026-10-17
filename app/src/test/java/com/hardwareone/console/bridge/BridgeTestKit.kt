package com.hardwareone.console.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** Short timeouts so failure paths finish in well under a second each. */
val TEST_LIMITS = BridgeLimits(
    deviceHandshakeTimeoutMs = 3_000,
    deviceHeaderTimeoutMs = 1_500,
    deviceBodyTimeoutMs = 1_500,
    upstreamConnectTimeoutMs = 2_000,
    upstreamHandshakeTimeoutMs = 3_000,
    upstreamHeaderTimeoutMs = 3_000,
    upstreamIdleTimeoutMs = 3_000,
    probeTimeoutMs = 3_000,
    chatTotalTimeoutMs = 20_000,
    terminalTotalTimeoutMs = 10_000,
)

/** Plain TCP, as the Android connector would do after binding to the VPN network. */
val DIRECT_CONNECTOR = UpstreamConnector { host, port, timeoutMs ->
    // No `apply` here: inside a Socket receiver scope, `port` would resolve to Socket.port (0).
    val socket = Socket()
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    socket
}

class FakeRelayRequest(val head: HttpRequestHead, val body: ByteArray)

/**
 * A stand-in for relay.py: TLS server on loopback with a scripted response per connection.
 * It records what it received and whether the bridge closed the connection on it.
 */
class FakeRelay(
    identity: ServerIdentity,
    /** Keep the connection open after replying and watch for the bridge closing it (cancellation tests). */
    private val holdOpenAfterReply: Boolean = false,
    private val responder: (FakeRelayRequest, OutputStream, FakeRelay) -> Unit,
) : Closeable {
    private val server = BridgeTls.serverContext(identity).serverSocketFactory.createServerSocket() as SSLServerSocket
    val requests = ConcurrentLinkedQueue<FakeRelayRequest>()
    val connections = AtomicInteger()
    val tlsFailures = AtomicInteger()
    val peerClosed = CountDownLatch(1)
    private val thread: Thread

    init {
        BridgeTls.configure(server)
        server.bind(InetSocketAddress(LOOPBACK, 0))
        thread = Thread({ loop() }, "fake-relay").apply { isDaemon = true; start() }
    }

    val port: Int get() = server.localPort

    private fun loop() {
        while (!server.isClosed) {
            val socket = try { server.accept() as SSLSocket } catch (e: Exception) { return }
            connections.incrementAndGet()
            Thread({ serve(socket) }, "fake-relay-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: SSLSocket) {
        try {
            socket.soTimeout = 5_000
            try { socket.startHandshake() } catch (e: Exception) { tlsFailures.incrementAndGet(); return }
            val input = BufferedInputStream(socket.inputStream)
            val head = HttpHead.readHead(input, 8192) ?: return
            val parsed = HttpHead.parseRequest(head, 64)
            val body = ByteArray(parsed.headers["content-length"]?.toInt() ?: 0)
            var off = 0
            while (off < body.size) { val n = input.read(body, off, body.size - off); if (n < 0) break; off += n }
            val request = FakeRelayRequest(parsed, body)
            requests += request
            responder(request, socket.outputStream, this)
            if (holdOpenAfterReply) {
                // Observe the bridge closing its side (used by cancellation tests).
                socket.soTimeout = 10_000
                runCatching { if (input.read() < 0) peerClosed.countDown() }
            }
            // Otherwise behave like relay.py: the handler returns and the connection closes.
        } catch (e: Exception) {
            peerClosed.countDown()
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun close() { runCatching { server.close() } }
}

/** Reads a response from the bridge the way the ESP32 does: head, then bytes until EOF/length. */
class DeviceResponse(val head: HttpResponseHead, private val input: InputStream, val socket: SSLSocket) : Closeable {
    private val collected = ByteArrayOutputStream()

    /** Reads until [predicate] matches the body so far (or EOF). Returns the body so far. */
    fun readUntil(timeoutMs: Long = 5_000, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        while (!predicate(bodyText()) && System.currentTimeMillis() < deadline) {
            socket.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { break }
            if (n < 0) break
            collected.write(buf, 0, n)
        }
        return bodyText()
    }

    fun readToEnd(timeoutMs: Long = 5_000): String = readUntil(timeoutMs) { false }

    fun readExactly(length: Int, timeoutMs: Long = 5_000): String = readUntil(timeoutMs) { collected.size() >= length }

    fun bodyText(): String = collected.toString("UTF-8")
    override fun close() { runCatching { socket.close() } }
}

/** A stand-in for the ESP32: trusts only the bridge CA, speaks the firmware's request shape. */
class FakeDevice(bridgeCaPem: String, private val bridgePort: Int) {
    private val context = BridgeTls.clientContext(Pem.decodeCertificates(bridgeCaPem))

    fun connect(): SSLSocket {
        val plain = Socket()
        plain.connect(InetSocketAddress(LOOPBACK, bridgePort), 3_000)
        plain.soTimeout = 5_000
        val ssl = context.socketFactory.createSocket(plain, "127.0.0.1", bridgePort, true) as SSLSocket
        BridgeTls.configure(ssl)
        ssl.startHandshake()
        return ssl
    }

    fun request(
        path: String = BridgeRoute.CHAT.path,
        body: String = """{"model":"openclaw/main","user":"hw1:00000000000000000000000000000001","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
        token: String? = "device-token-0123456789abcdef0123456789",
        method: String = "POST",
        contentType: String = "application/json",
        contentLength: Int? = null,
        extraHeaders: String = "",
        accept: String = "text/event-stream",
    ): DeviceResponse {
        val ssl = connect()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("User-Agent: ESP32 HTTP Client/1.0\r\nHost: 127.0.0.1:$bridgePort\r\n")
            if (token != null) append("Authorization: Bearer $token\r\n")
            append("Content-Type: $contentType\r\nAccept: $accept\r\n")
            append("Content-Length: ${contentLength ?: bodyBytes.size}\r\n")
            append(extraHeaders)
            append("\r\n")
        }
        ssl.outputStream.write(head.toByteArray(Charsets.US_ASCII))
        ssl.outputStream.write(bodyBytes)
        ssl.outputStream.flush()
        return readResponse(ssl)
    }

    fun readResponse(ssl: SSLSocket): DeviceResponse {
        val input = BufferedInputStream(ssl.inputStream)
        val headBytes = HttpHead.readHead(input, 8192) ?: throw java.io.EOFException("bridge closed before a response head")
        return DeviceResponse(HttpHead.parseResponse(headBytes, 64), input, ssl)
    }
}

class RecordingObserver : BridgeObserver {
    val listener = CopyOnWriteArrayList<ListenerState>()
    val relay = CopyOnWriteArrayList<RelayState>()
    val records = CopyOnWriteArrayList<RequestRecord>()
    val opened = AtomicInteger()
    val closed = AtomicInteger()
    private val recordLatch = ConcurrentLinkedQueue<CountDownLatch>()

    override fun onListener(state: ListenerState) { listener += state }
    override fun onRelay(state: RelayState) { relay += state }
    override fun onRequest(record: RequestRecord) { records += record; recordLatch.forEach { it.countDown() } }
    override fun onConnectionOpened(deviceAddress: String) { opened.incrementAndGet() }
    override fun onConnectionClosed(deviceAddress: String) { closed.incrementAndGet() }

    fun awaitRecord(index: Int = 0, timeoutMs: Long = 5_000): RequestRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (records.size <= index && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(records.size > index) { "no request record #$index within $timeoutMs ms (have ${records.size})" }
        return records[index]
    }
}

/** Everything one end-to-end test needs, wired on loopback with freshly generated identities. */
class BridgeHarness(
    limits: BridgeLimits = TEST_LIMITS,
    relayCa: BridgeCa = BridgeCa.generate(),
    relayIdentity: ServerIdentity = relayCa.issueServerIdentity(LOOPBACK),
    trustedCaPem: String = relayCa.certificatePem,
    connector: UpstreamConnector = DIRECT_CONNECTOR,
    relayPortOverride: Int? = null,
    holdRelayOpen: Boolean = false,
    responder: (FakeRelayRequest, OutputStream, FakeRelay) -> Unit = ::sseHello,
) : Closeable {
    val relay = FakeRelay(relayIdentity, holdRelayOpen, responder)
    val bridgeCa: BridgeCa = BridgeCa.generate()
    val config = BridgeConfig(relayHost = "127.0.0.1", relayPort = relayPortOverride ?: relay.port, relayCaPem = trustedCaPem, listenPort = 0)
    val observer = RecordingObserver()
    val client = RelayClient(config, limits, connector)
    val server = BridgeServer(LOOPBACK, 0, "lo0", bridgeCa.issueServerIdentity(LOOPBACK), client, limits, observer).also { it.start() }
    val device = FakeDevice(bridgeCa.certificatePem, server.boundPort)

    override fun close() { server.close(); relay.close() }

    companion object {
        const val SSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        fun sseHello(request: FakeRelayRequest, out: OutputStream, relay: FakeRelay) {
            out.write(SSE_HEAD.toByteArray()); out.flush()
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}\n\n".toByteArray()); out.flush()
            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
        }
        fun json(status: Int, reason: String, body: String): ByteArray =
            "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body".toByteArray()
        fun freePort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        fun await(latch: CountDownLatch, what: String) = check(latch.await(5, TimeUnit.SECONDS)) { "timed out waiting for $what" }
    }
}
