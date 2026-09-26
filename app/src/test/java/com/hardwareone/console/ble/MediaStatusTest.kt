package com.hardwareone.console.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera/mic presence semantics.
 *
 * The rule these pin: across this firmware, `connected` is uniformly a runtime init flag, never
 * hardware presence. Only the camera's `supported`/`detected` may be read as presence, and the
 * mic has no detection layer at all.
 */
class MediaStatusTest {

    // --- camera -----------------------------------------------------------------------

    /** Camera present but never started: must NOT read as absent. */
    @Test
    fun stoppedCameraIsSupportedAndNotAbsent() {
        val c = CameraStatus.parse(
            """{"supported":true,"detected":false,"enabled":false,"connected":false,
               "streaming":false,"model":"","width":0,"height":0,"psram":true}""".trimIndent(),
        )
        requireNotNull(c)
        assertTrue(c.supported)
        assertFalse(c.detected)
        assertFalse(c.connected)
    }

    /** `detected` is sticky — it survives the camera being stopped again after a start. */
    @Test
    fun detectedSurvivesAStop() {
        val c = CameraStatus.parse(
            """{"supported":true,"detected":true,"enabled":false,"connected":false,
               "model":"OV2640","width":0,"height":0}""".trimIndent(),
        )
        requireNotNull(c)
        assertTrue(c.detected)
        assertFalse(c.connected)
    }

    /** A camera-less build is the one case that may claim absence. */
    @Test
    fun unsupportedBuildReportsAbsence() {
        val c = CameraStatus.parse(
            """{"supported":false,"detected":false,"enabled":false,"connected":false}""",
        )
        requireNotNull(c)
        assertFalse(c.supported)
    }

    /**
     * Firmware predating these keys must degrade to "show the control", not to "no camera" —
     * defaulting them false would recreate the bug they were added to fix.
     */
    @Test
    fun olderFirmwareDefaultsToPresent() {
        val c = CameraStatus.parse(
            """{"enabled":false,"connected":false,"streaming":false,"model":"OV2640"}""",
        )
        requireNotNull(c)
        assertTrue("missing 'supported' must not read as absent", c.supported)
        assertTrue("missing 'detected' must not read as absent", c.detected)
    }

    @Test
    fun rejectsForeignJson() {
        assertNull(CameraStatus.parse("""{"features":[]}"""))
        assertNull(CameraStatus.parse("nope"))
    }

    // --- microphone -------------------------------------------------------------------

    /**
     * `pdmAvailable` is a COMPILE-TIME constant (HAL_Audio: PDM over I2S has no ID register), so
     * on a build with the mic compiled in, absence can never be claimed no matter the runtime
     * state. That is the correct behaviour, not a gap.
     */
    @Test
    fun compiledInMicIsNeverReportedAbsent() {
        val m = MicStatus.parse(
            """{"schema":1,"enabled":false,"connected":false,"recording":false,
               "pdmAvailable":true,"g2Available":false,"sampleRate":16000,"level":0}""".trimIndent(),
        )
        requireNotNull(m)
        assertFalse("stopped mic must not read as absent", m.noSourceCompiledIn)
    }

    /** Only a build with neither source compiled/linked may say so. */
    @Test
    fun buildWithoutAnyMicSourceSaysSo() {
        val m = MicStatus.parse(
            """{"enabled":false,"connected":false,"pdmAvailable":false,"g2Available":false,
               "sampleRate":0}""".trimIndent(),
        )
        requireNotNull(m)
        assertTrue(m.noSourceCompiledIn)
    }

    /** G2 glasses supply a mic even on a board with no onboard PDM. */
    @Test
    fun g2GlassesCountAsASource() {
        val m = MicStatus.parse(
            """{"enabled":false,"connected":false,"pdmAvailable":false,"g2Available":true,
               "sampleRate":16000}""".trimIndent(),
        )
        requireNotNull(m)
        assertFalse(m.noSourceCompiledIn)
    }

    /** A running mic is present regardless of what the availability fields claim. */
    @Test
    fun aConnectedMicIsNeverAbsent() {
        val m = MicStatus.parse(
            """{"enabled":true,"connected":true,"pdmAvailable":false,"g2Available":false,
               "sampleRate":16000}""".trimIndent(),
        )
        requireNotNull(m)
        assertFalse(m.noSourceCompiledIn)
    }

    @Test
    fun olderMicFirmwareDefaultsToPresent() {
        val m = MicStatus.parse("""{"enabled":false,"connected":false,"sampleRate":16000}""")
        requireNotNull(m)
        assertFalse(m.noSourceCompiledIn)
    }
}
