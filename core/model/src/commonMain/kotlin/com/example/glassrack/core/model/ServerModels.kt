package com.example.glassrack.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

@Serializable
enum class AuthType {
    PASSWORD,
    KEY,
    INTERACTIVE,
}

@Serializable
enum class ServerHealth {
    ONLINE,
    WARNING,
    OFFLINE,
    CONNECTING,
    UNKNOWN,
}

@Immutable
@Serializable
data class OsCapabilities(
    val osFamily: String = "Linux",
    val distro: String = "",
    val kernel: String = "",
    val arch: String = "",
    val isRaspberryPi: Boolean = false,
    val hasDocker: Boolean = false,
    val hasDockerCompose: Boolean = false,
    val hasPodman: Boolean = false,
    val hasPct: Boolean = false,
    val hasSystemctl: Boolean = false,
    val hasSmartctl: Boolean = false,
    val hasZpool: Boolean = false,
    val hasNvidiaSmi: Boolean = false,
    val hasVcgencmd: Boolean = false,
    val hasSudo: Boolean = false,
)

@Immutable
@Serializable
data class RaspberryPiStats(
    val boardModel: String = "",
    val socTempC: Double? = null,
    val cpuFreqMhz: Int? = null,
    val throttledRaw: String? = null,
    val underVoltageDetected: Boolean = false,
    val armFreqCapped: Boolean = false,
    val currentlyThrottled: Boolean = false,
    val softTempLimit: Boolean = false,
    val underVoltageOccurred: Boolean = false,
    val fanRpm: Int? = null,
)

@Immutable
@Serializable
data class DiskMount(
    val filesystem: String,
    val mountPoint: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val usedPercent: Int,
)

@Immutable
@Serializable
data class SmartHealthInfo(
    val device: String,
    val model: String,
    val healthPassed: Boolean,
    val tempC: Int? = null,
    val wearPercent: Int? = null,
)

@Immutable
@Serializable
data class ZfsPoolInfo(
    val name: String,
    val state: String,
    val sizeBytes: Long,
    val allocatedBytes: Long,
    val scrubStatus: String = "",
)

@Immutable
@Serializable
data class GpuStats(
    val name: String,
    val utilizationPct: Int,
    val vramUsedMb: Int,
    val vramTotalMb: Int,
    val tempC: Int,
    val powerWatts: Double? = null,
)

@Immutable
@Serializable
data class CustomProbeResult(
    val id: String,
    val label: String,
    val command: String,
    val output: String,
    val exitCode: Int,
)

@Immutable
@Serializable
data class Server(
    val id: String,
    val name: String,
    val groupTag: String = "Homelab",
    val tags: List<String> = emptyList(),
    val accentColorHex: Long = 0xFF00D1FF,
    val primaryHost: String,
    val fallbackHost: String = "",
    val port: Int = 22,
    val username: String = "root",
    val authType: AuthType = AuthType.KEY,
    val credentialVaultId: String? = null,
    val sshKeyId: String? = null,
    val jumpHostServerId: String? = null,
    val macAddress: String = "",
    val wolRelayServerId: String? = null,
    val hostKeyFingerprint: String? = null,
    val pollIntervalSec: Int = 5,
    val preferredWifiSsids: List<String> = emptyList(),
    val notes: String = "",
    val sortOrder: Int = 0,
    val capabilities: OsCapabilities = OsCapabilities(),
    val customProbes: List<CustomProbeDefinition> = emptyList(),
)

@Immutable
@Serializable
data class CustomProbeDefinition(
    val id: String,
    val label: String,
    val command: String,
)

@Immutable
@Serializable
data class MetricSnapshot(
    val serverId: String,
    val timestampEpochMs: Long,
    val cpuPct: Float = 0f,
    val perCorePct: List<Float> = emptyList(),
    val memUsedBytes: Long = 0L,
    val memTotalBytes: Long = 0L,
    val swapUsedBytes: Long = 0L,
    val swapTotalBytes: Long = 0L,
    val load1m: Float = 0f,
    val load5m: Float = 0f,
    val load15m: Float = 0f,
    val uptimeSec: Long = 0L,
    val tempC: Float? = null,
    val netRxBps: Long = 0L,
    val netTxBps: Long = 0L,
    val latencyMs: Int = 0,
    val activeEndpoint: String = "",
    val runningContainers: Int = 0,
    val totalContainers: Int = 0,
    val disks: List<DiskMount> = emptyList(),
    val rpiStats: RaspberryPiStats? = null,
    val gpus: List<GpuStats> = emptyList(),
    val zfsPools: List<ZfsPoolInfo> = emptyList(),
    val customResults: List<CustomProbeResult> = emptyList(),
) {
    val memPct: Float
        get() = if (memTotalBytes > 0L) (memUsedBytes.toFloat() / memTotalBytes.toFloat()) * 100f else 0f
}

@Immutable
data class ServerRuntimeState(
    val server: Server,
    val health: ServerHealth = ServerHealth.UNKNOWN,
    val latestMetric: MetricSnapshot? = null,
    val history: ImmutableList<MetricSnapshot> = persistentListOf(),
    val lastSeenEpochMs: Long? = null,
    val lastError: String? = null,
    val pendingHostKeyChallenge: HostKeyChallenge? = null,
)

@Immutable
data class HostKeyChallenge(
    val serverId: String,
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val expectedFingerprint: String? = null,
) {
    val isMismatch: Boolean get() = expectedFingerprint != null && expectedFingerprint != fingerprintSha256
}
