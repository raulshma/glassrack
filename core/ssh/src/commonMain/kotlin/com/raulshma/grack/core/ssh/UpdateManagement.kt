package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerUpdateEntry
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.OsPackageUpdate
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.OsUpdateSection
import com.raulshma.grack.core.model.distroFamilyForDistroId

/**
 * Phase 3B update probes and parsers.
 *
 * FIXTURE NOTE: every fixture these parsers are tested against is **synthetic** — written
 * from the documented output formats of each tool (`apt list --upgradable`,
 * `dnf -q check-update`, `checkupdates`, `apk version -l '<'`,
 * `docker buildx imagetools inspect`, `docker manifest inspect -v`, `docker info`).
 * This environment has no real nodes to capture live output from; verify the fixtures
 * against a real node per family before release.
 */
object UpdateProbes {

    /** Storage-driver value `docker info` reports when the containerd image store is enabled. */
    const val CONTAINERD_STORE_DRIVER: String = "overlayfs"

    /**
     * Read-only OS update check, selected by `capabilities.distroId`. Output lands in one
     * `===OSUPD===` section; each family appends its own `__*_EXIT__` marker line(s) so the
     * parser can tell "no updates" from "probe failed" (notably `dnf check-update`, which
     * exits 100 when updates exist — success-with-updates, not failure).
     */
    fun updateCheckScript(capabilities: OsCapabilities): String {
        val family = distroFamilyForDistroId(capabilities.distroId)
        return buildString {
            appendLine("echo '===OSUPD==='")
            when (family) {
                DistroFamily.DEBIAN -> {
                    appendLine("apt-get update -qq >/dev/null 2>&1")
                    appendLine("echo \"__APT_UPDATE_EXIT__=${'$'}?\"")
                    appendLine("apt list --upgradable 2>/dev/null")
                }
                DistroFamily.RHEL -> {
                    appendLine("dnf -q check-update 2>/dev/null")
                    appendLine("echo \"__DNF_EXIT__=${'$'}?\"")
                }
                DistroFamily.ARCH -> {
                    // checkupdates comes from pacman-contrib; without it there is no safe
                    // (non-root, non-database-writing) way to list updates on Arch.
                    appendLine("if command -v checkupdates >/dev/null 2>&1; then")
                    appendLine("  checkupdates")
                    appendLine("  echo \"__CHECKUPDATES_EXIT__=${'$'}?\"")
                    appendLine("else")
                    appendLine("  echo '__CHECKUPDATES_MISSING__'")
                    appendLine("fi")
                }
                DistroFamily.ALPINE -> {
                    appendLine("apk update >/dev/null 2>&1")
                    appendLine("apk version -l '<' 2>/dev/null")
                }
                DistroFamily.UNSUPPORTED -> {
                    appendLine("echo '__UPDATE_UNSUPPORTED__'")
                }
            }
        }.trimIndent()
    }

    /**
     * The OS update apply command for [family]. NEVER contains a reboot/shutdown verb
     * (asserted in tests); the UI surfaces a separate "reboot recommended" probe instead.
     *
     * - Debian: non-interactive, waits up to 10 minutes for the dpkg lock instead of
     *   failing while unattended-upgrades holds it, and keeps existing config files on
     *   conffile conflicts.
     * - RHEL: `dnf -y upgrade`. Arch: `pacman -Syu --noconfirm`. Alpine: `apk upgrade`.
     */
    fun osUpdateApplyCommand(family: DistroFamily): String = when (family) {
        DistroFamily.DEBIAN ->
            "DEBIAN_FRONTEND=noninteractive apt-get -y " +
                "-o DPkg::Lock::Timeout=600 " +
                "-o Dpkg::Options::=\"--force-confdef\" " +
                "-o Dpkg::Options::=\"--force-confold\" " +
                "upgrade"
        DistroFamily.RHEL -> "dnf -y upgrade"
        DistroFamily.ARCH -> "pacman -Syu --noconfirm"
        DistroFamily.ALPINE -> "apk upgrade"
        DistroFamily.UNSUPPORTED -> ""
    }

