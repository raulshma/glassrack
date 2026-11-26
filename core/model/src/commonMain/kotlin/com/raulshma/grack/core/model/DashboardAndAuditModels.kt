package com.raulshma.grack.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class TileType {
    SINGLE_METRIC,
    COMPOSITE_NODE,
    PINNED_CONTAINER,
    CLUSTER_AGGREGATE,
    CUSTOM_COMMAND,
    RPI_HEALTH,
}

@Serializable
enum class MetricKind {
    CPU_PCT,
    RAM_PCT,
    DISK_PCT,
    TEMP_C,
    UPTIME,
    LOAD_AVG,
    CONTAINER_COUNT,
    NET_THROUGHPUT,
}

@Immutable
@Serializable
data class DashboardProfile(
    val id: String,
    val title: String,
    val sortOrder: Int = 0,
)

@Immutable
@Serializable
data class DashboardTile(
    val id: String,
    val dashboardId: String,
    val serverId: String?,
    val tileType: TileType,
    val metricKind: MetricKind = MetricKind.CPU_PCT,
    val titleOverride: String = "",
    val containerName: String? = null,
    val customCommand: String? = null,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val positionIndex: Int = 0,
)

@Serializable
enum class AuditCategory {
    SSH,
    CONTAINER,
    SERVICE,
    PROCESS,
    SYSTEM,
    CONFIG,
    POWER,
}

@Serializable
enum class AuditOutcome {
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT,
    BLOCKED,
}

@Serializable
enum class TriggerSource {
    UI,
    WIDGET,
    QUICK_TILE,
    TERMINAL,
    BACKGROUND_WORKER,
}

@Immutable
@Serializable
data class AuditEntry(
    val id: String,
    val timestampEpochMs: Long,
    val serverId: String?,
    val serverNameSnapshot: String,
    val category: AuditCategory,
    val actionTitle: String,
    val commandSanitized: String,
    val triggerSource: TriggerSource,
    val outcome: AuditOutcome,
    val exitCode: Int? = null,
    val durationMs: Long = 0L,
    val outputSnippet: String = "",
    val prevHash: String = "",
    val entryHash: String = "",
)

@Immutable
data class AuditChainVerification(
    val totalEntries: Int,
    val isValid: Boolean,
    val brokenEntryId: String? = null,
)

@Serializable
enum class JobPhase {
    QUEUED,
    CONNECTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Immutable
data class ActiveJob(
    val id: String,
    val serverId: String,
    val serverName: String,
    val title: String,
    val commandPreview: String,
    val phase: JobPhase,
    val startedEpochMs: Long,
    val elapsedMs: Long = 0L,
    val liveOutput: String = "",
    val exitCode: Int? = null,
)

@Immutable
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val description: String = "",
    val commandTemplate: String,
    val targetTags: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
enum class SshKeyAlgorithm {
    ED25519,
    ECDSA,
    RSA,
}

@Immutable
@Serializable
data class SshKeyRecord(
    val id: String,
    val alias: String,
    val algorithm: SshKeyAlgorithm,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val privateKeyVaultRef: String,
    val hasPassphrase: Boolean = false,
    val createdAtEpochMs: Long,
)

@Serializable
enum class AlertMetric {
    CPU_PCT,
    RAM_PCT,
    DISK_PCT,
    TEMP_C,
    SERVER_OFFLINE,
}

@Immutable
@Serializable
data class AlertRule(
    val id: String,
    val name: String,
    val serverId: String? = null,
    val metric: AlertMetric,
    val threshold: Float,
    val enabled: Boolean = true,
)

@Immutable
data class AppAlert(
    val id: String,
    val ruleId: String,
    val serverId: String,
    val serverName: String,
    val message: String,
    val timestampEpochMs: Long,
    val severity: ServerHealth = ServerHealth.WARNING,
)
