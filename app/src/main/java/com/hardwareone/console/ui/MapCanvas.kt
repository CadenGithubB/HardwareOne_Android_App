package com.hardwareone.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.hardwareone.console.ble.HwMapFeature
import com.hardwareone.console.ble.HwMapFile
import com.hardwareone.console.ble.MapFeatureType
import com.hardwareone.console.ble.Waypoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native renderer for parsed `.hwmap` vector maps. The palette, layer/LOD thresholds, and draw
 * order replicate the firmware web viewer (WebPage_Maps.h renderMap) so a map looks the same in
 * the app as in the device's web UI; interaction is touch-first (drag pan, pinch zoom, two-finger
 * rotate) with the OLED map mode's follow-GPS semantics on top.
 */

// --- Palette (verbatim from the web viewer's COLORS / SUBTYPE_COLORS tables) ---------------

internal class MapStyle(
    val stroke: Color,
    val width: Float,
    val fill: Color? = null,
    val dash: FloatArray? = null,
)

private val STYLE_FALLBACK = MapStyle(Color.White, 1f)

private val BASE_STYLES: Map<Int, MapStyle> = mapOf(
    MapFeatureType.HIGHWAY to MapStyle(Color(0xFFFF6B6B), 6f),
    MapFeatureType.ROAD_MAJOR to MapStyle(Color(0xFFFFD93D), 4f),
    MapFeatureType.ROAD_MINOR to MapStyle(Color(0xFFFFFFFF), 3f),
    MapFeatureType.PATH to MapStyle(Color(0xFFAAAAAA), 2f, dash = floatArrayOf(8f, 8f)),
    MapFeatureType.WATER to MapStyle(Color(0xFF4DABF7), 2f, fill = Color(0xFF1864AB)),
    MapFeatureType.PARK to MapStyle(Color(0xFF69DB7C), 1f, fill = Color(0xFF2B8A3E)),
    MapFeatureType.LAND_MASK to MapStyle(Color(0xFFC9B896), 0f, fill = Color(0xFFC9B896)),
    MapFeatureType.RAILWAY to MapStyle(Color(0xFFDA77F2), 4f, dash = floatArrayOf(16f, 8f)),
    MapFeatureType.BUS to MapStyle(Color(0xFFFAB005), 3f, dash = floatArrayOf(12f, 6f)),
    MapFeatureType.FERRY to MapStyle(Color(0xFF15AABF), 4f, dash = floatArrayOf(16f, 8f)),
    MapFeatureType.BUILDING to MapStyle(Color(0xFF868E96), 2f, fill = Color(0xFF495057)),
    MapFeatureType.STATION to MapStyle(Color(0xFFF06595), 0f),
)

private fun key(type: Int, subtype: Int) = (type shl 8) or subtype

private val SUBTYPE_STYLES: Map<Int, MapStyle> = mapOf(
    key(0x00, 0) to MapStyle(Color(0xFFFF6B6B), 7f), // motorway
    key(0x00, 1) to MapStyle(Color(0xFFFF8787), 5f), // trunk
    key(0x01, 0) to MapStyle(Color(0xFFFFD93D), 4f), // primary
    key(0x01, 1) to MapStyle(Color(0xFFFFE066), 3f), // secondary
    key(0x02, 0) to MapStyle(Color(0xFFFFFFFF), 3f), // tertiary
    key(0x02, 1) to MapStyle(Color(0xFFE9ECEF), 2f), // residential
    key(0x02, 2) to MapStyle(Color(0xFFCED4DA), 1f), // service
    key(0x03, 0) to MapStyle(Color(0xFFAAAAAA), 2f, dash = floatArrayOf(6f, 6f)), // footway
    key(0x03, 1) to MapStyle(Color(0xFF74C0FC), 2f, dash = floatArrayOf(8f, 4f)), // cycleway
    key(0x03, 2) to MapStyle(Color(0xFF8B7355), 2f, dash = floatArrayOf(10f, 5f)), // track
    key(0x10, 0) to MapStyle(Color(0xFF4DABF7), 2f, fill = Color(0xFF1864AB)), // lake
    key(0x10, 1) to MapStyle(Color(0xFF339AF0), 3f), // river
    key(0x10, 2) to MapStyle(Color(0xFF1C7ED6), 2f, fill = Color(0xFF1864AB)), // coastline
    key(0x11, 0) to MapStyle(Color(0xFF69DB7C), 1f, fill = Color(0xFF2B8A3E)), // park
    key(0x11, 1) to MapStyle(Color(0xFF40C057), 1f, fill = Color(0xFF1B4332)), // forest
    key(0x11, 2) to MapStyle(Color(0xFFA9E34B), 1f, fill = Color(0xFF5C940D)), // grassland
    key(0x20, 0) to MapStyle(Color(0xFFDA77F2), 4f, dash = floatArrayOf(16f, 8f)), // rail
    key(0x20, 1) to MapStyle(Color(0xFFE599F7), 4f, dash = floatArrayOf(8f, 8f)), // subway
    key(0x30, 0) to MapStyle(Color(0xFF868E96), 2f, fill = Color(0xFF495057)), // building
    key(0x30, 1) to MapStyle(Color(0xFF868E96), 2f, fill = Color(0xFF5C4A1D)), // industrial
    key(0x30, 2) to MapStyle(Color(0xFF868E96), 2f, fill = Color(0xFF1C4A6E)), // commercial
    key(0x30, 3) to MapStyle(Color(0xFF868E96), 2f, fill = Color(0xFF2D4A3A)), // residential area
)

internal fun mapStyleFor(type: Int, subtype: Int): MapStyle =
    SUBTYPE_STYLES[key(type, subtype)] ?: BASE_STYLES[type] ?: STYLE_FALLBACK

