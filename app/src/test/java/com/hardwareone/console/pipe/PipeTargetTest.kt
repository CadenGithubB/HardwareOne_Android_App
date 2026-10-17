package com.hardwareone.console.pipe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Destination canonicalization for the relay pipe. Every expected value comes from how the P4
 * builds OPEN from its configured base URL (System_LLMHttpCore.h `endpoint()`, http_parser, then
 * BLE_PhonePipe.cpp `phonePipeOpen`: lwIP IPv4 → lwIP IPv6 → A–Z-lowercased name, encoded by
 * BLE_PhonePipeCore.h `Session::open`), not from the Android parser. An approved URL must give
 * exactly the OPEN target the P4 sends; a form the P4 could read differently, or that the handoff
 * forbids, must be refused rather than approved under another meaning.
 */
class PipeTargetTest {

    // ---- Accepted base URLs: the exact OPEN target the P4 derives ------------------------------

    @Test
    fun dnsNameWithoutPortUsesHttpsDefault443() {
        assertTarget("https://relay.example/v1", DNS, ascii("relay.example"), 443)
    }

    /** phonePipeOpen lowercases A–Z before Session::open, so case never changes the OPEN bytes. */
    @Test
    fun mixedCaseNameIsLowercasedAndExplicitPortKept() {
        assertTarget("https://Relay.Example:8443/v1/", DNS, ascii("relay.example"), 8443)
        assertTarget("https://RELAY.EXAMPLE/v1", DNS, ascii("relay.example"), 443)
        assertTarget("https://XN--BCHER-KVA.example/v1", DNS, ascii("xn--bcher-kva.example"), 443)
    }

    @Test
    fun dottedQuadBecomesFourNetworkOrderBytes() {
        assertTarget("https://192.0.2.10:18790/v1", IPV4, bytes(192, 0, 2, 10), 18790)
        assertTarget("https://203.0.113.254/v1", IPV4, bytes(203, 0, 113, 254), 443)
    }

    @Test
    fun bracketedIpv6BecomesSixteenBytes() {
        assertTarget("https://[2001:db8::1]:443/v1", IPV6, docV6, 443)
        assertEquals(16, PipeTarget.parseUrl("https://[2001:db8::1]:443/v1").host.size)
    }

    /** lwIP ip6addr_aton reads upper-case hex and uncompressed groups to the same 16 bytes. */
    @Test
    fun uncompressedUppercaseIpv6EqualsCompressedForm() {
        assertTarget("https://[2001:DB8:0:0:0:0:0:1]/v1", IPV6, docV6, 443)
        val compressed = PipeTarget.parseUrl("https://[2001:db8::1]/v1")
        val long = PipeTarget.parseUrl("https://[2001:DB8:0:0:0:0:0:1]/v1")
        assertEquals(compressed, long)
        assertEquals(compressed.hashCode(), long.hashCode())
    }

    /** http_parser accumulates port digits in decimal, so leading zeros are not octal. */
    @Test
    fun portWithLeadingZerosIsReadAsDecimal() {
        assertTarget("https://relay.example:00443/v1", DNS, ascii("relay.example"), 443)
        assertTarget("https://relay.example:08443/v1", DNS, ascii("relay.example"), 8443)
    }

    /** endpoint() permits reverse-proxy prefixes; OPEN never carries the path. */
    @Test
    fun pathPrefixBeforeV1IsAllowedAndNotPartOfTheTarget() {
        val plain = PipeTarget.parseUrl("https://relay.example/v1")
        assertTarget("https://relay.example/api/v1", DNS, ascii("relay.example"), 443)
        assertEquals(plain, PipeTarget.parseUrl("https://relay.example/api/v1"))
        assertEquals(plain, PipeTarget.parseUrl("https://relay.example/gateway/llm/v1/"))
    }

    @Test
    fun explicitDefaultPortEqualsImplicitPort() {
        assertEquals(PipeTarget.parseUrl("https://relay.example/v1"), PipeTarget.parseUrl("https://relay.example:443/v1"))
        assertEquals(PipeTarget.parseUrl("https://[2001:db8::1]/v1"), PipeTarget.parseUrl("https://[2001:db8::1]:443/v1"))
        assertEquals(PipeTarget.parseUrl("https://192.0.2.10/v1"), PipeTarget.parseUrl("https://192.0.2.10:443/v1"))
    }

