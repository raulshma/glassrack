package com.raulshma.grack.core.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val groupTag: String,
    val tagsJson: String,
    val accentColorHex: Long,
    val primaryHost: String,
    val fallbackHost: String,
    val port: Int,
    val username: String,
    val authType: String,
    val credentialVaultId: String?,
    val sshKeyId: String?,
    val jumpHostServerId: String?,
    val macAddress: String,
    val wolRelayServerId: String?,
    val hostKeyFingerprint: String?,
    val pollIntervalSec: Int,
    val preferredWifiSsidsJson: String,
    val notes: String,
    val sortOrder: Int,
    val isSimulated: Boolean,
    val capabilitiesJson: String,
    val customProbesJson: String,
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val algorithm: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val privateKeyVaultRef: String,
    val hasPassphrase: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "dashboards")
data class DashboardProfileEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "dashboard_tiles",
    indices = [Index(value = ["dashboardId", "positionIndex"])],
)
data class DashboardTileEntity(
    @PrimaryKey val id: String,
    val dashboardId: String,
    val serverId: String?,
    val tileType: String,
    val metricKind: String,
    val titleOverride: String,
    val containerName: String?,
    val customCommand: String?,
    val spanX: Int,
    val spanY: Int,
    val positionIndex: Int,
)

@Entity(
    tableName = "metric_samples",
    indices = [Index(value = ["serverId", "timestampEpochMs"])],
)
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val serverId: String,
    val timestampEpochMs: Long,
    val cpuPct: Float,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val netRxBps: Long,
    val netTxBps: Long,
    val tempC: Float?,
    val load1m: Float,
    val runningContainers: Int,
    val totalContainers: Int,
    val payloadJson: String,
)

@Entity(
    tableName = "audit_entries",
    indices = [Index(value = ["timestampEpochMs"]), Index(value = ["serverId"])],
)
data class AuditEntryEntity(
    @PrimaryKey val id: String,
    val timestampEpochMs: Long,
    val serverId: String?,
    val serverNameSnapshot: String,
    val category: String,
    val actionTitle: String,
    val commandSanitized: String,
    val triggerSource: String,
    val outcome: String,
    val exitCode: Int?,
    val durationMs: Long,
    val outputSnippet: String,
    val prevHash: String,
    val entryHash: String,
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val commandTemplate: String,
    val targetTagsJson: String,
    val requiresConfirmation: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val serverId: String?,
    val metric: String,
    val threshold: Float,
    val enabled: Boolean,
)

@Entity(tableName = "tunnels")
data class TunnelEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val label: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)