private val CANVAS_BG = Color(0xFF0A0A14)
private val LAND_TAN = Color(0xFFC9B896)
private val WATER_FILL = Color(0xFF1864AB)
private val CASING = Color(0xFF8B0000)
private val GPS_RED = Color(0xFFFF3333)
private val WAYPOINT_YELLOW = Color(0xFFFFD93D)
private val WAYPOINT_TARGET = Color(0xFFFF6B6B)
private val SELECT_BLUE = Color(0xFF4DABF7)
private val CLUSTER_GRAY = Color(0xFF495057)

// --- LOD thresholds in degrees-per-pixel (smaller dpp = zoomed in) ---

// Calibrated to this app's actual dpp range (a full-island overview is ~0.0001 dpp; the original
// web-viewer values 0.001/0.0004 sat 4–10× above every reachable zoom and never fired). Minor roads
// (residential/service) now stay visible all the way out — they're cheap at coarse zoom because
// they draw as merged per-style paths — so their fade only kicks in past 1× toward the min zoom.
// Paths are dashed (not merged) so they'd be costly at overview: they stay overview-hidden and
// resolve in per-feature as you zoom past ~3×.
private const val LOD_MINOR_START = 0.0002f
private const val LOD_MINOR_FULL = 0.0001f
private const val LOD_PATH_START = 0.00003f
private const val LOD_PATH_FULL = 0.000018f
// Buildings switch to gray density blobs (clusters) when zoomed out and only resolve into
// individual footprints once you're zoomed in past LOD_BUILDING_START — otherwise a city overview
// tries to stroke ~30k sub-pixel rects every frame. CLUSTERS meets BUILDING_START so the two are
// continuous (no zoom band with neither). Tighter than the web viewer on purpose (phone budget).
private const val LOD_BUILDING_START = 0.00008f
private const val LOD_BUILDING_FULL = 0.00005f
private const val LOD_CLUSTERS = 0.00008f
private const val LOD_LINE_REF = 0.0003f

// Stroke pass order — later types draw on top (web renderMap order).
private val STROKE_ORDER = intArrayOf(
    MapFeatureType.BUILDING, MapFeatureType.PATH, MapFeatureType.ROAD_MINOR,
    MapFeatureType.ROAD_MAJOR, MapFeatureType.FERRY, MapFeatureType.BUS,
    MapFeatureType.RAILWAY, MapFeatureType.HIGHWAY, MapFeatureType.STATION,
)

// Coarse/fine crossover. Below this zoom the roads/transit draw as merged per-style paths and
// buildings as merged fills (one draw each instead of thousands — see MapRenderCache.mergedLines /
// mergedBuildings), so everything stays visible cheaply even mid-gesture. At/above it, per-feature
// drawing with viewport culling. Measured at 5×: below it a large viewport makes per-feature (with
// residential roads on) cost more than the fixed merged tessellation — a ~14k-feature fine frame ran
// ~210 ms vs ~130 ms merged; only past ~5× are few enough features on screen that culling wins.
private const val COARSE_ZOOM = 5f

/** Camera + interaction state. zoom 1.0 = the map fits 90% of the canvas (web `resetView`). */
@Stable
class MapViewState {
    var zoom by mutableFloatStateOf(1f)
    var rotationDeg by mutableFloatStateOf(0f)
    var centerRelLat by mutableFloatStateOf(0f)
    var centerRelLon by mutableFloatStateOf(0f)

    /** Set by a drag — stops the OLED-style GPS auto-follow until re-centered. */
    var manuallyPanned by mutableStateOf(false)

    fun zoomIn() { zoom = (zoom * 1.5f).coerceIn(ZOOM_MIN, ZOOM_MAX) }
    fun zoomOut() { zoom = (zoom / 1.5f).coerceIn(ZOOM_MIN, ZOOM_MAX) }
    fun rotateBy(deg: Float) { rotationDeg = ((rotationDeg + deg) % 360f + 360f) % 360f }

    fun reset() {
        zoom = 1f
        rotationDeg = 0f
        centerRelLat = 0f
        centerRelLon = 0f
        manuallyPanned = false
    }

    companion object {
        const val ZOOM_MIN = 0.5f
        const val ZOOM_MAX = 30f
    }
}

/** Per-map render caches: lazily-built Compose Paths in relative-degree coordinates. */
internal class MapRenderCache(val map: HwMapFile) {
    private val featurePaths = arrayOfNulls<Path>(map.features.size)

    fun pathFor(i: Int): Path {
        featurePaths[i]?.let { return it }
        val f = map.features[i]
        val p = Path()
        p.moveTo(f.pts[1], f.pts[0]) // x = relLon, y = relLat
        var j = 2
        while (j + 1 < f.pts.size) {
            p.lineTo(f.pts[j + 1], f.pts[j])
            j += 2
        }
        if (f.closed) p.close()
        featurePaths[i] = p
        return p
    }

    /** All coastline polys as one combined fill path (avoids visible tile seams — web parity). */
    val coastlinePath: Path by lazy {
        combined { it.type == MapFeatureType.WATER && it.subtype == MapFeatureType.SUB_WATER_COAST && it.closed }
    }

    /** All island land-mask polys as one combined fill path (restores land over ocean fill). */
    val landMaskPath: Path by lazy { combined { it.type == MapFeatureType.LAND_MASK && it.closed } }

    private fun combined(pick: (com.hardwareone.console.ble.HwMapFeature) -> Boolean): Path {
        val p = Path()
        map.features.forEachIndexed { i, f -> if (pick(f)) p.addPath(pathFor(i)) }
        return p
    }

    /**
     * Coarse-zoom merged geometry: one lightly-decimated [Path] per (type, subtype) style, so a whole
     * style's thousands of per-tile segments draw in ONE call instead of thousands. Built once, used
     * only below [COARSE_ZOOM] (most of the map on screen); fine zoom draws per feature for viewport
     * culling. [mergedLines] are the stroked line classes — roads plus rail/bus/ferry transit;
     * [mergedBuildings] are the building footprints (filled) — so both stay visible, downsampled, at
     * every zoom rather than dropping out or collapsing to gray cluster blobs. Dashes merge fine
     * (Skia dashes each sub-path independently). Paths/tracks are excluded on purpose — they're meant
     * to be overview-hidden and resolve in only when you zoom in.
     */
    class MergedLayer(val type: Int, val subtype: Int, val path: Path, val ptCount: Int)

