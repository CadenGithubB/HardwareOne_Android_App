package com.hardwareone.console.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hardwareone.console.R
import com.hardwareone.console.ble.ConnectionState
import com.hardwareone.console.ui.theme.HwColors
import com.hardwareone.console.ui.theme.LocalHwColors

/** The two top-level pages the header toggle flips between. */
enum class AppPage { CONSOLE, DEVICES }

/**
 * Everything the shared header needs: which segment is active, page-navigation, and the
 * dropdown actions. The **Devices** segment's dropdown holds the device tools (Status / Sensors /
 * LLM / Files / Sync clock) — all account-gated, so it only appears once [loggedIn]; until then
 * tapping Devices just jumps to the Devices page. The **Console** segment's dropdown (log actions)
 * is login-agnostic. [onOpenDevices] appears in the Devices dropdown only on the *tool* pages (a
 * route back to the device list); it's null on the Devices list page itself.
 */
class HeaderNav(
    val active: AppPage,
    val onSelect: (AppPage) -> Unit,
    val onOpenSettings: () -> Unit,
    val loggedIn: Boolean = false,
    // The Devices segment shows the current device page's name (e.g. "Status", "Files") so the
    // switcher doubles as the page title; "Devices" on the list page itself.
    val devicesLabel: String = "Devices",
    val onOpenDevices: (() -> Unit)? = null,
    val onOpenStatus: (() -> Unit)? = null,
    val onOpenSensors: (() -> Unit)? = null,
    val onOpenLlm: (() -> Unit)? = null,
    val onOpenFiles: (() -> Unit)? = null,
    val onSyncClock: (() -> Unit)? = null,
    val onSaveLog: (() -> Unit)? = null,
    val onClearLog: (() -> Unit)? = null,
)

/**
 * Shared top-of-page header: the switcher (left) — which now names the current page, so it
 * doubles as the title — then, just left of the settings gear, an activity [Spinner] while
 * [busy] or a refresh button when [onRefresh] is given and not busy.
 */
@Composable
fun AppHeader(
    nav: HeaderNav,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hw = LocalHwColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageToggle(nav)
        Spacer(Modifier.weight(1f))
        actions()
        when {
            busy -> Spinner()
            onRefresh != null -> IconButton(onClick = onRefresh) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh", tint = hw.onGradient)
            }
        }
        GearButton(nav.onOpenSettings)
    }
}

/**
 * The page switcher / nav hub, top-left on every page.
 *
 * **Devices** is a multi-page hub: tapping it *always* opens its dropdown (Devices list +
 * Status/Sensors/LLM/Files/Sync clock), so the device tools are reachable from any page —
 * including the Console. **Console** is a plain page: tapping it when inactive switches to it,
 * tapping it when active opens its log actions (Save/Clear). A ▾ marks a segment that opens a menu.
 */
// Every label either switcher segment can show (the Devices segment's possible page names — keep
// in sync with MainActivity's `devicesLabel` mapping — plus "Console"). BOTH segments reserve the
// widest of these, so the switcher is a fixed size, never resizes as you move between pages, and
// the two segments are always the same width as each other.
private val SWITCHER_LABELS = listOf("Devices", "Status", "Sensors", "LLM Chat", "Files", "Console")