    @Test
    fun portBoundariesOneAnd65535AreAccepted() {
        assertTarget("https://relay.example:1/v1", DNS, ascii("relay.example"), 1)
        assertTarget("https://relay.example:65535/v1", DNS, ascii("relay.example"), 65535)
        assertTarget("https://[2001:db8::1]:65535/v1", IPV6, docV6, 65535)
    }

    /** endpoint(): `strlen(base) > 384` fails, so exactly 384 characters is still a valid base URL. */
    @Test
    fun urlLimitIs384CharactersInclusive() {
        assertTarget(urlOfLength(384), DNS, ascii("relay.example"), 443)
        assertRefused(urlOfLength(385))
    }

    /** Handoff: DNS names are 1–253 bytes; RFC 1035 labels are 1–63. */
    @Test
    fun dnsLabelAndNameLimitsAreInclusive() {
        val label63 = "a".repeat(63) + ".example"
        assertTarget("https://$label63/v1", DNS, ascii(label63), 443)
        val name253 = nameOfLength(253)
        assertTarget("https://$name253/v1", DNS, ascii(name253), 443)
        assertRefused("https://" + "a".repeat(64) + ".example/v1")
        assertRefused("https://${nameOfLength(254)}/v1")
    }

    // ---- Round trip through the bytes the firmware actually sends ---------------------------------

    /**
     * For each accepted URL: the parsed target equals the target decoded from the OPEN body the
     * P4 would encode (`limit u32=1024 | port u16 | type u8 | len u8 | host`), at any offset.
     */
    @Test
    fun everyAcceptedUrlRoundTripsThroughTheFirmwareOpenBytes() {
        for (e in accepted) {
            val parsed = parse(e.url)
            assertEquals(e.url, e.type, parsed.type)
            assertArrayEquals(e.url, e.host, parsed.host)
            assertEquals(e.url, e.port, parsed.port)

            val direct = PipeTarget.fromWire(e.type, e.host, 0, e.host.size, e.port)
            assertNotNull(e.url, direct)
            assertEquals(e.url, parsed, direct)
            assertEquals(e.url, parsed.hashCode(), direct!!.hashCode())

            val body = firmwareOpenBody(e.type, e.host, e.port)
            assertEquals(e.url, parsed, openTarget(body, 0))
            // Inside a frame (12-byte header) and in a larger reassembly buffer with slack.
            val framed = ByteArray(12) + body + ByteArray(7)
            assertEquals(e.url, parsed, openTarget(framed, 12))

            val again = PipeTarget.fromWire(parsed.type, parsed.host, 0, parsed.host.size, parsed.port)
            assertEquals(e.url, parsed, again)
        }
    }

    /** Golden bytes from the firmware core encoder and harness (report-spec §6A–C). */
    @Test
    fun firmwareGoldenOpenBodiesDecodeToTheEnrolledTargets() {
        // §6A CORE-ENC: OPEN id 2, relay.example:8443, including its 12-byte frame header.
        val dnsFrame = hex("01 10 0200 02000000 1500 03 00  00040000 fb20 03 0d 72656c61792e6578616d706c65")
        assertEquals(PipeTarget.parseUrl("https://relay.example:8443/v1"), openTarget(dnsFrame, 12))
        // §6B: the IPv6 OPEN for 2001:db8::1 port 443, reassembled from its three MTU-23 fragments.
        val v6 = hex("00040000bb010210" + "20010db800000000" + "0000000000000001")
        assertEquals(PipeTarget.parseUrl("https://[2001:db8::1]/v1"), openTarget(v6, 0))
        // §6C, harness opened(): 192.0.2.1 port 443, reassembled from two fragments.
        val v4 = hex("00040000bb010104" + "c0000201")
        assertEquals(PipeTarget.parseUrl("https://192.0.2.1/v1"), openTarget(v4, 0))

        // The local encoder the round-trip test relies on reproduces those firmware bytes.
        assertArrayEquals(dnsFrame.copyOfRange(12, dnsFrame.size), firmwareOpenBody(DNS, ascii("relay.example"), 8443))
        assertArrayEquals(v6, firmwareOpenBody(IPV6, docV6, 443))
        assertArrayEquals(v4, firmwareOpenBody(IPV4, bytes(192, 0, 2, 1), 443))
    }