    val mergedLines: List<MergedLayer> by lazy {
        buildMerged(closed = false) {
            it.type == MapFeatureType.HIGHWAY || it.type == MapFeatureType.ROAD_MAJOR ||
                it.type == MapFeatureType.ROAD_MINOR || it.type == MapFeatureType.RAILWAY ||
                it.type == MapFeatureType.BUS || it.type == MapFeatureType.FERRY
        }
    }

    val mergedBuildings: List<MergedLayer> by lazy {
        buildMerged(closed = true) { it.type == MapFeatureType.BUILDING }
    }

    /** Merged area fills for coarse zoom: closed parks/nature and closed inland water (lakes) — so
     *  they don't need the harsh in-gesture cull that made small ones flicker while panning. */
    val mergedFills: List<MergedLayer> by lazy {
        buildMerged(closed = true) {
            it.type == MapFeatureType.PARK ||
                (it.type == MapFeatureType.WATER && it.subtype != MapFeatureType.SUB_WATER_COAST)
        }
    }

    /** Merged OPEN inland water (rivers/creeks — stroked lines) for coarse zoom; this map is mostly
     *  open creeks (~620 of them), so per-feature they were the last thing flickering mid-pan. */
    val mergedWaterLines: List<MergedLayer> by lazy {
        buildMerged(closed = false) {
            it.type == MapFeatureType.WATER && it.subtype != MapFeatureType.SUB_WATER_COAST && !it.closed
        }
    }

    private fun buildMerged(closed: Boolean, include: (HwMapFeature) -> Boolean): List<MergedLayer> {
        // Decimation epsilon in rel-degrees, resolution-independent (a small fraction of the map
        // extent): drops points closer than this, thinning the densest runs with no visible change
        // at coarse zoom while cutting the merged path's vertex count (and so its tessellation cost).
        val eps = max(map.spanLat, map.spanLon).toFloat() / 2000f
        val epsSq = eps * eps
        val byStyle = LinkedHashMap<Int, Path>() // insertion order → stable draw order within a type
        val counts = HashMap<Int, Int>()
        for (f in map.features) {
            if (!include(f) || (closed && !f.closed)) continue
            val key = (f.type shl 8) or f.subtype
            val p = byStyle.getOrPut(key) { Path() }
            counts[key] = (counts[key] ?: 0) + appendDecimated(p, f, epsSq, closed)
        }
        return byStyle.map { (k, p) -> MergedLayer(k shr 8, k and 0xFF, p, counts[k] ?: 0) }
    }

    /** Append [f]'s polyline to [dst], dropping points within √[epsSq] of the last kept one. */
    private fun appendDecimated(dst: Path, f: HwMapFeature, epsSq: Float, close: Boolean): Int {
        val pts = f.pts
        if (pts.size < 4) return 0
        dst.moveTo(pts[1], pts[0])
        var lastX = pts[1]; var lastY = pts[0]; var added = 1
        var j = 2
        val n = pts.size
        while (j + 1 < n) {
            val x = pts[j + 1]; val y = pts[j]
            val dx = x - lastX; val dy = y - lastY
            if (j + 3 >= n || dx * dx + dy * dy >= epsSq) { // always keep the final vertex
                dst.lineTo(x, y); lastX = x; lastY = y; added++
            }
            j += 2
        }
        if (close) dst.close()
        return added
    }
}

// --- Geo ↔ screen transforms (shared by drawing, gestures, and hit tests) -------------------

private fun baseScale(size: Size, map: HwMapFile): Float {
    val sLon = map.spanLon.coerceAtLeast(1e-9).toFloat()
    val sLat = map.spanLat.coerceAtLeast(1e-9).toFloat()
    return 0.9f * min(size.width / sLon, size.height / sLat)
}

private fun scalePx(size: Size, map: HwMapFile, view: MapViewState): Float =
    baseScale(size, map) * view.zoom

/** rel-degrees → screen px. */
internal fun geoToScreen(relLat: Float, relLon: Float, size: Size, map: HwMapFile, view: MapViewState): Offset {
    val s = scalePx(size, map, view)
    val sx = (relLon - view.centerRelLon) * s
    val sy = -(relLat - view.centerRelLat) * s
    val th = Math.toRadians(view.rotationDeg.toDouble())
    val c = cos(th).toFloat()
    val sn = sin(th).toFloat()
    return Offset(
        size.width / 2 + sx * c - sy * sn,
        size.height / 2 + sx * sn + sy * c,
    )
}

/** screen px → rel-degrees (x = relLon, y = relLat). */
internal fun screenToGeo(pos: Offset, size: Size, map: HwMapFile, view: MapViewState): Offset {
    val s = scalePx(size, map, view)
    val dx = pos.x - size.width / 2
    val dy = pos.y - size.height / 2
    val th = Math.toRadians(view.rotationDeg.toDouble())
    val c = cos(th).toFloat()
    val sn = sin(th).toFloat()
    val rx = dx * c + dy * sn
    val ry = -dx * sn + dy * c
    return Offset(
        view.centerRelLon + rx / s,
        view.centerRelLat - ry / s,
    )
}

