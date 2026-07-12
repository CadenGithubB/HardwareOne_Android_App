package com.hardwareone.console.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ble.GpsTrack
import com.hardwareone.console.ble.HwMapFile
import com.hardwareone.console.ble.MapFeatureType
import com.hardwareone.console.ble.MapGeo
import com.hardwareone.console.ble.Waypoint
import com.hardwareone.console.ui.theme.LocalHwColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maps page — the firmware's OLED map mode + web /maps viewer, rebuilt for touch. The map itself
 * is the device's `.hwmap` file fetched over BLE and rendered natively ([MapCanvas]); waypoints,
 * GPS, tracks, and device map management ride the same CLI the other interfaces use.
 *
 * Feature availability is graded: metadata + waypoints work for any logged-in user; fetching map
 * bytes / track files (`fileread`) needs the Secure Channel + an admin login, so rendering panels
 * surface that instead of failing silently.
 */

// The web page's 26 layer toggles: group → (label, type, subtype). Subtype -1 = the whole type.
private data class LayerItem(val label: String, val type: Int, val subtype: Int)
private val LAYER_GROUPS: List<Pair<String, List<LayerItem>>> = listOf(
    "Roads" to listOf(
        LayerItem("Motorways", 0x00, 0), LayerItem("Trunk roads", 0x00, 1),
        LayerItem("Primary", 0x01, 0), LayerItem("Secondary", 0x01, 1),
        LayerItem("Tertiary", 0x02, 0), LayerItem("Residential", 0x02, 1), LayerItem("Service", 0x02, 2),
    ),
    "Paths" to listOf(
        LayerItem("Footways", 0x03, 0), LayerItem("Cycleways", 0x03, 1), LayerItem("Tracks", 0x03, 2),
    ),
    "Water" to listOf(
        LayerItem("Lakes", 0x10, 0), LayerItem("Rivers", 0x10, 1),
        LayerItem("Coastline", 0x10, 2), LayerItem("Land mask", 0x12, -1),
    ),
    "Nature" to listOf(
        LayerItem("Parks", 0x11, 0), LayerItem("Forests", 0x11, 1), LayerItem("Grassland", 0x11, 2),
    ),
    "Rail & Transit" to listOf(
        LayerItem("Rail", 0x20, 0), LayerItem("Subway", 0x20, 1),
        LayerItem("Bus routes", 0x21, -1), LayerItem("Ferries", 0x22, -1), LayerItem("Stations", 0x40, -1),
    ),
    "Buildings" to listOf(
        LayerItem("Buildings", 0x30, 0), LayerItem("Industrial", 0x30, 1),
        LayerItem("Commercial", 0x30, 2), LayerItem("Residential areas", 0x30, 3),
    ),
)

private fun layerKey(type: Int, subtype: Int) = (type shl 8) or (subtype and 0xFF)

// Whole-type toggles (no per-subtype entries in the panel).
private val WHOLE_TYPE = setOf(0x12, 0x21, 0x22, 0x40)

// Firmware `maplayers` bitmask (for "sync layers to device").
private val FIRMWARE_LAYER_BITS = mapOf(
    0x00 to 1, 0x01 to 2, 0x02 to 4, 0x03 to 8, 0x10 to 16, 0x11 to 32,
    0x20 to 64, 0x21 to 128, 0x22 to 128, 0x40 to 128, 0x30 to 256, 0x12 to 512,
)

private val TRACK_COLORS = listOf(
    Color(0xFF00E5FF), Color(0xFFFF3D71), Color(0xFF9CFF57), Color(0xFFFFB347), Color(0xFFFFFFFF),
)

