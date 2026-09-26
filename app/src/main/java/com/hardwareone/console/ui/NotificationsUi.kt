package com.hardwareone.console.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hardwareone.console.R
import com.hardwareone.console.ble.DeviceNotification
import com.hardwareone.console.ui.theme.LocalHwColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(14.dp)
private val Amber = Color(0xFFFFC107)

/** Accent for a card's importance. [DeviceNotification.Level.INFO] is the fallback for any
 *  level a future firmware adds, so it must stay visually neutral. */
@Composable
private fun accentFor(level: DeviceNotification.Level): Color {
    val hw = LocalHwColors.current
    return when (level) {
        DeviceNotification.Level.INFO -> hw.link
        DeviceNotification.Level.SUCCESS -> hw.success
        DeviceNotification.Level.WARNING -> Amber
        DeviceNotification.Level.ERROR -> hw.danger
    }
}

/** Header bell with an unread count. Shown on every page — the device already decided these
 *  cards are interrupt-worthy, so the list must be reachable from wherever you are. */
@Composable
fun NotificationBell(unread: Int, onClick: () -> Unit) {
    val hw = LocalHwColors.current
    Box(contentAlignment = Alignment.TopEnd) {
        IconButton(onClick = onClick) {
            Icon(
                painter = painterResource(R.drawable.ic_bell),
                contentDescription = if (unread > 0) "Alerts ($unread unread)" else "Alerts",
                tint = hw.onGradient,
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp, end = 4.dp)
                    .clip(CircleShape)
                    .background(hw.danger)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (unread > 99) "99+" else unread.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * The transient banner, overlaid on whatever page is showing.
 *
 * Tap opens the full list; the × dismisses and lets the next queued card through. Auto-dismiss
 * is driven by the ViewModel (the device suggests a duration), not by this composable, so the
 * timer survives recomposition and page changes.
 */
@Composable
fun NotificationBanner(
    card: DeviceNotification?,
    onDismiss: () -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hw = LocalHwColors.current
    // Hold the last card so the exit animation has something to draw: by the time it plays,
    // [card] is already null and the content would otherwise slide out blank.
    var shown by remember { mutableStateOf<DeviceNotification?>(null) }
    if (card != null) shown = card
    AnimatedVisibility(
        visible = card != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val shownCard = shown ?: return@AnimatedVisibility
        val accent = accentFor(shownCard.level)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(CardShape)
                .background(hw.terminalBg)
                .border(1.dp, accent, CardShape)
                .clickable { onOpenAll() }
                .padding(start = 0.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 4.dp, height = 40.dp).background(accent))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = shownCard.title,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    // The device truncates `msg` to fit one BLE frame, so this can be a partial
                    // sentence. Wrap over two lines rather than clipping it further.
                    text = shownCard.msg,
                    color = hw.terminalFg,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Text("✕", color = hw.muted, fontSize = 16.sp)
            }
        }
    }
}

/**
 * The notification centre: everything received during this login, newest first.
 *
 * Cleared on logout and disconnect — cards passed the *logged-in user's* filters on the device,
 * so they belong to that session. The console log keeps the durable copy.
 */
@Composable
fun NotificationsScreen(
    cards: List<DeviceNotification>,
    onMuteKind: (String) -> Unit,
    onBack: () -> Unit,
) {
    val hw = LocalHwColors.current
    Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(hw.gradient))) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = hw.onGradient,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Alerts",
                    color = hw.onGradient,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (cards.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No alerts this session.\nThe device sends these once you're logged in.",
                        color = hw.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(cards) { card -> NotificationRow(card, onMuteKind) }
            }
        }
    }
}

@Composable
private fun NotificationRow(card: DeviceNotification, onMuteKind: (String) -> Unit) {
    val hw = LocalHwColors.current
    val accent = accentFor(card.level)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(hw.cardBg)
            .border(1.dp, hw.cardBorder, CardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.title,
                color = accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeLabel(card.receivedAt),
                color = hw.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(text = card.msg, color = hw.onGradient, style = MaterialTheme.typography.bodyMedium)
        if (card.kind.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.kind,
                    color = hw.muted,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                // Mutes the kind for THIS user on every surface (banner, web toast, G2 lens,
                // app) — the device owns the mute list. This is what the wire format's kind
                // name is for.
                TextButton(onClick = { onMuteKind(card.kind) }) {
                    Text("Mute this kind", color = hw.link, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun timeLabel(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
