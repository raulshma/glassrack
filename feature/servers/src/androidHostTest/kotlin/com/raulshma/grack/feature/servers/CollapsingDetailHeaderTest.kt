package com.raulshma.grack.feature.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveHeroCard
import com.raulshma.grack.core.designsystem.GlassrackTheme
import com.raulshma.grack.core.model.MetricSnapshot
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.UserPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The collapsing detail header under real layout, gestures and touch hit-testing (Phase 5,
 * request #4). It is mounted the way [ServerDetailPane] mounts it — hero above a scrollable
 * pane sharing the nested scroll connection — because that is how it failed on a running
 * build: once collapsed it never expanded again and showed clipped content, and the hero
 * pills ignored taps while expanded. Clicks go through [performTouchInput] rather than the
 * semantics action, since the point is what the hit-test resolves to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w412dp-h915dp-xxhdpi")
class CollapsingDetailHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Label of every hero/compact action fired, in order. */
    private val fired = mutableListOf<String>()
    private lateinit var headerState: CollapsingDetailHeaderState
    private lateinit var listState: LazyListState

    private fun mountHeader() {
        composeTestRule.setContent {
            val scope = rememberCoroutineScope()
            val state = remember { CollapsingDetailHeaderState(scope).also { headerState = it } }
            val connection = remember(state) { state.nestedScrollConnection() }
            val list = rememberLazyListState().also { listState = it }
            GlassrackTheme(UserPreferences()) {
                Column(Modifier.fillMaxSize()) {
                    CollapsingDetailHeader(
                        state = state,
                        heroActions = listOf(
                            HeroActionItem("SSH", Icons.Rounded.Terminal) { fired += "SSH" },
                            HeroActionItem("Poll", Icons.Rounded.CheckCircle) { fired += "Poll" },
                            HeroActionItem("Edit", Icons.Rounded.CheckCircle) { fired += "Edit" },
                            HeroActionItem("Delete", Icons.Rounded.CheckCircle, isDestructive = true) { fired += "Delete" },
                        ),
                        runtime = ServerRuntimeState(
                            server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5"),
                            health = ServerHealth.ONLINE,
                            latestMetric = MetricSnapshot(serverId = "srv-1", timestampEpochMs = 0L, latencyMs = 12),
                        ),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        badgeIcon = Icons.Rounded.CheckCircle,
                        hero = { actions ->
                            ExpressiveHeroCard(
                                headline = "node-a",
                                subtitle = HERO_SUBTITLE,
                                eyebrow = "lab · ONLINE · 12ms",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                badgeIcon = Icons.Rounded.CheckCircle,
                                badgeShape = ExpressiveBadgeShape.Cookie9Sided,
                                actions = actions,
                            )
                        },
                        modifier = Modifier.testTag(HEADER),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(connection)
                            .testTag(PANE),
                    ) {
                        LazyColumn(state = list, modifier = Modifier.testTag(LIST)) {
                            items(ROW_COUNT) { index ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ROW_HEIGHT_DP.dp),
                                ) {
                                    Text("row $index")
                                }
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Slow drag on the pane with no release velocity, so the list never flings on its own. */
    private fun dragPane(dy: Float) {
        composeTestRule.onNodeWithTag(PANE).performTouchInput {
            val steps = 30
            down(center)
            repeat(steps) {
                moveBy(Offset(0f, dy / steps))
                advanceEventTime(16)
            }
            advanceEventTime(400)
            up()
        }
        composeTestRule.waitForIdle()
    }

    /** Far enough to collapse the header and leave the list scrolled well past its top. */
    private fun scrollUp() = dragPane(dy = -900f)

    /** A fast full-height swipe, so the list keeps coasting after the finger lifts. */
    private fun flingPane(up: Boolean) {
        composeTestRule.onNodeWithTag(PANE).performTouchInput { if (up) swipeUp() else swipeDown() }
        composeTestRule.waitForIdle()
    }

    private fun header() = composeTestRule.onNodeWithTag(HEADER)
    private fun headerHeight() = header().fetchSemanticsNode().boundsInRoot.height

    /** The compact row, addressed by its subtitle: the row's clickable merges its texts. */
    private fun compactRow() = composeTestRule.onNodeWithText(COMPACT_SUBTITLE)

    /** The compact row's own SSH pill (the hero has one with the same label). */
    private fun compactSsh() = composeTestRule.onAllNodesWithText("SSH")
        .filterToOne(hasAnyAncestor(hasText(COMPACT_SUBTITLE)))

    /** "Poll" in the overflow menu: the hero's own pill is inert once the hero has faded out. */
    private fun overflowPoll() = composeTestRule.onAllNodesWithText("Poll").filter(isEnabled())
    private fun overflowPollItem() = overflowPoll()[0]

    /** Nothing of [node] is cut off by an ancestor's clip. */
    private fun assertWholeNodeVisible(node: SemanticsNodeInteraction) {
        assertEquals(
            node.getUnclippedBoundsInRoot(),
            node.getBoundsInRoot(),
            "node should be laid out whole, not clipped",
        )
    }

    @Test
    fun heroPillsTakeTapsWhileExpanded() {
        mountHeader()

        composeTestRule.onNodeWithText("Poll").performTouchInput { click() }
        composeTestRule.onNodeWithText("Delete").performTouchInput { click() }

        composeTestRule.runOnIdle { assertEquals(listOf("Poll", "Delete"), fired) }
    }

    @Test
    fun heroPillsAreWholeWhileExpanded() {
        mountHeader()

        // "SSH" is also the compact pill's label; the other pills cover the row.
        listOf("Poll", "Edit", "Delete").forEach { label ->
            assertWholeNodeVisible(composeTestRule.onNodeWithText(label))
        }
    }

    @Test
    fun collapsedHeaderShowsTheCompactRowWhole() {
        mountHeader()

        scrollUp()

        // Compact, with the compact row filling it: nothing left clipped or cut off.
        val compact = compactRow().fetchSemanticsNode()
        assertEquals(compact.boundsInRoot.height, headerHeight(), 1f, "collapsed header should be exactly the compact row")
        assertEquals(compact.boundsInRoot.top, header().fetchSemanticsNode().boundsInRoot.top, 1f, "compact row should sit at the top of the collapsed header")
        assertWholeNodeVisible(compactRow())
        compactRow().assertIsDisplayed()
        compactSsh().assertIsEnabled().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More actions for node-a").assertIsEnabled().assertIsDisplayed()
    }

    @Test
    fun collapsedHeaderExpandsAgainFromScrollingDownAtTheTop() {
        mountHeader()
        val expandedHeight = headerHeight()
        scrollUp()
        composeTestRule.runOnIdle {
            assertEquals(1f, headerState.collapseFraction, "header should be fully collapsed after scrolling up")
        }

        dragPane(dy = 1_200f)

        composeTestRule.runOnIdle {
            assertEquals(0f, headerState.collapseFraction, "header should be expanded again after scrolling down to the top")
        }
        assertEquals(expandedHeight, headerHeight(), 1f, "header should return to its expanded height")
        assertWholeNodeVisible(composeTestRule.onNodeWithText("Poll"))
        composeTestRule.onNodeWithText("Poll").performTouchInput { click() }
        composeTestRule.runOnIdle { assertEquals(listOf("Poll"), fired) }
    }

    @Test
    fun collapsingKeepsTheHeroMeasuredHeight() {
        mountHeader()
        val heroHeight = composeTestRule.runOnIdle { headerState.expandedHeightPx }

        scrollUp()

        composeTestRule.runOnIdle {
            assertEquals(
                heroHeight,
                headerState.expandedHeightPx,
                "hero height must not follow the collapsing container",
            )
            assertEquals(1f, headerState.collapseFraction)
        }
    }

    @Test
    fun compactRowTakesTapsOnceCollapsed() {
        mountHeader()

        scrollUp()

        compactSsh().performTouchInput { click() }
        composeTestRule.onNodeWithContentDescription("More actions for node-a").performTouchInput { click() }
        overflowPollItem().assertIsDisplayed()
        overflowPollItem().performTouchInput { click() }

        composeTestRule.runOnIdle { assertEquals(listOf("SSH", "Poll"), fired) }
        overflowPoll().assertCountEquals(0)
    }

    @Test
    fun tappingTheCompactRowReExpandsTheHeader() {
        mountHeader()
        val expandedHeight = headerHeight()
        scrollUp()

        compactRow().performTouchInput { click(centerLeft) }

        composeTestRule.runOnIdle { assertEquals(0f, headerState.collapseFraction) }
        assertEquals(expandedHeight, headerHeight(), 1f)
    }

    @Test
    fun midwayStopSettlesToTheNearerState() {
        mountHeader()
        val range = composeTestRule.runOnIdle { headerState.collapseRangePx }

        dragPane(dy = -range * 0.3f)
        composeTestRule.runOnIdle {
            assertEquals(0f, headerState.collapseFraction, "a third collapsed should settle back out")
        }

        dragPane(dy = -range * 0.7f)
        composeTestRule.runOnIdle {
            assertEquals(1f, headerState.collapseFraction, "two thirds collapsed should settle in")
        }
    }

    @Test
    fun flingingDownToTheTopExpandsAgain() {
        mountHeader()
        val expandedHeight = headerHeight()

        // A real fling, unlike dragPane: the list keeps coasting after the finger lifts. One
        // swipe spans under SWIPE_REACH_ROWS rows on its own, so this index proves it coasted.
        flingPane(up = true)
        composeTestRule.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex > SWIPE_REACH_ROWS, "list should coast past the swipe's own reach")
            assertEquals(1f, headerState.collapseFraction, "header should be fully collapsed after a fling up")
        }

        flingPane(up = false)
        composeTestRule.runOnIdle {
            assertEquals(0f, headerState.collapseFraction, "header should be expanded again once the fling down reaches the top")
        }
        assertEquals(expandedHeight, headerHeight(), 1f, "header should return to its expanded height")
    }

    private companion object {
        /** Rows the pane holds, and one more than a single full-height swipe can scroll by itself. */
        const val ROW_COUNT = 60
        const val ROW_HEIGHT_DP = 48
        const val SWIPE_REACH_ROWS = ROW_COUNT / 3

        const val HEADER = "detail-header"
        const val PANE = "detail-pane"
        const val LIST = "detail-list"
        const val HERO_SUBTITLE = "debian · x86_64"
        const val COMPACT_SUBTITLE = "root@10.0.0.5 · 12ms"
    }
}
