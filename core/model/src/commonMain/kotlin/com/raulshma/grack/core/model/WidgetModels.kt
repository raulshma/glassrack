package com.raulshma.grack.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Last-known container state per node, persisted by the app so home-screen widgets can
 * render container rows without causing any SSH traffic. Updated on every successful
 * in-app `fetchContainers`.
 */
@Serializable
data class WidgetCachedContainers(
    val serverId: String,
    val containers: List<ContainerInfo> = emptyList(),
    val fetchedAtEpochMs: Long = 0L,
)

/**
 * Everything the four Glance widgets render, derived purely from on-device data
 * (repository metrics + controller runtime states + the widget container cache).
 */
@Immutable
data class WidgetClusterSnapshot(
    val servers: List<Server> = emptyList(),
    val latestByServer: Map<String, MetricSnapshot> = emptyMap(),
    val historyByServer: Map<String, List<MetricSnapshot>> = emptyMap(),
    val containers: List<Pair<Server, ContainerInfo>> = emptyList(),
    /** Newest metric timestamp across the cluster, for the "Updated Xm ago" line. */
    val lastUpdateEpochMs: Long? = null,
    /** Freshness of the cached container rows shown by the control widget. */
    val containersUpdatedAtEpochMs: Long? = null,
)

/**
 * Derives the widget snapshot without any network access. The live runtime state wins over
 * the persisted history tail (it may hold a fresher in-memory sample), and container rows
 * come from the last-known cache in server order, capped like the control widget's layout.
 */
fun deriveWidgetClusterSnapshot(
    servers: List<Server>,
    metricsByServer: Map<String, List<MetricSnapshot>>,
    runtimeStates: Map<String, ServerRuntimeState>,
    cachedContainers: List<WidgetCachedContainers>,
): WidgetClusterSnapshot {
    val latest = servers.mapNotNull { srv ->
        val snap = runtimeStates[srv.id]?.latestMetric ?: metricsByServer[srv.id]?.lastOrNull()
        snap?.let { srv.id to it }
    }.toMap()
    val knownServerIds = servers.map { it.id }.toSet()
    val usableCache = cachedContainers.filter { it.serverId in knownServerIds }
    val containers = servers.flatMap { srv ->
        val cached = usableCache.firstOrNull { it.serverId == srv.id }
        cached?.containers?.map { srv to it } ?: emptyList()
    }.take(WIDGET_CONTAINER_ROW_LIMIT)
    return WidgetClusterSnapshot(
        servers = servers,
        latestByServer = latest,
        historyByServer = metricsByServer,
        containers = containers,
        lastUpdateEpochMs = (
            latest.values.map { it.timestampEpochMs } +
                usableCache.map { it.fetchedAtEpochMs }
            ).maxOrNull(),
        containersUpdatedAtEpochMs = usableCache.map { it.fetchedAtEpochMs }.maxOrNull(),
    )
}

/** How many container rows the ContainerControl widget renders. */
const val WIDGET_CONTAINER_ROW_LIMIT: Int = 4
