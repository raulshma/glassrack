package com.raulshma.grack.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllOnce(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    @Upsert
    suspend fun upsert(entity: ServerEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ServerEntity>)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int
}

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys ORDER BY createdAtEpochMs DESC")
    suspend fun getAllOnce(): List<SshKeyEntity>

    @Query("SELECT * FROM ssh_keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SshKeyEntity?

    @Upsert
    suspend fun upsert(entity: SshKeyEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SshKeyEntity>)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DashboardDao {
    @Query("SELECT * FROM dashboards ORDER BY sortOrder ASC")
    fun observeDashboards(): Flow<List<DashboardProfileEntity>>

    @Query("SELECT * FROM dashboards ORDER BY sortOrder ASC")
    suspend fun getDashboardsOnce(): List<DashboardProfileEntity>

    @Query("SELECT * FROM dashboard_tiles ORDER BY positionIndex ASC")
    fun observeAllTiles(): Flow<List<DashboardTileEntity>>

    @Query("SELECT * FROM dashboard_tiles ORDER BY positionIndex ASC")
    suspend fun getAllTilesOnce(): List<DashboardTileEntity>

    @Query("SELECT * FROM dashboard_tiles WHERE dashboardId = :dashboardId ORDER BY positionIndex ASC")
    fun observeTilesForDashboard(dashboardId: String): Flow<List<DashboardTileEntity>>

    @Upsert
    suspend fun upsertDashboard(entity: DashboardProfileEntity)

    @Upsert
    suspend fun upsertDashboards(entities: List<DashboardProfileEntity>)

    @Upsert
    suspend fun upsertTile(entity: DashboardTileEntity)

    @Upsert
    suspend fun upsertTiles(entities: List<DashboardTileEntity>)

    @Query("DELETE FROM dashboard_tiles WHERE id = :tileId")
    suspend fun deleteTile(tileId: String)

    @Query("DELETE FROM dashboards WHERE id = :dashboardId")
    suspend fun deleteDashboard(dashboardId: String)

    @Query("DELETE FROM dashboard_tiles WHERE dashboardId = :dashboardId")
    suspend fun deleteTilesForDashboard(dashboardId: String)
}

@Dao
interface MetricSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: MetricSampleEntity)

    @Query("SELECT * FROM metric_samples WHERE serverId = :serverId ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getRecentForServer(serverId: String, limit: Int = 60): List<MetricSampleEntity>

    @Query("SELECT * FROM metric_samples ORDER BY timestampEpochMs DESC LIMIT 300")
    fun observeRecentSamples(): Flow<List<MetricSampleEntity>>

    @Query("DELETE FROM metric_samples WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_entries ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<AuditEntryEntity>>

    @Query("SELECT * FROM audit_entries ORDER BY timestampEpochMs ASC")
    suspend fun getAllChronological(): List<AuditEntryEntity>

    @Query("SELECT * FROM audit_entries ORDER BY timestampEpochMs DESC LIMIT 1")
    suspend fun getLatest(): AuditEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditEntryEntity)

    @Query("DELETE FROM audit_entries WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)

    @Query("UPDATE audit_entries SET prevHash = :prevHash, entryHash = :entryHash WHERE id = :id")
    suspend fun updateHashes(id: String, prevHash: String, entryHash: String)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllOnce(): List<SnippetEntity>

    @Upsert
    suspend fun upsert(entity: SnippetEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SnippetEntity>)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AlertRuleDao {
    @Query("SELECT * FROM alert_rules ORDER BY name ASC")
    fun observeAll(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules ORDER BY name ASC")
    suspend fun getAllOnce(): List<AlertRuleEntity>

    @Upsert
    suspend fun upsert(entity: AlertRuleEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AlertRuleEntity>)

    @Query("DELETE FROM alert_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TunnelDao {
    @Query("SELECT * FROM tunnels WHERE serverId = :serverId ORDER BY label ASC")
    fun observeForServer(serverId: String): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels ORDER BY label ASC")
    fun observeAll(): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels ORDER BY label ASC")
    suspend fun getAllOnce(): List<TunnelEntity>

    @Upsert
    suspend fun upsert(entity: TunnelEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TunnelEntity>)

    @Query("DELETE FROM tunnels WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tunnels WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)
}
