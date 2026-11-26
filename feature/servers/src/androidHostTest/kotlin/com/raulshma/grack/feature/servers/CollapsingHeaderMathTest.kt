package com.raulshma.grack.feature.servers

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Collapsing detail header math (Phase 5, request #4): collapse progress, target-height
 * calculations, the settle decision and the compact-row versus overflow action split.
 */
class CollapsingHeaderMathTest {

    // --- Range and container height -----------------------------------------------------------

    @Test
    fun collapseRangeIsExpandedMinusCompact() {
        assertEquals(90f, CollapsingHeaderMath.collapseRangePx(expandedHeightPx = 150f, compactHeightPx = 60f))
    }

    @Test
    fun collapseRangeNeverNegativeWhenCompactIsTaller() {
        assertEquals(0f, CollapsingHeaderMath.collapseRangePx(expandedHeightPx = 60f, compactHeightPx = 90f))
        assertEquals(0f, CollapsingHeaderMath.collapseRangePx(expandedHeightPx = 80f, compactHeightPx = 80f))
    }

    @Test
    fun headerHeightShrinksWithCollapseOffset() {
        assertEquals(150f, CollapsingHeaderMath.headerHeightPx(expandedHeightPx = 150f, collapseOffsetPx = 0f))
        assertEquals(90f, CollapsingHeaderMath.headerHeightPx(expandedHeightPx = 150f, collapseOffsetPx = 60f))
    }

    @Test
    fun headerHeightNeverNegative() {
        assertEquals(0f, CollapsingHeaderMath.headerHeightPx(expandedHeightPx = 100f, collapseOffsetPx = 140f))
    }

    // --- Scroll consumption ---------------------------------------------------------------------

    @Test
    fun upwardScrollAccumulatesCollapseOffset() {
        assertEquals(30f, CollapsingHeaderMath.nextCollapseOffset(currentOffsetPx = 0f, collapseDeltaPx = 30f, rangePx = 90f))
        assertEquals(70f, CollapsingHeaderMath.nextCollapseOffset(currentOffsetPx = 40f, collapseDeltaPx = 30f, rangePx = 90f))
    }

    @Test
    fun collapseOffsetClampsToRange() {
        assertEquals(90f, CollapsingHeaderMath.nextCollapseOffset(currentOffsetPx = 80f, collapseDeltaPx = 30f, rangePx = 90f))
    }

    @Test
    fun leftoverDownwardScrollExpandsTowardZero() {
        assertEquals(40f, CollapsingHeaderMath.nextExpandOffset(currentOffsetPx = 60f, expandDeltaPx = 20f, rangePx = 90f))
    }

    @Test
    fun expandOffsetClampsAtZero() {
        assertEquals(0f, CollapsingHeaderMath.nextExpandOffset(currentOffsetPx = 10f, expandDeltaPx = 30f, rangePx = 90f))
    }

    @Test
    fun zeroRangeKeepsOffsetsAtExpanded() {
        assertEquals(0f, CollapsingHeaderMath.nextCollapseOffset(currentOffsetPx = 0f, collapseDeltaPx = 30f, rangePx = 0f))
        assertEquals(0f, CollapsingHeaderMath.nextExpandOffset(currentOffsetPx = 0f, expandDeltaPx = 30f, rangePx = 0f))
    }

    // --- Progress fraction ----------------------------------------------------------------------

    @Test
    fun fractionIsZeroWhenExpandedAndOneWhenCollapsed() {
        assertEquals(0f, CollapsingHeaderMath.collapseFraction(offsetPx = 0f, rangePx = 90f))
        assertEquals(1f, CollapsingHeaderMath.collapseFraction(offsetPx = 90f, rangePx = 90f))
    }

    @Test
    fun fractionIsLinearBetweenTheEndpoints() {
        assertEquals(0.5f, CollapsingHeaderMath.collapseFraction(offsetPx = 45f, rangePx = 90f))
    }

    @Test
    fun fractionClampsOutOfRangeValues() {
        assertEquals(1f, CollapsingHeaderMath.collapseFraction(offsetPx = 120f, rangePx = 90f))
        assertEquals(0f, CollapsingHeaderMath.collapseFraction(offsetPx = 0f, rangePx = 0f))
    }

    // --- Settle decision ------------------------------------------------------------------------

    private val range = 100f

    @Test
    fun decisiveFlingTowardContentSettlesCollapsed() {
        assertEquals(range, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 10f, rangePx = range, remainingVelocityYPxPerS = -800f))
    }

    @Test
    fun decisiveFlingTowardTopSettlesExpanded() {
        assertEquals(0f, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 90f, rangePx = range, remainingVelocityYPxPerS = 800f))
    }

    @Test
    fun slowStopPastMidpointSettlesCollapsed() {
        assertEquals(range, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 60f, rangePx = range, remainingVelocityYPxPerS = 0f))
    }

    @Test
    fun slowStopBeforeMidpointSettlesExpanded() {
        assertEquals(0f, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 40f, rangePx = range, remainingVelocityYPxPerS = 0f))
    }

    @Test
    fun thresholdVelocityFallsBackToMidpointRule() {
        val threshold = CollapsingHeaderMath.SettleVelocityThresholdPxPerS
        // Exactly at the threshold the fling is not decisive; the midpoint rule decides instead.
        assertEquals(range, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 60f, rangePx = range, remainingVelocityYPxPerS = -threshold))
        assertEquals(0f, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 40f, rangePx = range, remainingVelocityYPxPerS = threshold))
    }

    @Test
    fun settleWithZeroRangeTargetsExpanded() {
        assertEquals(0f, CollapsingHeaderMath.settleTargetOffsetPx(offsetPx = 50f, rangePx = 0f, remainingVelocityYPxPerS = -900f))
    }

    // --- Crossfade ------------------------------------------------------------------------------

    @Test
    fun crossfadeEndpointsAreFullyOpaqueCounterparts() {
        val (heroAtZero, compactAtZero) = CollapsingHeaderMath.crossfadeAlphas(0f)
        assertEquals(1f, heroAtZero)
        assertEquals(0f, compactAtZero)
        val (heroAtOne, compactAtOne) = CollapsingHeaderMath.crossfadeAlphas(1f)
        assertEquals(0f, heroAtOne)
        assertEquals(1f, compactAtOne)
    }

    @Test
    fun crossfadeMeetsHalfwayAtMidpoint() {
        val (hero, compact) = CollapsingHeaderMath.crossfadeAlphas(0.5f)
        assertEquals(0.5f, hero)
        assertEquals(0.5f, compact)
    }

    @Test
    fun crossfadeIsMonotonicAndClamped() {
        var previousCompact = 0f
        for (step in 0..20) {
            val (hero, compact) = CollapsingHeaderMath.crossfadeAlphas(step / 20f)
            assertTrue(compact >= previousCompact, "compact alpha must not decrease at step $step")
            assertTrue(hero in 0f..1f && compact in 0f..1f)
            assertTrue(abs(hero + compact - 1f) < 1e-6f)
            previousCompact = compact
        }
        val (heroUnder, compactUnder) = CollapsingHeaderMath.crossfadeAlphas(-0.5f)
        assertEquals(1f, heroUnder)
        assertEquals(0f, compactUnder)
        val (heroOver, compactOver) = CollapsingHeaderMath.crossfadeAlphas(1.5f)
        assertEquals(0f, heroOver)
        assertEquals(1f, compactOver)
    }

    // --- Compact row versus overflow actions ----------------------------------------------------

    @Test
    fun sshStaysInCompactRowAndRestGoesToOverflow() {
        val split = splitHeroActionIndices(listOf("SSH", "Poll", "Edit", "Delete"))
        assertEquals(0, split.primaryIndex)
        assertEquals(listOf(1, 2, 3), split.overflowIndices)
    }

    @Test
    fun splitKeepsOriginalOrderWhenSshIsNotFirst() {
        val split = splitHeroActionIndices(listOf("Poll", "Wake", "SSH", "Edit", "Delete"))
        assertEquals(2, split.primaryIndex)
        assertEquals(listOf(0, 1, 3, 4), split.overflowIndices)
    }

    @Test
    fun missingSshFallsBackToFirstAction() {
        val split = splitHeroActionIndices(listOf("Poll", "Edit", "Delete"))
        assertEquals(0, split.primaryIndex)
        assertEquals(listOf(1, 2), split.overflowIndices)
    }

    @Test
    fun primaryMatchIsCaseInsensitive() {
        assertEquals(0, splitHeroActionIndices(listOf("ssh", "Poll")).primaryIndex)
    }

    @Test
    fun emptyActionListYieldsNoPrimary() {
        val split = splitHeroActionIndices(emptyList())
        assertEquals(-1, split.primaryIndex)
        assertTrue(split.overflowIndices.isEmpty())
    }
}
