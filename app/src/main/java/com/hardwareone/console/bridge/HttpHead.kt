package com.hardwareone.console.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Parsed request head. Header names are lower-cased; values are trimmed of spaces/tabs. */
class HttpRequestHead(val method: String, val target: String, val headers: Map<String, String>)

/** Parsed response head. */
class HttpResponseHead(val status: Int, val reason: String, val headers: Map<String, String>) {
    val contentLength: Long?
        get() = headers["content-length"]?.let { if (CONTENT_LENGTH.matches(it)) it.toLong() else null }
    val isChunked: Boolean get() = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
    fun mediaType(): String? = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()

    private companion object {
        val CONTENT_LENGTH = Regex("[0-9]{1,15}")
    }
}

class HttpHeadException(message: String, val tooLarge: Boolean = false) : IOException(message)

/**
 * A deliberately small HTTP/1.x head parser: enough for the two fixed POST routes and the relay's
 * replies, and nothing that could be talked into buffering more than [BridgeLimits] allows.
 */
object HttpHead {
    private val TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
    private val REQUEST_LINE = Regex("([A-Z]{1,16}) ([^ ]{1,2048}) HTTP/1\\.[01]")
    private val STATUS_LINE = Regex("HTTP/1\\.[01] ([0-9]{3})(?: ([ -~]*))?")

    /**
     * Reads one head, including its terminating blank line, one byte at a time so nothing past
     * it is consumed. Returns null if the peer closes first. Throws when [maxBytes] is exceeded.
     */
    fun readHead(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(512)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
            if (out.size() >= maxBytes) throw HttpHeadException("Head exceeds $maxBytes bytes", tooLarge = true)
        }
    }

    fun parseRequest(head: ByteArray, maxLines: Int): HttpRequestHead {
        val lines = lines(head, maxLines)
        val m = REQUEST_LINE.matchEntire(lines[0]) ?: throw HttpHeadException("Malformed request line")
        return HttpRequestHead(m.groupValues[1], m.groupValues[2], headers(lines))
    }

    fun parseResponse(head: ByteArray, maxLines: Int): HttpResponseHead {
        val lines = lines(head, maxLines)
        val m = STATUS_LINE.matchEntire(lines[0]) ?: throw HttpHeadException("Malformed status line")
        return HttpResponseHead(m.groupValues[1].toInt(), m.groupValues[2], headers(lines))
    }

    private fun lines(head: ByteArray, maxLines: Int): List<String> {
        for (b in head) {
            val c = b.toInt() and 0xff
            if (c >= 0x80 || (c < 0x20 && c != '\r'.code && c != '\n'.code && c != '\t'.code) || c == 0x7f) {
                throw HttpHeadException("Non-ASCII or control byte in head")
            }
        }
        val text = String(head, Charsets.US_ASCII)
        if (!text.endsWith("\r\n\r\n")) throw HttpHeadException("Head not terminated")
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.isEmpty() || lines[0].isEmpty()) throw HttpHeadException("Empty start line")
        if (lines.size > maxLines) throw HttpHeadException("Too many header lines")
        return lines
    }

    private fun headers(lines: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpHeadException("Malformed header line")
            val name = line.substring(0, colon)
            if (!TOKEN.matches(name)) throw HttpHeadException("Malformed header name")
            val key = name.lowercase()
            if (out.containsKey(key)) throw HttpHeadException("Duplicate header: $key")
            out[key] = line.substring(colon + 1).trim(' ', '\t')
        }
        return out
    }
}
