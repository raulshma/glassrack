package com.raulshma.grack.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

@Serializable
enum class ContainerState {
    RUNNING,
    PAUSED,
    EXITED,
    RESTARTING,
    CREATED,
    DEAD,
    TRANSITIONING,
    UNKNOWN,
}

@Serializable
enum class ContainerRuntime {
    DOCKER,
    PODMAN,
    LXC,
}

@Immutable
@Serializable
data class ContainerInfo(
    val id: String,
    val shortId: String,
    val name: String,
    val image: String,
    val runtime: ContainerRuntime = ContainerRuntime.DOCKER,
    val state: ContainerState = ContainerState.UNKNOWN,
    val statusText: String = "",
    val composeProject: String? = null,
    val composeService: String? = null,
    val composeWorkingDir: String? = null,
    val portsSummary: String = "",
    val cpuPct: Float = 0f,
    val memUsedBytes: Long = 0L,
    val memLimitBytes: Long = 0L,
    val isCritical: Boolean = false,
    val updateAvailable: Boolean = false,
    val transitionalLabel: String? = null,
    val transitionStartedEpochMs: Long? = null,
)

@Immutable
data class ComposeStack(
    val projectName: String,
    val workingDir: String?,
    val containers: ImmutableList<ContainerInfo> = persistentListOf(),
) {
    val runningCount: Int get() = containers.count { it.state == ContainerState.RUNNING }
    val totalCount: Int get() = containers.size
}

@Serializable
enum class ContainerAction(val label: String, val isDestructive: Boolean) {
    START("Start", false),
    STOP("Stop", true),
    RESTART("Restart", false),
    PAUSE("Pause", false),
    UNPAUSE("Unpause", false),
    PULL_RECREATE("Pull & Recreate", true),
    KILL("Force Kill", true),
    REMOVE("Remove", true),
}

@Serializable
enum class SystemdActiveState {
    ACTIVE,
    RELOADING,
    INACTIVE,
    FAILED,
    ACTIVATING,
    DEACTIVATING,
    UNKNOWN,
}

@Immutable
@Serializable
data class SystemdUnit(
    val name: String,
    val loadState: String,
    val activeState: SystemdActiveState,
    val subState: String,
    val description: String,
    val enabledState: String = "",
)

@Immutable
@Serializable
data class CronEntry(
    val schedule: String,
    val command: String,
    val source: String,
)

@Immutable
@Serializable
data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpuPct: Float,
    val memPct: Float,
    val rssKb: Long,
    val state: String,
    val elapsed: String,
    val command: String,
)

@Serializable
enum class ProcessSignal(val signalName: String, val code: Int, val isDestructive: Boolean) {
    SIGTERM("SIGTERM", 15, true),
    SIGKILL("SIGKILL", 9, true),
    SIGHUP("SIGHUP", 1, false),
    SIGSTOP("SIGSTOP", 19, true),
    SIGCONT("SIGCONT", 18, false),
}

@Immutable
@Serializable
data class NetworkInterfaceInfo(
    val name: String,
    val ipv4: String = "",
    val ipv6: String = "",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBps: Long = 0L,
    val txBps: Long = 0L,
    val linkUp: Boolean = true,
)

@Immutable
@Serializable
data class NetworkPortInfo(
    val protocol: String,
    val localAddress: String,
    val port: Int,
    val processName: String,
)

@Immutable
@Serializable
data class RemoteFileEntry(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val permissions: String,
    val modifiedEpochSec: Long,
)

@Immutable
@Serializable
data class PortForwardTunnel(
    val id: String,
    val serverId: String,
    val label: String,
    val localPort: Int,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int,
    val isActive: Boolean = false,
)
