package com.hardwareone.console.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * The device's physical posture, distilled to what a single-screen console cares about.
 *
 * - [Flat]: a normal phone, the folded cover screen, or a fully-open inner screen.
 * - [Tabletop]: half-open with a horizontal hinge (Pixel Fold laid down like a laptop).
 *   We split the UI so the log sits on the upper panel and the controls on the lower one.
 * - [Book]: half-open with a vertical hinge. Not specially handled in v1 — rendered like
 *   [Flat] (the responsive width handling already makes good use of the space).
 *
 * Hinge bounds are in window pixels; only the thickness is used (the Pixel Fold hinge is
 * centred), which keeps the split robust against inset/coordinate drift.
 */
sealed interface FoldPosture {
    data object Flat : FoldPosture
    data class Tabletop(val hingeThicknessPx: Int) : FoldPosture
    data object Book : FoldPosture
}

/** Observe the current [FoldPosture]. Collection is scoped to the composition. */
@Composable
fun rememberFoldPosture(activity: Activity): FoldPosture {
    val posture by produceState<FoldPosture>(FoldPosture.Flat, activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                val fold = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
                value = when {
                    fold == null || fold.state == FoldingFeature.State.FLAT -> FoldPosture.Flat
                    fold.orientation == FoldingFeature.Orientation.HORIZONTAL ->
                        FoldPosture.Tabletop(fold.bounds.height())
                    else -> FoldPosture.Book
                }
            }
    }
    return posture
}
