package com.hardwareone.console.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Parser + in-memory model for the firmware's `.hwmap` v6 map files (magic "HWMP", little-endian).
 *
 * The format is a tiled VECTOR map: quantized lat/lon polylines/polygons binned into an N×N tile
 * grid with a halo overlap (features spanning tiles are stored once per tile, clipped). Layout:
 * 40-byte header → name table (len-prefixed strings) → tile directory (offset/size pairs, both
 * zero = empty) → per-tile payloads (u16 featureCount, then 6-byte feature headers + u16 qLat/qLon
 * point pairs quantized to the tile+halo box).
 *
 * Points are stored here as Float degrees RELATIVE to the map bounds' center — small values keep
 * full float precision (raw longitudes like -74.26° would eat the mantissa).
 *
 * Mirrors the reference parsers in the firmware (`System_Maps.cpp loadMapFile`) and the web page
 * (`WebPage_Maps.h parseHWMap`), including the ÷65536 dequantization both of those use.
 */

object MapFeatureType {
    const val HIGHWAY = 0x00
    const val ROAD_MAJOR = 0x01
    const val ROAD_MINOR = 0x02
    const val PATH = 0x03
    const val WATER = 0x10
    const val PARK = 0x11
    const val LAND_MASK = 0x12
    const val RAILWAY = 0x20
    const val BUS = 0x21
    const val FERRY = 0x22
    const val BUILDING = 0x30
    const val STATION = 0x40

    const val SUB_WATER_LAKE = 0
    const val SUB_WATER_RIVER = 1
    const val SUB_WATER_COAST = 2

    fun label(type: Int): String = when (type) {
        HIGHWAY -> "Highway"
        ROAD_MAJOR -> "Major road"
        ROAD_MINOR -> "Minor road"
        PATH -> "Path"
        WATER -> "Water"
        PARK -> "Park"
        LAND_MASK -> "Land"
        RAILWAY -> "Railway"
        BUS -> "Bus route"
        FERRY -> "Ferry"
        BUILDING -> "Building"
        STATION -> "Station"
        else -> "Feature"
    }
}

/**
 * One map feature. [pts] is [relLat0, relLon0, relLat1, relLon1, …] in degrees relative to the
 * map center. [closed] marks polygon rings (first point == last point). [tile] is the file tile
 * index this (halo-clipped) feature was stored in — reused as its spatial-index cell.
 */
class HwMapFeature(
    val type: Int,
    val subtype: Int,
    val nameIndex: Int,
    val pts: FloatArray,
    val closed: Boolean,
    val minRelLat: Float,
    val minRelLon: Float,
    val maxRelLat: Float,
    val maxRelLon: Float,
    val tile: Int,
)

