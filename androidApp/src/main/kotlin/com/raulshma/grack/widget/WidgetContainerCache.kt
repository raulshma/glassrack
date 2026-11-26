package com.raulshma.grack.widget

import android.content.Context
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.WidgetCachedContainers
import com.raulshma.grack.core.ssh.ContainerStateCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed last-known container state used by the home-screen widgets. The app process
 * writes it on every successful in-app container fetch, so widget updates can render
 * without any SSH traffic even after the process was killed.
 */
class WidgetContainerCache(context: Context) : ContainerStateCache {

    private val storeFile: File = File(context.filesDir, CACHE_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val ioMutex = Mutex()

    override suspend fun recordContainers(
        serverId: String,
        containers: List<ContainerInfo>,
        fetchedAtEpochMs: Long,
    ) = ioMutex.withLock {
        val updated = readAllLocked().toMutableMap()
        updated[serverId] = WidgetCachedContainers(
            serverId = serverId,
            containers = containers,
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
        writeAllLocked(updated)
    }

    override suspend fun containersFor(serverId: String): List<ContainerInfo> = ioMutex.withLock {
        readAllLocked()[serverId]?.containers.orEmpty()
    }

    override suspend fun allEntries(): List<WidgetCachedContainers> = ioMutex.withLock {
        readAllLocked().values.toList()
    }

    private fun readAllLocked(): Map<String, WidgetCachedContainers> {
        if (!storeFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, WidgetCachedContainers>>(storeFile.readText())
        }.getOrDefault(emptyMap())
    }

    private fun writeAllLocked(entries: Map<String, WidgetCachedContainers>) {
        runCatching {
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(json.encodeToString(entries))
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val CACHE_FILE_NAME = "grack_widget_container_cache.json"
    }
}
