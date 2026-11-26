package com.raulshma.grack.feature.servers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pure geometry math for the collapsing detail header (Phase 5, request #4). Kept free of
 * Compose UI state so the `androidHostTest` suite can pin the collapse-progress math,
 * target-height calculations and settle decisions down without a device.
 */
internal object CollapsingHeaderMath {

    /** Pixels the header can shrink by; never negative when the compact row is taller. */
    fun collapseRangePx(expandedHeightPx: Float, compactHeightPx: Float): Float =
        (expandedHeightPx - compactHeightPx).coerceAtLeast(0f)

    /** Height of the collapsing container for the current collapse offset. */
    fun headerHeightPx(expandedHeightPx: Float, collapseOffsetPx: Float): Float =
        (expandedHeightPx - collapseOffsetPx).coerceAtLeast(0f)

    /** Collapse offset after consuming [collapseDeltaPx] px of upward scroll (clamped). */
    fun nextCollapseOffset(currentOffsetPx: Float, collapseDeltaPx: Float, rangePx: Float): Float =
        if (rangePx <= 0f) 0f else (currentOffsetPx + collapseDeltaPx).coerceIn(0f, rangePx)

    /** Collapse offset after consuming [expandDeltaPx] px of leftover downward scroll (clamped). */
    fun nextExpandOffset(currentOffsetPx: Float, expandDeltaPx: Float, rangePx: Float): Float =
        if (rangePx <= 0f) 0f else (currentOffsetPx - expandDeltaPx).coerceIn(0f, rangePx)

    /** 0 = fully expanded, 1 = fully collapsed. */
    fun collapseFraction(offsetPx: Float, rangePx: Float): Float =
        if (rangePx <= 0f) 0f else (offsetPx / rangePx).coerceIn(0f, 1f)

    /**
     * Where the settle spring should land when scrolling stops: a decisive fling wins over the
     * midpoint rule, otherwise the header settles toward whichever state it is closer to.
     */
    fun settleTargetOffsetPx(
        offsetPx: Float,
        rangePx: Float,
        remainingVelocityYPxPerS: Float,
        velocityThresholdPxPerS: Float = SettleVelocityThresholdPxPerS,
    ): Float = when {
        rangePx <= 0f -> 0f
        remainingVelocityYPxPerS < -velocityThresholdPxPerS -> rangePx
        remainingVelocityYPxPerS > velocityThresholdPxPerS -> 0f
        offsetPx >= rangePx / 2f -> rangePx
        else -> 0f
    }

    /** Hero fades out on the same smoothstep curve the compact row fades in with. */
    fun crossfadeAlphas(fraction: Float): Pair<Float, Float> {
        val s = smoothstep(fraction)
        return (1f - s) to s
    }

    private fun smoothstep(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Fling speed (px/s) above which the settle picks the fling's direction outright. */
    const val SettleVelocityThresholdPxPerS: Float = 350f
}

/** Which hero actions land in the compact row versus the overflow menu. */
internal data class CompactHeaderActionSplit(
    val primaryIndex: Int,
    val overflowIndices: List<Int>,
)

/**
 * The compact header keeps the SSH action; every other hero action moves into the overflow
 * menu. Falls back to the first action when no SSH entry exists so the row is never empty.
 */
internal fun splitHeroActionIndices(
    labels: List<String>,
    primaryLabel: String = CompactPrimaryActionLabel,
): CompactHeaderActionSplit {
    if (labels.isEmpty()) {
        return CompactHeaderActionSplit(primaryIndex = -1, overflowIndices = emptyList())
    }
    val primary = labels.indexOfFirst { it.equals(primaryLabel, ignoreCase = true) }
        .let { if (it >= 0) it else 0 }
    return CompactHeaderActionSplit(
        primaryIndex = primary,
        overflowIndices = labels.indices.filter { it != primary },
    )
}

internal const val CompactPrimaryActionLabel: String = "SSH"

/**
 * Drives the collapsing detail header of [ServerDetailPane] (Phase 5). Both heights are
 * measured from composition (`onSizeChanged`), so the math adapts to any layout the pane is
 * hosted in — phone or the >=780dp split — and to health/banner content changes.
 *
 * The scroll protocol lives in [nestedScrollConnection]: upward scroll collapses the header
 * before the active pane's LazyColumn scrolls, and leftover downward scroll at the top of the
 * pane expands it again before overscroll. Mid-way stops are resolved by a spring
 * [Animatable] settle; a tab switch or a tap on the compact row expands it explicitly.
 */
internal class CollapsingDetailHeaderState(
    private val settleScope: CoroutineScope,
    private val settleSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    ),
) {
    /** Measured height of the fully expanded hero header, in pixels. */
    var expandedHeightPx by mutableFloatStateOf(0f)

    /** Measured height of the compact (collapsed) row, in pixels. */
    var compactHeightPx by mutableFloatStateOf(0f)

    /** Collapsed-away pixels of the hero header; 0 means fully expanded. */
    var collapseOffsetPx by mutableFloatStateOf(0f)
        private set

    val collapseRangePx: Float
        get() = CollapsingHeaderMath.collapseRangePx(expandedHeightPx, compactHeightPx)

    /** Offset clamped against the live range, so layout-driven height changes never over-collapse. */
    val effectiveOffsetPx: Float
        get() = collapseOffsetPx.coerceIn(0f, collapseRangePx)

    val collapseFraction: Float
        get() = CollapsingHeaderMath.collapseFraction(effectiveOffsetPx, collapseRangePx)

    /** Height the collapsing header container should render at, in pixels. */
    val headerHeightPx: Float
        get() = CollapsingHeaderMath.headerHeightPx(expandedHeightPx, effectiveOffsetPx)

    /** Compact row only answers taps once the hero has faded far enough out. */
    val isCompactInteractive: Boolean
        get() = collapseRangePx > 0f && collapseFraction >= CompactInteractiveFraction

    /** Hero pills stop intercepting taps once the compact row has taken over. */
    val isHeroInteractive: Boolean
        get() = collapseRangePx <= 0f || collapseFraction < HeroInteractiveFraction

    private val settleAnimatable = Animatable(0f)
    private var settleJob: Job? = null

    /**
     * Consumes an upward scroll delta (positive [collapseDeltaPx]) by collapsing the header
     * first. Returns the consumed distance, or 0 when there was nothing to collapse.
     */
    fun consumeCollapseScroll(collapseDeltaPx: Float): Float {
        val range = collapseRangePx
        if (range <= 0f || collapseDeltaPx <= 0f) return 0f
        val previous = collapseOffsetPx
        val next = CollapsingHeaderMath.nextCollapseOffset(previous, collapseDeltaPx, range)
        if (next == previous) return 0f
        cancelSettle()
        collapseOffsetPx = next
        return previous - next
    }

    /**
     * Consumes a leftover downward scroll delta (positive [expandDeltaPx]) by expanding the
     * header first. Returns the consumed distance, or 0 when it was already expanded.
     */
    fun consumeExpandScroll(expandDeltaPx: Float): Float {
        val range = collapseRangePx
        if (range <= 0f || expandDeltaPx <= 0f) return 0f
        val previous = collapseOffsetPx
        val next = CollapsingHeaderMath.nextExpandOffset(previous, expandDeltaPx, range)
        if (next == previous) return 0f
        cancelSettle()
        collapseOffsetPx = next
        return previous - next
    }

    /** Resolves a scroll/fling stop mid-way with the settle spring. */
    fun settle(remainingVelocityYPxPerS: Float) {
        val range = collapseRangePx
        if (range <= 0f) return
        animateTo(CollapsingHeaderMath.settleTargetOffsetPx(effectiveOffsetPx, range, remainingVelocityYPxPerS))
    }

    /** Expands the header with the settle spring; used on tab switch and compact-row taps. */
    fun expandAnimated() {
        if (collapseRangePx <= 0f || effectiveOffsetPx <= 0f) return
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        cancelSettle()
        val start = effectiveOffsetPx
        if (abs(target - start) < 0.5f) {
            collapseOffsetPx = target
            return
        }
        settleJob = settleScope.launch {
            try {
                settleAnimatable.snapTo(start)
                settleAnimatable.animateTo(target, settleSpec) { collapseOffsetPx = value }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Logger.w(failure) { "Detail header settle animation failed" }
            }
        }
    }

    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    companion object {
        /** Fraction beyond which the compact row becomes tappable. */
        internal const val CompactInteractiveFraction: Float = 0.65f

        /** Fraction below which the hero pills remain tappable. */
        internal const val HeroInteractiveFraction: Float = 0.35f
    }
}

/**
 * Nested scroll connection collapsing the header before the active pane scrolls and expanding
 * it from leftover scroll before the pane overscrolls at the top. Attached once at the pane
 * container level in [ServerDetailPane], so it covers all five detail tabs without any
 * callbacks into the panes themselves.
 */
internal fun CollapsingDetailHeaderState.nestedScrollConnection(): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Content moving up (y < 0), drag or fling: collapse the header before the pane scrolls.
            if (available.y < 0f) {
                val consumed = consumeCollapseScroll(-available.y)
                if (consumed > 0f) return Offset(0f, -consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Leftover downward scroll at the top of the pane: expand before overscroll kicks in.
            if (available.y > 0f) {
                val consumedExpand = consumeExpandScroll(available.y)
                if (consumedExpand > 0f) return Offset(0f, consumedExpand)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle(available.y)
            return super.onPostFling(consumed, available)
        }
    }
