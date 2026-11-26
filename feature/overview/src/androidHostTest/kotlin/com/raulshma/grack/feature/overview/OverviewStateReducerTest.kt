package com.raulshma.grack.feature.overview

import com.raulshma.grack.core.model.ActiveJob
import com.raulshma.grack.core.model.AppAlert
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.DashboardProfile
import com.raulshma.grack.core.model.DashboardTile
import com.raulshma.grack.core.model.JobPhase
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.TileType
import com.raulshma.grack.core.model.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 8 combine split: the 14-flow monolithic combine in [OverviewViewModel] was replaced
 * by four staged combines whose reductions are the pure functions under test here. The
 * equivalence contract: for identical inputs the staged reductions compose to exactly the
 * same [OverviewUiState] the single combine used to produce, field for field.
 */
class OverviewStateReducerTest {

    private val dashboards = listOf(
        DashboardProfile(id = "dash-main", title = "Main Rack", sortOrder = 0),
        DashboardProfile(id = "dash-second", title = "Second", sortOrder = 1),
    )

    private val tiles = listOf(
        DashboardTile(
            id = "tile-1",
            dashboardId = "dash-main",
            serverId = "srv-1",
            tileType = TileType.SINGLE_METRIC,
            titleOverride = "",
            spanX = 1,
            spanY = 1,
            positionIndex = 1,
        ),
        DashboardTile(
            id = "tile-0",
            dashboardId = "dash-main",
            serverId = "srv-1",
            tileType = TileType.SINGLE_METRIC,
            titleOverride = "",
            spanX = 2,
            spanY = 1,
            positionIndex = 0,
        ),
        DashboardTile(
            id = "tile-other",
            dashboardId = "dash-second",
            serverId = null,
            tileType = TileType.SINGLE_METRIC,
            titleOverride = "",
            spanX = 1,
            spanY = 1,
            positionIndex = 0,
        ),
    )

    private val servers = mapOf(
        "srv-1" to ServerRuntimeState(
            server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5"),
        ),
    )

    private val jobs = listOf(
        ActiveJob(
            id = "job-1",
            serverId = "srv-1",
            serverName = "node-a",
            title = "Container Stop: web",
            commandPreview = "docker stop web",
            phase = JobPhase.RUNNING,
            startedEpochMs = 1_000L,
        ),
    )

    private val alerts = listOf(
        AppAlert(
            id = "rule-1-srv-1",
            ruleId = "rule-1",
            serverId = "srv-1",
            serverName = "node-a",
            message = "CPU high",
            timestampEpochMs = 2_000L,
        ),
    )

    private val prefs = UserPreferences(safeReadOnlyMode = true)

    private val pending = PendingTileContainerAction(
        server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5"),
        containerName = "web",
        action = ContainerAction.STOP,
    )

    @Test
    fun stageOneResolvesTheRequestedDashboardAndItsSortedTiles() {
        val content = resolveDashboardContent(dashboards, tiles, "dash-second")

        assertEquals("dash-second", content.selectedDashboardId)
        assertEquals(listOf("dash-main", "dash-second"), content.dashboards.map { it.id })
        assertEquals(listOf("tile-other"), content.tiles.map { it.id })
    }

    @Test
    fun stageOneSortsTilesByPositionIndex() {
        val content = resolveDashboardContent(dashboards, tiles, "dash-main")

        assertEquals(listOf("tile-0", "tile-1"), content.tiles.map { it.id })
    }

    @Test
    fun stageOneFallsBackToTheFirstDashboardForAnUnknownId() {
        val content = resolveDashboardContent(dashboards, tiles, "dash-missing")

        assertEquals("dash-main", content.selectedDashboardId)
        assertEquals(listOf("tile-0", "tile-1"), content.tiles.map { it.id })
    }

    @Test
    fun stageOneWithNoDashboardsYieldsTheDefaultId() {
        // Behavior preserved from the single combine: tiles are filtered by the effective
        // dashboard id ("dash-main" here) even when no dashboard rows exist yet.
        val content = resolveDashboardContent(emptyList(), tiles, "dash-missing")

        assertEquals("dash-main", content.selectedDashboardId)
        assertEquals(listOf("tile-0", "tile-1"), content.tiles.map { it.id })
    }

    @Test
    fun stagedAssemblyEqualsTheDirectOverviewUiStateConstruction() {
        val content = resolveDashboardContent(dashboards, tiles, "dash-main")
        val environment = OverviewEnvironment(
            servers = servers,
            activeJobs = jobs,
            alerts = alerts,
            preferences = prefs,
        )
        val flags = OverviewEditorFlags(
            isEditMode = true,
            showTilePicker = true,
            showNewDashboardDialog = false,
            showActiveJobsSheet = true,
        )
        val transient = OverviewEditorTransient(
            pinnedContainerStates = mapOf("srv-1:web" to ContainerInfo(id = "abc", shortId = "abc", name = "web", image = "nginx")),
            statusBanner = "web: Stop (exit 0)",
            pendingTileContainerAction = pending,
        )

        // The reference shape the old single combine produced for the same inputs.
        val expected = OverviewUiState(
            dashboards = dashboards,
            selectedDashboardId = "dash-main",
            tiles = tiles.filter { it.dashboardId == "dash-main" }.sortedBy { it.positionIndex },
            servers = servers,
            activeJobs = jobs,
            alerts = alerts,
            preferences = prefs,
            isEditMode = true,
            showTilePicker = true,
            showNewDashboardDialog = false,
            showActiveJobsSheet = true,
            pinnedContainerStates = transient.pinnedContainerStates,
            statusBanner = "web: Stop (exit 0)",
            pendingTileContainerAction = pending,
        )

        assertEquals(expected, assembleOverviewUiState(content, environment, flags, transient))
    }

    @Test
    fun assemblyOfAllDefaultsEqualsTheBareOverviewUiState() {
        assertEquals(
            OverviewUiState(),
            assembleOverviewUiState(
                resolveDashboardContent(emptyList(), emptyList(), "dash-main"),
                OverviewEnvironment(),
                OverviewEditorFlags(),
                OverviewEditorTransient(),
            ),
        )
    }
}