@Composable
fun PageToggle(nav: HeaderNav) {
    val hw = LocalHwColors.current
    var devMenu by remember { mutableStateOf(false) }
    var conMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
    ) {
        Box {
            if (nav.loggedIn) {
                // Logged in → Devices is a dropdown hub for every device page.
                PageToggleSegment(nav.devicesLabel, selected = nav.active == AppPage.DEVICES, caret = true, expanded = devMenu, reserveLabels = SWITCHER_LABELS) {
                    devMenu = true
                }
                DropdownMenu(expanded = devMenu, onDismissRequest = { devMenu = false }) {
                    nav.onOpenDevices?.let { go -> DropdownMenuItem(text = { Text("Devices") }, onClick = { devMenu = false; go() }) }
                    nav.onOpenStatus?.let { go -> DropdownMenuItem(text = { Text("Status") }, onClick = { devMenu = false; go() }) }
                    nav.onOpenSensors?.let { go -> DropdownMenuItem(text = { Text("Sensors") }, onClick = { devMenu = false; go() }) }
                    nav.onOpenLlm?.let { go -> DropdownMenuItem(text = { Text("LLM Chat") }, onClick = { devMenu = false; go() }) }
                    nav.onOpenFiles?.let { go -> DropdownMenuItem(text = { Text("Files") }, onClick = { devMenu = false; go() }) }
                    nav.onSyncClock?.let { go -> DropdownMenuItem(text = { Text("Sync clock") }, onClick = { devMenu = false; go() }) }
                }
            } else {
                // Not logged in → no device tools to branch to; just jump to the Devices page.
                PageToggleSegment(nav.devicesLabel, selected = nav.active == AppPage.DEVICES, caret = false, expanded = false, reserveLabels = SWITCHER_LABELS) {
                    nav.onSelect(AppPage.DEVICES)
                }
            }
        }
        Box {
            PageToggleSegment("Console", selected = nav.active == AppPage.CONSOLE, caret = nav.active == AppPage.CONSOLE, expanded = conMenu, reserveLabels = SWITCHER_LABELS) {
                if (nav.active != AppPage.CONSOLE) nav.onSelect(AppPage.CONSOLE) else conMenu = true
            }
            DropdownMenu(expanded = conMenu, onDismissRequest = { conMenu = false }) {
                nav.onSaveLog?.let { go -> DropdownMenuItem(text = { Text("Save log") }, onClick = { conMenu = false; go() }) }
                nav.onClearLog?.let { go -> DropdownMenuItem(text = { Text("Clear log") }, onClick = { conMenu = false; go() }) }
            }
        }
    }
}

@Composable
private fun PageToggleSegment(
    label: String,
    selected: Boolean,
    caret: Boolean,
    expanded: Boolean,
    reserveLabels: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val hw = LocalHwColors.current
    // The caret is its own element so it can spin: at rest it points right (▸); 90° clockwise
    // when its dropdown opens it points down, and back to 0° (right) when it closes. Its slot is
    // always laid out (transparent when hidden) so showing/hiding it never resizes the segment.
    val caretAngle by animateFloatAsState(if (expanded) 90f else 0f, label = "caretSpin")
    val style = MaterialTheme.typography.labelLarge
    // Selected pill is white → plain black text; unselected sits on the gradient → outlined white.
    val color = if (selected) Color.Black else hw.onGradient

    // One renderer for both styles so the invisible width-reservation sizers measure exactly like
    // the visible text.
    @Composable
    fun seg(text: String, modifier: Modifier = Modifier) {
        if (selected) Text(text, color = color, style = style, fontWeight = FontWeight.SemiBold, modifier = modifier)
        else OutlinedText(text, color = color, style = style, modifier = modifier)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) hw.onGradient else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Invisible sizers reserve the widest (label + caret) across the whole switcher, so the
            // segment is a fixed size and both segments match each other.
            reserveLabels.forEach { lbl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    seg(lbl, Modifier.alpha(0f))
                    seg("▸", Modifier.padding(start = 4.dp).alpha(0f))
                }
            }
            // Visible content, centred as a group so it never looks lopsided. The caret expands/
            // shrinks in place, so the label glides over to stay centred rather than teleporting.
            Row(verticalAlignment = Alignment.CenterVertically) {
                seg(label)
                AnimatedVisibility(
                    visible = caret,
                    // Slow, deliberate glide (the default spring felt like a teleport).
                    enter = fadeIn(tween(300)) + expandHorizontally(tween(300)),
                    exit = fadeOut(tween(300)) + shrinkHorizontally(tween(300)),
                ) {
                    seg("▸", Modifier.padding(start = 4.dp).rotate(caretAngle))
                }
            }
        }
    }
}

