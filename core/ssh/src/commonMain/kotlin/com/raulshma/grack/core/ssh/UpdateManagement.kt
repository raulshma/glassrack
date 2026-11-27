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
import com.raulshma.grack.core.security.CommandSanitizer

/**
 * Phase 3B update probes and parsers.
 *
 * FIXTURE PROVENANCE: the output shapes these parse were captured from real distros on
 * 2026-11-27 — Linux Mint 22.2 and Debian 12 (apt), Fedora 45 (dnf5), Arch with
 * pacman-contrib (`checkupdates`), Alpine 3.24 (apk) — and the same captures live as
 * REAL-marked fixtures in the androidHostTest suites. `docs/real-node-verification.md`
 * tracks whatever is still unconfirmed.
 */
object UpdateProbes {

    /** Storage-driver value `docker info` reports when the containerd image store is enabled. */
    const val CONTAINERD_STORE_DRIVER: String = "overlayfs"

    /** Registry probes per wave in [containerDigestBatchScript]; more waves mean a slower node is hammered harder. */
    const val CONTAINER_PROBE_PARALLELISM: Int = 6

    /** Per-image registry probe budget inside the batch script (needs `timeout` on the node). */
    const val CONTAINER_PROBE_TIMEOUT_SEC: Int = 25

    /**
     * Read-only update listing, selected by `capabilities.distroId`. Output lands in one
     * `===OSUPD===` section; each family appends its own exit marker line so the parser can
     * tell "no updates" from "listing failed" — notably `dnf check-update`, which exits 100
     * when updates exist (success-with-updates, not failure), and `checkupdates`, which
     * exits 2 when current and 1 on a real error.
     *
     * The package-index refresh is deliberately NOT here: on apt and apk it needs root, so it
     * runs separately over the node's privilege path ([indexRefreshCommand], driven by
     * `HomelabController.scanOsUpdates`). dnf and `checkupdates` refresh into a per-user cache
     * by themselves and need no privilege.
     */
    fun updateListScript(capabilities: OsCapabilities): String {
        val family = distroFamilyForDistroId(capabilities.distroId)
        return buildString {
            appendLine("echo '===OSUPD==='")
            when (family) {
                DistroFamily.DEBIAN -> {
                    appendLine("apt list --upgradable 2>/dev/null")
                    appendLine("echo \"__APT_LIST_EXIT__=${'$'}?\"")
                }
                DistroFamily.RHEL -> {
                    // dnf5 skips a repository it cannot reach and still exits 0, which would read
                    // as "up to date" (real Fedora 45); dnf4 fails but keeps the reason on stderr.
                    // This makes every dnf fail loudly, with the reason kept for the detail.
                    // Accepted by dnf 4.14 (Rocky 9) and dnf5 5.4 (Fedora 45).
                    appendLine("dnf -q check-update --setopt='*.skip_if_unavailable=False' 2>&1")
                    appendLine("echo \"__DNF_EXIT__=${'$'}?\"")
                }
                DistroFamily.ARCH -> {
                    // checkupdates comes from pacman-contrib; without it there is no safe
                    // (non-root, non-database-writing) way to list updates on Arch. Its stderr
                    // is kept: exit 1 carries the reason (e.g. a missing fakeroot binary).
                    appendLine("if command -v checkupdates >/dev/null 2>&1; then")
                    appendLine("  checkupdates 2>&1")
                    appendLine("  echo \"__CHECKUPDATES_EXIT__=${'$'}?\"")
                    appendLine("else")
                    appendLine("  echo '__CHECKUPDATES_MISSING__'")
                    appendLine("fi")
                }
                DistroFamily.ALPINE -> {
                    appendLine("apk version -l '<' 2>/dev/null")
                    appendLine("echo \"__APK_EXIT__=${'$'}?\"")
                }
                DistroFamily.UNSUPPORTED -> {
                    appendLine("echo '__UPDATE_UNSUPPORTED__'")
                }
            }
        }.trimIndent()
    }

    /** One-line form of [updateListScript], for audit trails. */
    fun indexListSummary(family: DistroFamily): String = when (family) {
        DistroFamily.DEBIAN -> "apt list --upgradable"
        DistroFamily.RHEL -> "dnf -q check-update --setopt='*.skip_if_unavailable=False'"
        DistroFamily.ARCH -> "checkupdates"
        DistroFamily.ALPINE -> "apk version -l '<'"
        DistroFamily.UNSUPPORTED -> "(unsupported distro)"
    }

    /**
     * Seconds the Debian index refresh waits for apt's lock before giving up. apt waits
     * indefinitely by default, so without it a scan that lands while unattended-upgrades runs
     * hangs until the controller's own timeout and reports that; with it the node's own lock
     * message arrives in half a minute and the scan says what holds the lock (verified against
     * a real lock on Debian 12).
     */
    const val INDEX_REFRESH_LOCK_TIMEOUT_SEC: Int = 30