    /**
     * Registry probe for one image, executed in a single round trip:
     * - `buildx imagetools inspect --format '{{.Manifest.Digest}}'` — the index (manifest
     *   list) digest as it exists in the registry right now. This is the only stable value
     *   to compare with `RepoDigests`, and it works on both the classic and the containerd
     *   image store.
     * - `manifest inspect -v` fallback — per-platform manifests with no index digest, so
     *   the comparison there is against the local image id (a config digest) and is valid
     *   for multi-arch only on the classic image store.
     * - `docker info --format '{{.Driver}}'` — distinguishes the stores (`overlayfs` means
     *   the containerd image store, where image ids are not config digests).
     */
    fun containerDigestProbeScript(bin: String, image: String): String = buildString {
        appendLine("echo '===BUILDX==='")
        appendLine("$bin buildx imagetools inspect --format '{{.Manifest.Digest}}' ${shellQuote(image)} 2>/dev/null")
        appendLine("echo \"__BUILDX_EXIT__=${'$'}?\"")
        appendLine("echo '===MANIFESTV==='")
        appendLine("$bin manifest inspect -v ${shellQuote(image)} 2>/dev/null | head -c 20000")
        appendLine("echo \"__MANIFEST_EXIT__=${'$'}?\"")
        appendLine("echo '===DRIVER==='")
        appendLine("$bin info --format '{{.Driver}}' 2>/dev/null")
    }.trimIndent()

    /**
     * Post-apply reboot-recommendation check: the Debian/Ubuntu marker file, plus
     * `needs-restarting -r` on RHEL family (exit 1 = reboot required). Read-only; Arch and
     * Alpine have no reliable pre-reboot marker and default to "not recommended".
     */
    fun rebootRecommendedScript(): String = """
        echo '===REBOOTCHK==='
        if [ -f /var/run/reboot-required ]; then echo 'REBOOT_REQUIRED=1'; else echo 'REBOOT_REQUIRED=0'; fi
        if command -v needs-restarting >/dev/null 2>&1; then
          needs-restarting -r >/dev/null 2>&1
          echo "__NR_EXIT__=${'$'}?"
        else
          echo '__NR_EXIT__=skip'
        fi
    """.trimIndent()
}

/** Parsed container-digest probe output (see [UpdateProbes.containerDigestProbeScript]). */
data class ContainerDigestProbe(
    /** Index digest from buildx, when buildx ran and succeeded. */
    val remoteIndexDigest: String = "",
    val buildxExit: Int? = null,
    /** All digests appearing in the `manifest inspect -v` JSON (per-platform + config digests). */
    val manifestDigests: List<String> = emptyList(),
    val manifestExit: Int? = null,
    /** Storage driver from `docker info` (`overlayfs` = containerd image store). */
    val driver: String = "",
    /** True when the probe could not run at all (connection error, unparseable output). */
    val errored: Boolean = false,
)

/** The container-update verdict derived from a probe plus the local image row. */
data class ContainerUpdateDecision(
    val status: ContainerUpdateStatus,
    val remoteDigest: String = "",
)

/** Result of a node's container update scan: report entries plus containers with [ContainerInfo.updateAvailable] set. */
data class ContainerUpdateScanResult(
    val entries: List<ContainerUpdateEntry>,
    val containers: List<ContainerInfo>,
)

/** Outcome of an OS update apply: the audited exec result plus the reboot recommendation. */
data class OsApplyResult(
    val exec: SshExecResult,
    val rebootRecommended: Boolean,
    val family: DistroFamily,
)

/**
 * Parsers for the Phase 3B update probes. All fixtures are synthetic (documented formats);
 * see the object KDoc.
 */
object UpdateParsers {

    /** Digests found in a `docker manifest inspect -v` JSON document (per-platform manifests and configs). */
    private val MANIFEST_DIGEST_REGEX = Regex("\"digest\"\\s*:\\s*\"(sha256:[0-9a-fA-F]{8,128})\"")

    /** `^(name)-([^-]+-r[0-9]+)$` — Alpine's `name-version-rN` side format of `apk version`. */
    private val APK_NAME_VERSION_REGEX = Regex("^(.+)-([^-]+-r[0-9]+)$")

    /** `[upgradable from: X]` suffix of `apt list --upgradable` rows. */
    private val APT_UPGRADABLE_FROM_REGEX = Regex("\\[upgradable from:\\s*(.+?)\\]")

    /** RPM arch suffixes of `dnf check-update` rows (`kernel-core.x86_64 ...`). */
    private val RPM_ARCHES = setOf(
        "noarch", "x86_64", "aarch64", "i386", "i486", "i586", "i686", "x86",
        "armv5tel", "armv6hl", "armv7hl", "armv7l", "armv8l", "ppc64", "ppc64le", "s390", "s390x", "riscv64", "src",
    )

    /** DNF check-update rows use aligned columns, not single spaces. */
    private val DNF_COLUMN_REGEX = Regex("\\s{2,}")

