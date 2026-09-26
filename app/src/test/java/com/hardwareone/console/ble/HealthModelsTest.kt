package com.hardwareone.console.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 health parsing.
 *
 * The column-order tests matter most: the log's shape depends on the device's sensor-log mask, so
 * a positional assumption wouldn't crash — it would silently plot HRV as SpO₂.
 */
class HealthModelsTest {

    // --- healthstatus json ------------------------------------------------------------

    @Test
    fun statusReadsLoggingConfigWithTheRingAway() {
        val s = HealthStatus.parse(
            """{"schema":1,"ringConnected":false,"setupState":"ready","hrValid":false,"hr":0,
               "hrvValid":false,"spo2Valid":false,"tempValid":false,"batteryValid":false,
               "wearValid":false,"healthLoggingEnabled":true,"healthLoggingActive":true,
               "healthLoggingPollIntervalSec":60,"healthLoggingR1Selected":true,
               "historyState":"complete","historyStoreAvailable":true}""".trimIndent(),
        )
        requireNotNull(s)
        assertFalse(s.ringConnected)
        assertTrue(s.loggingEnabled)
        assertEquals(60, s.pollIntervalSec)
        assertEquals("complete", s.historyState)
        // Invalid readings must be null, never 0 — a plotted zero HR is a lie.
        assertNull(s.hr)
        assertNull(s.battery)
    }

    @Test
    fun statusExposesValidReadingsAndConvertsTemperature() {
        val s = HealthStatus.parse(
            """{"ringConnected":true,"hrValid":true,"hr":62,"tempValid":true,"tempTenths":365,
               "wearValid":true,"wear":2}""".trimIndent(),
        )
        requireNotNull(s)
        assertEquals(62, s.hr)
        assertEquals(36.5, s.tempCelsius!!, 0.001)
        assertEquals(2, s.wear)
    }

    @Test
    fun statusRejectsForeignJson() {
        assertNull(HealthStatus.parse("""{"features":[]}"""))
        assertNull(HealthStatus.parse("nope"))
    }

    // --- the sensor log ---------------------------------------------------------------

    /** r1 alone in the mask: the columns sit right after the timestamp. */
    @Test
    fun parsesAnR1OnlyLog() {
        val csv = """
            time,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear
            2026-08-17T05:00:00,1,61,44,97,36.4,88,2
            2026-08-17T05:01:00,1,63,41,96,36.5,88,2
        """.trimIndent()
        val log = HealthLog.parse(csv)
        assertEquals(2, log.samples.size)
        val first = log.samples.first()
        assertEquals(61, first.hr)
        assertEquals(44, first.hrv)
        assertEquals(97, first.spo2)
        assertEquals(36.4, first.tempC!!, 0.001)
        assertEquals(88, first.battery)
        assertEquals(2, first.wear)
        assertTrue(first.connected)
    }

    /**
     * The load-bearing one. With other sensors in the mask the r1 columns shift, and `r1_temp`
     * sits BETWEEN spo2 and battery rather than at the end. Resolving by name must survive both.
     */
    @Test
    fun resolvesColumnsByNameWhenOtherSensorsAreLogged() {
        val csv = """
            time,thermal_min,thermal_max,gps_lat,gps_lon,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear
            2026-08-17T05:00:00,20.1,31.7,40.1,-74.2,1,58,52,98,36.2,91,2
        """.trimIndent()
        val s = HealthLog.parse(csv).samples.single()
        assertEquals(58, s.hr)
        assertEquals(52, s.hrv)
        assertEquals(98, s.spo2)
        assertEquals(36.2, s.tempC!!, 0.001)
        assertEquals(91, s.battery)
    }

    /** A tail read starts mid-file, so the header arrives separately and every line is data. */
    @Test
    fun usesASeparatelyReadHeaderForTailContent() {
        val header = "time,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear"
        val tail = """
            2026-08-17T09:00:00,1,70,38,95,36.8,74,2
            2026-08-17T09:01:00,1,72,37,95,36.8,74,2
        """.trimIndent()
        val log = HealthLog.parse(tail, headerLine = header)
        assertEquals(2, log.samples.size)
        assertEquals(70, log.samples.first().hr)
    }

    /** Rows with no ring reading are dropped — with a mixed mask most rows have none. */
    @Test
    fun dropsRowsWithoutAnyRingReading() {
        val csv = """
            time,thermal_min,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear
            2026-08-17T05:00:00,20.1,0,,,,,,
            2026-08-17T05:01:00,20.4,1,64,40,97,36.5,80,2
        """.trimIndent()
        val log = HealthLog.parse(csv)
        assertEquals(1, log.samples.size)
        assertEquals(64, log.samples.single().hr)
    }

    /** Truncated rows at either end of a windowed read are skipped, not half-parsed. */
    @Test
    fun toleratesTruncatedRowsAtBothEnds() {
        val header = "time,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear"
        val log = HealthLog.parse(
            "1,64,40,97\n2026-08-17T05:01:00,1,65,41,97,36.5,80,2\n2026-08-17T05:02:00,1,6",
            headerLine = header,
        )
        assertEquals(1, log.samples.size)
        assertEquals(65, log.samples.single().hr)
    }

    /** A log with no ring columns at all yields nothing rather than mis-mapping other sensors. */
    @Test
    fun logWithoutRingColumnsYieldsNoSamples() {
        val csv = "time,thermal_min,thermal_max\n2026-08-17T05:00:00,20.1,31.7"
        assertTrue(HealthLog.parse(csv).samples.isEmpty())
    }

    @Test
    fun ignoresCommentLinesAndBlanks() {
        val csv = """
            # HardwareOne sensor log
            time,r1_connected,r1_hr,r1_hrv,r1_spo2,r1_temp,r1_battery,r1_wear

            2026-08-17T05:00:00,1,61,44,97,36.4,88,2
        """.trimIndent()
        assertEquals(1, HealthLog.parse(csv).samples.size)
    }
}
