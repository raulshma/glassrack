package com.raulshma.grack.core.database

import com.raulshma.grack.core.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Automatic audit-log retention: prunes entries older than the user's
 * [UserPreferences.auditRetentionDays] once at startup and then every [pruneIntervalMs].
 * The prune itself is [GlassrackRepository.pruneAuditLogs], which re-anchors the hash
 * chain after removal.
 */
class AuditRetentionCoordinator(
    private val scope: CoroutineScope,
    private val preferences: Flow<UserPreferences>,
    private val prune: suspend (retentionDays: Int) -> Int,
    private val pruneIntervalMs: Long = DEFAULT_PRUNE_INTERVAL_MS,
) {
    /** Prunes with the retention window currently stored in preferences. */
    suspend fun pruneNow(): Int = prune(preferences.first().auditRetentionDays)

    /** Starts the recurring prune loop. Safe to call once from app startup. */
    fun start(): Job = scope.launch {
        while (currentCoroutineContext().isActive) {
            runCatching { pruneNow() }
            delay(pruneIntervalMs)
        }
    }

    companion object {
        /** Audit retention only needs to run once a day; startup covers the first sweep. */
        const val DEFAULT_PRUNE_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
