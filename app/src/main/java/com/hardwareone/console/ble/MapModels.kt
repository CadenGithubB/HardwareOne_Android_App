package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Models for the Maps page (firmware `map`/`maplist`/`waypoint`/`gpstrack` commands plus the
 * PA1010D `gpsread json`). Only `map`, `maplist` and `gpsread` speak JSON over the CLI; the
 * waypoint/track verbs reply plain text with stable prefixes, so those are parsed from scraped
 * console lines. Waypoint notes/attachments live in a per-map sidecar JSON next to the map file
 * (readable via `fileread`, admin only) — the CLI list output carries only index/name/lat/lon.
 */

/** `map json` → the map currently loaded on the DEVICE (independent of what the app renders). */
data class MapInfo(
    val valid: Boolean,
    val filename: String,
    val region: String,
    val featureCount: Long,
    val fileSize: Long,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    /** Base name without the .hwmap extension (keys the waypoint sidecar + canonical path). */
    val base: String get() = filename.removeSuffix(".hwmap")

    companion object {
        fun parse(json: String): MapInfo? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("valid")) return null
            if (!o.optBoolean("valid")) return MapInfo(false, "", "", 0, 0, 0.0, 0.0, 0.0, 0.0)
            val b = o.optJSONObject("bounds")
            return MapInfo(
                valid = true,
                filename = o.optString("filename"),
                region = o.optString("region"),
                featureCount = o.optLong("featureCount"),
                fileSize = o.optLong("fileSize"),
                minLat = b?.optDouble("minLat", 0.0) ?: 0.0,
                minLon = b?.optDouble("minLon", 0.0) ?: 0.0,
                maxLat = b?.optDouble("maxLat", 0.0) ?: 0.0,
                maxLon = b?.optDouble("maxLon", 0.0) ?: 0.0,
            )
        }
    }
}

/** `maplist json` → absolute .hwmap paths under /maps (firmware caps this at 8 entries). */
data class MapListing(val maps: List<String>) {
    companion object {
        fun parse(json: String): MapListing? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val arr = o.optJSONArray("maps") ?: return null
            return MapListing(buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }.filter { it.isNotEmpty() })
        }
    }
}

/**
 * `gpsread json` → sensor envelope. No fix zeroes lat/lon; `connected=false` means the sensor
 * isn't answering. Speed is in knots (firmware contract).
 */
data class GpsFix(
    val valid: Boolean,
    val connected: Boolean,
    val fix: Boolean,
    val sats: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val speedKn: Double,
) {
    companion object {
        fun parse(json: String): GpsFix? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("connected") && !o.has("fix")) return null
            return GpsFix(
                valid = o.optBoolean("valid", false),
                connected = o.optBoolean("connected", false),
                fix = o.optInt("fix", 0) != 0,
                sats = o.optInt("sats", 0),
                lat = o.optDouble("lat", 0.0),
                lon = o.optDouble("lon", 0.0),
                alt = o.optDouble("alt", 0.0),
                speedKn = o.optDouble("speed", 0.0),
            )
        }
    }
}

/**
 * One waypoint. [index] is the DEVICE's slot number from `waypoint list` — slots go sparse after
 * deletes, so the scraped list is the only trustworthy index source (the sidecar array is written
 * dense and can't be mapped back to slots). Notes/files are enriched from the sidecar by matching
 * name + coordinates.
 */
data class Waypoint(
    val index: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isTarget: Boolean,
    val notes: String = "",
    val files: List<String> = emptyList(),
)

/** Scraper for the plain-text `waypoint list` reply. */
object WaypointText {
    // "Waypoints (3/16):"
    private val header = Regex("""^Waypoints \((\d+)/(\d+)\):\s*$""")

    // "  4*: Home (40.61234, -74.10456)"
    private val row = Regex("""^\s*(\d+)(\*)?:\s(.*)\s\((-?\d+(?:\.\d+)?),\s(-?\d+(?:\.\d+)?)\)\s*$""")

