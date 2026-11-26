package com.raulshma.grack.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the adaptive-layout width breakpoints (Phase 8).
 *
 * Every adaptive decision in the app must compare the available width against these
 * constants instead of a local `dp` literal, so the phone -> split -> rail thresholds
 * cannot drift apart between screens.
 */
object WindowWidths {
    /**
     * At or above this width the servers screen switches from a single pane to the
     * list + detail two-pane split.
     */
    val twoPaneSplit: Dp = 780.dp

    /**
     * At or above this width the app shell shows the navigation rail instead of the
     * bottom-style navigation and gives the content the remaining width.
     */
    val navigationRail: Dp = 840.dp
}