/**
 * Text with a thin black outline (a stroked copy behind the filled text) — improves readability
 * of the header controls on the light gradient. Near-invisible on the dark theme, by design.
 */
@Composable
internal fun OutlinedText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    outline: Color = Color.Black,
) {
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Box(modifier) {
        Text(
            text = text,
            color = outline,
            fontWeight = fontWeight,
            style = style.copy(drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round)),
        )
        Text(text = text, color = color, fontWeight = fontWeight, style = style)
    }
}

@Composable
internal fun GearButton(onOpenSettings: () -> Unit) {
    IconButton(onClick = onOpenSettings) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = "Settings",
            tint = LocalHwColors.current.onGradient,
        )
    }
}

/** Solid light button with accent text — the primary action, high-contrast on the gradient. */
@Composable
internal fun PrimaryButton(onClick: () -> Unit, enabled: Boolean = true, text: String) {
    val hw = LocalHwColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = hw.onGradient,
            contentColor = hw.accent,
            disabledContainerColor = hw.cardBg,
            disabledContentColor = hw.muted,
        ),
    ) {
        // Plain black text on the light button (not accent/"purple"). Disabled inherits the
        // button's muted content colour.
        Text(text, color = if (enabled) Color.Black else Color.Unspecified)
    }
}

/** Frosted outlined button — secondary actions. */
@Composable
internal fun SecondaryButton(onClick: () -> Unit, text: String) {
    val hw = LocalHwColors.current
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, hw.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = hw.onGradient),
    ) { Text(text) }
}

@Composable
internal fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = LocalHwColors.current.onGradient,
        strokeWidth = 2.dp,
    )
}

internal fun statusLabel(
    state: ConnectionState,
    authenticated: Boolean,
    user: String? = null,
): String = when (state) {
    is ConnectionState.Disconnected -> "● disconnected"
    is ConnectionState.Scanning -> "◌ scanning"
    is ConnectionState.Connecting -> "◌ connecting"
    is ConnectionState.DiscoveringServices -> "◌ discovering"
    is ConnectionState.NegotiatingMtu -> "◌ mtu"
    is ConnectionState.EnablingNotifications -> "◌ subscribing"
    is ConnectionState.Securing -> "◌ securing"
    is ConnectionState.Ready ->
        if (authenticated) "● online${if (!user.isNullOrBlank()) " as $user" else ""}"
        else "● connected — login required"
    is ConnectionState.Failed -> "● ${state.reason}"
}

/** A small battery glyph filled to [percent], colour-coded, with a charging bolt overlay. */
@Composable
internal fun BatteryGlyph(percent: Int, charging: Boolean) {
    val hw = LocalHwColors.current
    val frac = percent.coerceIn(0, 100) / 100f
    val fill = when {
        charging -> hw.success
        percent < 0 -> hw.muted
        percent < 15 -> hw.danger
        percent < 40 -> Color(0xFFE0A800) // amber
        else -> hw.success
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.5.dp, hw.onGradient, RoundedCornerShape(4.dp))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (percent >= 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(frac)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fill),
                )
            }
            if (charging) {
                // Yellow bolt with an even thin black outline — a single drawable whose path has
                // a yellow fill + black stroke (rendered untinted so both colours show).
                Image(
                    painter = painterResource(R.drawable.ic_bolt_charging),
                    contentDescription = "charging",
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 1.dp)
                .size(width = 2.5.dp, height = 7.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(hw.onGradient),
        )
    }
}

internal fun statusColor(state: ConnectionState, hw: HwColors): Color = when (state) {
    is ConnectionState.Ready -> hw.success
    is ConnectionState.Failed -> hw.danger
    is ConnectionState.Disconnected -> hw.muted
    else -> hw.onGradient
}
