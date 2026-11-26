package com.raulshma.grack.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The package-manager family of a node, resolved from `capabilities.distroId` (Phase 1A).
 * Drives the update-check script, the apply script and the reboot-recommendation probe.
 */
@Serializable
enum class DistroFamily {
    /** apt-based: debian, ubuntu, raspbian, linuxmint, pop, kali, ... */
    DEBIAN,

    /** dnf-based: fedora, rhel, rocky, almalinux, centos, ol, amzn, ... */
    RHEL,

    /** pacman-based: arch, manjaro, endeavouros, cachyos, ... */
    ARCH,

    /** apk-based: alpine. */
    ALPINE,

    /** Unknown or unsupported distro: update scanning reports UNSUPPORTED, applying is refused. */
    UNSUPPORTED,
}

/** Maps an os-release `ID=` (lowercase) to its package-manager family. */
fun distroFamilyForDistroId(distroId: String): DistroFamily {
    val id = distroId.trim().lowercase()
    return when {
        id.isEmpty() -> DistroFamily.UNSUPPORTED
        id in DEBIAN_DISTRO_IDS -> DistroFamily.DEBIAN
        id in RHEL_DISTRO_IDS -> DistroFamily.RHEL
        id in ARCH_DISTRO_IDS -> DistroFamily.ARCH
        id == "alpine" -> DistroFamily.ALPINE
        else -> DistroFamily.UNSUPPORTED
    }
}

private val DEBIAN_DISTRO_IDS =
    setOf("debian", "ubuntu", "raspbian", "linuxmint", "linux-mint", "pop", "kali", "devuan", "mx", "astra", "elbrus")

private val RHEL_DISTRO_IDS =
    setOf("fedora", "rhel", "redhat", "rocky", "almalinux", "centos", "ol", "amzn", "rocky-linux", "suse", "opensuse")

private val ARCH_DISTRO_IDS = setOf("arch", "archarm", "manjaro", "endeavouros", "cachyos", "artix", "garuda")

/** Outcome of one node's OS update-check probe. */
@Serializable
enum class OsScanStatus {
    /** Probe ran; the package list below is authoritative (possibly empty). */
    OK,

    /** The distro family is unsupported or its check tool is missing (e.g. no `checkupdates`). */
    UNSUPPORTED,

    /** The probe failed (connection, package-manager error such as `dnf` exit 1). */
    FAILED,
}

/** One pending OS package update. [currentVersion] is unavailable from `dnf check-update`. */
@Serializable
data class OsPackageUpdate(
    val packageName: String,
    val currentVersion: String? = null,
    val candidateVersion: String? = null,
)

/** OS half of a node's update scan report (persisted as `servers.updateReportJson`). */
@Serializable
data class OsUpdateSection(
    val family: DistroFamily,
    val status: OsScanStatus,
    val packages: List<OsPackageUpdate> = emptyList(),
    /** Extra context for non-OK statuses (why the scan failed or is unsupported). */
    val detail: String = "",
)

/**
 * Update verdict per container image. Store- and registry-aware (Phase 3B):
 * - [PINNED]: the image reference is digest-pinned (`name@sha256:...`) — never floated.
 * - [LOCAL]: the image has no `RepoDigests` (built locally, never pulled).
 * - [UP_TO_DATE]: local `RepoDigests` matches the remote index digest (buildx), or on the
 *   classic store the local image id (config digest) is present in the remote manifest list.
 * - [UPDATE_AVAILABLE]: the remote index digest differs from the local `RepoDigests`.
 * - [UNKNOWN]: Podman (digest semantics differ), the containerd image store without buildx,
 *   or a registry error — never guessed.
 */
@Serializable
enum class ContainerUpdateStatus {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    PINNED,
    LOCAL,
    UNKNOWN,
}

/** One container's update verdict in a persisted scan report. */
@Serializable
data class ContainerUpdateEntry(
    val containerName: String,
    val image: String,
    val composeProject: String? = null,
    /** Stack working dir (`com.docker.compose.project.working_dir`), for the Update-stack action. */
    val composeWorkingDir: String? = null,
    /** Stack `config_files` label, forwarded to `compose -f` on update. */
    val composeConfigFiles: String? = null,
    val status: ContainerUpdateStatus,
    /** Local `RepoDigests` value, when known. */
    val localRepoDigest: String = "",
    /** Remote digest the local one was compared against (index digest via buildx, when known). */
    val remoteDigest: String = "",
)

/**
 * Everything Phase 3B knows about a node's updates: the OS section, per-container verdicts,
 * and whether an OS apply concluded that a reboot is recommended. Serialized into
 * `servers.updateReportJson` (schema v2 column) so the Overview card renders counts without
 * scanning again.
 */
@Serializable
data class NodeUpdateReport(
    val scannedAtEpochMs: Long,
    val os: OsUpdateSection? = null,
    val containers: List<ContainerUpdateEntry> = emptyList(),
    /** Set after a successful OS apply whose reboot-recommendation probe returned true. */
    val rebootRecommended: Boolean = false,
) {
    val osUpdateCount: Int get() = os?.packages?.size ?: 0
    val containerUpdateCount: Int get() = containers.count { it.status == ContainerUpdateStatus.UPDATE_AVAILABLE }
    val hasAnyUpdates: Boolean get() = osUpdateCount > 0 || containerUpdateCount > 0

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a persisted `updateReportJson`; null when unset or unparseable. */
        fun fromJson(raw: String?): NodeUpdateReport? =
            raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString<NodeUpdateReport>(it) }.getOrNull() }
    }
}

/**
 * Applies the persisted scan report's container verdicts to freshly fetched containers:
 * containers whose name appears with [ContainerUpdateStatus.UPDATE_AVAILABLE] get
 * [ContainerInfo.updateAvailable] set, so the Containers tab badge survives re-polls.
 */
fun List<ContainerInfo>.withUpdateFlags(report: NodeUpdateReport?): List<ContainerInfo> {
    if (report == null) return this
    val updateNames = report.containers
        .filter { it.status == ContainerUpdateStatus.UPDATE_AVAILABLE }
        .map { it.containerName }
        .toSet()
    if (updateNames.isEmpty()) return this
    return map { if (it.name in updateNames) it.copy(updateAvailable = true) else it }
}