    /**
     * The privileged package-index refresh for [family], or "" when the family refreshes its
     * index unprivileged (dnf, `checkupdates`) or is unsupported. It only rewrites the package
     * manager's local index — never installs, upgrades or reboots anything.
     */
    fun indexRefreshCommand(family: DistroFamily): String = when (family) {
        DistroFamily.DEBIAN -> "apt-get -o DPkg::Lock::Timeout=$INDEX_REFRESH_LOCK_TIMEOUT_SEC update -qq"
        DistroFamily.ALPINE -> "apk update"
        DistroFamily.RHEL, DistroFamily.ARCH, DistroFamily.UNSUPPORTED -> ""
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
     * Image listing for the update scan. Unlike the Images tab's listing this prints full
     * image ids (`--no-trunc`): on the classic store an id is the image's config digest, and
     * the no-buildx fallback ([containerManifestBatchScript]) matches it against the remote
     * config digests. The default listing's 12-character id is only a prefix of one and would
     * never equal it — confirmed on a real host, where the full id appears verbatim in the
     * registry's answer.
     */
    fun updateScanImagesScript(bin: String): String =
        "echo \"===IMAGES===\"\n" +
            "$bin images --no-trunc --format '{{.ID}}|{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.Size}}|{{.CreatedSince}}' 2>/dev/null || true"

    /** `grep -oE` filter reducing a `docker manifest inspect -v` document to its digest properties. */
    internal const val MANIFEST_DIGEST_FILTER: String =
        """grep -oE '"digest"[[:space:]]*:[[:space:]]*"sha256:[0-9a-fA-F]+"'"""

    /**
     * Every registry probe of a node in one round trip. The probes run concurrently on the
     * node — POSIX background jobs, at most [parallelism] at a time, each cut off after
     * [perProbeTimeoutSec] when `timeout` exists there — and each prints exactly one line
     * `__PROBE__|<exit>|<digest>|<ref>|<reason>`: a single write, so concurrent records cannot
     * interleave, and nothing after the ref can shift the fields before it. The reason is the
     * probe's own complaint (last line of what it printed, capped) — a registry refusal or a
     * rate limit shows up in the report instead of a bare "undetermined". This is a read-only
     * registry query: no privilege, no credential, so its stderr carries nothing sensitive.
     *
     * For each ref: `buildx imagetools inspect --format '{{.Manifest.Digest}}'`, the index
     * (manifest list) digest as it exists in the registry right now — the only stable value
     * to compare with `RepoDigests`, on both the classic and the containerd image store.
     * `__BUILDX__=ok|missing` says whether the plugin exists at all, `===DRIVER===` closes
     * the script (`overlayfs` = containerd image store).
     *
     * Shell only, POSIX constructs only: the node's login shell runs this as-is. The worst
     * case is one probe timeout per wave, which is exactly the budget the controller allows.
     */
    fun containerDigestBatchScript(
        bin: String,
        refs: Collection<String>,
        perProbeTimeoutSec: Int = CONTAINER_PROBE_TIMEOUT_SEC,
        parallelism: Int = CONTAINER_PROBE_PARALLELISM,
    ): String = buildString {
        appendLine("echo '===BUILDX==='")
        // A missing buildx plugin and a refused registry both leave exit 1 on the probes
        // (docker exits 1 for an unknown command, not the shell's 127); this line tells them apart.
        appendLine("if $bin buildx version >/dev/null 2>&1; then echo '__BUILDX__=ok'; else echo '__BUILDX__=missing'; fi")
        appendTimeoutPrefix(perProbeTimeoutSec)
        appendLine("n=0")
        appendLine("for ref in ${refs.joinToString(" ") { shellQuote(it) }}; do")
        // On success the digest is the only output; picking the `sha256:` line rather than
        // the first line keeps a stray warning from shadowing it, and keeps the rest as the reason.
        appendLine("  ( all=\$(${'$'}T $bin buildx imagetools inspect --format '{{.Manifest.Digest}}' \"${'$'}ref\" 2>&1); rc=${'$'}?; d=\$(printf '%s\\n' \"${'$'}all\" | grep -oE 'sha256:[0-9a-fA-F]{64}' | head -n 1); why=\$(printf '%s\\n' \"${'$'}all\" | grep -vE 'sha256:[0-9a-fA-F]{64}' | tr '\\n|' '  ' | tr -s ' ' | cut -c1-160); echo \"__PROBE__|${'$'}rc|${'$'}d|${'$'}ref|${'$'}why\" ) &")
        appendLine("  n=\$((n+1))")
        appendLine("  if [ \$((n % $parallelism)) -eq 0 ]; then wait; fi")
        appendLine("done")
        appendLine("wait")
        appendLine("echo '===DRIVER==='")
        appendLine("$bin info --format '{{.Driver}}' 2>/dev/null")
    }.trimIndent()

    /**
     * No-buildx fallback for the classic image store, one round trip for every ref. Records
     * are delimited — `__MANIFEST_BEGIN__|<ref>`, the digest lines, `__MANIFEST_EXIT__=<docker
     * exit>`, `__MANIFEST_END__|<ref>` — and each document is reduced on the node to its
     * `"digest"` properties ([MANIFEST_DIGEST_FILTER]) so a multi-arch manifest list cannot
     * flood the channel. The interpretation is only valid where image ids are still config
     * digests; see [UpdateParsers.decideContainerUpdate].
     *
     * Probes run in the same waves as the buildx batch. A record spans several lines, so
     * concurrent jobs cannot print directly: each writes its record to a private file under a
     * fresh `mktemp -d` directory, the main shell prints them afterwards and removes the
     * directory. Without a usable one the loop prints sequentially, record by record.
     */
    fun containerManifestBatchScript(
        bin: String,
        refs: Collection<String>,
        perProbeTimeoutSec: Int = CONTAINER_PROBE_TIMEOUT_SEC,
        parallelism: Int = CONTAINER_PROBE_PARALLELISM,
    ): String = buildString {
        appendLine("echo '===MANIFESTV==='")
        appendTimeoutPrefix(perProbeTimeoutSec)
        // emit prints one finished record; record runs the lookup, then emits to a file
        // (concurrent mode) or to standard output.
        appendLine("emit() {")
        appendLine("  echo \"__MANIFEST_BEGIN__|${'$'}1\"")
        appendLine("  printf '%s\\n' \"${'$'}out\" | $MANIFEST_DIGEST_FILTER")
        appendLine("  echo \"__MANIFEST_EXIT__=${'$'}2\"")
        appendLine("  echo \"__MANIFEST_END__|${'$'}1\"")
        appendLine("}")
        appendLine("record() {")
        appendLine("  out=\$(${'$'}T $bin manifest inspect -v \"${'$'}1\" 2>/dev/null)")
        appendLine("  rc=${'$'}?")
        appendLine("  if [ -n \"${'$'}2\" ]; then emit \"${'$'}1\" \"${'$'}rc\" > \"${'$'}2\"; else emit \"${'$'}1\" \"${'$'}rc\"; fi")
        appendLine("}")
        val refList = refs.joinToString(" ") { shellQuote(it) }
        appendLine("tmp=\$(mktemp -d 2>/dev/null)")
        appendLine("n=0")
        appendLine("if [ -n \"${'$'}tmp\" ] && [ -d \"${'$'}tmp\" ]; then")
        appendLine("  for ref in $refList; do")
        appendLine("    n=\$((n+1))")
        appendLine("    record \"${'$'}ref\" \"${'$'}tmp/${'$'}n\" &")
        appendLine("    if [ \$((n % $parallelism)) -eq 0 ]; then wait; fi")
        appendLine("  done")
        appendLine("  wait")
        appendLine("  for f in \"${'$'}tmp\"/*; do [ -f \"${'$'}f\" ] && cat \"${'$'}f\"; done")
        appendLine("  rm -rf \"${'$'}tmp\"")
        appendLine("else")
        appendLine("  for ref in $refList; do")
        appendLine("    record \"${'$'}ref\"")
        appendLine("  done")
        appendLine("fi")
    }.trimIndent()

    /**
     * `T="timeout <sec>"` when the node has `timeout`, else empty: a later unquoted `$T` is
     * then the whole prefix or nothing, so a probe is cut off after its budget where the tool
     * exists and simply runs where it does not.
     */
    private fun StringBuilder.appendTimeoutPrefix(perProbeTimeoutSec: Int) {
        appendLine("if command -v timeout >/dev/null 2>&1; then T=\"timeout $perProbeTimeoutSec\"; else T=\"\"; fi")
    }

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

/**
 * One image's parsed registry probe. The buildx half comes from
 * [UpdateProbes.containerDigestBatchScript], the manifest half from the no-buildx fallback
 * [UpdateProbes.containerManifestBatchScript].
 */
data class ContainerDigestProbe(
    /** Index digest from buildx, when buildx ran and succeeded. */
    val remoteIndexDigest: String = "",
    val buildxExit: Int? = null,
    /** All digests appearing in the `manifest inspect -v` JSON (per-platform + config digests). */
    val manifestDigests: List<String> = emptyList(),
    val manifestExit: Int? = null,
    /** Storage driver from `docker info` (`overlayfs` = containerd image store). */
    val driver: String = "",
    /** What a failed buildx probe printed (one line, capped); "" when it said nothing. */
    val reason: String = "",
    /** True when the probe could not run at all (connection error, unparseable output). */
    val errored: Boolean = false,
) {
    /** True when buildx returned the registry's index digest for this image. */
    val buildxAnswered: Boolean get() = buildxExit == 0 && remoteIndexDigest.isNotBlank()

    /** True when the registry gave something comparable: buildx's index digest, or digests from a successful fallback lookup. */
    val answered: Boolean get() =
        buildxAnswered || (manifestExit == 0 && manifestDigests.isNotEmpty())
}

/** The container-update verdict derived from a probe plus the local image row. */
data class ContainerUpdateDecision(
    val status: ContainerUpdateStatus,
    val remoteDigest: String = "",
)

/** Result of a node's container update scan: report entries plus containers with [ContainerInfo.updateAvailable] set. */
data class ContainerUpdateScanResult(
    val entries: List<ContainerUpdateEntry>,
    val containers: List<ContainerInfo>,
    /**
     * Why the verdicts are not a complete registry check, or null when they are: Podman (no
     * registry comparison at all), a registry batch that failed or timed out, or lookups the
     * registry refused. Lands in [NodeUpdateReport.containerScanDetail] for the UI to show.
     */
    val detail: String? = null,
    /** Distinct image references the registry was asked about through buildx; 0 when nothing needed probing. */
    val probedRefCount: Int = 0,
    /** References asked the older way (`manifest inspect -v`) after buildx gave no answer. */
    val fallbackRefCount: Int = 0,
    /** True when the pass failed as a whole and [entries] are a previous scan's verdicts, not fresh ones. */
    val keptPreviousVerdicts: Boolean = false,
    /** True when a registry batch outlived its budget — the scan's ledger entry is then TIMEOUT. */
    val timedOut: Boolean = false,
)

/** Outcome of an OS update apply: the audited exec result plus the reboot recommendation. */
data class OsApplyResult(
    val exec: SshExecResult,
    val rebootRecommended: Boolean,
    val family: DistroFamily,
)

/**
 * Result of the package-index refresh that precedes an apt/apk listing. When [refreshed] is
 * false the listing is demoted to [OsScanStatus.STALE]; [reason] then says what went wrong
 * and what to change on the node. No credential ever appears in any field: the password rides
 * `stdinText` only, so the command and the reason are safe to audit and to show.
 */
data class IndexRefreshResult(
    /** True when the listing that follows is authoritative (refreshed, or needs no refresh). */
    val refreshed: Boolean,
    /** The privilege path that ran the refresh ("root login", "passwordless sudo", "stored sudo password"). */
    val via: String = "",
    /** The command the transport received for the refresh, for the scan's audit trail. */
    val command: String = "",
    /** Set when [refreshed] is false: why the index is stale. */
    val reason: String = "",
    /** True when a policy (Safe Read-Only Mode) refused the refresh — the scan's ledger entry is then BLOCKED, not FAILED. */
    val blocked: Boolean = false,
    /**
     * True when the refresh ran and exited 0 but could not refresh every repository (apt
     * reports an unreachable or untrusted repository as a warning): part of the index is
     * newer, part is old, so the listing is still only a lower bound.
     */
    val partial: Boolean = false,
)

/**
 * Parsers for the Phase 3B update probes. Provenance of the shapes is in the KDoc of
 * [UpdateProbes]; the REAL-captured fixtures live in the androidHostTest suite.
 */
object UpdateParsers {

    /** Digests found in a `docker manifest inspect -v` JSON document (per-platform manifests and configs). */
    private val MANIFEST_DIGEST_REGEX = Regex("\"digest\"\\s*:\\s*\"(sha256:[0-9a-fA-F]{8,128})\"")

    /** The trailing `-rN` build counter of every apk version: `3.5.9-r1`. */
    private val APK_RELEASE_SUFFIX = Regex("-r[0-9]+$")

    /** `[upgradable from: X]` suffix of `apt list --upgradable` rows. */
    private val APT_UPGRADABLE_FROM_REGEX = Regex("\\[upgradable from:\\s*(.+?)\\]")

    /** RPM arch suffixes of `dnf check-update` rows (`kernel-core.x86_64 ...`). */
    private val RPM_ARCHES = setOf(
        "noarch", "x86_64", "aarch64", "i386", "i486", "i586", "i686", "x86",
        "armv5tel", "armv6hl", "armv7hl", "armv7l", "armv8l", "ppc64", "ppc64le", "s390", "s390x", "riscv64", "src",
    )

    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Exit-marker line a listing script ends with: `__<NAME>_EXIT__=<code>`. */
    private fun exitMarker(body: String, name: String): Int? =
        body.lineSequence()
            .firstOrNull { it.startsWith("__${name}_EXIT__=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()

    /** Trimmed, non-blank section lines that are not `__…__` markers. */
    private fun rows(body: String): Sequence<String> =
        body.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("__") }

    /**
     * Why a listing failed: plain words when the node's own lines say what the matter is, and
     * those lines themselves otherwise — never a bare exit code where the node said more.
     *
     * The unreachable-repository lines are real captures. dnf 4.14 (Rocky 9): `Error: Failed to
     * download metadata for repo 'bad': Cannot download repomd.xml: … All mirrors were tried`;
     * dnf5 5.4 (Fedora 45): `Failed to download metadata (baseurl: "…") for repository "bad":
     * Usable URL not found` — printed only with `skip_if_unavailable=False`, which the listing
     * script sets; `checkupdates` (Arch): `==> ERROR: Cannot fetch updates`.
     */
    private fun describeListingFailure(tool: String, exit: Int, lines: Sequence<String>): String {
        val own = lines.joinToString(" ").trim().take(200)
        val lower = own.lowercase()
        return when {
            "failed to download metadata" in lower || "cannot fetch updates" in lower || "all mirrors were tried" in lower ->
                "the node could not reach all of its package repositories — $own"
            "cannot find the fakeroot binary" in lower ->
                "checkupdates needs fakeroot, which is not installed on the node"
            own.isBlank() -> "$tool exited $exit"
            else -> own
        }
    }

    /** One family's listing: status, the packages it yielded and why a non-OK status is what it is. */
    private data class FamilyParse(
        val status: OsScanStatus,
        val packages: List<OsPackageUpdate>,
        val detail: String = "",
    )

    /**
     * Parses [UpdateProbes.updateListScript] output into an [OsUpdateSection]. Whether the
     * package index behind it is fresh is a separate question, answered by the refresh step
     * in `HomelabController.scanOsUpdates` (which demotes a listing to [OsScanStatus.STALE]).
     */
    fun parseOsUpdateSection(output: String, family: DistroFamily): OsUpdateSection {
        val body = TelemetryParsers.splitSections(output)["OSUPD"].orEmpty()
        val parsed = when (family) {
            DistroFamily.DEBIAN -> parseAptUpgradable(body)
            DistroFamily.RHEL -> parseDnfCheckUpdate(body)
            DistroFamily.ARCH -> parseCheckUpdates(body)
            DistroFamily.ALPINE -> parseApkVersion(body)
            DistroFamily.UNSUPPORTED -> FamilyParse(OsScanStatus.UNSUPPORTED, emptyList(), "Unsupported distro family")
        }
        return OsUpdateSection(
            family = family,
            status = parsed.status,
            packages = parsed.packages,
            detail = parsed.detail,
        )
    }

    /**
     * `apt list --upgradable` rows: `name/repo candidate arch [upgradable from: current]`,
     * under a `Listing...` banner (newer apt prints it without ` Done`). A non-zero
     * `__APT_LIST_EXIT__` is a failed listing, not an empty one.
     */
    private fun parseAptUpgradable(body: String): FamilyParse {
        val exit = exitMarker(body, "APT_LIST")
        if (exit != null && exit != 0) return FamilyParse(OsScanStatus.FAILED, emptyList(), "apt list exited $exit")
        val packages = rows(body).mapNotNull { line ->
            val tokens = line.split(WHITESPACE_REGEX)
            // Only rows carry `name/suite`; the banner does not.
            if (tokens.size < 2 || !tokens[0].contains('/')) return@mapNotNull null
            OsPackageUpdate(
                packageName = tokens[0].substringBefore('/'),
                candidateVersion = tokens[1],
                currentVersion = APT_UPGRADABLE_FROM_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim(),
            )
        }.toList()
        return FamilyParse(OsScanStatus.OK, packages)
    }

    /**
     * `dnf -q check-update` rows: `name.arch evr repo`. The exit marker decides status:
     * 0 = no updates, 100 = updates exist (success-with-updates), anything else is a real
     * failure, and the script keeps stderr so the failure can say why (dnf 4.14 on Rocky 9 and
     * dnf5 on Fedora 45 both exit 1 when a repository cannot be reached). dnf5 groups rows
     * under headings (`Upgrades`, `Obsoleting packages:`), dnf4 pads its columns and prints a
     * blank line first — on real Fedora 45 output some rows even carry a single space between
     * the version and the repo — so rows are read as whitespace-separated fields, and a row
     * counts as a package only when its first field carries an RPM arch suffix.
     */
    private fun parseDnfCheckUpdate(body: String): FamilyParse {
        val exit = exitMarker(body, "DNF")
        if (exit != null && exit != 0 && exit != 100) {
            return FamilyParse(OsScanStatus.FAILED, emptyList(), describeListingFailure("dnf check-update", exit, rows(body)))
        }
        val packages = rows(body).mapNotNull { line ->
            val fields = line.split(WHITESPACE_REGEX)
            if (fields.size < 2 || stripRpmArch(fields[0]) == fields[0]) return@mapNotNull null
            OsPackageUpdate(
                packageName = stripRpmArch(fields[0]),
                candidateVersion = fields[1],
                currentVersion = null, // check-update does not print the installed evr
            )
        }.toList()
        return FamilyParse(OsScanStatus.OK, packages)
    }

    /**
     * `checkupdates` rows: `name current -> candidate`. Its exit code is part of the
     * contract: 0 = updates listed, 2 = nothing to do, 1 = a real error (the script keeps
     * stderr for exactly that case, e.g. a missing fakeroot binary).
     */
    private fun parseCheckUpdates(body: String): FamilyParse {
        if (body.contains("__CHECKUPDATES_MISSING__")) {
            return FamilyParse(OsScanStatus.UNSUPPORTED, emptyList(), "Check tool missing (install pacman-contrib)")
        }
        val exit = exitMarker(body, "CHECKUPDATES")
        if (exit != null && exit != 0 && exit != 2) {
            return FamilyParse(OsScanStatus.FAILED, emptyList(), describeListingFailure("checkupdates", exit, rows(body)))
        }
        val packages = rows(body).mapNotNull { line ->
            val sides = line.split(" -> ")
            if (sides.size != 2) return@mapNotNull null
            val left = sides[0].trim().split(WHITESPACE_REGEX)
            if (left.size < 2) return@mapNotNull null
            OsPackageUpdate(
                packageName = left.dropLast(1).joinToString(" "),
                currentVersion = left.last(),
                candidateVersion = sides[1].trim(),
            )
        }.toList()
        return FamilyParse(OsScanStatus.OK, packages)
    }

    /**
     * `apk version -l '<'` rows under an `Installed: … Available:` banner: the installed
     * `name-version-rN`, then `<`, then the available side — which is the bare `version-rN`
     * of the same package (real output on Alpine 3.24), though older apks repeat the name
     * there. A non-zero `__APK_EXIT__` is a failed listing, not an empty one.
     */
    private fun parseApkVersion(body: String): FamilyParse {
        val exit = exitMarker(body, "APK")
        if (exit != null && exit != 0) return FamilyParse(OsScanStatus.FAILED, emptyList(), "apk version exited $exit")
        val packages = rows(body).mapNotNull { line ->
            val sides = line.split(" < ")
            if (sides.size != 2) return@mapNotNull null
            val current = parseApkSide(sides[0].trim())
            val candidate = parseApkSide(sides[1].trim().substringBefore(" (").trim())
            val name = current?.name ?: candidate?.name ?: return@mapNotNull null
            OsPackageUpdate(
                packageName = name,
                currentVersion = current?.version,
                candidateVersion = candidate?.version,
            )
        }.toList()
        return FamilyParse(OsScanStatus.OK, packages)
    }

    private data class ApkSide(val name: String?, val version: String)

    /**
     * One side of an apk row. Both always end in `-rN`; before it, `name-version` has the
     * package name up to its last dash, while a bare version has none. Null for anything else
     * (the banner line never gets here: it has no `<`).
     */
    private fun parseApkSide(side: String): ApkSide? {
        val release = APK_RELEASE_SUFFIX.find(side) ?: return null
        val body = side.removeRange(release.range)
        val suffix = release.value
        val dash = body.lastIndexOf('-')
        return if (dash <= 0) {
            ApkSide(name = null, version = body + suffix)
        } else {
            ApkSide(name = body.substring(0, dash), version = body.substring(dash + 1) + suffix)
        }
    }

    /** `kernel-core.x86_64` -> `kernel-core`; leaves non-arch dotted names untouched. */
    private fun stripRpmArch(nameArch: String): String {
        val idx = nameArch.lastIndexOf('.')
        if (idx <= 0) return nameArch
        val arch = nameArch.substring(idx + 1)
        return if (arch in RPM_ARCHES) nameArch.substring(0, idx) else nameArch
    }

    // ------------------------------------------------------------------
    // Registry probes (batched)
    // ------------------------------------------------------------------

    private const val PROBE_RECORD_PREFIX = "__PROBE__|"
    private const val MANIFEST_BEGIN_PREFIX = "__MANIFEST_BEGIN__|"
    private const val MANIFEST_END_PREFIX = "__MANIFEST_END__|"
    private const val MANIFEST_EXIT_PREFIX = "__MANIFEST_EXIT__="

    /**
     * The per-image probes printed by [UpdateProbes.containerDigestBatchScript]. Every ref in
     * [refs] gets an entry; one that printed no record (cut off by its timeout, or lost with
     * a truncated channel) stays an errored probe, so its verdict can only be UNKNOWN — never
     * a false "up to date". [driver] is the store driver from the same script.
     */
    fun parseContainerDigestBatch(output: String?, refs: Collection<String>, driver: String): Map<String, ContainerDigestProbe> {
        val missing = ContainerDigestProbe(driver = driver, errored = true)
        val body = output?.let { TelemetryParsers.splitSections(it)["BUILDX"] } ?: return refs.associateWith { missing }
        val records = body.lineSequence()
            .filter { it.startsWith(PROBE_RECORD_PREFIX) }
            .mapNotNull { line ->
                // exit|digest|ref|reason — the digest may be empty, the reason is optional and last.
                val cols = line.trimEnd().removePrefix(PROBE_RECORD_PREFIX).split('|', limit = 5)
                if (cols.size < 3) return@mapNotNull null
                val exit = cols[0].toIntOrNull() ?: return@mapNotNull null
                val digest = cols[1].trim()
                cols[2].trim() to ContainerDigestProbe(
                    remoteIndexDigest = if (exit == 0) digest else "",
                    buildxExit = exit,
                    driver = driver,
                    reason = cols.getOrNull(3)?.trim().orEmpty(),
                )
            }
            .toList()
        return refs.associateWith { ref -> records.lastOrNull { it.first == ref }?.second ?: missing }
    }

    /** The `===DRIVER===` value of a batch script's output (`overlayfs` = containerd image store), or "". */
    fun parseStoreDriver(output: String?): String =
        output?.let { TelemetryParsers.splitSections(it)["DRIVER"] }?.trim().orEmpty()

    /**
     * Whether the node has the buildx plugin, from the batch script's `__BUILDX__=ok|missing`
     * line; null when the line never arrived (truncated channel, older script).
     */
    fun parseBuildxAvailable(output: String?): Boolean? {
        val body = output?.let { TelemetryParsers.splitSections(it)["BUILDX"] } ?: return null
        for (rawLine in body.lineSequence()) {
            when (rawLine.trim()) {
                "__BUILDX__=ok" -> return true
                "__BUILDX__=missing" -> return false
            }
        }
        return null
    }

    /**
     * Why some probes went unanswered, read off their exit codes and what they printed:
     * 124/125 come from `timeout` cutting a probe off (the registry did not answer in time); a
     * probe with no record at all lost its batch; a `429` is the registry rate-limiting the
     * node. The distinct reasons the probes printed follow, quoted, so a refusal is read off
     * the registry's own words rather than guessed. Null when every probe answered.
     */
    fun describeFailedProbes(probes: Collection<ContainerDigestProbe>): String? {
        val failed = probes.filter { !it.answered }
        if (failed.isEmpty()) return null
        val timedOut = failed.count { it.buildxExit == 124 || it.buildxExit == 125 || it.manifestExit == 124 || it.manifestExit == 125 }
        val noRecord = failed.count { it.buildxExit == null && it.manifestExit == null }
        val rateLimited = failed.count { it.reason.contains("429") || it.reason.contains("toomanyrequests", ignoreCase = true) }
        val head = when {
            rateLimited == failed.size -> "${failed.size} of ${probes.size} registry lookups failed — rate-limited by the registry"
            timedOut == failed.size -> "${failed.size} of ${probes.size} registry lookups failed — timed out"
            noRecord == failed.size -> "${failed.size} of ${probes.size} registry lookups failed — no result came back"
            else -> {
                val causes = listOfNotNull(
                    timedOut.takeIf { it > 0 }?.let { "$it timed out" },
                    rateLimited.takeIf { it > 0 }?.let { "$it rate-limited" },
                    noRecord.takeIf { it > 0 }?.let { "$it without a result" },
                )
                "${failed.size} of ${probes.size} registry lookups failed" +
                    causes.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
            }
        }
        val reasons = failed.map { it.reason.trim() }.filter { it.isNotEmpty() }.distinct()
        return reasons.takeIf { it.isNotEmpty() }?.let { "$head: ${it.joinToString(" | ").take(300)}" } ?: head
    }

    /**
     * The per-image records of [UpdateProbes.containerManifestBatchScript]. Refs without a
     * complete record are errored probes; a record whose docker exit is non-zero carries no
     * digests, so [decideContainerUpdate] cannot treat a failed lookup as a mismatch.
     */
    fun parseContainerManifestBatch(output: String?, refs: Collection<String>, driver: String): Map<String, ContainerDigestProbe> {
        val missing = ContainerDigestProbe(driver = driver, errored = true)
        val body = output?.let { TelemetryParsers.splitSections(it)["MANIFESTV"] } ?: return refs.associateWith { missing }
        val parsed = mutableMapOf<String, ContainerDigestProbe>()
        var ref: String? = null
        var errored = false
        var exit: Int? = null
        val digests = mutableListOf<String>()
        fun store() {
            parsed[ref.orEmpty()] = ContainerDigestProbe(
                manifestDigests = digests.toList().distinct(),
                manifestExit = exit,
                driver = driver,
                errored = errored,
            )
            ref = null
            errored = false
            exit = null
            digests.clear()
        }
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith(MANIFEST_BEGIN_PREFIX) -> {
                    ref = line.removePrefix(MANIFEST_BEGIN_PREFIX).trim()
                    digests.clear()
                }
                ref == null -> Unit
                line.startsWith(MANIFEST_END_PREFIX) -> store()
                line.startsWith(MANIFEST_EXIT_PREFIX) -> exit = line.removePrefix(MANIFEST_EXIT_PREFIX).toIntOrNull()
                line.startsWith("__") -> Unit
                else -> for (match in MANIFEST_DIGEST_REGEX.findAll(line)) digests += match.groupValues[1]
            }
        }
        // A record left open by a truncated channel is not a usable lookup.
        if (ref != null) {
            errored = true
            store()
        }
        return refs.associateWith { parsed[it]?.takeUnless { p -> p.errored } ?: missing }
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
        // Confirmed on a real classic-store host: a current image's id appears verbatim as the
        // remote amd64 config digest, an outdated one's does not.
        val classicStore = probe.driver.isNotBlank() && probe.driver != UpdateProbes.CONTAINERD_STORE_DRIVER
        val localId = normalizeDigest(localImageId)
        // A truncated id is only the prefix of a digest: nothing to compare, so no verdict.
        val fullId = localId.length == FULL_DIGEST_HEX_LENGTH
        if (probe.manifestExit == 0 && classicStore && probe.manifestDigests.isNotEmpty() && fullId) {
            val present = probe.manifestDigests.any { normalizeDigest(it) == localId }
            return ContainerUpdateDecision(
                status = if (present) ContainerUpdateStatus.UP_TO_DATE else ContainerUpdateStatus.UPDATE_AVAILABLE,
            )
        }
        // Podman, containerd store without buildx, or registry errors: unknown, not a guess.
        return ContainerUpdateDecision(status = ContainerUpdateStatus.UNKNOWN)
    }

    // ------------------------------------------------------------------
    // Failure diagnosis
    // ------------------------------------------------------------------

    /**
     * Why an apt index refresh that exited 0 still left part of the index old, or null when it
     * did not. apt treats an unreachable or untrusted repository as a warning — `apt-get
     * update` prints `W: Failed to fetch …` and then `W: Some index files failed to download.
     * They have been ignored, or old ones used instead.`, and exits 0 (real Debian 12) — so a
     * node whose security repository is unreachable would list against a stale index and read
     * as "up to date". Failures apt does report with a non-zero exit (an unsigned repository,
     * a missing release) never get here; the controller reads those off the exit code.
     */
    fun indexRefreshGap(stdout: String, stderr: String): String? {
        val lines = (stderr + "\n" + stdout).lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val summary = lines.firstOrNull { it.contains("index files failed to download", ignoreCase = true) } ?: return null
        val first = lines.firstOrNull { it.contains("failed to fetch", ignoreCase = true) } ?: summary
        return "the node could not fetch every repository's index — ${CommandSanitizer.sanitize(first).take(240)}"
    }

    /**
     * Plain-language reason for a privileged update command that failed — the OS apply
     * ([UpdateProbes.osUpdateApplyCommand]) and the package-index refresh
     * ([UpdateProbes.indexRefreshCommand]) alike — read off what the node actually printed:
     * sudo's and the package manager's own wording, never a guess from the exit code alone.
     * Carries no credential: sudo runs with its prompt suppressed and never echoes the
     * password it read from stdin, and the node lines it quotes are sanitized like a ledger entry.
     *
     * Every wording matched here was captured on 2026-11-27: sudo 1.9.13p3 on Debian 12
     * (rejected password, no right, no terminal, requiretty, a user outside sudoers, sudo
     * absent), apt 2.6.1 (lock held, unreachable / unsigned / missing repositories), apk
     * 3.0.9, pacman on Arch, dnf 4.14 on Rocky 9 and dnf5 on Fedora 45. The REAL fixtures
     * are in `OsUpdateScanTest`; the two generic needles with no capture of their own
     * ("permission denied" among the ran-without-root words) are listed for their plain
     * meaning, not for a remembered output.
     */
    fun explainPrivilegedFailure(exec: SshExecResult, username: String): String {
        val text = exec.stderr.ifBlank { exec.stdout }
        val lower = text.lowercase()
        fun has(vararg needles: String) = needles.any { it in lower }
        fun quote() = quoteLine(text)
        return when {
            has("incorrect password attempt", "sorry, try again", "no password was provided") ->
                "sudo rejected the stored sudo password for '$username'. sudo asks for the SSH login " +
                    "user's password, not root's — correct it under Edit node → Privilege Escalation, " +
                    "or allow passwordless sudo on the node."
            has("a password is required", "terminal is required to read the password") ->
                "'$username' needs a sudo password and none is stored — set one under Edit node → " +
                    "Privilege Escalation, or allow passwordless sudo on the node."
            has("must have a tty", "no tty was found") ->
                "this node's sudoers demands a terminal (requiretty) — allow passwordless sudo " +
                    "for '$username' instead; a password cannot be delivered over this connection."
            has("not in the sudoers file", "not allowed to execute") ->
                "'$username' is not allowed to run sudo on this node — check its sudoers rules."
            has("sudo: command not found", "sudo: not found") ->
                "sudo is not installed on this node — log in as root to apply OS updates."
            has(
                "are you root?", "unless you are root", "permission denied", "must be run as root",
                "requires superuser", "must be superuser", "operation not permitted",
            ) ->
                "ran without root: the node did not let GRack escalate privileges."
            has(
                "could not get lock", "unable to acquire the dpkg", "is another process using it",
                "waiting for cache lock", "unable to lock database", "unable to lock directory",
                // dnf 4.14 (Rocky 9), captured under a really held /var/lib/rpm/.rpm.lock. dnf5
                // has no lock wording at all — its apply prints only "Transaction failed: Rpm
                // transaction failed.", which falls through to the quote below.
                "can't create transaction lock",
            ) ->
                "another package manager holds the lock on this node${lockHolder(exec.stderr, exec.stdout)} — retry in a few minutes."
            has("gpg error", "no_pubkey", "keyexpired", "is not signed", "signatures couldn't be verified") ->
                "a repository's signing key is missing or expired on this node, so apt refused its index: ${quote()}"
            has("does not have a release file") ->
                "a configured repository no longer exists on this node (a removed release or mirror): ${quote()}"
            has(
                "failed to fetch", "failed to download metadata", "cannot download", "could not resolve",
                "usable url not found", "all mirrors were tried", "failed retrieving", "failed to synchronize",
                "cannot fetch updates", "updating and opening", "connection refused", "connection timed out",
                "network is unreachable",
            ) ->
                "the node could not reach its package repositories — check its network or DNS: ${quote()}"
            else -> quoteLine(text).let { line -> if (line.isBlank()) "no output was captured (exit ${exec.exitCode})" else line }
        }
    }

    /** The first line that looks like a tool's own complaint, else the first non-blank line; sanitized and capped for a banner. */
    private fun quoteLine(text: String): String = CommandSanitizer.sanitize(firstErrorLine(text))

    /**
     * apt's own naming of the lock holder, when it printed one — `It is held by process 2655
     * (perl)` on stderr when the wait ran out, or on stdout while it waited. "" when neither
     * stream says.
     */
    private fun lockHolder(vararg streams: String): String {
        val match = streams.firstNotNullOfOrNull { LOCK_HOLDER_REGEX.find(it) } ?: return ""
        val (pid, name) = match.destructured
        return if (name.isBlank()) " (process $pid)" else " ($name, process $pid)"
    }

    private val LOCK_HOLDER_REGEX = Regex("""held by process (\d+) \(([^)]*)\)""")

    /** The first line that looks like a tool's own complaint, else the first non-blank line. */
    private fun firstErrorLine(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val complaint = lines.firstOrNull { line ->
            line.startsWith("E:") || line.startsWith("W:") || line.startsWith("error") ||
                line.startsWith("ERROR") || line.startsWith("err:") || line.startsWith("sudo:") ||
                line.startsWith("dpkg:") || line.startsWith("==>")
        }
        return (complaint ?: lines.firstOrNull().orEmpty()).take(200)
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

    /** Hex digits of a SHA-256 digest; anything shorter is a truncated id, not a digest. */
    private const val FULL_DIGEST_HEX_LENGTH = 64
}