    /**
     * Parses `updateCheckScript` output into an [OsUpdateSection].
     * Synthetic fixtures per family live in the androidHostTest suite.
     */
    fun parseOsUpdateSection(output: String, family: DistroFamily): OsUpdateSection {
        val sec = TelemetryParsers.splitSections(output)
        val body = sec["OSUPD"].orEmpty()
        val (status, packages) = when (family) {
            DistroFamily.DEBIAN -> parseAptUpgradable(body)
            DistroFamily.RHEL -> parseDnfCheckUpdate(body)
            DistroFamily.ARCH -> parseCheckUpdates(body)
            DistroFamily.ALPINE -> parseApkVersion(body)
            DistroFamily.UNSUPPORTED ->
                OsScanStatus.UNSUPPORTED to emptyList<OsPackageUpdate>()
        }
        val detail = when (status) {
            OsScanStatus.UNSUPPORTED ->
                if (family == DistroFamily.UNSUPPORTED) "Unsupported distro family" else "Check tool missing"
            OsScanStatus.FAILED -> "Package manager reported an error"
            OsScanStatus.OK -> ""
        }
        return OsUpdateSection(
            family = family,
            status = status,
            packages = packages,
            detail = detail,
        )
    }

    /** `apt list --upgradable` rows: `name/repo candidate arch [upgradable from: current]`. */
    private fun parseAptUpgradable(body: String): Pair<OsScanStatus, List<OsPackageUpdate>> {
        val packages = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("__APT_UPDATE_EXIT__") }
            .filterNot { it.startsWith("Listing") }
            .mapNotNull { line ->
                val tokens = line.split(Regex("\\s+"))
                if (tokens.size < 2 || !tokens[0].contains('/')) return@mapNotNull null
                OsPackageUpdate(
                    packageName = tokens[0].substringBefore('/'),
                    candidateVersion = tokens[1],
                    currentVersion = APT_UPGRADABLE_FROM_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim(),
                )
            }
            .toList()
        return OsScanStatus.OK to packages
    }

    /**
     * `dnf -q check-update` rows: `name.arch evr repo` in aligned columns. The exit marker
     * decides status: 0 = no updates, 100 = updates exist (success-with-updates), anything
     * else is a real failure.
     */
    private fun parseDnfCheckUpdate(body: String): Pair<OsScanStatus, List<OsPackageUpdate>> {
        val exit = body.lineSequence()
            .firstOrNull { it.startsWith("__DNF_EXIT__") }
            ?.substringAfter("__DNF_EXIT__=")
            ?.trim()?.toIntOrNull()
        if (exit != null && exit != 0 && exit != 100) return OsScanStatus.FAILED to emptyList()
        val packages = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("__DNF_EXIT__") }
            .mapNotNull { line ->
                val cols = line.split(DNF_COLUMN_REGEX)
                if (cols.size < 2) return@mapNotNull null
                OsPackageUpdate(
                    packageName = stripRpmArch(cols[0]),
                    candidateVersion = cols[1],
                    currentVersion = null, // check-update does not print the installed evr
                )
            }
            .toList()
        return OsScanStatus.OK to packages
    }

    /** `checkupdates` rows: `name current -> candidate`; missing tool reported by the script. */
    private fun parseCheckUpdates(body: String): Pair<OsScanStatus, List<OsPackageUpdate>> {
        if (body.contains("__CHECKUPDATES_MISSING__")) {
            return OsScanStatus.UNSUPPORTED to emptyList()
        }
        val packages = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("__CHECKUPDATES_EXIT__") }
            .mapNotNull { line ->
                val sides = line.split(" -> ")
                if (sides.size != 2) return@mapNotNull null
                val left = sides[0].trim().split(Regex("\\s+"))
                if (left.size < 2) return@mapNotNull null
                OsPackageUpdate(
                    packageName = left.dropLast(1).joinToString(" "),
                    currentVersion = left.last(),
                    candidateVersion = sides[1].trim(),
                )
            }
            .toList()
        return OsScanStatus.OK to packages
    }

    /** `apk version -l '<'` rows: `name-ver-rN < name-ver-rN [(repo)]`. */
    private fun parseApkVersion(body: String): Pair<OsScanStatus, List<OsPackageUpdate>> {
        val packages = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.contains(" < ") }
            .mapNotNull { line ->
                val sides = line.split(" < ")
                if (sides.size != 2) return@mapNotNull null
                val current = parseApkSide(sides[0].trim())
                val candidateSide = sides[1].trim().substringBefore(" (").trim()
                val candidate = parseApkSide(candidateSide)
                val name = current?.first ?: candidate?.first ?: return@mapNotNull null
                OsPackageUpdate(
                    packageName = name,
                    currentVersion = current?.second,
                    candidateVersion = candidate?.second,
                )
            }
            .toList()
        return OsScanStatus.OK to packages
    }

    /** Splits `name-version-rN` into (name, version); the name itself may contain dashes. */
    private fun parseApkSide(side: String): Pair<String, String>? {
        val match = APK_NAME_VERSION_REGEX.matchEntire(side) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** `kernel-core.x86_64` -> `kernel-core`; leaves non-arch dotted names untouched. */
    private fun stripRpmArch(nameArch: String): String {
        val idx = nameArch.lastIndexOf('.')
        if (idx <= 0) return nameArch
        val arch = nameArch.substring(idx + 1)
        return if (arch in RPM_ARCHES) nameArch.substring(0, idx) else nameArch
    }

    /** Parses the per-image registry probe output (buildx digest / manifest JSON / store driver). */
    fun parseContainerDigestProbe(output: String?): ContainerDigestProbe {
        if (output == null) return ContainerDigestProbe(errored = true)
        val sec = TelemetryParsers.splitSections(output)
        if (sec.isEmpty()) return ContainerDigestProbe(errored = true)
        val buildxSection = sec["BUILDX"].orEmpty()
        val buildxExit = buildxSection.lineSequence()
            .firstOrNull { it.startsWith("__BUILDX_EXIT__") }
            ?.substringAfter("__BUILDX_EXIT__=")?.trim()?.toIntOrNull()
        val remoteIndexDigest = buildxSection.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("sha256:") && !it.startsWith("__") }
            .orEmpty()
        val manifestSection = sec["MANIFESTV"].orEmpty()
        val manifestExit = manifestSection.lineSequence()
            .firstOrNull { it.startsWith("__MANIFEST_EXIT__") }
            ?.substringAfter("__MANIFEST_EXIT__=")?.trim()?.toIntOrNull()
        val manifestDigests = MANIFEST_DIGEST_REGEX.findAll(manifestSection)
            .map { it.groupValues[1] }
            .toList()
            .distinct()
        return ContainerDigestProbe(
            remoteIndexDigest = remoteIndexDigest,
            buildxExit = buildxExit,
            manifestDigests = manifestDigests,
            manifestExit = manifestExit,
            driver = sec["DRIVER"].orEmpty().trim(),
        )
    }

    /**
     * The verdict for one image. [localRepoDigest] is the image's `RepoDigests` entry,
     * [localImageId] the store image id (a config digest on the classic store only).
     */
    fun decideContainerUpdate(
        localRepoDigest: String,
        localImageId: String,
        probe: ContainerDigestProbe,
    ): ContainerUpdateDecision {
        val localDigest = normalizeDigest(localRepoDigest)
        // Preferred path: index digest comparison — valid on both image stores.
        if (probe.buildxExit == 0 && probe.remoteIndexDigest.isNotBlank()) {
            val remote = normalizeDigest(probe.remoteIndexDigest)
            return ContainerUpdateDecision(
                status = if (remote == localDigest) {
                    ContainerUpdateStatus.UP_TO_DATE
                } else {
                    ContainerUpdateStatus.UPDATE_AVAILABLE
                },
                remoteDigest = probe.remoteIndexDigest,
            )
        }
        // Fallback without buildx: `manifest inspect -v` exposes per-platform manifests with
        // no index digest; comparing the local image id (config digest) against the remote
        // config digests is valid for multi-arch only on the classic image store, where ids
        // are still config digests. On the containerd store this would be a guess -> UNKNOWN.
        val classicStore = probe.driver.isNotBlank() && probe.driver != UpdateProbes.CONTAINERD_STORE_DRIVER
        if (probe.manifestExit == 0 && classicStore && probe.manifestDigests.isNotEmpty()) {
            val localId = normalizeDigest(localImageId)
            val present = probe.manifestDigests.any { normalizeDigest(it) == localId }
            return ContainerUpdateDecision(
                status = if (present) ContainerUpdateStatus.UP_TO_DATE else ContainerUpdateStatus.UPDATE_AVAILABLE,
            )
        }
        // Podman, containerd store without buildx, or registry errors: unknown, not a guess.
        return ContainerUpdateDecision(status = ContainerUpdateStatus.UNKNOWN)
    }

    /** Parses the post-apply reboot recommendation probe. Arch/Alpine default to false. */
    fun parseRebootRecommended(output: String, family: DistroFamily): Boolean {
        if (family != DistroFamily.DEBIAN && family != DistroFamily.RHEL) return false
        val sec = TelemetryParsers.splitSections(output)
        val body = sec["REBOOTCHK"].orEmpty()
        return body.contains("REBOOT_REQUIRED=1") || body.contains("__NR_EXIT__=1")
    }

    /** Case-insensitive digest identity: `sha256:` prefixes are compared away. */
    fun normalizeDigest(raw: String): String = raw.trim().removePrefix("sha256:").lowercase()
}
