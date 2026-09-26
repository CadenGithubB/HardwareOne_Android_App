package com.hardwareone.console.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Select-control options.
 *
 * The device publishes `value|label` pairs; the value is what a set command takes and the label is
 * display text. Conflating them meant dropdowns showed `0|Preserve` and sent it back verbatim, so
 * every select in Device Settings displayed nonsense and changed nothing.
 */
class ControlOptionsTest {

    private fun optionsOf(raw: String): List<ControlOption> =
        ControlsModule.parse(
            """{"module":"m","name":"M","entries":[
               {"key":"k","label":"K","type":"int","value":0,"options":"$raw"}]}""",
        )!!.entries.single().options

    @Test
    fun splitsValueAndLabelPairs() {
        val o = optionsOf("0|Preserve,1|Off,2|On")
        assertEquals(listOf("0", "1", "2"), o.map { it.value })
        assertEquals(listOf("Preserve", "Off", "On"), o.map { it.label })
    }

    /** Labels routinely contain spaces and punctuation; only the first separator splits. */
    @Test
    fun keepsPunctuationInLabels() {
        val o = optionsOf("0|Preserve,1|Off (unsupported),2|On")
        assertEquals("Off (unsupported)", o[1].label)
        assertEquals("1", o[1].value)
    }

    /** String-valued enums work the same way — this is what the mic source needs. */
    @Test
    fun handlesStringValues() {
        val o = optionsOf("auto|Auto,pdm|Onboard PDM,g2|G2 glasses")
        assertEquals(listOf("auto", "pdm", "g2"), o.map { it.value })
        assertEquals("Onboard PDM", o[1].label)
    }

    /** Plain lists with no separator are their own value and label. */
    @Test
    fun barewordOptionsAreValueAndLabel() {
        val o = optionsOf("user,assistant")
        assertEquals(listOf("user", "assistant"), o.map { it.value })
        assertEquals(listOf("user", "assistant"), o.map { it.label })
    }

    /** `:` is the older web UI's separator, honoured only when no `|` is present. */
    @Test
    fun acceptsColonAsTheLegacySeparator() {
        val o = optionsOf("0:Text,1:CSV")
        assertEquals(listOf("0", "1"), o.map { it.value })
        assertEquals(listOf("Text", "CSV"), o.map { it.label })
    }

    /** Splitting on the FIRST separator is what keeps a colon inside a label intact. */
    @Test
    fun pipeWinsOverColonWithinTheSameToken() {
        val o = optionsOf("0|Off,1|Mode: fast")
        assertEquals("1", o[1].value)
        assertEquals("Mode: fast", o[1].label)
    }

    /** The separator is chosen per token, so a mixed list still parses correctly. */
    @Test
    fun handlesAMixedSeparatorList() {
        val o = optionsOf("0:Auto,1|Sunny")
        assertEquals(listOf("0", "1"), o.map { it.value })
        assertEquals(listOf("Auto", "Sunny"), o.map { it.label })
    }

    /** The camera module still ships the colon form — these must not break. */
    @Test
    fun parsesTheCameraModulesLegacyForm() {
        val o = optionsOf("0:Auto,1:Sunny,2:Cloudy")
        assertEquals(listOf("0", "1", "2"), o.map { it.value })
        assertEquals("Cloudy", o[2].label)
    }

    /** Labels are UTF-8 and carry non-ASCII — the thermal rotation options use degree signs. */
    @Test
    fun preservesNonAsciiLabels() {
        val o = optionsOf("0|0°,1|90°,2|180°,3|270°")
        assertEquals(listOf("0", "1", "2", "3"), o.map { it.value })
        assertEquals("180°", o[2].label)
    }

    /** An enum-valued int keeps its numeric bounds; the renderer must still prefer the select. */
    @Test
    fun enumValuedIntKeepsItsOptionsAlongsideMinMax() {
        val entry = ControlsModule.parse(
            """{"module":"microphone","name":"Microphone","entries":[
               {"key":"microphoneBitDepth","label":"Bit depth","type":"int","value":16,
                "min":16,"max":32,"options":"16|16-bit,32|32-bit"}]}""",
        )!!.entries.single()
        assertEquals(listOf("16", "32"), entry.options.map { it.value })
        assertEquals(16.0, entry.min!!, 0.001)
        assertEquals(32.0, entry.max!!, 0.001)
        // The stored value must match an option's value so the right label shows.
        assertEquals("16", entry.displayValue)
    }

    /**
     * A bitmask hint is a checkbox grid over an int mask, not a select. Offering it as a dropdown
     * would set one bit and clear the rest, so it must produce no options at all.
     */
    @Test
    fun bitmaskHintIsNotASelect() {
        assertTrue(optionsOf("bitmask:1|Thermal,2|ToF").isEmpty())
    }

    @Test
    fun noOptionsMeansNotASelect() {
        assertTrue(optionsOf("").isEmpty())
    }
}
