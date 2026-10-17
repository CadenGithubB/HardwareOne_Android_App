package com.hardwareone.console.pipe

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stage-4 stored configuration ([PipeConfig]): the approved relay destination only, as a v2
 * record `{"v":2,"type","host","port"}` with the host as lowercase hex of the OPEN bytes (the
 * bonded-design codec without address or time, R12). The earlier bonded design's v1 record is
 * readable only through [PipeConfig.fromLegacyEnrollment], which keeps just its target. Addresses
 * are RFC 5737 / RFC 3849 documentation values and names are under example.com.
 */
class PipeConfigTest {

    // ---- Encoding ---------------------------------------------------------------------------------

    @Test
    fun toJsonWritesExactlyTheFourV2Fields() {
        val o = JSONObject(PipeConfig(v4).toJson())
        assertEquals(setOf("v", "type", "host", "port"), o.keySet())
        assertEquals(2, o.get("v"))
        assertEquals(1, o.get("type"))
        assertEquals("c000020a", o.get("host"))
        assertEquals(18790, o.get("port"))
    }

    @Test
    fun toJsonHostIsLowercaseHexOfTheOpenBytes() {
        val high = PipeTarget.parseUrl("https://203.0.113.254/v1")
        assertEquals("cb0071fe", JSONObject(PipeConfig(high).toJson()).getString("host"))
        assertEquals(
            "20010db8000000000000000000000001",
            JSONObject(PipeConfig(v6).toJson()).getString("host"),
        )
        val named = JSONObject(PipeConfig(dns).toJson())
        assertEquals(3, named.get("type"))
        assertEquals(toHex(ascii("relay.example.com")), named.getString("host"))
        assertEquals(8443, named.get("port"))
    }

    /** Allowlist values stay private: neither the record nor toString spells out the destination. */
    @Test
    fun neitherJsonNorToStringRevealsTheDestination() {
        for (config in listOf(PipeConfig(v4), PipeConfig(v6), PipeConfig(dns))) {
            for (text in listOf(config.toJson(), config.toString())) {
                for (plain in listOf("192.0.2", "2001:db8", "2001:0db8", "relay", "example")) {
                    assertFalse("$plain in $text", text.contains(plain))
                }
            }
        }
    }

    // ---- Round trips ------------------------------------------------------------------------------

    @Test
    fun ipv4RoundTrips() = assertRoundTrips(v4)

    @Test
    fun ipv6RoundTrips() = assertRoundTrips(v6)

