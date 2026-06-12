package com.hardwareone.console.ui

import com.hardwareone.console.ble.SensorEntry

/**
 * A live action control for a sensor (the contract's §3/§4 action verbs — distinct from the
 * generic `controls json` settings). Tokens are the exact firmware commands (concatenated, e.g.
 * `fmradiotune`, not `fmradio tune`). Where the report says the current value is in
 * `sensors json data`, the control binds to it for two-way feedback.
 */
sealed interface SensorAction {
    /** A one-shot command button (reads print to the console; state changes re-poll). */
    data class Button(val label: String, val command: String) : SensorAction

    /** A numeric control that sends `<commandPrefix> <value>`. [current] binds to live data. */
    data class Slider(
        val label: String,
        val commandPrefix: String,
        val min: Float,
        val max: Float,
        val isInt: Boolean,
        val current: Float?,
    ) : SensorAction

    /** A bool control sending [onCommand]/[offCommand]. [current] binds to live data (or null). */
    data class Toggle(
        val label: String,
        val onCommand: String,
        val offCommand: String,
        val current: Boolean?,
    ) : SensorAction
}

/**
 * The documented action verbs for a sensor (controls-contract §3/§4), bound to its live `data`.
 * Settings (poll intervals, thresholds, offsets, autostart) are NOT here — they come from the
 * generic `controls json` panel. Power/enable is the card's toggle. Returns empty for sensors
 * with no bespoke actions (imu/tof/thermal — settings + read-only readings only).
 */
fun actionsFor(e: SensorEntry): List<SensorAction> = when (e.id) {
    "fmradio" -> listOf(
        SensorAction.Slider("Tune (MHz)", "fmradiotune", 87f, 108f, isInt = false, current = e.numbers["frequency"]?.toFloat()),
        SensorAction.Slider("Volume", "fmradiovolume", 0f, 15f, isInt = true, current = e.numbers["volume"]?.toFloat()),
        SensorAction.Toggle("Mute", "fmradiomute", "fmradiounmute", e.flags["muted"]),
        SensorAction.Button("Seek ◀", "fmradioseek down"),
        SensorAction.Button("Seek ▶", "fmradioseek up"),
    )
    "apds" -> listOf(
        SensorAction.Toggle("Color mode", "apdsmode color on", "apdsmode color off", null),
        SensorAction.Toggle("Proximity mode", "apdsmode proximity on", "apdsmode proximity off", null),
        SensorAction.Toggle("Gesture mode", "apdsmode gesture on", "apdsmode gesture off", null),
        SensorAction.Button("Read color", "apdscolor"),
        SensorAction.Button("Read proximity", "apdsproximity"),
        SensorAction.Button("Read gesture", "apdsgesture"),
    )
    "rtc" -> listOf(
        SensorAction.Button("Read", "rtcread"),
        SensorAction.Button("Sync → RTC", "rtcsync to"),
        SensorAction.Button("Sync ← RTC", "rtcsync from"),
    )
    "presence" -> listOf(SensorAction.Button("Read status", "presencestatus"))
    "gps" -> listOf(SensorAction.Button("Start track log", "gpslog"))
    else -> emptyList()
}