@Composable
fun MapsScreen(vm: ConsoleViewModel, nav: HeaderNav) {
    val hw = LocalHwColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mapInfo by vm.mapInfo.collectAsState()
    val mapListPaths by vm.mapList.collectAsState()
    val busy by vm.mapsBusy.collectAsState()
    val error by vm.mapsError.collectAsState()
    val notice by vm.mapsNotice.collectAsState()
    val map by vm.mapFile.collectAsState()
    val mapPath by vm.mapFilePath.collectAsState()
    val download by vm.mapDownload.collectAsState()
    val waypoints by vm.mapWaypoints.collectAsState()
    val target by vm.mapTarget.collectAsState()
    val gps by vm.mapGps.collectAsState()
    val gpsUpdates by vm.mapGpsUpdates.collectAsState()
    val gpsInterval by vm.mapGpsIntervalMs.collectAsState()
    val track by vm.mapTrack.collectAsState()
    val trackFiles by vm.mapTrackFiles.collectAsState()
    val live by vm.mapLiveTracking.collectAsState()
    val uploadTransfer by vm.fileTransfer.collectAsState()

    // Keep the screen on while a map transfer is running. A full 800 KB map is ~400 windowed
    // fileread round trips (a couple of minutes); if the screen auto-sleeps, Android backgrounds
    // the app and drops the BLE link, aborting the transfer. Scoped to the transfer so the screen
    // sleeps normally the rest of the time.
    val transferActive = (download?.let { !it.finished } == true) ||
        (uploadTransfer?.let { it.upload && it.name.endsWith(".hwmap") && !it.finished } == true)
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(transferActive) {
        hostView.keepScreenOn = transferActive
        onDispose { hostView.keepScreenOn = false }
    }

    // --- Camera + render caches (reset when a different map file is parsed) ---
    val view = remember(map) { MapViewState() }
    val cache = remember(map) { map?.let { MapRenderCache(it) } }

    // --- Layer toggles (client-side render gating, default all on) ---
    val layers = remember { mutableStateMapOf<Int, Boolean>() }
    fun toggleOn(type: Int, subtype: Int): Boolean =
        layers[layerKey(type, if (type in WHOLE_TYPE) -1 else subtype)] ?: true
    val layerEnabled: (Int, Int) -> Boolean = remember { { t, s -> toggleOn(t, s) } }

    // --- Selection (search / routes) ---
    var selectedName by remember(map) { mutableStateOf<String?>(null) }
    var selectionCursor by remember(map) { mutableIntStateOf(0) }
    val selectionIndices = remember(map, selectedName) {
        val m = map
        val n = selectedName
        if (m == null || n == null) emptyList() else m.featureIndicesNamed(n)
    }

    fun centerOnFeature(idx: Int) {
        val m = map ?: return
        if (idx !in m.features.indices) return
        val f = m.features[idx]
        view.centerRelLat = f.pts[0]
        view.centerRelLon = f.pts[1]
        view.manuallyPanned = true
        if (view.zoom < 4f) view.zoom = 4f
    }

    fun select(name: String) {
        selectedName = name
        selectionCursor = 0
        map?.featureIndicesNamed(name)?.firstOrNull()?.let { centerOnFeature(it) }
    }

    // --- GPS auto-follow (OLED behavior: follow the fix until the user pans) ---
    LaunchedEffect(gps, map) {
        val m = map ?: return@LaunchedEffect
        val g = gps ?: return@LaunchedEffect
        if (g.valid && g.fix && !view.manuallyPanned && m.contains(g.lat, g.lon)) {
            view.centerRelLat = m.relLat(g.lat)
            view.centerRelLon = m.relLon(g.lon)
        }
    }

    // --- Location context bar ("On <road> • Near <area>") — computed client-side, throttled ---
    var locationCtx by remember(map) { mutableStateOf<HwMapFile.Context?>(null) }
    LaunchedEffect(gps?.lat, gps?.lon, map) {
        val m = map ?: return@LaunchedEffect
        val g = gps ?: return@LaunchedEffect
        if (!g.valid || !g.fix || !m.contains(g.lat, g.lon)) return@LaunchedEffect
        locationCtx = withContext(Dispatchers.Default) { m.locationContext(g.lat, g.lon) }
    }

    // --- Track render points (rel-degrees) ---
    var trackColor by remember { mutableStateOf(TRACK_COLORS[0]) }
    val trackRel = remember(track, map) {
        val m = map
        val t = track
        if (m == null || t == null || t.points.isEmpty()) null
        else FloatArray(t.points.size * 2).also { arr ->
            t.points.forEachIndexed { i, p ->
                arr[i * 2] = m.relLat(p.lat)
                arr[i * 2 + 1] = m.relLon(p.lon)
            }
        }
    }

    // --- Dialog state ---
    val trackFilesLoading by vm.mapTrackFilesLoading.collectAsState()
    var waypointMenuFor by remember { mutableStateOf<Waypoint?>(null) }
    var renameFor by remember { mutableStateOf<Waypoint?>(null) }
    var notesFor by remember { mutableStateOf<Waypoint?>(null) }
    var addAt by remember { mutableStateOf<Pair<Double, Double>?>(null) } // long-press add
    var showAddForm by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val mergeSelection = remember { mutableStateListOf<String>() }
    var mergeNameDialog by remember { mutableStateOf(false) }
    // Files deleted or dropped by a re-list must not stay invisibly selected for stitching.
    LaunchedEffect(trackFiles) { mergeSelection.retainAll(trackFiles.toSet()) }

    // --- File pickers: save (map/waypoints/track exports), upload .hwmap, import waypoints ---
    val downloadReady by vm.downloadReady.collectAsState()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = vm.downloadReady.value
        if (uri != null && pending != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second) } }
        }
        vm.clearDownload()
    }
    LaunchedEffect(downloadReady) { downloadReady?.let { saver.launch(it.first) } }

    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = displayName(context, uri)
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) withContext(Dispatchers.Main) { vm.uploadMapFile(name, bytes) }
        }
    }
    val waypointImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null) withContext(Dispatchers.Main) { vm.importWaypoints(text) }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppHeader(nav, busy = busy, onRefresh = { vm.loadMaps(); vm.pollMapsGps() })

                notice?.let { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hw.cardBg)
                            .border(1.dp, hw.cardBorder, RoundedCornerShape(10.dp))
                            .clickable { vm.clearMapsNotice() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            n,
                            color = if (n.startsWith("Error")) hw.danger else hw.muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("✕", color = hw.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // ---- Map viewport (or placeholder while there's nothing to render) ----
                val m = map
                val c = cache
                if (m != null && c != null) {
                    Box(
                        Modifier.fillMaxWidth().height(420.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, hw.cardBorder, RoundedCornerShape(14.dp)),
                    ) {
                        MapCanvas(
                            map = m,
                            cache = c,
                            view = view,
                            layerEnabled = layerEnabled,
                            waypoints = waypoints,
                            targetIndex = target,
                            trackRel = trackRel,
                            trackColor = trackColor,
                            gpsRel = gps?.takeIf { it.valid && it.fix }?.let { Offset(m.relLon(it.lon), m.relLat(it.lat)) },
                            selection = selectionIndices,
                            selectionCurrent = selectionIndices.getOrElse(selectionCursor) { -1 },
                            onTapWaypoint = { waypointMenuFor = it },
                            onLongPress = { lat, lon -> addAt = lat to lon },
                            modifier = Modifier.fillMaxSize(),
                        )
                        MapHud(
                            map = m,
                            view = view,
                            gpsSats = gps?.takeIf { it.connected }?.sats,
                            gpsFix = gps?.let { it.valid && it.fix } == true,
                            live = live,
                            ctx = locationCtx,
                            navTarget = waypoints.firstOrNull { it.index == target },
                            gpsPos = gps?.takeIf { it.valid && it.fix }?.let { it.lat to it.lon },
                            selectionName = selectedName,
                            selectionCount = selectionIndices.size,
                            selectionCursor = selectionCursor,
                            gpsUpdates = gpsUpdates,
                            onPrevResult = {
                                if (selectionIndices.isNotEmpty()) {
                                    selectionCursor = (selectionCursor - 1 + selectionIndices.size) % selectionIndices.size
                                    centerOnFeature(selectionIndices[selectionCursor])
                                }
                            },
                            onNextResult = {
                                if (selectionIndices.isNotEmpty()) {
                                    selectionCursor = (selectionCursor + 1) % selectionIndices.size
                                    centerOnFeature(selectionIndices[selectionCursor])
                                }
                            },
                            onClearSelection = { selectedName = null },
                            onToggleGps = {
                                val on = !gpsUpdates
                                vm.setMapGpsUpdates(on)
                                // Turning updates on resumes GPS auto-follow (recenters on the next
                                // fix); a manual pan then stops the follow again while updates flow.
                                if (on) view.manuallyPanned = false
                            },
                            onSearchSelect = { select(it) },
                        )
                    }
                } else {
                    SectionCard("Map view") {
                        when {
                            mapInfo == null && error != null -> Text(error!!, color = hw.danger, style = MaterialTheme.typography.bodyMedium)
                            mapInfo == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = hw.onGradient, strokeWidth = 2.dp)
                                Text("Asking the device about maps…", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            mapInfo?.valid == true -> {
                                Text(
                                    "\"${mapInfo?.filename}\" is loaded on the device. Download it to view and search it here (needs the Secure Channel + admin login).",
                                    color = hw.muted, style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton(onClick = { vm.downloadCurrentMap() }, text = "Download map (${sizeLabel(mapInfo?.fileSize ?: 0)})")
                                }
                            }
                            else -> Text(
                                "No map loaded on the device. Pick one below, or upload .hwmap files (created with the map tool) to /maps/.",
                                color = hw.muted, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // Transfer progress (map download or .hwmap upload).
                download?.let { d ->
                    TransferRow(
                        label = if (d.error != null) "Download failed: ${d.error}" else "Downloading ${d.name}…",
                        done = d.done, total = d.total, failed = d.error != null,
                        onCancel = { vm.cancelMapDownload() },
                    )
                }
                uploadTransfer?.takeIf { it.upload && it.name.endsWith(".hwmap") }?.let { t ->
                    TransferRow(
                        label = when {
                            t.error != null -> "Upload failed: ${t.error}"
                            t.finished -> "Uploaded ${t.name}"
                            else -> "Uploading ${t.name}…"
                        },
                        done = t.done, total = t.total, failed = t.error != null, finished = t.finished,
                        onCancel = { if (t.finished) vm.dismissTransfer() else vm.cancelTransfer() },
                    )
                }

                // ---- Maps on device ----
                Panel("Maps on device", initiallyExpanded = map == null) {
                    mapInfo?.takeIf { it.valid }?.let { info ->
                        InfoRow("Loaded", info.filename)
                        InfoRow("Region", info.region.ifEmpty { "—" })
                        InfoRow("Features", info.featureCount.toString())
                        InfoRow("Size", sizeLabel(info.fileSize))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(onClick = { vm.downloadCurrentMap() }, text = if (map == null) "Download" else "Re-download")
                            SecondaryButton(onClick = { vm.saveMapToPhone() }, text = "Save to phone")
                            SecondaryButton(onClick = { vm.deviceUnloadMap() }, text = "Unload")
                        }
                    }
                    if (mapInfo?.valid == false) {
                        Text("No map loaded on the device.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (mapListPaths.isEmpty()) {
                        Text("No maps found in /maps/.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    mapListPaths.forEach { path ->
                        val isLoaded = mapInfo?.valid == true && path.endsWith("/${mapInfo?.filename}")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                path.removePrefix("/maps/"),
                                color = if (isLoaded) hw.onGradient else hw.muted,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            if (isLoaded) {
                                Text("loaded", color = hw.success, style = MaterialTheme.typography.labelMedium)
                            } else {
                                TextButton(onClick = { vm.deviceLoadMap(path) }) { Text("Load", color = hw.link) }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(onClick = { mapPicker.launch("*/*") }, text = "Upload .hwmap")
                        SecondaryButton(onClick = { vm.organizeMaps() }, text = "Organize /maps")
                    }
                    Text(
                        "BLE uploads cap at 256 KB — use the device's web page for bigger maps.",
                        color = hw.muted, style = MaterialTheme.typography.labelSmall,
                    )
                }

                // ---- Layers ----
                Panel("Layers") {
                    LAYER_GROUPS.forEach { (group, items) ->
                        Text(group, color = hw.onGradient, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        items.forEach { item ->
                            val k = layerKey(item.type, item.subtype)
                            val present = m == null || layerAvailable(m, item)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = present) { layers[k] = !(layers[k] ?: true) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = (layers[k] ?: true) && present,
                                    onCheckedChange = { layers[k] = it },
                                    enabled = present,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = hw.accent, uncheckedColor = hw.muted, checkmarkColor = hw.onGradient,
                                    ),
                                )
                                Text(
                                    if (present) item.label else "${item.label} (not in this map)",
                                    color = if (present) hw.onGradient else hw.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendDot(Color(0xFFFF3333)); Text("GPS", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                        LegendDot(Color(0xFFFFD93D)); Text("Waypoint", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                        LegendDot(trackColor); Text("Track", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // ---- Waypoints ----
                Panel("Waypoints (${waypoints.size}/16)") {
                    val tgt = waypoints.firstOrNull { it.index == target }
                    InfoRow("Nav target", tgt?.name ?: "none")
                    waypoints.forEach { wp ->
                        Row(
                            Modifier.fillMaxWidth().clickable { waypointMenuFor = wp }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(wp.name, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    if (wp.index == target) Text("★", color = hw.accent, style = MaterialTheme.typography.labelMedium)
                                    if (wp.notes.isNotEmpty()) Text("📝", style = MaterialTheme.typography.labelSmall)
                                    if (wp.files.isNotEmpty()) Text("📎${wp.files.size}", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    "%.5f, %.5f".format(wp.lat, wp.lon),
                                    color = hw.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text("⋮", color = hw.muted, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(onClick = { showAddForm = true }, text = "Add")
                        SecondaryButton(onClick = { vm.exportWaypoints() }, text = "Export")
                        SecondaryButton(onClick = { waypointImporter.launch("application/json") }, text = "Import")
                        if (waypoints.isNotEmpty()) SecondaryButton(onClick = { confirmClearAll = true }, text = "Clear all")
                    }
                    Text(
                        "Tip: long-press the map to drop a waypoint there.",
                        color = hw.muted, style = MaterialTheme.typography.labelSmall,
                    )
                }

                // ---- GPS track ----
                Panel("GPS track") {
                    track?.let { t ->
                        InfoRow("Track", t.name + if (live) " (LIVE)" else "")
                        InfoRow("Points", t.points.size.toString())
                        InfoRow("Distance", if (t.distanceM < 1000) "${t.distanceM.toInt()} m" else "%.2f km".format(t.distanceM / 1000))
                        if (t.durationS > 0) {
                            InfoRow("Duration", "%d:%02d".format((t.durationS / 60).toInt(), (t.durationS % 60).toInt()))
                            InfoRow("Avg speed", "%.1f m/s".format(t.distanceM / t.durationS))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!live) SecondaryButton(onClick = { vm.startLiveTrack() }, text = "Start live")
                        else PrimaryButton(onClick = { vm.stopLiveTrack() }, text = "Stop live")
                        if (track != null) {
                            SecondaryButton(onClick = { vm.exportMapTrack() }, text = "Export")
                            SecondaryButton(onClick = { vm.clearMapTrack() }, text = "Clear")
                        }
                        SecondaryButton(
                            onClick = { if (!trackFilesLoading) vm.loadMapTrackFiles() },
                            text = if (trackFilesLoading) "Listing…" else "List device logs",
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Color", color = hw.muted, style = MaterialTheme.typography.labelMedium)
                        TRACK_COLORS.forEach { col ->
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(col)
                                    .border(if (col == trackColor) 2.dp else 1.dp, if (col == trackColor) hw.onGradient else hw.cardBorder, CircleShape)
                                    .clickable { trackColor = col },
                            )
                        }
                    }
                    if (trackFiles.isNotEmpty()) {
                        Text("Device log files (tap to load):", color = hw.muted, style = MaterialTheme.typography.labelMedium)
                        trackFiles.forEach { path ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val checked = path in mergeSelection
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        when {
                                            !it -> mergeSelection.remove(path)
                                            // Firmware tokenizer caps gpstrackmerge at 9 inputs.
                                            mergeSelection.size >= 9 -> vm.reportMapsNotice(
                                                "Max 9 files per stitch — stitch these first, then stitch the result with the rest.",
                                            )
                                            else -> mergeSelection.add(path)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = hw.accent, uncheckedColor = hw.muted, checkmarkColor = hw.onGradient),
                                )
                                if (checked) {
                                    Text(
                                        "${mergeSelection.indexOf(path) + 1}",
                                        color = hw.accent, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Text(
                                    path.removePrefix("/logging_captures/"),
                                    color = hw.onGradient, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f).clickable { vm.loadMapTrack(path) }.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
                                )
                                TextButton(onClick = { mergeSelection.remove(path); vm.deleteMapTrackFile(path) }) { Text("Del", color = hw.danger) }
                            }
                        }
                        if (mergeSelection.size >= 2) {
                            SecondaryButton(onClick = { mergeNameDialog = true }, text = "Stitch ${mergeSelection.size} files…")
                            Text(
                                "Tick order = stitch order (max 9 files).",
                                color = hw.muted, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // ---- Transit routes ----
                Panel("Transit routes") {
                    if (m == null) {
                        Text("Download the map to list its transit routes.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val routes = remember(m) { m.transitRoutes() }
                        if (routes.isEmpty()) Text("No named transit routes in this map.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                        routes.forEach { (name, type, _) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { select(name) }.padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val (tag, col) = when (type) {
                                    MapFeatureType.RAILWAY -> "R" to Color(0xFFDA77F2)
                                    MapFeatureType.BUS -> "B" to Color(0xFFFAB005)
                                    else -> "F" to Color(0xFF15AABF)
                                }
                                Text(tag, color = col, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(name, color = if (name == selectedName) hw.accent else hw.onGradient, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // ---- Map info ----
                Panel("Map info") {
                    val info = mapInfo
                    if (info?.valid == true) {
                        InfoRow("File", info.filename)
                        InfoRow("Region", info.region.ifEmpty { "—" })
                        InfoRow("Features", info.featureCount.toString())
                        InfoRow("Size", sizeLabel(info.fileSize))
                        InfoRow("Bounds", "%.4f,%.4f → %.4f,%.4f".format(info.minLat, info.minLon, info.maxLat, info.maxLon))
                    } else {
                        Text("No map loaded on the device.", color = hw.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    m?.let {
                        InfoRow("Parsed features", it.features.size.toString())
                        InfoRow("Named features", it.names.size.toString())
                    }
                }

                // ---- GPS updates (app-side polling over BLE) ----
                Panel("GPS updates") {
                    Text(
                        "How often the app reads your position from the device over Bluetooth. The " +
                            "satellite button on the map toggles this on and off too.",
                        color = hw.muted, style = MaterialTheme.typography.labelSmall,
                    )
                    SwitchRow("Poll GPS over Bluetooth", gpsUpdates) {
                        vm.setMapGpsUpdates(it)
                        if (it) view.manuallyPanned = false // resume follow when re-enabled
                    }
                    Text("Update rate", color = hw.muted, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2_000 to "2s", 5_000 to "5s", 10_000 to "10s", 30_000 to "30s").forEach { (ms, lbl) ->
                            val sel = gpsInterval == ms
                            Text(
                                lbl,
                                color = hw.onGradient,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) hw.accent else Color.Transparent)
                                    .then(if (sel) Modifier else Modifier.border(1.dp, hw.muted, RoundedCornerShape(6.dp)))
                                    .clickable { vm.setMapGpsInterval(ms) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                // ---- Device settings (admin) ----
                Panel("Device settings") {
                    Text(
                        "Persisted on the device (admin): the OLED map's default zoom, visible layers, and tile-cache size.",
                        color = hw.muted, style = MaterialTheme.typography.labelSmall,
                    )
                    SwitchRow("GPS sensor", gps?.connected == true) { vm.setMapsGpsPower(it) }
                    var zoomText by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = zoomText, onValueChange = { zoomText = it },
                            label = { Text("Zoom 0.5–20") }, singleLine = true,
                            modifier = Modifier.weight(1f), colors = mapsFieldColors(),
                        )
                        SecondaryButton(
                            onClick = { zoomText.toFloatOrNull()?.let { vm.setDeviceMapZoom(it.coerceIn(0.5f, 20f)) } },
                            text = "Set",
                        )
                    }
                    var cacheText by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cacheText, onValueChange = { cacheText = it },
                            label = { Text("Cache KB 256–4096") }, singleLine = true,
                            modifier = Modifier.weight(1f), colors = mapsFieldColors(),
                        )
                        SecondaryButton(
                            onClick = { cacheText.toIntOrNull()?.let { vm.setDeviceMapCacheKb(it.coerceIn(256, 4096)) } },
                            text = "Set",
                        )
                    }
                    SecondaryButton(
                        onClick = {
                            var mask = 0
                            for ((_, items) in LAYER_GROUPS) for (item in items) {
                                if (layers[layerKey(item.type, item.subtype)] != false) {
                                    mask = mask or (FIRMWARE_LAYER_BITS[item.type] ?: 0)
                                }
                            }
                            vm.setDeviceMapLayers(mask)
                        },
                        text = "Sync layers to device",
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ---- Dialogs ----

    waypointMenuFor?.let { wp ->
        AlertDialog(
            onDismissRequest = { waypointMenuFor = null },
            title = { Text(wp.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("%.6f, %.6f".format(wp.lat, wp.lon), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    if (wp.notes.isNotEmpty()) Text(wp.notes, style = MaterialTheme.typography.bodyMedium)
                    wp.files.forEach { Text("📎 $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    Spacer(Modifier.height(6.dp))
                    map?.let { mm ->
                        TextButton(onClick = {
                            view.centerRelLat = mm.relLat(wp.lat)
                            view.centerRelLon = mm.relLon(wp.lon)
                            view.manuallyPanned = true
                            if (view.zoom < 4f) view.zoom = 4f
                            waypointMenuFor = null
                        }) { Text("Center on map") }
                    }
                    if (wp.index == target) {
                        TextButton(onClick = { vm.clearWaypointTarget(); waypointMenuFor = null }) { Text("Clear nav target") }
                    } else {
                        TextButton(onClick = { vm.gotoWaypoint(wp.index); waypointMenuFor = null }) { Text("Set as nav target") }
                    }
                    TextButton(onClick = { renameFor = wp; waypointMenuFor = null }) { Text("Rename") }
                    TextButton(onClick = { notesFor = wp; waypointMenuFor = null }) { Text("Edit notes") }
                    TextButton(onClick = { vm.deleteWaypoint(wp.index); waypointMenuFor = null }) { Text("Delete", color = hw.danger) }
                }
            },
            confirmButton = { TextButton(onClick = { waypointMenuFor = null }) { Text("Close") } },
        )
    }

    renameFor?.let { wp ->
        TextFieldDialog(
            title = "Rename \"${wp.name}\"", label = "New name (≤11 chars)", initial = wp.name,
            onDismiss = { renameFor = null },
        ) { vm.renameWaypoint(wp.index, it); renameFor = null }
    }

    notesFor?.let { wp ->
        TextFieldDialog(
            title = "Notes for \"${wp.name}\"", label = "Notes (≤255 chars)", initial = wp.notes,
            onDismiss = { notesFor = null },
        ) { vm.setWaypointNotes(wp.index, it); notesFor = null }
    }

    if (showAddForm || addAt != null) {
        val prefill = addAt
        AddWaypointDialog(
            initialLat = prefill?.first ?: gps?.takeIf { it.valid && it.fix }?.lat
                ?: map?.let { it.centerLat + view.centerRelLat.toDouble() },
            initialLon = prefill?.second ?: gps?.takeIf { it.valid && it.fix }?.lon
                ?: map?.let { it.centerLon + view.centerRelLon.toDouble() },
            onDismiss = { showAddForm = false; addAt = null },
            onAdd = { lat, lon, name, notes ->
                vm.addWaypoint(lat, lon, name, notes)
                showAddForm = false
                addAt = null
            },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all waypoints?") },
            text = { Text("Delete every waypoint on this map. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.clearAllWaypoints(); confirmClearAll = false }) { Text("Clear all", color = hw.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }

    if (mergeNameDialog) {
        TextFieldDialog(
            title = "Stitch ${mergeSelection.size} log files", label = "Output name (e.g. trip1)", initial = "",
            onDismiss = { mergeNameDialog = false },
        ) { name ->
            if (name.isNotBlank()) vm.mergeMapTracks(name.trim(), mergeSelection.toList())
            mergeSelection.clear()
            mergeNameDialog = false
        }
    }
}

/** True when the map's header/feature scan says this layer item exists in the file. */
private fun layerAvailable(map: HwMapFile, item: LayerItem): Boolean {
    // Header featureTypeMask bit per type (bit index mirrors the firmware HWMAP_FTYPE_* order).
    val bitIndex = when (item.type) {
        0x00 -> 0; 0x01 -> 1; 0x02 -> 2; 0x03 -> 3
        0x10 -> 4; 0x11 -> 5; 0x12 -> 6
        0x20 -> 7; 0x21 -> 8; 0x22 -> 9
        0x30 -> 10; 0x40 -> 11
        else -> -1
    }
    val headerSaysNo = map.featureTypeMask != 0 && bitIndex >= 0 && (map.featureTypeMask and (1 shl bitIndex)) == 0
    if (headerSaysNo) return false
    return if (item.subtype < 0) map.hasType(item.type)
    else map.availability.contains((item.type shl 8) or item.subtype) || map.featureTypeMask != 0
}

// --- HUD: chips + control buttons drawn over the canvas -------------------------------------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MapHud(
    map: HwMapFile,
    view: MapViewState,
    gpsSats: Int?,
    gpsFix: Boolean,
    live: Boolean,
    ctx: HwMapFile.Context?,
    navTarget: Waypoint?,
    gpsPos: Pair<Double, Double>?,
    selectionName: String?,
    selectionCount: Int,
    selectionCursor: Int,
    gpsUpdates: Boolean,
    onPrevResult: () -> Unit,
    onNextResult: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleGps: () -> Unit,
    onSearchSelect: (String) -> Unit,
) {
    val hw = LocalHwColors.current

    // Top strip: search button + GPS context (left, with a search field that expands below the
    // button), zoom/angle/sats (right). Top-aligned so the search field grows downward.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                HudSearchButton(active = searchOpen) { searchOpen = !searchOpen }
                // GPS-updates toggle sits next to search so the two icon buttons read as a group.
                HudSatelliteButton(active = gpsUpdates, hasFix = gpsFix, onClick = onToggleGps)
                // GPS context only ("On <road> • Near <area>"); the generic region name is gone —
                // the search button took its place.
                val gpsCtx = if (gpsFix && ctx != null && (ctx.road != null || ctx.area != null)) buildString {
                    ctx.road?.let { append("On $it") }
                    ctx.area?.let {
                        if (isNotEmpty()) append(" • ")
                        append("Near $it")
                        if (ctx.areaDistanceM < 500) append(" (${ctx.areaDistanceM.toInt()}m)")
                    }
                } else null
                HudChip(gpsCtx ?: "", visible = gpsCtx != null, modifier = Modifier.weight(1f, fill = false))
            }
            if (searchOpen) {
                HudSearchField(
                    map = map,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSelect = { onSearchSelect(it); searchOpen = false; searchQuery = "" },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HudChip("%.1f×".format(view.zoom), visible = true, onClick = { view.zoom = 1f })
            HudChip("${view.rotationDeg.toInt()}°", visible = true, onClick = { view.rotationDeg = 0f })
            HudChip("${gpsSats}S", visible = gpsSats != null, tint = if (gpsFix) hw.success else hw.muted)
        }
    }
    // Pan / zoom / rotate are gesture-only (drag, pinch, two-finger twist); the zoom/angle chips
    // up top tap to reset. The GPS toggle now lives next to search in the top strip.

    // Bottom strip: LIVE + nav readout left, selected-feature panel right (name + X, with a
    // segment cycler below it when the selection spans more than one segment).
    Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HudChip("LIVE", visible = live, tint = LocalHwColors.current.danger)
            // Computed HERE so the per-frame view.center reads during a pan invalidate only this
            // small HUD scope, not the whole page.
            val navReadout = navTarget?.let { wp ->
                val fromLat = gpsPos?.first ?: (map.centerLat + view.centerRelLat)
                val fromLon = gpsPos?.second ?: (map.centerLon + view.centerRelLon)
                "${wp.name} ${MapGeo.navLabel(fromLat, fromLon, wp.lat, wp.lon)}"
            }
            HudChip(navReadout ?: "", visible = navReadout != null)
        }
        if (selectionCount >= 1) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The segment cycler sits above the name so the name (and its ✕) stay pinned to
                // the bottom edge next to nothing else.
                if (selectionCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        HudButton("‹", onClick = onPrevResult)
                        HudChip("${selectionCursor + 1}/$selectionCount", visible = true)
                        HudButton("›", onClick = onNextResult)
                    }
                }
                // Name of the selected feature + an inline ✕. Tapping the ✕ clears everything —
                // name, cycler, and the map pinpoint — back to nothing-selected.
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectionName ?: "Selected",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 168.dp).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    )
                    Text(
                        "✕",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onClearSelection)
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HudChip(
    text: String,
    visible: Boolean,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, // when set, the chip is tappable (e.g. tap zoom/angle to reset)
) {
    if (!visible) return
    Text(
        text,
        color = tint ?: Color.White,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xB3000000))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun HudButton(text: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color(0xB3000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint, style = MaterialTheme.typography.titleMedium)
    }
}

// --- Pixel-art HUD glyphs --------------------------------------------------------------------

// Hand-placed monochrome pixel glyphs in the spirit of the firmware map tool's custom shape
// buttons — not emoji or vector icons. '1' = filled cell; grids may be any width×height.

// Magnifying glass (13×13): a centered hollow ring with a short handle, inset from the corners so
// it can render as large as the satellite inside the circular button without the handle clipping.
private val MAGNIFIER_PIXELS = listOf(
    "0000000000000",
    "0001111000000",
    "0010000100000",
    "0100000010000",
    "0100000010000",
    "0100000010000",
    "0010000110000",
    "0001111011000",
    "0000000011100",
    "0000000001100",
    "0000000000000",
    "0000000000000",
    "0000000000000",
)

// Satellite (13×13): antenna mast + crossbars, a central body, and solar wings joined by a
// full-width boom — one connected shape (no floating pixels) that fills the button.
private val SATELLITE_PIXELS = listOf(
    "0000001000000",
    "0000001000000",
    "0001111111000",
    "0000001000000",
    "0000011100000",
    "0000011100000",
    "1111111111111",
    "1111111111111",
    "0000011100000",
    "0000011100000",
    "0000001000000",
    "0001111111000",
    "0000001000000",
)

@Composable
private fun PixelGlyph(rows: List<String>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val nRows = rows.size
        val nCols = rows.maxOf { it.length }
        val cell = minOf(size.width / nCols, size.height / nRows)
        val ox = (size.width - cell * nCols) / 2f // center the glyph in its box
        val oy = (size.height - cell * nRows) / 2f
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == '1') {
                    drawRect(color, topLeft = Offset(ox + c * cell, oy + r * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

/** Round HUD button carrying the pixel magnifier; accent-filled when the search field is open. */
@Composable
private fun HudSearchButton(active: Boolean, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (active) hw.accent else Color(0xB3000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PixelGlyph(
            rows = MAGNIFIER_PIXELS,
            color = if (active) hw.onGradient else Color.White,
            modifier = Modifier.size(30.dp), // matches the satellite glyph so the two buttons pair
        )
    }
}

/**
 * Round HUD button carrying the pixel satellite; toggles GPS updates over BLE. Dark when off,
 * accent when on, green once there's a fix.
 */
@Composable
private fun HudSatelliteButton(active: Boolean, hasFix: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    // Off = dark; on-but-acquiring = yellow; on-with-fix = green. The bright fills need a dark glyph.
    val bg = when {
        !active -> Color(0xB3000000)
        hasFix -> hw.success
        else -> Color(0xFFFAB005)
    }
    Box(
        modifier.size(34.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PixelGlyph(
            rows = SATELLITE_PIXELS,
            color = if (active) Color(0xEE121212) else Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** In-map name search: a compact HUD text field + tappable results, wired to [onSelect]. */
@Composable
private fun HudSearchField(
    map: HwMapFile,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val hw = LocalHwColors.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, map) { map.searchNames(query) }
    Column(
        Modifier.width(212.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xD9000000)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = Color.White),
            cursorBrush = SolidColor(hw.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 6.dp, vertical = 6.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search roads, water, parks…",
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                inner()
            },
        )
        when {
            results.isNotEmpty() -> results.take(8).forEach { name ->
                Text(
                    name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .clickable { onSelect(name) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            query.trim().length >= 2 -> Text(
                "No matches",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

// --- Small shared pieces ---------------------------------------------------------------------

@Composable
private fun Panel(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val hw = LocalHwColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = hw.onGradient, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (expanded) "▴" else "▾", color = hw.muted)
        }
        if (expanded) content()
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun TransferRow(label: String, done: Long, total: Long, failed: Boolean, onCancel: () -> Unit, finished: Boolean = false) {
    val hw = LocalHwColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (failed) hw.danger else hw.onGradient,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(if (failed || finished) "Dismiss" else "Cancel", color = hw.link) }
        }
        if (!failed && total > 0) {
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = hw.accent,
            )
            Text(
                "${sizeLabel(done)} / ${sizeLabel(total)}",
                color = hw.muted, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSubmit(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddWaypointDialog(
    initialLat: Double?,
    initialLon: Double?,
    onDismiss: () -> Unit,
    onAdd: (lat: Double, lon: Double, name: String, notes: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(initialLat?.let { "%.6f".format(java.util.Locale.US, it) } ?: "") }
    var lon by remember { mutableStateOf(initialLon?.let { "%.6f".format(java.util.Locale.US, it) } ?: "") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add waypoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (≤11 chars)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Lat") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Lon") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val la = lat.toDoubleOrNull()
                val lo = lon.toDoubleOrNull()
                if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                    onAdd(la, lo, name, notes)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun mapsFieldColors(): androidx.compose.material3.TextFieldColors {
    val hw = LocalHwColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = hw.onGradient,
        unfocusedTextColor = hw.onGradient,
        focusedBorderColor = hw.accent,
        unfocusedBorderColor = hw.cardBorder,
        cursorColor = hw.onGradient,
        focusedLabelColor = hw.muted,
        unfocusedLabelColor = hw.muted,
    )
}

private fun sizeLabel(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024))
}

private fun displayName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.hwmap"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
        }
    }
    return name
}