    /** Returns the active count, or null if [line] isn't the list header. */
    fun parseHeader(line: String): Int? = header.find(line.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun parseRow(line: String): Waypoint? {
        val m = row.find(line) ?: return null
        return Waypoint(
            index = m.groupValues[1].toIntOrNull() ?: return null,
            isTarget = m.groupValues[2] == "*",
            name = m.groupValues[3],
            lat = m.groupValues[4].toDoubleOrNull() ?: return null,
            lon = m.groupValues[5].toDoubleOrNull() ?: return null,
        )
    }
}

/**
 * The per-map waypoint sidecar, written by the firmware on every waypoint mutation:
 * `{"waypoints":[{lat,lon,name,notes[,files]}],"target":N}`. The firmware probes (in order)
 * `waypoints_<base>.hwmap.json`, `waypoints_<base>.json`, then legacy `waypoints.json` in the
 * map's directory; the app mirrors that order. Used only to enrich the scraped list with
 * notes/files/full-precision coords — never for slot indices.
 */
data class WaypointSidecar(val waypoints: List<Waypoint>, val target: Int) {
    companion object {
        fun parse(json: String): WaypointSidecar? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val arr = o.optJSONArray("waypoints") ?: return null
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val files = w.optJSONArray("files")?.let { f ->
                        buildList { for (j in 0 until f.length()) add(f.optString(j)) }
                    } ?: emptyList()
                    add(
                        Waypoint(
                            index = i,
                            name = w.optString("name"),
                            lat = w.optDouble("lat", 0.0),
                            lon = w.optDouble("lon", 0.0),
                            isTarget = false,
                            notes = w.optString("notes"),
                            files = files,
                        ),
                    )
                }
            }
            return WaypointSidecar(list, o.optInt("target", -1))
        }
    }

    /** Merge notes/files onto the scraped (authoritative) list by name + ~coordinate match. */
    fun enrich(scraped: List<Waypoint>): List<Waypoint> = scraped.map { wp ->
        val side = waypoints.firstOrNull {
            it.name == wp.name &&
                kotlin.math.abs(it.lat - wp.lat) < 1e-4 && kotlin.math.abs(it.lon - wp.lon) < 1e-4
        } ?: return@map wp
        wp.copy(notes = side.notes, files = side.files, lat = side.lat, lon = side.lon)
    }
}

/** One GPS-track point. [timeS] is seconds (epoch or seconds-of-day, format-dependent; only
 *  differences are used) or NaN when the row carried no usable timestamp. */
data class TrackPoint(val timeS: Double, val lat: Double, val lon: Double)