    // ---- Privacy, equality and ownership ---------------------------------------------------------

    /** Handoff: keep allowlist values out of logs; toString is what a stray log line prints. */
    @Test
    fun toStringNeverRevealsTheDestination() {
        for (e in accepted) {
            val t = parse(e.url)
            val s = t.toString()
            val secrets = listOf(t.hostString(), "relay", "example", "edge", "xn--", "192.0", "198.51", "203.0", "2001", "db8", "7334")
            for (secret in secrets) assertFalse("${e.url} -> $s", s.contains(secret, ignoreCase = true))
        }
    }

    @Test
    fun equalityIsBytewiseAndTypeAware() {
        val a = PipeTarget.parseUrl("https://relay.example:8443/v1")
        val b = PipeTarget.parseUrl("https://RELAY.example:08443/api/v1/")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PipeTarget.parseUrl("https://relay.example:8444/v1"))
        assertNotEquals(a, PipeTarget.parseUrl("https://relay2.example:8443/v1"))
        val nothing: Any? = null
        val text: Any = "relay.example:8443"
        assertFalse(a.equals(nothing))
        assertFalse(a.equals(text))

        // The same four bytes as a name and as an IPv4 address are different OPEN targets.
        val asName = PipeTarget.fromWire(DNS, ascii("abcd"), 0, 4, 443)
        val asIpv4 = PipeTarget.fromWire(IPV4, ascii("abcd"), 0, 4, 443)
        assertNotNull(asName)
        assertNotNull(asIpv4)
        assertNotEquals(asName, asIpv4)
    }

    /** A reassembly buffer is reused for the next message; an approved target must not change with it. */
    @Test
    fun targetsNeverAliasCallerOrWireBuffers() {
        val expected = PipeTarget.parseUrl("https://relay.example:8443/v1")
        val wire = firmwareOpenBody(DNS, ascii("relay.example"), 8443)
        val decoded = openTarget(wire, 0)
        assertNotNull(decoded)
        wire.fill('z'.code.toByte())
        assertEquals(expected, decoded)
        decoded!!.host.fill(0.toByte())
        assertEquals(expected, decoded)
    }

    /** The socket layer must be handed exactly the approved destination, never a reinterpretation. */
    @Test
    fun hostStringIsTheExactApprovedDestination() {
        assertEquals("relay.example", PipeTarget.parseUrl("https://Relay.Example/v1").hostString())
        assertEquals("192.0.2.10", PipeTarget.parseUrl("https://192.0.2.10/v1").hostString())
        val v6 = PipeTarget.parseUrl("https://[2001:db8::1]/v1").hostString()
        assertTrue(v6, v6 == "2001:db8::1" || v6 == "2001:db8:0:0:0:0:0:1")
    }

    // ---- Refused base URLs ----------------------------------------------------------------------

    /** endpoint() compares `https://` case-sensitively, and plain HTTP never uses the phone path. */
    @Test
    fun nonHttpsOrNonLowercaseSchemeIsRefused() {
        assertRefused(
            "http://relay.example/v1",
            "HTTPS://relay.example/v1",
            "Https://relay.example/v1",
            "httpS://relay.example/v1",
            "https:/relay.example/v1",
            "https:relay.example/v1",
            "ftp://relay.example/v1",
            "wss://relay.example/v1",
            "//relay.example/v1",
            "relay.example/v1",
        )
    }

    @Test
    fun baseUrlMustEndInV1() {
        assertRefused(
            "https://relay.example",
            "https://relay.example/",
            "https://relay.example/v2",
            "https://relay.example/V1",
            "https://relay.example/v10",
            "https://relay.example/xv1",
            "https://relay.example/v1//",
            "https://relay.example/v1/chat/completions",
        )
    }

    @Test
    fun userinfoQueryFragmentAndBackslashAreRefused() {
        assertRefused(
            "https://u1x9@relay.example/v1",
            "https://relay.example/a@b/v1",
            "https://relay.example/x?y/v1",
            "https://relay.example/?/v1",
            "https://relay.example/x#y/v1",
            "https://relay.example\\x/v1",
            "https://relay.example/a\\b/v1",
        )
    }

    @Test
    fun dotSegmentsAreRefused() {
        assertRefused(
            "https://relay.example/a/../v1",
            "https://relay.example/../v1",
            "https://relay.example/./v1",
            "https://relay.example/a/./b/v1",
        )
    }

    /** endpoint() admits only bytes 0x21–0x7e, so U-labels and whitespace can never be configured. */
    @Test
    fun nonPrintableOrNonAsciiCharactersAreRefused() {
        assertRefused(
            "https://relay .example/v1",
            "https://relay.example/ /v1",
            "https://relay.example/\t/v1",
            "https://relay.example/\n/v1",
            "https://relay.example\u007f/v1",
            "https://relay.example/ /v1",
            "https://bücher.example/v1",
            "https://relay.exämple/v1",
            "https://Kelvin.example/v1",
            "https://relay.example:４４３/v1",
        )
    }

    @Test
    fun emptyHostIsRefused() {
        assertRefused("https:///v1", "https://:443/v1", "https:///api/v1")
    }

    /** http_parser rejects an empty port and values above 65535; the transport rejects port 0. */
    @Test
    fun invalidPortsAreRefused() {
        assertRefused(
            "https://relay.example:/v1",
            "https://relay.example:0/v1",
            "https://relay.example:00000/v1",
            "https://relay.example:65536/v1",
            "https://relay.example:99999/v1",
            "https://relay.example:100000/v1",
            "https://relay.example:https/v1",
            "https://relay.example:44a3/v1",
            "https://relay.example:+443/v1",
            "https://relay.example:-1/v1",
            "https://relay.example:443:443/v1",
            "https://[2001:db8::1]:/v1",
            "https://[2001:db8::1]:0/v1",
            "https://192.0.2.10:65536/v1",
        )
    }

    /**
     * lwIP `inet_pton(AF_INET)` (ip4addr_aton) reads 1–4 parts in C literal syntax: every form here
     * is 192.0.2.10 on the P4 (`192.0.522` = a.b.c with c as 16 bits, `192.522` = a.b with b as 24
     * bits, 3221225994 = 0xC000020A, 0300 = octal 192). Approving the text would bind a meaning the
     * user never wrote, so each is refused.
     */
    @Test
    fun lenientIpv4FormsAreRefused() {
        assertRefused(
            "https://192.0.522/v1",
            "https://192.522/v1",
            "https://3221225994/v1",
            "https://0300.0.2.10/v1",
            "https://0xc0.0.2.10/v1",
            "https://0XC0.0.2.10/v1",
            "https://0xc000020a/v1",
            "https://192.0.2.0xa/v1",
        )
    }

    /**
     * lwIP ip4addr_aton (esp-idf lwip/src/core/ipv4/ip4_addr.c) reads a part spelled `0x` with no
     * hex digits as 0, so `192.0.2.0x` reaches the P4 as IPv4 192.0.2.0 (type 1, 4 bytes) and `0x`
     * as 0.0.0.0, while a name parser sees a valid DNS name. Approving either would bind a target
     * the P4 never sends under that meaning.
     */
    @Test
    fun bareHexPrefixPartIsIpv4ZeroOnTheP4AndRefused() {
        assertRefused(
            "https://192.0.2.0x/v1",
            "https://198.51.100.0X/v1",
            "https://192.0x/v1",
            "https://0x/v1",
            "https://0X.0x/v1",
        )
    }

    /** `192.0.2.010` is 192.0.2.8 to lwIP (octal); `0192…`, `…256` and `…1000` fall through to a name. */
    @Test
    fun ipv4WithLeadingZerosOrOutOfRangeOctetIsRefused() {
        assertRefused(
            "https://192.0.2.010/v1",
            "https://192.0.02.10/v1",
            "https://192.000.2.10/v1",
            "https://0192.0.2.10/v1",
            "https://192.0.2.256/v1",
            "https://192.0.2.1000/v1",
        )
    }

    /** Handoff: IPv6 OPEN carries no zone ID; lwIP silently drops it, so the zone would be lost. */
    @Test
    fun ipv6ZoneIsRefused() {
        assertRefused(
            "https://[2001:db8::1%eth0]/v1",
            "https://[2001:db8::1%25eth0]/v1",
            "https://[2001:db8::1%1]:8443/v1",
        )
    }

    /** `[192.0.2.10]` would even become a type-1 OPEN on the P4 (http_parser strips the brackets). */
    @Test
    fun ipv6WithEmbeddedDottedIpv4IsRefused() {
        assertRefused(
            "https://[2001:db8::192.0.2.10]/v1",
            "https://[::ffff:192.0.2.10]/v1",
            "https://[::192.0.2.10]/v1",
            "https://[64:ff9b::192.0.2.10]/v1",
            "https://[192.0.2.10]/v1",
        )
    }

    /** lwIP accepts over-long groups (`12345`) and folds them into a neighbouring group's bits. */
    @Test
    fun malformedIpv6IsRefused() {
        assertRefused(
            "https://[2001:db8::1::2]/v1",
            "https://[2001:db8:::1]/v1",
            "https://[2001:db8:0:0:0:0:0:0:1]/v1",
            "https://[2001:db8:0:0:0:0:0:1::]/v1",
            "https://[2001:db8:0:0:0:0:0]/v1",
            "https://[2001:db8::g]/v1",
            "https://[2001:db8::12345]/v1",
            "https://[:2001:db8::1]/v1",
            "https://[2001:db8::1:]/v1",
            "https://[]/v1",
            "https://[2001:db8::1/v1",
            "https://2001:db8::1]/v1",
            "https://[2001:db8::1]x/v1",
            "https://[2001:db8::1]8443/v1",
            "https://[[2001:db8::1]]/v1",
            "https://2001:db8::1/v1",
        )
    }

    /** Handoff: type 3 is lowercase A-labels with no trailing dot; the P4 itself only checks the charset. */
    @Test
    fun nonCanonicalDnsNamesAreRefused() {
        assertRefused(
            "https://relay.example./v1",
            "https://relay..example/v1",
            "https://.relay.example/v1",
            "https://-relay.example/v1",
            "https://relay-.example/v1",
            "https://relay.-example/v1",
            "https://relay.example-/v1",
            "https://relay_1.example/v1",
            "https://relay*.example/v1",
            "https://relay!.example/v1",
        )
    }

    /** All-numeric last labels are not host names (RFC 1123 §2.1); the P4 would send them as type 3. */
    @Test
    fun nameEndingInNumericLabelIsRefused() {
        assertRefused(
            "https://relay.123/v1",
            "https://relay.example.10/v1",
            "https://192.0.2.10.5/v1",
            "https://198.51.100.7.8/v1",
        )
    }

    @Test
    fun unspecifiedLoopbackAndMulticastAddressesAreRefused() {
        assertRefused(
            "https://0.0.0.0/v1",
            "https://127.0.0.1/v1",
            "https://224.0.0.1/v1",
            "https://[::]/v1",
            "https://[::1]/v1",
            "https://[ff02::1]/v1",
        )
    }

    /** Refusal reasons reach the UI and possibly logs; they must not repeat the private destination. */
    @Test
    fun refusalReasonsNeverEchoTheDestination() {
        val cases = listOf(
            "https://relay.example./v1" to listOf("relay.example"),
            "https://u1x9@relay.example/v1" to listOf("u1x9", "relay.example"),
            "https://relay.example/x?q=s3cr3t/v1" to listOf("s3cr3t", "relay.example"),
            "https://relay.example:65536/v1" to listOf("relay.example", "65536"),
            "https://192.0.2.256/v1" to listOf("192.0.2"),
            "https://0xc0.0.2.10/v1" to listOf("0xc0"),
            "https://[2001:db8::1%eth0]/v1" to listOf("2001:db8", "eth0"),
            "https://bücher.example/v1" to listOf("bücher"),
            urlOfLength(385) to listOf("relay.example", "ppp"),
        )
        for ((url, secrets) in cases) {
            val reason = try {
                PipeTarget.parseUrl(url)
                null
            } catch (e: PipeTarget.Invalid) {
                listOfNotNull(e.message, e.reason, e.toString()).joinToString("\n")
            }
            assertNotNull("accepted: $url", reason)
            for (secret in secrets) assertFalse("reason for $url repeats $secret", reason!!.contains(secret))
        }
    }

    /**
     * Forms the sources leave open: refusing is fine, but if accepted they must give exactly the
     * bytes the P4 sends (http_parser reads `000443` as 443; lwIP reads the full-width IPv6 and the
     * hex IPv4-mapped form as 16 bytes of type 2, never type 1; `0xc0.example` and `123.example`
     * fail both inet_pton calls and become names).
     */
    @Test
    fun formsTheSourcesLeaveOpenAreRefusedOrMatchTheFirmwareBytes() {
        val open = listOf(
            Expected("https://relay.example:000443/v1", DNS, ascii("relay.example"), 443),
            Expected("https://[2001:0db8:0000:0000:0000:0000:0000:0001]/v1", IPV6, docV6, 443),
            Expected("https://[::ffff:c000:20a]/v1", IPV6, hex("00000000000000000000ffffc000020a"), 443),
            Expected("https://0xc0.example/v1", DNS, ascii("0xc0.example"), 443),
            Expected("https://123.example/v1", DNS, ascii("123.example"), 443),
        )
        for (e in open) {
            val t = try {
                PipeTarget.parseUrl(e.url)
            } catch (_: PipeTarget.Invalid) {
                continue
            }
            assertEquals(e.url, e.type, t.type)
            assertArrayEquals(e.url, e.host, t.host)
            assertEquals(e.url, e.port, t.port)
        }
    }

    // ---- parseAuthority ---------------------------------------------------------------------------

    @Test
    fun parseAuthorityAppliesTheSameCanonicalRules() {
        assertEquals(PipeTarget.parseUrl("https://relay.example/v1"), PipeTarget.parseAuthority("relay.example"))
        assertEquals(PipeTarget.parseUrl("https://Relay.Example:8443/v1"), PipeTarget.parseAuthority("Relay.Example:8443"))
        assertEquals(PipeTarget.parseUrl("https://192.0.2.10:18790/v1"), PipeTarget.parseAuthority("192.0.2.10:18790"))
        assertEquals(PipeTarget.parseUrl("https://[2001:db8::1]:8443/v1"), PipeTarget.parseAuthority("[2001:db8::1]:8443"))
        assertAuthorityRefused(
            "", ":443", "relay.example:", "relay.example:0", "relay.example:65536", "relay.example.",
            "relay..example", "192.0.522", "0xc0.0.2.10", "192.0.2.010", "[2001:db8::1%eth0]",
            "[::ffff:192.0.2.10]", "[2001:db8::12345]", "2001:db8::1", "relay .example",
            "bücher.example", "relay_1.example", "127.0.0.1", "[::1]",
        )
    }

    /**
     * The P4 can only ever be configured with ASCII hosts, and the handoff's type 3 is an ASCII
     * name. KELVIN SIGN (U+212A) lowercases to ASCII 'k', so a lowercase-then-validate parser would
     * approve `kelvin.example` for text the user did not type in ASCII.
     */
    @Test
    fun parseAuthorityRefusesNonAsciiEvenWhenItLowercasesToAscii() {
        assertAuthorityRefused("Kelvin.example", "relay.Kelvin.example:8443", "relay.example:８443")
    }

    // ---- fromWire ---------------------------------------------------------------------------------

    @Test
    fun fromWireAcceptsExactlyTheFirmwareTypeLengthPairs() {
        assertNotNull(PipeTarget.fromWire(IPV4, bytes(192, 0, 2, 10), 0, 4, 443))
        assertNotNull(PipeTarget.fromWire(IPV6, docV6, 0, 16, 443))
        assertNotNull(PipeTarget.fromWire(DNS, ascii("a"), 0, 1, 443))
        assertNotNull(PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, 65535))
        val name253 = ascii(nameOfLength(253))
        assertNotNull(PipeTarget.fromWire(DNS, name253, 0, 253, 1))
    }

    @Test
    fun fromWireRejectsIpLengthsOtherThanFourAndSixteen() {
        val buf = ByteArray(32) { (it + 1).toByte() }
        for (len in listOf(0, 1, 3, 5, 16)) assertNull("IPv4 length $len", PipeTarget.fromWire(IPV4, buf, 0, len, 443))
        for (len in listOf(0, 4, 15, 17)) assertNull("IPv6 length $len", PipeTarget.fromWire(IPV6, buf, 0, len, 443))
    }

    /** Session::open only encodes `[a-z0-9.-]`, 1–253 bytes, after lowercasing A–Z. */
    @Test
    fun fromWireRejectsNamesTheFirmwareNeverEncodes() {
        val bad = listOf(
            "Relay.example", "RELAY.EXAMPLE", "relay_1.example", "relay .example", "relay.example/v1",
            "relay.example:443", "u1x9@relay.example", "relay.example\u0000", "rüb.example",
        )
        for (s in bad) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            assertNull(s, PipeTarget.fromWire(DNS, b, 0, b.size, 443))
        }
        val utf8 = "bücher.example".toByteArray(Charsets.UTF_8)
        assertNull(PipeTarget.fromWire(DNS, utf8, 0, utf8.size, 443))
        assertNull(PipeTarget.fromWire(DNS, ByteArray(0), 0, 0, 443))
        assertNull(PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 0, 443))
        val name254 = ascii(nameOfLength(254))
        assertNull(PipeTarget.fromWire(DNS, name254, 0, 254, 443))
    }

    @Test
    fun fromWireRejectsUnknownAddressTypes() {
        for (type in listOf(0, 4, 5, 255)) {
            assertNull("type $type", PipeTarget.fromWire(type, bytes(192, 0, 2, 10), 0, 4, 443))
            assertNull("type $type", PipeTarget.fromWire(type, docV6, 0, 16, 443))
            assertNull("type $type", PipeTarget.fromWire(type, ascii("relay.example"), 0, 13, 443))
        }
    }

    @Test
    fun fromWireRejectsPortZeroAndOutOfRange() {
        assertNull(PipeTarget.fromWire(IPV4, bytes(192, 0, 2, 10), 0, 4, 0))
        assertNull(PipeTarget.fromWire(IPV6, docV6, 0, 16, 0))
        assertNull(PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, 0))
        assertNull(PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, 65536))
        assertNull(PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, -1))
    }

    /** A length field larger than the bytes actually received is a truncated frame, never a target. */
    @Test
    fun fromWireRejectsLengthBeyondTheBuffer() {
        val body = firmwareOpenBody(DNS, ascii("relay.example"), 8443)
        assertNull(openTarget(body.copyOf(body.size - 1), 0))
        assertNull(PipeTarget.fromWire(IPV4, bytes(192, 0, 2), 0, 4, 443))
        assertNull(PipeTarget.fromWire(IPV6, docV6, 1, 16, 443))
    }

    /**
     * The allowlist compares OPEN targets byte for byte: anything the P4 could send that differs
     * from the enrolled bytes (a non-canonical name, another port, an IPv4-mapped IPv6 address for
     * an enrolled dotted quad) is either undecodable or unequal, so it can never be admitted.
     */
    @Test
    fun wireTargetsThatDifferInAnyByteNeverMatchTheEnrolledTarget() {
        val enrolled = PipeTarget.parseUrl("https://relay.example:8443/v1")
        for (s in listOf("relay.example.", "relay..example", "-relay.example", "relay.exampl", "relay.example.example")) {
            val b = ascii(s)
            assertNotEquals(s, enrolled, PipeTarget.fromWire(DNS, b, 0, b.size, 8443))
        }
        assertNotEquals(enrolled, PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, 8444))
        assertNotEquals(enrolled, PipeTarget.fromWire(DNS, ascii("relay.example"), 0, 13, 443))

        val v4 = PipeTarget.parseUrl("https://192.0.2.10/v1")
        assertNotEquals(v4, PipeTarget.fromWire(IPV6, hex("00000000000000000000ffffc000020a"), 0, 16, 443))
        assertNotEquals(v4, PipeTarget.fromWire(IPV4, bytes(192, 0, 2, 8), 0, 4, 443))
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private class Expected(val url: String, val type: Int, val host: ByteArray, val port: Int)

    /** 2001:db8::1 in network order. */
    private val docV6 = hex("20010db8000000000000000000000001")

    /** Every URL the app must accept, with the OPEN target derived per report-firmware §E. */
    private val accepted = listOf(
        Expected("https://relay.example/v1", DNS, ascii("relay.example"), 443),
        Expected("https://Relay.Example:8443/v1/", DNS, ascii("relay.example"), 8443),
        Expected("https://192.0.2.10:18790/v1", IPV4, bytes(192, 0, 2, 10), 18790),
        Expected("https://[2001:db8::1]:443/v1", IPV6, docV6, 443),
        Expected("https://[2001:DB8:0:0:0:0:0:1]/v1", IPV6, docV6, 443),
        Expected("https://relay.example:00443/v1", DNS, ascii("relay.example"), 443),
        Expected("https://relay.example/api/v1", DNS, ascii("relay.example"), 443),
        Expected("https://198.51.100.7:1/gateway/llm/v1/", IPV4, bytes(198, 51, 100, 7), 1),
        Expected("https://203.0.113.254/v1", IPV4, bytes(203, 0, 113, 254), 443),
        Expected(
            "https://[2001:db8:85a3::8a2e:370:7334]:65535/v1", IPV6,
            hex("20010db885a3000000008a2e03707334"), 65535,
        ),
        Expected("https://xn--bcher-kva.example/v1", DNS, ascii("xn--bcher-kva.example"), 443),
        Expected("https://relay-1.edge.example:8443/v1", DNS, ascii("relay-1.edge.example"), 8443),
        Expected(urlOfLength(384), DNS, ascii("relay.example"), 443),
        Expected("https://${nameOfLength(253)}/v1", DNS, ascii(nameOfLength(253)), 443),
    )

    private fun parse(url: String): PipeTarget = try {
        PipeTarget.parseUrl(url)
    } catch (e: PipeTarget.Invalid) {
        throw AssertionError("refused: $url (${e.reason})", e)
    }

    private fun assertTarget(url: String, type: Int, host: ByteArray, port: Int) {
        val t = parse(url)
        assertEquals(url, type, t.type)
        assertArrayEquals(url, host, t.host)
        assertEquals(url, port, t.port)
    }

    private fun assertRefused(vararg urls: String) {
        for (url in urls) {
            val t = try {
                PipeTarget.parseUrl(url)
            } catch (_: PipeTarget.Invalid) {
                null
            }
            if (t != null) fail("accepted as type ${t.type} port ${t.port}: $url")
        }
    }

    private fun assertAuthorityRefused(vararg authorities: String) {
        for (authority in authorities) {
            val t = try {
                PipeTarget.parseAuthority(authority)
            } catch (_: PipeTarget.Invalid) {
                null
            }
            if (t != null) fail("accepted as type ${t.type} port ${t.port}: $authority")
        }
    }

    /** OPEN body exactly as BLE_PhonePipeCore.h `Session::open` builds it (receive_limit always 1024). */
    private fun firmwareOpenBody(type: Int, host: ByteArray, port: Int): ByteArray {
        val b = ByteArray(8 + host.size)
        val limit = 1024
        b[0] = limit.toByte()
        b[1] = (limit ushr 8).toByte()
        b[2] = (limit ushr 16).toByte()
        b[3] = (limit ushr 24).toByte()
        b[4] = port.toByte()
        b[5] = (port ushr 8).toByte()
        b[6] = type.toByte()
        b[7] = host.size.toByte()
        host.copyInto(b, 8)
        return b
    }

    /** Decodes an OPEN body at [off] the way the phone must: little-endian port, then type/len/host. */
    private fun openTarget(b: ByteArray, off: Int): PipeTarget? {
        val port = u8(b[off + 4]) or (u8(b[off + 5]) shl 8)
        return PipeTarget.fromWire(u8(b[off + 6]), b, off + 8, u8(b[off + 7]), port)
    }

    /** `https://relay.example/ppp…/v1` of exactly [n] characters. */
    private fun urlOfLength(n: Int): String {
        val head = "https://relay.example/"
        val tail = "/v1"
        val url = head + "p".repeat(n - head.length - tail.length) + tail
        check(url.length == n)
        return url
    }

    /** `a{63}.b{63}.c{63}.d{x}.example`: exactly [n] characters, every label 1–63. */
    private fun nameOfLength(n: Int): String {
        val x = n - 3 * 64 - ".example".length
        require(x in 1..63)
        val name = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(x)).joinToString(".") + ".example"
        check(name.length == n)
        return name
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun hex(s: String): ByteArray {
        val c = s.filter { !it.isWhitespace() }
        require(c.length % 2 == 0)
        return ByteArray(c.length / 2) { c.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xff

    private companion object {
        /** OPEN address_type values from the handoff's v1 table. */
        const val IPV4 = 1
        const val IPV6 = 2
        const val DNS = 3
    }
}
