package com.raulshma.grack.core.model

import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Widget snapshot derivation (Phase 1C item 3): the widgets render from repository metrics,
 * in-memory runtime states and the persisted container cache — the derivation itself must
 * never need SSH. Runtime-state samples win over the history tail; container rows come from
 * the cache in server order; ages are derived from metric and cache timestamps.
 */
class WidgetSnapshotDerivationTest {

    private val now = 1_800_000_000_000L

    private fun metric(serverId: String, atEpochMs: Long, cpu: Float = 10f) = MetricSnapshot(
        serverId = serverId,
        timestampEpochMs = atEpochMs,
        cpuPct = cpu,
    )

    private fun container(name: String, state: ContainerState = ContainerState.RUNNING) = ContainerInfo(
        id = "ctr-$name",
        shortId = name.take(4),
        name = name,
        image = "nginx:latest",
        state = state,
    )

    @Test
    fun runtimeStateWinsOverHistoryTail() {
        val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")
        val history = listOf(metric("srv-1", now - 60_000L, cpu = 5f))
        val runtimeState = ServerRuntimeState(
            server = server,
            latestMetric = metric("srv-1", now - 10_000L, cpu = 42f),
        )
        val snap = deriveWidgetClusterSnapshot(
            servers = listOf(server),
            metricsByServer = mapOf("srv-1" to history),
            runtimeStates = mapOf("srv-1" to runtimeState),
            cachedContainers = emptyList(),
        )
        assertEquals(42f, snap.latestByServer["srv-1"]?.cpuPct)
        assertEquals(now - 10_000L, snap.lastUpdateEpochMs)
    }

    @Test
    fun fallsBackToPersistedHistoryWhenRuntimeIsEmpty() {
        val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")
        val history = listOf(metric("srv-1", now - 120_000L), metric("srv-1", now - 60_000L, cpu = 7f))
        val snap = deriveWidgetClusterSnapshot(
            servers = listOf(server),
            metricsByServer = mapOf("srv-1" to history),
            runtimeStates = emptyMap(),
            cachedContainers = emptyList(),
        )
        assertEquals(7f, snap.latestByServer["srv-1"]?.cpuPct)
    }

    @Test
    fun containerRowsComeFromTheCacheInServerOrderCappedAtFour() {
        val srvA = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")
        val srvB = Server(id = "srv-2", name = "node-b", primaryHost = "10.0.0.6")
        val cache = listOf(
            WidgetCachedContainers("srv-1", listOf(container("a1"), container("a2")), fetchedAtEpochMs = now - 30_000L),
            WidgetCachedContainers("srv-2", listOf(container("b1")), fetchedAtEpochMs = now - 20_000L),
            // Entries for deleted nodes must not leak into the snapshot.
            WidgetCachedContainers("srv-gone", listOf(container("x")), fetchedAtEpochMs = now - 1_000L),
        )
        val snap = deriveWidgetClusterSnapshot(
            servers = listOf(srvA, srvB),
            metricsByServer = emptyMap(),
            runtimeStates = emptyMap(),
            cachedContainers = cache,
        )
        assertEquals(
            listOf("a1", "a2", "b1"),
            snap.containers.map { it.second.name },
        )
        // Server names repeat once per cached row of that node.
        assertEquals(listOf("node-a", "node-a", "node-b"), snap.containers.map { it.first.name })
        assertEquals(now - 20_000L, snap.containersUpdatedAtEpochMs)
        assertEquals(now - 20_000L, snap.lastUpdateEpochMs)
    }

    @Test
    fun capLimitsRenderedRowsWithoutTouchingTheCacheData() {
        val srv = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")
        val rows = (1..6).map { container("c$it") }
        val snap = deriveWidgetClusterSnapshot(
            servers = listOf(srv),
            metricsByServer = emptyMap(),
            runtimeStates = emptyMap(),
            cachedContainers = listOf(WidgetCachedContainers("srv-1", rows, fetchedAtEpochMs = now)),
        )
        assertEquals(WIDGET_CONTAINER_ROW_LIMIT, snap.containers.size)
    }

    @Test
    fun emptyInputsYieldAnEmptySnapshotWithoutAges() {
        val snap = deriveWidgetClusterSnapshot(
            servers = emptyList(),
            metricsByServer = emptyMap(),
            runtimeStates = emptyMap(),
            cachedContainers = emptyList(),
        )
        assertNull(snap.lastUpdateEpochMs)
        assertNull(snap.containersUpdatedAtEpochMs)
        assertTrue(snap.containers.isEmpty())
        assertTrue(snap.latestByServer.isEmpty())
    }

    @Test
    fun historyFlowsThroughForTheSparkline() {
        val srv = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")
        val history = listOf(metric("srv-1", now - 60_000L), metric("srv-1", now - 30_000L))
        val snap = deriveWidgetClusterSnapshot(
            servers = listOf(srv),
            metricsByServer = mapOf("srv-1" to history),
            runtimeStates = mapOf(
                "srv-1" to ServerRuntimeState(
                    server = srv,
                    history = history.toImmutableList(),
                ),
            ),
            cachedContainers = emptyList(),
        )
        assertEquals(history, snap.historyByServer["srv-1"])
    }
}