class HwMapFile(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val regionName: String,
    val featureTypeMask: Int,
    val names: List<String>,
    val features: List<HwMapFeature>,
    val tileGrid: Int,
    val fileSize: Int,
) {
    val centerLat: Double = (minLat + maxLat) / 2
    val centerLon: Double = (minLon + maxLon) / 2
    val spanLat: Double = maxLat - minLat
    val spanLon: Double = maxLon - minLon

    fun nameOf(f: HwMapFeature): String? =
        if (f.nameIndex in names.indices) names[f.nameIndex].takeIf { it.isNotEmpty() } else null

    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    fun relLat(lat: Double): Float = (lat - centerLat).toFloat()
    fun relLon(lon: Double): Float = (lon - centerLon).toFloat()

    /** (type<<8)|subtype pairs actually present — used to gray out absent layer toggles. */
    val availability: Set<Int> by lazy { features.mapTo(HashSet()) { (it.type shl 8) or it.subtype } }

    fun hasType(type: Int): Boolean = features.any { it.type == type }

    // --- Spatial index: reuse the file's own tile grid instead of a second, coarser one. Each
    // feature is stored halo-clipped to exactly one source tile, so it lands in exactly one cell
    // (cell index == file tile index) — no bbox fan-out at build time, no dedup at query time. ---

    private val cells = Array(tileGrid * tileGrid) { mutableListOf<Int>() }

    init {
        features.forEachIndexed { idx, f -> cells[f.tile].add(idx) }
    }

    /** Every feature index — handed back verbatim when a query window covers the whole map. */
    private val allIndices: IntArray by lazy { IntArray(features.size) { it } }

    /**
     * Feature indices whose bbox intersects the given rel-degree window. Returns a primitive
     * [IntArray] (not `List<Int>`) — at full extent this is ~all 38k indices every frame, and
     * boxing that many `Integer`s was ~40 ms of the per-frame cull. Allocates a fresh array per call
     * (grown by doubling, trimmed once), so it stays safe to call off the UI thread.
     */
    fun featuresInWindow(minRLat: Float, minRLon: Float, maxRLat: Float, maxRLon: Float): IntArray {
        val sLat = (spanLat).coerceAtLeast(1e-9)
        val sLon = (spanLon).coerceAtLeast(1e-9)
        // ±1 tile of slop: a feature's halo-clipped geometry can overhang its home tile by up to the
        // halo width (< 1 tile), so a neighbour tile can still hold a feature touching the window.
        val x0 = (((minRLon + sLon / 2) / sLon) * tileGrid).toInt() - 1
        val x1 = (((maxRLon + sLon / 2) / sLon) * tileGrid).toInt() + 1
        val y0 = (((minRLat + sLat / 2) / sLat) * tileGrid).toInt() - 1
        val y1 = (((maxRLat + sLat / 2) / sLat) * tileGrid).toInt() + 1
        // Whole-map window (overview / zoomed out past the bounds): every feature is in view, so
        // skip the per-cell bbox scan and hand back the shared all-indices array (callers read-only).
        if (x0 <= 0 && x1 >= tileGrid - 1 && y0 <= 0 && y1 >= tileGrid - 1) return allIndices
        var out = IntArray(256)
        var n = 0
        for (y in y0..y1) {
            if (y < 0 || y >= tileGrid) continue
            val base = y * tileGrid
            for (x in x0..x1) {
                if (x < 0 || x >= tileGrid) continue
                for (i in cells[base + x]) {
                    val f = features[i]
                    if (f.maxRelLon >= minRLon && f.minRelLon <= maxRLon &&
                        f.maxRelLat >= minRLat && f.minRelLat <= maxRLat
                    ) {
                        if (n == out.size) out = out.copyOf(out.size * 2)
                        out[n++] = i
                    }
                }
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** Building-cluster blobs for zoomed-out rendering: (relLat, relLon, count) per occupied cell. */
    val buildingClusters: List<Triple<Float, Float, Int>> by lazy {
        val n = 16
        val counts = IntArray(n * n)
        val sumLat = FloatArray(n * n)
        val sumLon = FloatArray(n * n)
        val sLat = (spanLat).coerceAtLeast(1e-9)
        val sLon = (spanLon).coerceAtLeast(1e-9)
        for (f in features) {
            if (f.type != MapFeatureType.BUILDING) continue
            val cLat = (f.minRelLat + f.maxRelLat) / 2
            val cLon = (f.minRelLon + f.maxRelLon) / 2
            val x = (((cLon + sLon / 2) / sLon) * n).toInt().coerceIn(0, n - 1)
            val y = (((cLat + sLat / 2) / sLat) * n).toInt().coerceIn(0, n - 1)
            counts[y * n + x]++
            sumLat[y * n + x] += cLat
            sumLon[y * n + x] += cLon
        }
        buildList {
            for (i in counts.indices) if (counts[i] > 0) {
                add(Triple(sumLat[i] / counts[i], sumLon[i] / counts[i], counts[i]))
            }
        }
    }

    // --- Name search / feature lookup (client-side; the parsed name table is complete) ---

    fun searchNames(query: String, limit: Int = 20): List<String> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return names.asSequence().filter { it.isNotEmpty() && it.lowercase().contains(q) }
            .distinct().take(limit).toList()
    }

    /** All feature indices carrying exactly [name] (a name-table entry). */
    fun featureIndicesNamed(name: String): List<Int> {
        val ni = names.indexOf(name)
        if (ni < 0) return emptyList()
        return features.indices.filter { features[it].nameIndex == ni }
    }

    /** Deduped named transit features (rail/bus/ferry) → (name, type, first feature index). */
    fun transitRoutes(limitPerType: Int = 60): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        val seen = HashSet<Long>()
        val counts = HashMap<Int, Int>()
        features.forEachIndexed { idx, f ->
            if (f.type != MapFeatureType.RAILWAY && f.type != MapFeatureType.BUS && f.type != MapFeatureType.FERRY) return@forEachIndexed
            if (f.nameIndex !in names.indices) return@forEachIndexed
            val name = names[f.nameIndex]
            if (name.isEmpty()) return@forEachIndexed
            val key = (f.type.toLong() shl 32) or f.nameIndex.toLong()
            if (!seen.add(key)) return@forEachIndexed
            val c = counts.getOrDefault(f.type, 0)
            if (c >= limitPerType) return@forEachIndexed
            counts[f.type] = c + 1
            out.add(Triple(name, f.type, idx))
        }
        return out.sortedWith(compareBy({ it.second }, { it.first }))
    }

    // --- Location context ("On <road> • Near <area>") --------------------------------------
    //
    // The firmware's `whereami` only updates while the device's own OLED map mode is running, so
    // the app computes context itself from the parsed map (same idea as
    // LocationContextManager::updateContext: nearest NAMED road and nearest NAMED area).

    data class Context(val road: String?, val roadDistanceM: Double, val area: String?, val areaDistanceM: Double)

    fun locationContext(lat: Double, lon: Double): Context {
        val rLat = relLat(lat)
        val rLon = relLon(lon)
        // Search a ~500 m half-window around the position (plus a floor for tiny maps).
        val winLat = max(0.005, spanLat / tileGrid).toFloat()
        val winLon = max(0.005 / cos(Math.toRadians(lat)).coerceAtLeast(0.2), spanLon / tileGrid).toFloat()
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        var road: String? = null
        var roadD = Double.MAX_VALUE
        var area: String? = null
        var areaD = Double.MAX_VALUE
        for (i in featuresInWindow(rLat - winLat, rLon - winLon, rLat + winLat, rLon + winLon)) {
            val f = features[i]
            val isRoad = f.type in MapFeatureType.HIGHWAY..MapFeatureType.PATH
            val isArea = f.type == MapFeatureType.WATER || f.type == MapFeatureType.PARK
            if (!isRoad && !isArea) continue
            val name = nameOf(f) ?: continue
            val d = distanceToFeatureM(f, rLat, rLon, mPerDegLat, mPerDegLon)
            if (isRoad && d < roadD) { roadD = d; road = name }
            if (isArea && d < areaD) { areaD = d; area = name }
        }
        return Context(road, roadD, area, areaD)
    }

    /** Point-to-polyline distance in meters (equirectangular, fine at this scale). */
    private fun distanceToFeatureM(
        f: HwMapFeature,
        rLat: Float,
        rLon: Float,
        mPerDegLat: Double,
        mPerDegLon: Double,
    ): Double {
        val px = rLon * mPerDegLon
        val py = rLat * mPerDegLat
        var best = Double.MAX_VALUE
        var ax = f.pts[1] * mPerDegLon
        var ay = f.pts[0] * mPerDegLat
        var i = 2
        while (i + 1 < f.pts.size) {
            val bx = f.pts[i + 1] * mPerDegLon
            val by = f.pts[i] * mPerDegLat
            best = min(best, pointSegmentDistance(px, py, ax, ay, bx, by))
            ax = bx
            ay = by
            i += 2
        }
        if (f.pts.size == 2) best = min(best, sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay)))
        return best
    }

    private fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    companion object {
        /** Parse a full `.hwmap` v6 file. Returns null on bad magic/version/truncation. */
        fun parse(bytes: ByteArray): HwMapFile? {
            if (bytes.size < 40) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.get() != 'H'.code.toByte() || buf.get() != 'W'.code.toByte() ||
                buf.get() != 'M'.code.toByte() || buf.get() != 'P'.code.toByte()
            ) {
                return null
            }
            val version = buf.short.toInt() and 0xFFFF
            if (version != 6) return null
            val flags = buf.short.toInt() and 0xFFFF
            // On-disk bounds are degrees × 1e7.
            val minLat = buf.int / 1e7
            val minLon = buf.int / 1e7
            val maxLat = buf.int / 1e7
            val maxLon = buf.int / 1e7
            @Suppress("UNUSED_VARIABLE") val declaredFeatures = buf.int // per-tile count is authoritative
            val nameCount = buf.short.toInt() and 0xFFFF
            val regionBytes = ByteArray(8).also { buf.get(it) }
            val regionName = String(regionBytes, Charsets.US_ASCII).takeWhile { it != '\u0000' }
            val featureTypeMask = buf.short.toInt() and 0xFFFF

            // Flags: bits0-1 tile grid (firmware rule: 0→16, 2→64, else 32); bits2-6 halo percent.
            val grid = when (flags and 0x3) {
                0 -> 16
                2 -> 64
                else -> 32
            }
            val haloFrac = ((flags shr 2) and 0x1F) / 100.0

            return runCatching {
                val names = ArrayList<String>(nameCount)
                repeat(nameCount) {
                    val len = buf.get().toInt() and 0xFF
                    val b = ByteArray(len).also { a -> buf.get(a) }
                    names.add(String(b, Charsets.ISO_8859_1))
                }

                val tileCount = grid * grid
                val tileOff = IntArray(tileCount)
                val tileLen = IntArray(tileCount)
                for (i in 0 until tileCount) {
                    tileOff[i] = buf.int
                    tileLen[i] = buf.int
                }

                val centerLat = (minLat + maxLat) / 2
                val centerLon = (minLon + maxLon) / 2
                val tileH = (maxLat - minLat) / grid
                val tileW = (maxLon - minLon) / grid
                val haloLat = tileH * haloFrac
                val haloLon = tileW * haloFrac

                val features = ArrayList<HwMapFeature>()
                for (ti in 0 until tileCount) {
                    if (tileLen[ti] == 0 || tileOff[ti] == 0) continue
                    if (tileOff[ti] + tileLen[ti] > bytes.size) continue // truncated tile — skip
                    val tx = ti % grid
                    val ty = ti / grid
                    // Quantization box = tile bounds expanded by the halo (both sides).
                    val hMinLat = minLat + ty * tileH - haloLat
                    val hMinLon = minLon + tx * tileW - haloLon
                    val hSpanLat = tileH + 2 * haloLat
                    val hSpanLon = tileW + 2 * haloLon

                    val tb = ByteBuffer.wrap(bytes, tileOff[ti], tileLen[ti]).order(ByteOrder.LITTLE_ENDIAN)
                    val fc = tb.short.toInt() and 0xFFFF
                    repeat(fc) {
                        val type = tb.get().toInt() and 0xFF
                        val subtype = tb.get().toInt() and 0xFF
                        val nameIndex = tb.short.toInt() and 0xFFFF
                        val pointCount = tb.short.toInt() and 0xFFFF
                        val pts = FloatArray(pointCount * 2)
                        var minRLat = Float.MAX_VALUE
                        var minRLon = Float.MAX_VALUE
                        var maxRLat = -Float.MAX_VALUE
                        var maxRLon = -Float.MAX_VALUE
                        for (p in 0 until pointCount) {
                            val qLat = tb.short.toInt() and 0xFFFF
                            val qLon = tb.short.toInt() and 0xFFFF
                            // ÷65536 matches the device + web renderers (the map tool writes ×65535;
                            // the sub-centimeter difference is irrelevant).
                            val lat = hMinLat + (qLat / 65536.0) * hSpanLat
                            val lon = hMinLon + (qLon / 65536.0) * hSpanLon
                            val rLat = (lat - centerLat).toFloat()
                            val rLon = (lon - centerLon).toFloat()
                            pts[p * 2] = rLat
                            pts[p * 2 + 1] = rLon
                            minRLat = min(minRLat, rLat)
                            minRLon = min(minRLon, rLon)
                            maxRLat = max(maxRLat, rLat)
                            maxRLon = max(maxRLon, rLon)
                        }
                        if (pointCount >= 2) {
                            val closed = pointCount > 2 &&
                                abs(pts[0] - pts[pts.size - 2]) < 0.0001f &&
                                abs(pts[1] - pts[pts.size - 1]) < 0.0001f
                            features.add(
                                HwMapFeature(type, subtype, nameIndex, pts, closed, minRLat, minRLon, maxRLat, maxRLon, ti),
                            )
                        }
                    }
                }
                HwMapFile(minLat, minLon, maxLat, maxLon, regionName, featureTypeMask, names, features, grid, bytes.size)
            }.getOrNull()
        }
    }
}