/** A track parsed on the phone from a device log/CSV (the same formats the firmware loader eats). */
data class GpsTrack(val name: String, val points: List<TrackPoint>) {
    val distanceM: Double by lazy {
        var d = 0.0
        for (i in 1 until points.size) {
            d += MapGeo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        d
    }

    val durationS: Double by lazy {
        val ts = points.map { it.timeS }.filter { !it.isNaN() }
        if (ts.size < 2) 0.0 else (ts.last() - ts.first()).coerceAtLeast(0.0)
    }

    /** Fraction (0..1) of points inside the given bounds — mirrors the firmware's validation. */
    fun coverage(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Double {
        if (points.isEmpty()) return 0.0
        val inside = points.count { it.lat in minLat..maxLat && it.lon in minLon..maxLon }
        return inside.toDouble() / points.size
    }

    companion object {
        // "12:34:56,40.612340,-74.104560,12.0,0.5,7" (sensorlog track format; lat "---"/"~~~" rows
        // are signal-lost sentinels) and "0,40.612340,-74.104560" (saveTrack format — the first
        // column is a ROW INDEX, not a timestamp; older sensorlog rows carried milliseconds).
        // Raw first-column values are normalized in a post-pass below.
        private val csvRow = Regex("""^(\d{2}):(\d{2}):(\d{2}),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
        private val epochRow = Regex("""^(\d+),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

        // "gps: lat=40.612340 lon=-74.104560 ..." (general sensor-log format).
        private val gpsLine = Regex("""gps:.*?lat=(-?\d+(?:\.\d+)?)\s+lon=(-?\d+(?:\.\d+)?)""")

        fun parse(name: String, text: String): GpsTrack {
            val pts = mutableListOf<TrackPoint>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                csvRow.find(line)?.let { m ->
                    val t = m.groupValues[1].toInt() * 3600.0 + m.groupValues[2].toInt() * 60.0 + m.groupValues[3].toInt()
                    val lat = m.groupValues[4].toDoubleOrNull() ?: return@let
                    val lon = m.groupValues[5].toDoubleOrNull() ?: return@let
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) pts.add(TrackPoint(t, lat, lon))
                    return@let
                } ?: epochRow.find(line)?.let { m ->
                    val lat = m.groupValues[2].toDoubleOrNull() ?: return@let
                    val lon = m.groupValues[3].toDoubleOrNull() ?: return@let
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                        pts.add(TrackPoint(m.groupValues[1].toDoubleOrNull() ?: Double.NaN, lat, lon))
                    }
                } ?: gpsLine.find(line)?.let { m ->
                    val lat = m.groupValues[1].toDoubleOrNull() ?: return@let
                    val lon = m.groupValues[2].toDoubleOrNull() ?: return@let
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) pts.add(TrackPoint(Double.NaN, lat, lon))
                }
            }
            normalizeTimes(pts)
            return GpsTrack(name, pts)
        }

        /** Fix up first-column values: saveTrack writes a row index (0,1,2,…) — drop it rather
         *  than fabricate a duration; old sensorlog rows carried milliseconds — rescale them.
         *  HH:MM:SS-derived seconds-of-day and epoch seconds pass through unchanged. */
        private fun normalizeTimes(pts: MutableList<TrackPoint>) {
            val ts = pts.mapNotNull { it.timeS.takeIf { t -> !t.isNaN() } }
            if (ts.size < 2) return
            val deltas = ts.zipWithNext()
            val isRowIndex = ts.first() == 0.0 && deltas.all { (a, b) -> b - a == 1.0 }
            // Millis: epoch-ms is huge; boot-ms is ≥1e5 (dodges ≤86400 seconds-of-day) with
            // per-row gaps ≥100 (a 1 Hz logger in ms), and <1e9 dodges epoch-seconds.
            val isMillis = ts.first() >= 1e11 ||
                (ts.first() >= 1e5 && ts.last() < 1e9 && deltas.all { (a, b) -> b - a >= 100.0 })
            when {
                isRowIndex -> for (i in pts.indices) {
                    if (!pts[i].timeS.isNaN()) pts[i] = pts[i].copy(timeS = Double.NaN)
                }
                isMillis -> for (i in pts.indices) {
                    if (!pts[i].timeS.isNaN()) pts[i] = pts[i].copy(timeS = pts[i].timeS / 1000.0)
                }
            }
        }
    }
}

/** Shared spherical-geometry helpers (also used by the renderer + location context). */
object MapGeo {
    private const val EARTH_R = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * EARTH_R * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val f1 = Math.toRadians(lat1)
        val f2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(f2)
        val x = kotlin.math.cos(f1) * kotlin.math.sin(f2) -
            kotlin.math.sin(f1) * kotlin.math.cos(f2) * kotlin.math.cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /** "830m 213°" / "1.2km 45°" — the OLED nav-readout format. */
    fun navLabel(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val d = distanceM(fromLat, fromLon, toLat, toLon)
        val b = bearingDeg(fromLat, fromLon, toLat, toLon).toInt()
        val dist = if (d < 1000) "${d.toInt()}m" else "%.1fkm".format(d / 1000)
        return "$dist $b°"
    }
}