@androidx.compose.runtime.Composable
internal fun MapCanvas(
    map: HwMapFile,
    cache: MapRenderCache,
    view: MapViewState,
    layerEnabled: (Int, Int) -> Boolean,
    waypoints: List<Waypoint>,
    targetIndex: Int,
    trackRel: FloatArray?,
    trackColor: Color,
    gpsRel: Offset?, // x = relLon, y = relLat; null = no fix
    selection: List<Int>,
    selectionCurrent: Int,
    onTapWaypoint: (Waypoint) -> Unit,
    onLongPress: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `interacting` = a gesture is in flight (debounced: the handler bumps [gestureTick] on every
    // change; the keyed effect re-arms a 150 ms settle delay each bump, so it stays true until the
    // gestures stop). Its ONLY effect now is to skip the expensive text-label pass while moving —
    // geometry draws identically whether panning or settled (coarse is merged, fine is few
    // features), so nothing pops in/out anymore.
    var gestureTick by remember { mutableIntStateOf(0) }
    var interacting by remember { mutableStateOf(false) }
    LaunchedEffect(gestureTick) {
        if (gestureTick == 0) return@LaunchedEffect
        interacting = true
        kotlinx.coroutines.delay(150)
        interacting = false
    }
    Canvas(
        modifier = modifier
            .pointerInput(map) {
                detectTransformGestures { centroid, pan, gestureZoom, gestureRotate ->
                    gestureTick++
                    val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                    // Pan: keep the map glued to the finger (rotation-compensated).
                    if (pan != Offset.Zero) {
                        val s = scalePx(size, map, view)
                        val th = Math.toRadians(view.rotationDeg.toDouble())
                        val c = cos(th).toFloat()
                        val sn = sin(th).toFloat()
                        val rx = pan.x * c + pan.y * sn
                        val ry = -pan.x * sn + pan.y * c
                        view.centerRelLon -= rx / s
                        view.centerRelLat += ry / s
                        view.manuallyPanned = true
                    }
                    // Zoom + rotate anchored at the gesture centroid.
                    if (gestureZoom != 1f || gestureRotate != 0f) {
                        val before = screenToGeo(centroid, size, map, view)
                        view.zoom = (view.zoom * gestureZoom).coerceIn(MapViewState.ZOOM_MIN, MapViewState.ZOOM_MAX)
                        view.rotateBy(gestureRotate)
                        val after = screenToGeo(centroid, size, map, view)
                        view.centerRelLon += before.x - after.x
                        view.centerRelLat += before.y - after.y
                    }
                }
            }
            .pointerInput(map, waypoints) {
                detectTapGestures(
                    onTap = { pos ->
                        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                        // Waypoint hit test, 16 px radius (closest wins).
                        var best: Waypoint? = null
                        var bestD = 16f * density
                        for (wp in waypoints) {
                            val p = geoToScreen(map.relLat(wp.lat), map.relLon(wp.lon), size, map, view)
                            val d = (p - pos).getDistance()
                            if (d < bestD) { bestD = d; best = wp }
                        }
                        best?.let(onTapWaypoint)
                    },
                    onLongPress = { pos ->
                        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
                        val geo = screenToGeo(pos, size, map, view)
                        onLongPress(map.centerLat + geo.y, map.centerLon + geo.x)
                    },
                )
            },
    ) {
        drawMap(map, cache, view, layerEnabled, waypoints, targetIndex, trackRel, trackColor, gpsRel, selection, selectionCurrent, interacting)
    }
}

/**
 * Per-frame render profiler for [MapCanvas]. [drawMap] fills a [Sample]; once a second we log two
 * aggregated lines to logcat (tag "MapPerf") — a summary (fps, frame avg/max, the
 * query/geom/labels/screen split, working-set counts) and a geom breakdown (per sub-pass time, per-
 * pass draw counts, vertices/frame). Pan/zoom and watch `adb logcat -s MapPerf` to see what
 * dominates. Set [enabled] = false to silence it once the hot path is understood.
 */
internal object MapPerf {
    @Volatile var enabled = false

    /** One frame's measurements, populated by [drawMap] and handed to [frame]. */
    class Sample {
        var queryNs = 0L; var geomNs = 0L; var labelNs = 0L; var screenNs = 0L; var totalNs = 0L
        // geom sub-phases (ns)
        var baseNs = 0L      // mainland rect + combined coastline + combined land-mask fills
        var waterNs = 0L; var parksNs = 0L; var casingNs = 0L; var strokeNs = 0L
        var visible = 0; var labelsDrawn = 0
        var waterDraws = 0; var parkDraws = 0; var casingDraws = 0; var strokeDraws = 0
        var ptsDrawn = 0L    // vertices summed over every per-feature path drawn this frame
        var coarse = false   // merged-roads path taken this frame (zoomed out) vs per-feature (fine)
    }

    private var frames = 0
    private var q = 0L; private var g = 0L; private var lb = 0L; private var sc = 0L; private var tot = 0L; private var maxTot = 0L
    private var base = 0L; private var water = 0L; private var parks = 0L; private var casing = 0L; private var stroke = 0L
    private var vis = 0L; private var lab = 0L; private var pts = 0L
    private var wd = 0L; private var pd = 0L; private var cd = 0L; private var sd = 0L
    private var coarseFrames = 0
    private var windowStartMs = 0L

    fun frame(s: Sample) {
        if (!enabled) return
        frames++
        q += s.queryNs; g += s.geomNs; lb += s.labelNs; sc += s.screenNs; tot += s.totalNs
        if (s.totalNs > maxTot) maxTot = s.totalNs
        base += s.baseNs; water += s.waterNs; parks += s.parksNs; casing += s.casingNs; stroke += s.strokeNs
        vis += s.visible; lab += s.labelsDrawn; pts += s.ptsDrawn
        wd += s.waterDraws; pd += s.parkDraws; cd += s.casingDraws; sd += s.strokeDraws
        if (s.coarse) coarseFrames++
        val now = android.os.SystemClock.uptimeMillis()
        if (windowStartMs == 0L) { windowStartMs = now; return }
        val elapsed = now - windowStartMs
        if (elapsed < 1000L) return
        val f = frames
        fun ms(n: Long) = String.format(java.util.Locale.US, "%.1f", n / 1e6 / f)
        val draws = wd + pd + cd + sd
        android.util.Log.d("MapPerf",
            "fps=${String.format(java.util.Locale.US, "%.1f", f * 1000.0 / elapsed)} " +
            "frame(avg=${ms(tot)} max=${String.format(java.util.Locale.US, "%.1f", maxTot / 1e6)})ms | " +
            "query=${ms(q)} geom=${ms(g)} labels=${ms(lb)} screen=${ms(sc)} ms | " +
            "visible=${vis / f} draws=${draws / f} labelsDrawn=${lab / f} coarse=$coarseFrames/$f")
        android.util.Log.d("MapPerf",
            "  geom: base=${ms(base)} water=${ms(water)} parks=${ms(parks)} casing=${ms(casing)} stroke=${ms(stroke)} ms | " +
            "drawn water=${wd / f} parks=${pd / f} casing=${cd / f} stroke=${sd / f} | " +
            "pts/frame=${pts / f} (~${if (draws > 0) pts / draws else 0}/path)")
        frames = 0
        q = 0; g = 0; lb = 0; sc = 0; tot = 0; maxTot = 0
        base = 0; water = 0; parks = 0; casing = 0; stroke = 0
        vis = 0; lab = 0; pts = 0; wd = 0; pd = 0; cd = 0; sd = 0
        coarseFrames = 0
        windowStartMs = now
    }
}

private fun DrawScope.drawMap(
    map: HwMapFile,
    cache: MapRenderCache,
    view: MapViewState,
    layerEnabled: (Int, Int) -> Boolean,
    waypoints: List<Waypoint>,
    targetIndex: Int,
    trackRel: FloatArray?,
    trackColor: Color,
    gpsRel: Offset?,
    selection: List<Int>,
    selectionCurrent: Int,
    interacting: Boolean,
) {
    val frameStartNs = System.nanoTime()
    val perf = MapPerf.Sample()
    val canvasSize = Size(size.width, size.height)
    val s = scalePx(canvasSize, map, view)
    val dpp = 1f / s // degrees per pixel — drives LOD
    val dppRatio = dpp / LOD_LINE_REF
    val coarse = view.zoom < COARSE_ZOOM // merged solid roads + building fills; fine = per-feature

    // Sub-pixel cull only: drop geometry that projects to < 1px in BOTH axes (the short per-tile clip
    // stubs tiling shatters features into). Deliberately NOT made aggressive mid-gesture anymore —
    // coarse zoom draws merged paths (nothing per-feature to flicker) and fine zoom has few features,
    // so there's no reason to drop detail while swiping. Only labels are still skipped in-gesture.
    val minPx = 1.0f
    fun tiny(f: HwMapFeature): Boolean =
        (f.maxRelLon - f.minRelLon) * s < minPx && (f.maxRelLat - f.minRelLat) * s < minPx
    fun tinyFill(f: HwMapFeature): Boolean = tiny(f) // fills share the sub-pixel threshold now

    drawRect(CANVAS_BG)

    // Viewport window in rel-degrees (rotation-safe: use the canvas diagonal).
    val halfDiag = sqrt(size.width * size.width + size.height * size.height) / 2f / s
    val queryStartNs = System.nanoTime()
    val visible = map.featuresInWindow(
        view.centerRelLat - halfDiag,
        view.centerRelLon - halfDiag,
        view.centerRelLat + halfDiag,
        view.centerRelLon + halfDiag,
    )
    perf.queryNs = System.nanoTime() - queryStartNs

    val geomStartNs = System.nanoTime()
    withTransform({
        translate(size.width / 2, size.height / 2)
        rotate(view.rotationDeg, pivot = Offset.Zero)
        scale(s, -s, pivot = Offset.Zero)
        translate(-view.centerRelLon, -view.centerRelLat)
    }) {
        val baseStartNs = System.nanoTime()
        // 1. Mainland: the whole map bounds filled tan (web draw order step 1).
        drawRect(
            color = LAND_TAN,
            topLeft = Offset(map.relLon(map.minLon), map.relLat(map.minLat)),
            size = Size(map.spanLon.toFloat(), map.spanLat.toFloat()),
        )

        val waterOn = layerEnabled(MapFeatureType.WATER, MapFeatureType.SUB_WATER_COAST)
        val landOn = layerEnabled(MapFeatureType.LAND_MASK, 0)

        // 2. Coastline ocean as ONE combined fill; 3. island land masks back on top.
        if (waterOn) drawPath(cache.coastlinePath, WATER_FILL)
        if (landOn) drawPath(cache.landMaskPath, LAND_TAN)
        perf.baseNs = System.nanoTime() - baseStartNs

        // 4. Inland water: closed lakes filled, open rivers stroked. Coarse = merged closed-water
        // fills (so small lakes don't flicker mid-pan) with open rivers still per-feature; fine =
        // per-feature (closed + open).
        val waterStartNs = System.nanoTime()
        if (coarse) {
            for (mf in cache.mergedFills) {
                if (mf.type != MapFeatureType.WATER || !layerEnabled(mf.type, mf.subtype)) continue
                drawPath(mf.path, mapStyleFor(mf.type, mf.subtype).fill ?: WATER_FILL)
                perf.waterDraws++; perf.ptsDrawn += mf.ptCount
            }
            for (ml in cache.mergedWaterLines) {
                if (!layerEnabled(ml.type, ml.subtype)) continue
                strokeFeature(ml.path, mapStyleFor(ml.type, ml.subtype), 1f, 1f, s)
                perf.waterDraws++; perf.ptsDrawn += ml.ptCount
            }
        } else {
            for (i in visible) {
                val f = map.features[i]
                if (f.type != MapFeatureType.WATER || f.subtype == MapFeatureType.SUB_WATER_COAST) continue
                if (tinyFill(f)) continue
                if (!layerEnabled(f.type, f.subtype)) continue
                val style = mapStyleFor(f.type, f.subtype)
                if (f.closed) {
                    drawPath(cache.pathFor(i), style.fill ?: WATER_FILL)
                } else {
                    strokeFeature(cache.pathFor(i), style, 1f, 1f, s)
                }
                perf.waterDraws++; perf.ptsDrawn += f.pts.size
            }
        }
        perf.waterNs = System.nanoTime() - waterStartNs

        // 5. Building-cluster blobs — legacy fine-zoom-out fallback; at coarse zoom buildings now
        // draw as merged footprints in the stroke pass below, so this only runs if that's disabled.
        if (!coarse && dpp >= LOD_CLUSTERS && layerEnabled(MapFeatureType.BUILDING, 0)) {
            val cellR = (map.spanLat.toFloat() / 16f) * 0.45f
            for ((cLat, cLon, count) in map.buildingClusters) {
                val alpha = min(0.25f, 0.05f + count * 0.008f)
                drawCircle(CLUSTER_GRAY, radius = cellR, center = Offset(cLon, cLat), alpha = alpha)
            }
        }

        // 6. Parks/nature (closed rings only). Coarse = merged per-style fills (no flicker cull
        // needed); fine = per-feature.
        val parksStartNs = System.nanoTime()
        if (coarse) {
            for (mf in cache.mergedFills) {
                if (mf.type != MapFeatureType.PARK || !layerEnabled(mf.type, mf.subtype)) continue
                val parkFill = mapStyleFor(mf.type, mf.subtype).fill ?: continue
                drawPath(mf.path, parkFill)
                perf.parkDraws++; perf.ptsDrawn += mf.ptCount
            }
        } else {
            for (i in visible) {
                val f = map.features[i]
                if (f.type != MapFeatureType.PARK || !f.closed) continue
                if (tinyFill(f)) continue
                if (!layerEnabled(f.type, f.subtype)) continue
                val parkFill = mapStyleFor(f.type, f.subtype).fill ?: continue
                drawPath(cache.pathFor(i), parkFill)
                perf.parkDraws++; perf.ptsDrawn += f.pts.size
            }
        }
        perf.parksNs = System.nanoTime() - parksStartNs

        // Below the crossover the solid roads (highway/major/minor) draw as merged per-style paths
        // and buildings as merged fills — one draw each instead of thousands — which keeps residential
        // roads and building footprints on at overview affordably; at/inside it, per-feature with
        // viewport culling. Water/parks fills and dashed types (paths/rail/ferry/bus) are always
        // per-feature. `coarse` also means "most of the map is on screen", so the merged paths (which
        // carry the whole map's geometry) aren't wasted.
        fun drawTypePerFeature(type: Int, alpha: Float, lineScale: Float) {
            for (i in visible) {
                val f = map.features[i]
                if (f.type != type || !layerEnabled(f.type, f.subtype)) continue
                if (tiny(f)) continue
                val style = mapStyleFor(f.type, f.subtype)
                if (type == MapFeatureType.BUILDING) {
                    if (f.closed) { drawPath(cache.pathFor(i), style.fill ?: CLUSTER_GRAY, alpha = alpha); perf.strokeDraws++; perf.ptsDrawn += f.pts.size }
                    continue
                }
                val a = if (type == MapFeatureType.FERRY) alpha * 0.55f else alpha
                strokeFeature(cache.pathFor(i), style, a, lineScale, s)
                perf.strokeDraws++; perf.ptsDrawn += f.pts.size
            }
        }

        // 7. Highway casings — dark red underlay, width + 4. Merged when coarse, per-feature when
        // fine; drawn whether moving or settled.
        val casingStartNs = System.nanoTime()
        if (layerEnabled(MapFeatureType.HIGHWAY, 0) || layerEnabled(MapFeatureType.HIGHWAY, 1)) {
            val lineScale = roadLineScale(dppRatio)
            if (coarse) {
                for (cl in cache.mergedLines) {
                    if (cl.type != MapFeatureType.HIGHWAY || !layerEnabled(cl.type, cl.subtype)) continue
                    val w = (mapStyleFor(cl.type, cl.subtype).width + 4f) * lineScale
                    drawPath(cl.path, CASING, style = Stroke(w / s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    perf.casingDraws++; perf.ptsDrawn += cl.ptCount
                }
            } else {
                for (i in visible) {
                    val f = map.features[i]
                    if (f.type != MapFeatureType.HIGHWAY || !layerEnabled(f.type, f.subtype)) continue
                    if (tiny(f)) continue
                    val w = (mapStyleFor(f.type, f.subtype).width + 4f) * lineScale
                    drawPath(cache.pathFor(i), CASING, style = Stroke(w / s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    perf.casingDraws++; perf.ptsDrawn += f.pts.size
                }
            }
        }
        perf.casingNs = System.nanoTime() - casingStartNs

        // 8. Main stroke passes in fixed z-order. Coarse = merged buildings + merged solid roads/
        // transit (+ per-feature dashed path/point types); fine = per-feature everything.
        perf.coarse = coarse
        val strokeStartNs = System.nanoTime()
        if (coarse) {
            // Buildings as merged fills — first in the stroke z-order (over parks, under roads),
            // downsampled so they stay visible at every zoom instead of collapsing to blobs.
            for (mb in cache.mergedBuildings) {
                if (!layerEnabled(MapFeatureType.BUILDING, mb.subtype)) continue
                val fill = mapStyleFor(MapFeatureType.BUILDING, mb.subtype).fill ?: continue
                drawPath(mb.path, fill)
                perf.strokeDraws++; perf.ptsDrawn += mb.ptCount
            }
            for (type in STROKE_ORDER) {
                if (type == MapFeatureType.BUILDING) continue // drawn merged above
                val lod = lodFor(type, dpp) ?: continue // null = LOD-hidden at this zoom
                val (alpha, fadeScale) = lod
                val lineScale = lineScaleFor(type, dppRatio) * fadeScale
                if (type == MapFeatureType.HIGHWAY || type == MapFeatureType.ROAD_MAJOR ||
                    type == MapFeatureType.ROAD_MINOR || type == MapFeatureType.RAILWAY ||
                    type == MapFeatureType.BUS || type == MapFeatureType.FERRY
                ) {
                    val a = if (type == MapFeatureType.FERRY) alpha * 0.55f else alpha
                    for (cl in cache.mergedLines) {
                        if (cl.type != type || !layerEnabled(cl.type, cl.subtype)) continue
                        strokeFeature(cl.path, mapStyleFor(cl.type, cl.subtype), a, lineScale, s)
                        perf.strokeDraws++; perf.ptsDrawn += cl.ptCount
                    }
                } else {
                    drawTypePerFeature(type, alpha, lineScale)
                }
            }
        } else {
            for (type in STROKE_ORDER) {
                val lod = lodFor(type, dpp) ?: continue
                val (alpha, fadeScale) = lod
                val lineScale = lineScaleFor(type, dppRatio) * fadeScale
                drawTypePerFeature(type, alpha, lineScale)
            }
        }
        perf.strokeNs = System.nanoTime() - strokeStartNs

        // Selection highlight: the searched/route feature(s) glow in web blue.
        if (selection.isNotEmpty()) {
            for (i in selection) {
                if (i !in map.features.indices) continue
                val w = 8f / s
                drawPath(cache.pathFor(i), SELECT_BLUE, alpha = 0.45f, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(cache.pathFor(i), SELECT_BLUE, style = Stroke(3f / s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    perf.geomNs = System.nanoTime() - geomStartNs

    // --- Screen-space passes: labels, track, waypoints, GPS dot, selection pin ---

    // Labels are the second-biggest cost (~1s/frame) and pointless mid-drag — skip them in-gesture.
    val labelStartNs = System.nanoTime()
    perf.labelsDrawn = if (interacting) 0 else drawLabels(map, view, canvasSize, visible, dpp, layerEnabled)
    perf.labelNs = System.nanoTime() - labelStartNs

    val screenStartNs = System.nanoTime()
    trackRel?.let { drawTrack(it, map, view, canvasSize, trackColor) }
    drawWaypoints(map, view, canvasSize, waypoints, targetIndex)
    gpsRel?.let { drawGpsDot(geoToScreen(it.y, it.x, canvasSize, map, view)) }
    if (selectionCurrent in map.features.indices) {
        val f = map.features[selectionCurrent]
        drawSelectionPin(geoToScreen(f.pts[0], f.pts[1], canvasSize, map, view))
    }
    perf.screenNs = System.nanoTime() - screenStartNs

    perf.visible = visible.size
    perf.totalNs = System.nanoTime() - frameStartNs
    MapPerf.frame(perf)
}

/** LOD gate per type → (alpha, lineScaleFactor), or null when the type is hidden at this dpp. */
private fun lodFor(type: Int, dpp: Float): Pair<Float, Float>? = when (type) {
    MapFeatureType.ROAD_MINOR -> fade(dpp, LOD_MINOR_START, LOD_MINOR_FULL)
    MapFeatureType.PATH -> fade(dpp, LOD_PATH_START, LOD_PATH_FULL)
    MapFeatureType.BUILDING -> {
        val t = ((LOD_BUILDING_START - dpp) / (LOD_BUILDING_START - LOD_BUILDING_FULL)).coerceIn(0f, 1f)
        if (t < 0.3f) null else 1f to 1f // sharp threshold, no translucency (web parity)
    }
    else -> 1f to 1f
}

/** Quadratic fade-in between [start] (invisible) and [full] (opaque) dpp thresholds. */
private fun fade(dpp: Float, start: Float, full: Float): Pair<Float, Float>? {
    if (dpp > start) return null
    val t = ((start - dpp) / (start - full)).coerceIn(0f, 1f)
    return (t * t) to (0.3f + 0.7f * t)
}

private fun roadLineScale(dppRatio: Float): Float =
    if (dppRatio < 1f) sqrt(dppRatio).coerceAtLeast(0.3f) else (1f / dppRatio).coerceAtLeast(0.4f)

private fun lineScaleFor(type: Int, dppRatio: Float): Float = when (type) {
    MapFeatureType.HIGHWAY, MapFeatureType.ROAD_MAJOR -> roadLineScale(dppRatio)
    MapFeatureType.FERRY ->
        if (dppRatio < 1f) sqrt(dppRatio).coerceAtLeast(0.25f) else (1f / dppRatio).coerceAtLeast(0.35f)
    MapFeatureType.PATH -> if (dppRatio < 1f) 1f else (1f / dppRatio).coerceAtLeast(0.5f)
    else -> if (dppRatio < 1f) 1f else (1f / dppRatio).coerceAtLeast(0.4f)
}

/** Stroke a feature path inside the geo transform (widths/dashes are compensated by /[s]). */
private fun DrawScope.strokeFeature(path: Path, style: MapStyle, alpha: Float, lineScale: Float, s: Float) {
    val w = (style.width * lineScale).coerceAtLeast(0.5f) / s
    val effect = style.dash?.let { d ->
        PathEffect.dashPathEffect(FloatArray(d.size) { d[it] / s }, 0f)
    }
    drawPath(path, style.stroke, alpha = alpha, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

// --- Feature-name labels (zoom-gated, deduped — web pass 9, simplified) ---------------------

private fun labelGate(type: Int): Float = when (type) {
    MapFeatureType.HIGHWAY -> 0.0008f
    MapFeatureType.ROAD_MAJOR -> 0.0006f
    MapFeatureType.WATER, MapFeatureType.PARK -> 0.001f
    MapFeatureType.ROAD_MINOR -> 0.0003f
    MapFeatureType.RAILWAY, MapFeatureType.BUS, MapFeatureType.FERRY -> 0.0004f
    else -> 0f // unlabeled types
}

private fun DrawScope.drawLabels(
    map: HwMapFile,
    view: MapViewState,
    canvasSize: Size,
    visible: IntArray,
    dpp: Float,
    layerEnabled: (Int, Int) -> Boolean,
): Int {
    val textPx = 11f * density
    val placed = mutableListOf<Pair<Offset, String>>()
    val fill = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val outline = android.graphics.Paint(fill).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f * density
        color = android.graphics.Color.BLACK
    }
    val minSameNamePx = 300f * density
    val minAnyPx = 60f * density
    var drawn = 0
    for (i in visible) {
        if (drawn >= 40) break
        val f = map.features[i]
        // Cheapest rejects first: the label gate (most features are unlabeled → gate 0) and the
        // geometry LOD (don't label a minor road/path that isn't even drawn at this zoom). Both run
        // before the SnapshotStateMap read in layerEnabled, which — done per visible feature — was
        // most of this pass's ~1s. Now only actually-labelable features pay for it.
        val gate = labelGate(f.type)
        if (gate <= 0f || dpp > gate) continue
        if (lodFor(f.type, dpp) == null) continue
        if (!layerEnabled(f.type, f.subtype)) continue // no labels for toggled-off layers
        val name = map.nameOf(f) ?: continue
        val mid = (f.pts.size / 4) * 2 // middle point, even-aligned
        val pos = geoToScreen(f.pts[mid], f.pts[mid + 1], canvasSize, map, view)
        if (pos.x < 0 || pos.y < 0 || pos.x > canvasSize.width || pos.y > canvasSize.height) continue
        val clash = placed.any { (p, n) ->
            val d = (p - pos).getDistance()
            (n == name && d < minSameNamePx) || d < minAnyPx
        }
        if (clash) continue
        placed.add(pos to name)
        fill.color = mapStyleFor(f.type, f.subtype).stroke.toArgbInt()
        drawContext.canvas.nativeCanvas.drawText(name, pos.x, pos.y, outline)
        drawContext.canvas.nativeCanvas.drawText(name, pos.x, pos.y, fill)
        drawn++
    }
    return drawn
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

// --- Track, waypoints, GPS dot, selection pin (screen space) --------------------------------

private fun DrawScope.drawTrack(
    trackRel: FloatArray,
    map: HwMapFile,
    view: MapViewState,
    canvasSize: Size,
    color: Color,
) {
    if (trackRel.size < 4) return
    val path = Path()
    var prev = geoToScreen(trackRel[0], trackRel[1], canvasSize, map, view)
    path.moveTo(prev.x, prev.y)
    val arrows = mutableListOf<Pair<Offset, Float>>() // (position, heading rad)
    var sincePx = 0f
    var i = 2
    while (i + 1 < trackRel.size) {
        val p = geoToScreen(trackRel[i], trackRel[i + 1], canvasSize, map, view)
        path.lineTo(p.x, p.y)
        val seg = (p - prev).getDistance()
        sincePx += seg
        if (sincePx > 60f * density && seg > 0.1f) {
            arrows.add(p to atan2(p.y - prev.y, p.x - prev.x))
            sincePx = 0f
        }
        prev = p
        i += 2
    }
    // Glow underlay + core line (web track styling).
    drawPath(path, color, alpha = 0.3f, style = Stroke(7f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // Direction arrowheads.
    val ah = 5f * density
    for ((p, th) in arrows) {
        val a = Offset(p.x - ah * cos(th - 0.5f), p.y - ah * sin(th - 0.5f))
        val b = Offset(p.x - ah * cos(th + 0.5f), p.y - ah * sin(th + 0.5f))
        drawLine(Color.White, a, p, strokeWidth = 1.5f * density, cap = StrokeCap.Round)
        drawLine(Color.White, b, p, strokeWidth = 1.5f * density, cap = StrokeCap.Round)
    }
    // Start pill (green circle) / end pill (red square).
    val start = geoToScreen(trackRel[0], trackRel[1], canvasSize, map, view)
    drawCircle(Color(0xFF2F9E44), radius = 5f * density, center = start)
    drawCircle(Color.White, radius = 5f * density, center = start, style = Stroke(1.5f * density))
    val endR = 4.5f * density
    drawRect(Color(0xFFE03131), topLeft = Offset(prev.x - endR, prev.y - endR), size = Size(endR * 2, endR * 2))
    drawRect(Color.White, topLeft = Offset(prev.x - endR, prev.y - endR), size = Size(endR * 2, endR * 2), style = Stroke(1.5f * density))
}

private fun DrawScope.drawWaypoints(
    map: HwMapFile,
    view: MapViewState,
    canvasSize: Size,
    waypoints: List<Waypoint>,
    targetIndex: Int,
) {
    val textPx = 10f * density
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = textPx
        textAlign = android.graphics.Paint.Align.CENTER
        color = android.graphics.Color.WHITE
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
    }
    for (wp in waypoints) {
        val p = geoToScreen(map.relLat(wp.lat), map.relLon(wp.lon), canvasSize, map, view)
        if (p.x < -30 || p.y < -30 || p.x > canvasSize.width + 30 || p.y > canvasSize.height + 30) continue
        val target = wp.index == targetIndex
        val r = (if (target) 8f else 6f) * density
        drawCircle(if (target) WAYPOINT_TARGET else WAYPOINT_YELLOW, radius = r, center = p)
        drawCircle(Color.White, radius = r, center = p, style = Stroke(1.5f * density))
        drawContext.canvas.nativeCanvas.drawText(wp.name, p.x, p.y - r - 3f * density, paint)
    }
}

private fun DrawScope.drawGpsDot(p: Offset) {
    drawCircle(GPS_RED, radius = 12f * density, center = p, alpha = 0.15f)
    drawCircle(GPS_RED, radius = 8f * density, center = p, alpha = 0.35f)
    drawCircle(GPS_RED, radius = 4.5f * density, center = p)
    drawCircle(Color.White, radius = 4.5f * density, center = p, style = Stroke(1.5f * density))
}

/** Teardrop pin at the selected search/route feature (web selected-feature pin). */
private fun DrawScope.drawSelectionPin(p: Offset) {
    val r = 7f * density
    val tip = Offset(p.x, p.y)
    val c = Offset(p.x, p.y - r * 2)
    val pin = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(c.x - r * 0.8f, c.y + r * 0.3f)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r),
            startAngleDegrees = 160f,
            sweepAngleDegrees = -320f,
            forceMoveTo = false,
        )
        close()
    }
    drawPath(pin, SELECT_BLUE)
    drawPath(pin, Color.White, style = Stroke(1.5f * density))
    drawCircle(Color.White, radius = r * 0.35f, center = c)
}