    @Test
    fun dnsRoundTrips() {
        assertRoundTrips(dns)
        val name253 = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(49)).joinToString(".") + ".example.com"
        assertEquals(253, name253.length)
        assertRoundTrips(PipeTarget.parseUrl("https://$name253/v1"))
        assertRoundTrips(PipeTarget.fromWire(PipeTarget.DNS, ascii("a"), 0, 1, 65535)!!)
        assertRoundTrips(PipeTarget.fromWire(PipeTarget.IPV4, hex("c6336401"), 0, 4, 1)!!)
    }

    /** A record written by hand (not by toJson), in another key order, decodes to the same target. */
    @Test
    fun handWrittenRecordDecodes() {
        val config = PipeConfig.fromJson("""{ "port": 18790, "host": "c000020a", "v": 2, "type": 1 }""")
        assertEquals(PipeConfig(v4), config)
        assertEquals(
            PipeConfig(v6),
            PipeConfig.fromJson("""{"v":2,"type":2,"host":"20010db8000000000000000000000001","port":443}"""),
        )
    }

    // ---- Refusals ---------------------------------------------------------------------------------

    @Test
    fun garbageIsRefused() {
        val texts = listOf(
            "", " ", "not json", "[]", "{}", "null", "2", "\"v\"", "{\"v\":2}", "{\"v\":2,\"type\":1}",
            "{\"v\":2,\"type\":1,\"host\":\"c000020a\"}", "{\"v\":2,\"type\":1,\"port\":443}",
            "{\"v\":2,\"type\":1,\"host\":\"c000020a\",\"port\":18790", "[{\"v\":2,\"type\":1,\"host\":\"c000020a\",\"port\":18790}]",
        )
        for (text in texts) {
            assertNull("'$text'", PipeConfig.fromJson(text))
            assertNull("legacy '$text'", PipeConfig.fromLegacyEnrollment(text))
        }
    }

    @Test
    fun wrongVersionIsRefused() {
        assertNotNull("baseline record must decode", decode(stored()))
        for (v in listOf(0, 1, 3, -1, 100)) assertNull("v=$v", decode(stored().put("v", v)))
        assertNull("v missing", decode(stored().apply { remove("v") }))
        assertNull("v as string", decode(stored().put("v", "2")))
        assertNull("v null", decode(stored().put("v", JSONObject.NULL)))
        assertNull("v true", decode(stored().put("v", true)))
        assertNull("v 2.5", PipeConfig.fromJson("""{"v":2.5,"type":1,"host":"c000020a","port":18790}"""))
        assertNull("v 2.0", PipeConfig.fromJson("""{"v":2.0,"type":1,"host":"c000020a","port":18790}"""))
    }

    /** Numbers must be JSON integers: a string or fraction is a malformed record, not coerced. */
    @Test
    fun nonIntegerTypeOrPortIsRefused() {
        assertNull(decode(stored().put("type", "1")))
        assertNull(decode(stored().put("port", "18790")))
        assertNull(PipeConfig.fromJson("""{"v":2,"type":1.0,"host":"c000020a","port":18790}"""))
        assertNull(PipeConfig.fromJson("""{"v":2,"type":1,"host":"c000020a","port":18790.5}"""))
        assertNull(decode(stored().put("port", 4_294_985_286L))) // 18790 + 2^32: never truncated
        // 18790 + 2^64 and an exponent form: well-formed numbers, but not JSON integers in range.
        assertNull(PipeConfig.fromJson("""{"v":2,"type":1,"host":"c000020a","port":18446744073709570406}"""))
        assertNull(PipeConfig.fromJson("""{"v":2,"type":1,"host":"c000020a","port":18790e0}"""))
    }

    @Test
    fun oddOrInvalidHexIsRefused() {
        val good = stored().getString("host")
        assertEquals("c000020a", good)
        for (bad in listOf(
            good.dropLast(1), good + "0", "zz" + good.drop(2), good.dropLast(2) + "0g", "C000020A",
            "c00002-1", "c000+20a", " c000020a", "c000020a ", "0xc000020a", "c0 00 02 0a",
        )) {
            assertNull("host '$bad'", decode(stored().put("host", bad)))
        }
        assertNull("host as number", decode(stored().put("host", 12345678)))
        assertNull("host null", decode(stored().put("host", JSONObject.NULL)))
    }

    /** The stored target passes the same OPEN validation as the wire, so a corrupt record cannot approve. */
    @Test
    fun invalidTargetIsRefused() {
        assertNull("uppercase name", decode(stored().put("type", 3).put("host", toHex(ascii("Relay.example.com")))))
        assertNull("underscore", decode(stored().put("type", 3).put("host", toHex(ascii("relay_1.example.com")))))
        assertNull("empty name", decode(stored().put("type", 3).put("host", "")))
        assertNull("254-byte name", decode(stored().put("type", 3).put("host", toHex(ascii("a".repeat(254))))))
        assertNull("3-byte IPv4", decode(stored().put("type", 1).put("host", "c00002")))
        assertNull("5-byte IPv4", decode(stored().put("type", 1).put("host", "c000020a01")))
        assertNull("4-byte IPv6", decode(stored().put("type", 2).put("host", "c000020a")))
        assertNull("type 0", decode(stored().put("type", 0)))
        assertNull("type 4", decode(stored().put("type", 4)))
        assertNull("port 0", decode(stored().put("port", 0)))
        assertNull("port -1", decode(stored().put("port", -1)))
        assertNull("port 65536", decode(stored().put("port", 65536)))
    }

    // ---- The bonded design's v1 record ------------------------------------------------------------

    /** The record the bonded design wrote: not a v2 config, but its target survives migration. */
    @Test
    fun legacyEnrollmentKeepsOnlyItsTarget() {
        for (target in listOf(v4, v6, dns)) {
            val legacy = legacyRecord(target)
            assertNull(PipeConfig.fromJson(legacy))
            val migrated = PipeConfig.fromLegacyEnrollment(legacy)
            assertEquals(PipeConfig(target), migrated)
            // The re-encoded record is v2 and carries nothing from the bond.
            val o = JSONObject(migrated!!.toJson())
            assertEquals(setOf("v", "type", "host", "port"), o.keySet())
            assertEquals(2, o.get("v"))
        }
    }

    @Test
    fun handWrittenLegacyRecordMigrates() {
        val legacy = """{"v":1,"address":"00:00:5E:00:53:01","type":1,"host":"c000020a","port":18790,"at":1760000000000}"""
        assertNull(PipeConfig.fromJson(legacy))
        assertEquals(PipeConfig(v4), PipeConfig.fromLegacyEnrollment(legacy))
        // Only the target is read: the bond's address and time are not validated, since they are dropped.
        val bare = """{"v":1,"type":1,"host":"c000020a","port":18790}"""
        assertEquals(PipeConfig(v4), PipeConfig.fromLegacyEnrollment(bare))
    }

    @Test
    fun legacyReaderRefusesV2AndInvalidRecords() {
        assertNull(PipeConfig.fromLegacyEnrollment(PipeConfig(v4).toJson()))
        val legacy = JSONObject(legacyRecord(v4))
        assertNotNull(PipeConfig.fromLegacyEnrollment(legacy.toString()))
        assertNull(PipeConfig.fromLegacyEnrollment(JSONObject(legacy.toString()).put("v", 0).toString()))
        assertNull(PipeConfig.fromLegacyEnrollment(JSONObject(legacy.toString()).put("host", "c00002").toString()))
        assertNull(PipeConfig.fromLegacyEnrollment(JSONObject(legacy.toString()).put("host", "c000020").toString()))
        assertNull(PipeConfig.fromLegacyEnrollment(JSONObject(legacy.toString()).put("port", 0).toString()))
        assertNull(PipeConfig.fromLegacyEnrollment(JSONObject(legacy.toString()).put("type", 9).toString()))
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /** Exactly what the bonded design's PipeEnrollment.toJson wrote (that class no longer exists). */
    private fun legacyRecord(target: PipeTarget): String = JSONObject()
        .put("v", 1)
        .put("address", LEGACY_ADDRESS)
        .put("type", target.type)
        .put("host", target.host.joinToString("") { "%02x".format(it) })
        .put("port", target.port)
        .put("at", LEGACY_AT)
        .toString()

    private val v4 = PipeTarget.parseUrl("https://192.0.2.10:18790/v1")
    private val v6 = PipeTarget.parseUrl("https://[2001:db8::1]/v1")
    private val dns = PipeTarget.parseUrl("https://relay.example.com:8443/v1")

    private fun assertRoundTrips(target: PipeTarget) {
        val original = PipeConfig(target)
        val restored = PipeConfig.fromJson(original.toJson())
        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals(original.hashCode(), restored!!.hashCode())
        assertEquals(target.type, restored.target.type)
        assertArrayEquals(target.host, restored.target.host)
        assertEquals(target.port, restored.target.port)
    }

    /** A valid stored v2 record as a mutable JSON object. */
    private fun stored(): JSONObject = JSONObject(PipeConfig(v4).toJson())

    private fun decode(o: JSONObject): PipeConfig? = PipeConfig.fromJson(o.toString())

    /** The same text as a v1 record: every text above has the version as its first '2'. */
    private fun asLegacy(text: String): String = text.replaceFirst('2', '1')

    private companion object {
        /** RFC 7042 documentation MAC, only to build a bonded-design record. */
        const val LEGACY_ADDRESS = "00:00:5E:00:53:01"
        const val LEGACY_AT = 1_760_000_000_000L
    }
}
