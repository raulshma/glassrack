package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.WidgetCachedContainers

/**
 * Last-known container state store. [HomelabController] records every successful
 * `fetchContainers` so widgets (and other offline consumers) can render container state
 * without causing SSH traffic. Implementations should persist across processes.
 */
interface ContainerStateCache {
    /** Replaces the cached rows for [serverId]; call only on a successful fetch. */
    suspend fun recordContainers(serverId: String, containers: List<ContainerInfo>, fetchedAtEpochMs: Long)

    suspend fun containersFor(serverId: String): List<ContainerInfo>

    suspend fun allEntries(): List<WidgetCachedContainers>
}
