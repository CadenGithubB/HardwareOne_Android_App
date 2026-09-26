package com.hardwareone.console.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `features json` — the device's own registry of what its build contains.
 *
 * The load-bearing property is that every default is PERMISSIVE. Gating the UI on this map means
 * a parsing slip or an older firmware could otherwise hide most of the app, which is a far worse
 * failure than one wasted request.
 */
class DeviceCapabilitiesTest {

    /** Shaped like the real reply from a XIAO carrier build: I²C layer dropped for BT headroom. */
    private val xiaoBuild = """
        {"schema":1,"hint":"…","features":[
          {"id":"wifi","name":"WiFi","category":"Network","heapKB":24,"compiled":true,"enabled":true,"toggleable":false},
          {"id":"bluetooth","name":"Bluetooth","category":"Network","heapKB":12,"compiled":true,"enabled":true,"toggleable":true},
          {"id":"i2c","name":"I2C Bus","category":"Sensor","heapKB":4,"compiled":false,"enabled":false,"toggleable":false},
          {"id":"thermal","name":"Thermal Camera","category":"Sensor","heapKB":32,"compiled":false,"enabled":false,"toggleable":true},
          {"id":"espnow","name":"ESP-NOW","category":"Network","heapKB":8,"compiled":true,"enabled":false,"toggleable":true}
        ]}
    """.trimIndent()

    @Test
    fun parsesTheRegistry() {
        val caps = DeviceCapabilities.parse(xiaoBuild)
        requireNotNull(caps)
        assertEquals(5, caps.features.size)
        val thermal = caps.feature("thermal")
        requireNotNull(thermal)
        assertEquals("Thermal Camera", thermal.name)
        assertEquals(32, thermal.heapKB)
        assertFalse(thermal.compiled)
        assertTrue(thermal.toggleable)
    }

    /** The case that started all this: no I²C layer means no `sensors` / `devices` commands. */
    @Test
    fun reportsTheI2cLayerAsAbsent() {
        val caps = DeviceCapabilities.parse(xiaoBuild)
        requireNotNull(caps)
        assertFalse(caps.isCompiled(DeviceCapabilities.FEATURE_I2C))
        assertTrue(caps.isCompiled("wifi"))
    }

    /**
     * Compiled-but-off must stay REACHABLE — the user may be opening the app precisely to turn it
     * on. Only `compiled:false` may hide anything.
     */
    @Test
    fun compiledButDisabledIsStillAvailable() {
        val caps = DeviceCapabilities.parse(xiaoBuild)
        requireNotNull(caps)
        assertTrue("espnow is off, not absent — must not be gated out", caps.isCompiled("espnow"))
        assertFalse(caps.isEnabled("espnow"))
    }

    /** A feature this firmware never mentions is assumed present, never hidden. */
    @Test
    fun unknownFeatureIsAssumedPresent() {
        val caps = DeviceCapabilities.parse(xiaoBuild)
        requireNotNull(caps)
        assertTrue(caps.isCompiled("camera"))
        assertTrue(caps.isCompiled("something-a-future-firmware-adds"))
    }

    /** A missing `compiled` key must not read as false and hide a working feature. */
    @Test
    fun missingCompiledKeyDefaultsToPresent() {
        val caps = DeviceCapabilities.parse(
            """{"features":[{"id":"llm","name":"LLM"}]}""",
        )
        requireNotNull(caps)
        assertTrue(caps.isCompiled("llm"))
    }

    /** Anything that isn't a features reply must yield null, leaving every gate permissive. */
    @Test
    fun rejectsForeignJson() {
        assertNull(DeviceCapabilities.parse("""{"schema":1,"tasks":[]}"""))
        assertNull(DeviceCapabilities.parse("""{"dram":{"total":1}}"""))
        assertNull(DeviceCapabilities.parse("not json"))
        assertNull(DeviceCapabilities.parse("""{"features":[]}"""))
    }

    /** Entries without an id are skipped rather than poisoning the map with a blank key. */
    @Test
    fun skipsEntriesWithoutAnId() {
        val caps = DeviceCapabilities.parse(
            """{"features":[{"name":"nameless","compiled":false},{"id":"wifi","compiled":true}]}""",
        )
        requireNotNull(caps)
        assertEquals(1, caps.features.size)
        assertEquals("wifi", caps.features.single().id)
    }
}
